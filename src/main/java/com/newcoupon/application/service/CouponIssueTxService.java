package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.CouponExpireTaskGateway;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 领券事务服务（阶段 1 形态，ADR-0006）。
 *
 * <p>职责收窄：模板读取与可领性校验已前移到编排层（Redis 闸门之前）；
 * 本类只做"DB 侧双防线 + 落库 + 记事件"：
 * <pre>
 *   ① UPDATE coupon_template 条件扣账（issued_count+1，守卫未售罄+已发布+已审批）——
 *      即使 Redis 闸门初始化偏大，DB 仍保证零超发（双防线的第二道）
 *   ② INSERT user_coupon（快照 + seq 序号——撞 uk_user_seq=超领兜底、撞 uk_idem=幂等终审）
 *   ③ INSERT coupon_event 发放事件
 * </pre>
 *
 * <p>Redis 侧的扣减/补偿由编排层负责（Redis 不参与 DB 事务——阶段 0 "回滚即补偿"
 * 的优雅就此消失，这是引入 Redis 的代价，ADR-0006 已认账）。
 */
@Service
public class CouponIssueTxService {

    private final TemplateRepository templateRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponEventRepository couponEventRepository;
    private final CouponExpireTaskGateway expireTaskGateway;
    private final com.newcoupon.infrastructure.persistence.mapper.StockBucketMapper stockBucketMapper;
    private final com.newcoupon.infrastructure.persistence.mapper.CouponOutboxMapper outboxMapper;

    public CouponIssueTxService(TemplateRepository templateRepository,
                                UserCouponRepository userCouponRepository,
                                CouponEventRepository couponEventRepository,
                                CouponExpireTaskGateway expireTaskGateway,
                                com.newcoupon.infrastructure.persistence.mapper.StockBucketMapper stockBucketMapper,
                                com.newcoupon.infrastructure.persistence.mapper.CouponOutboxMapper outboxMapper) {
        this.templateRepository = templateRepository;
        this.userCouponRepository = userCouponRepository;
        this.couponEventRepository = couponEventRepository;
        this.expireTaskGateway = expireTaskGateway;
        this.stockBucketMapper = stockBucketMapper;
        this.outboxMapper = outboxMapper;
    }

    /**
     * @param template 编排层已读取并校验过的模板（避免事务内重复读——事务内是旧快照，读它反而误导）
     * @param seq      Redis 用户计数给出的发放序号（1..N）
     * @param bucket/gen 令牌命中的桶与世代（P2b：DB 扣账从模板行改打桶行——A 簇红线落位）
     */
    @Transactional
    public UserCoupon issue(ReceiveRequest req, CouponTemplate template, long seq,
                            int bucket, long gen, LocalDateTime now) {
        // 故障注入接缝（红灯基建）：挂起本事务——构造"迟到 confirm/cancel 跨桶重开窗口"。生产零开销
        com.newcoupon.infrastructure.testing.FaultHooks.fire(
                com.newcoupon.infrastructure.testing.FaultHooks.ISSUE_SUSPEND, req.templateId());
        // ① DB 双防线（P2b 桶行版）：条件扣账打 stock_bucket 行——超发红线强度不变，
        //    热行从"1 个模板行"分散到"16 个桶行"（Redis 谎报有货（平移攻击/残留旧桶）在此被封顶）
        int deducted = stockBucketMapper.casDeductBucket(req.templateId(), bucket, (int) gen);
        if (deducted == 0) {
            // 0行=桶行额度真尽或世代已封账；抛信号由编排层在事务外用新快照分类（study/04a 的 RR 教训）
            throw new StockDeductRejectedException(req.templateId());
        }
        // ② 落券（seq 写入；唯一索引兜底限领与幂等）
        UserCoupon saved = userCouponRepository.save(
                UserCoupon.issue(template, req.userId(), req.requestId(), (int) seq, now));
        // ③ 事实源
        CouponEvent issuedEvent = CouponEvent.issued(saved, now);
        couponEventRepository.append(issuedEvent);
        // ⑤ 发件箱（ADR-0011，与事件同一事务）：事件表保持只追加，投递状态归 outbox 行。
        //    payload 内嵌 event_no=消费方幂等锚点；JSON 手拼——字段即契约，消费方按名取值
        com.newcoupon.infrastructure.persistence.po.CouponOutboxPO outbox =
                new com.newcoupon.infrastructure.persistence.po.CouponOutboxPO();
        outbox.setEventNo(issuedEvent.getEventNo());
        outbox.setEventType(issuedEvent.getEventType().getCode());
        outbox.setPayload("{\"eventNo\":\"" + issuedEvent.getEventNo()
                + "\",\"type\":\"" + issuedEvent.getEventType().name()
                + "\",\"userCouponId\":" + saved.getId()
                + ",\"templateId\":" + saved.getTemplateId()
                + ",\"userId\":" + saved.getUserId()
                + ",\"requestId\":\"" + saved.getIdempotentKey()
                + "\",\"occurredAt\":\"" + now + "\"}");
        outbox.setStatus(com.newcoupon.infrastructure.persistence.po.CouponOutboxPO.STATUS_PENDING);
        outbox.setRetryCount(0);
        outbox.setNextScanTime(now);
        outboxMapper.insert(outbox);
        // ④ L2：注册过期任务（同一事务——券和"它的死亡时间表"要么都在要么都没有；
        //    任何未来产生券的新路径必须复用本方法，禁止旁路直插）
        expireTaskGateway.register(saved.getId(), req.userId(), saved.getValidEnd());
        return saved;
    }
}
