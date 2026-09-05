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

    public CouponIssueTxService(TemplateRepository templateRepository,
                                UserCouponRepository userCouponRepository,
                                CouponEventRepository couponEventRepository) {
        this.templateRepository = templateRepository;
        this.userCouponRepository = userCouponRepository;
        this.couponEventRepository = couponEventRepository;
    }

    /**
     * @param template 编排层已读取并校验过的模板（避免事务内重复读——事务内是旧快照，读它反而误导）
     * @param seq      Redis 用户计数给出的发放序号（1..N）
     */
    @Transactional
    public UserCoupon issue(ReceiveRequest req, CouponTemplate template, long seq, LocalDateTime now) {
        // ① DB 双防线：条件扣账（Redis 过了闸门，这里再以 DB 账本为准挡一次）
        int deducted = templateRepository.casDeductStock(req.templateId());
        if (deducted == 0) {
            // 0行=真售罄或模板状态竞态；抛信号由编排层在事务外用新快照分类（study/04a 的 RR 教训）
            throw new StockDeductRejectedException(req.templateId());
        }
        // ② 落券（seq 写入；唯一索引兜底限领与幂等）
        UserCoupon saved = userCouponRepository.save(
                UserCoupon.issue(template, req.userId(), req.requestId(), (int) seq, now));
        // ③ 事实源
        couponEventRepository.append(CouponEvent.issued(saved, now));
        return saved;
    }
}
