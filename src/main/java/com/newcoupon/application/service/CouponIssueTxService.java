package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.CouponExpireTaskGateway;
import com.newcoupon.infrastructure.persistence.mapper.CouponDailyCounterMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponUserDailyCounterMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 领券事务服务（C1 唯一库存模型，ADR-0018）。
 *
 * <p>一次事务只尝试一个 C1 分片：
 * <pre>
 *   事务内重读模板 → layoutVersion fence（不匹配则零写入回滚）
 *   每人日计数（V27 条件自增）
 *   模板日计数（V27 条件自增）
 *   UPDATE coupon_stock_shard SET used=used+1
 *    WHERE template_id=? AND layout_version=? AND shard_no=? AND status=0 AND used<quota
 *   INSERT user_coupon（快照 + seq；uk_idem/uk_user_seq 终审）
 *   INSERT coupon_event
 *   INSERT coupon_outbox（事件投递）
 *   INSERT coupon_expire_task（过期时间表）
 * </pre>
 *
 * <p>日计数和库存/券在同一个本地事务：计数递增后库存或落券失败，计数一起回滚。
 * 同步与异步 worker 都走这里，不再各自维护一份日限逻辑；每人总限由调用方用
 * {@code MAX(seq)+1} 判断，热路径不再对 user_coupon 做 COUNT。
 * 0 行换下一片；所有片都 0 行抛 {@link StockShardDeductRejectedException}，事务整体回滚。
 * 旧 Redis 桶/世代链路已废弃。
 */
@Service
public class CouponIssueTxService {

    private final UserCouponRepository userCouponRepository;
    private final TemplateRepository templateRepository;
    private final CouponEventRepository couponEventRepository;
    private final CouponExpireTaskGateway expireTaskGateway;
    private final CouponStockShardMapper stockShardMapper;
    private final com.newcoupon.infrastructure.persistence.mapper.CouponOutboxMapper outboxMapper;
    private final CouponUserDailyCounterMapper userDailyCounterMapper;
    private final CouponDailyCounterMapper dailyCounterMapper;

    public CouponIssueTxService(UserCouponRepository userCouponRepository,
                                TemplateRepository templateRepository,
                                CouponEventRepository couponEventRepository,
                                CouponExpireTaskGateway expireTaskGateway,
                                CouponStockShardMapper stockShardMapper,
                                com.newcoupon.infrastructure.persistence.mapper.CouponOutboxMapper outboxMapper,
                                CouponUserDailyCounterMapper userDailyCounterMapper,
                                CouponDailyCounterMapper dailyCounterMapper) {
        this.userCouponRepository = userCouponRepository;
        this.templateRepository = templateRepository;
        this.couponEventRepository = couponEventRepository;
        this.expireTaskGateway = expireTaskGateway;
        this.stockShardMapper = stockShardMapper;
        this.outboxMapper = outboxMapper;
        this.userDailyCounterMapper = userDailyCounterMapper;
        this.dailyCounterMapper = dailyCounterMapper;
    }

    /**
     * C1 领券事务：**一次事务只尝试一个分片**，命中就落券，0 行就抛信号让外层换下一片。
     *
     * <p>为什么不在同一个事务里顺序探测所有分片：每探测一个满片都会在该行上留下锁，
     * 而不同用户从不同片开始探测时，两个事务可能各自持有对方的下一片，形成死锁。
     * 把“探测”放到事务外（失败即回滚、换片重开事务），每个事务最多持有一行锁，
     * 死锁概率降到最低；代价是满片场景多几次短事务（有售罄提示兜底）。
     */
    @Transactional
    public UserCoupon issueC1(ReceiveRequest req, CouponTemplate template, int seq,
                              int layoutVersion, int shardNo, LocalDateTime now, LocalDate statDay) {
        com.newcoupon.infrastructure.testing.FaultHooks.fire(
                com.newcoupon.infrastructure.testing.FaultHooks.ISSUE_SUSPEND, req.templateId());
        if (statDay == null) {
            throw new IllegalStateException("issue statDay is required");
        }

        // 缓存只做入口快筛；硬参数在事务内按 DB 当前模板复核。
        // 多实例下旧 Caffeine 可能仍认为“日限=10/已发布”，不能让它决定最终发放。
        CouponTemplate current = templateRepository.findById(template.getId())
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND,
                        "id=" + template.getId()));
        current.assertReceivable(now);
        if (seq > current.getPerUserLimit()) {
            throw new BizException(ErrorCode.USER_LIMIT_EXCEEDED);
        }
        // 版本 fence 必须在任何日计数/库存更新之前：调用方读的是缓存/任务快照，
        // reshard 或发布幂等重跑可能已经切换 active version。旧版本扣减会命中退役行，
        // 即使 SQL 返回 0 也已经写脏了日计数——所以先比较版本，不匹配直接回滚。
        if (layoutVersion != current.getStockLayoutVersion()) {
            throw new StockLayoutChangedException(current.getId(),
                    layoutVersion, current.getStockLayoutVersion());
        }
        if (shardNo < 0 || shardNo >= current.getStockShardCount()) {
            throw new BizException(ErrorCode.DEGRADED, "库存布局已变化，请刷新后重试");
        }

        // 锁顺序固定：先用户日计数，再模板日计数，最后库存分片。同步与异步 worker 一致，
        // 避免两条链路以相反顺序拿行锁。计数和库存/券在同一事务，后两步失败会一起回滚。
        if (current.getPerUserDailyLimit() > 0) {
            userDailyCounterMapper.ensureRow(req.userId(), current.getId(), statDay);
            int affected = userDailyCounterMapper.tryIncrement(
                    req.userId(), current.getId(), statDay, current.getPerUserDailyLimit());
            if (affected == 0) {
                throw new BizException(ErrorCode.USER_LIMIT_EXCEEDED, "已达每日限领上限");
            }
        }
        if (current.getDailyLimit() > 0) {
            dailyCounterMapper.ensureRow(current.getId(), statDay);
            int affected = dailyCounterMapper.tryIncrement(
                    current.getId(), statDay, current.getDailyLimit());
            if (affected == 0) {
                throw new BizException(ErrorCode.DAILY_LIMIT_EXCEEDED);
            }
        }

        if (stockShardMapper.casDeduct(current.getId(), layoutVersion, shardNo) == 0) {
            throw new StockShardDeductRejectedException(req.templateId());
        }
        return persistCoupon(req, current, seq, now);
    }

    /**
     * 领券事务的公共后半段：券、事件、发件箱、过期任务。
     *
     * <p>任何一条插入失败都会让整个事务回滚——包括前面已经执行的库存扣减。
     * 这就是 C1 不需要 Redis confirm/cancel 的原因：库存和券在同一个本地事务里。
     */
    private UserCoupon persistCoupon(ReceiveRequest req, CouponTemplate template,
                                     long seq, LocalDateTime now) {
        // ② 落券（seq 写入；唯一索引兜底限领与幂等）
        UserCoupon saved = userCouponRepository.save(
                UserCoupon.issue(template, req.userId(), req.requestId(), (int) seq, now));
        // ③ 事实源
        CouponEvent issuedEvent = CouponEvent.issued(saved, now);
        couponEventRepository.append(issuedEvent);
        // ④ 发件箱（ADR-0011，与事件同一事务）：事件表保持只追加，投递状态归 outbox 行
        outboxMapper.insert(CouponIssueArtifacts.issuedOutbox(issuedEvent, saved, now));
        // ⑤ L2：注册过期任务（同一事务——券和它的死亡时间表要么都在要么都没有）
        expireTaskGateway.register(saved.getId(), req.userId(), saved.getValidEnd());
        return saved;
    }
}
