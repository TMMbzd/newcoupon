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
 * 领券事务服务——项目第一个 @Transactional，三写同生共死：
 *
 * <pre>
 *   ① UPDATE coupon_template  扣库存（条件更新，守卫防超发）
 *   ② INSERT user_coupon      落券（快照固化）
 *   ③ INSERT coupon_event     发放事件（事实源）
 * </pre>
 *
 * <p>为什么必须加事务：不加时"①成功 ②失败"= 库存虚扣（少发），即 docs/03
 * "扣减成功落库失败"事故在纯 DB 版的形态。加了事务，任一步抛异常（含撞唯一索引的
 * DuplicateKeyException）→ 三步全部撤销，**回滚就是补偿**——这是纯 DB 方案的优雅之处，
 * 阶段 1 引入 Redis 后失去它（Redis 不参与 DB 事务），到时对比讲解手工补偿。
 *
 * <p>为什么放在独立的类（而不是塞进编排层）：Spring 事务靠代理实现，同类内
 * this.method() 自调用不走代理 → @Transactional 失效（Spring 事务三大坑之首）。
 * 编排层通过 Spring 注入的 Bean 调用本类，调用经过代理，事务才真正生效。
 *
 * <p>回滚规则：BizException 是 RuntimeException，@Transactional 默认对
 * RuntimeException 回滚——无需写 rollbackFor（若抛的是受检异常则必须显式声明，这是第二个坑）。
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
     * 在一个事务内完成"扣库存 → 落券 → 记事件"。
     * 任何一步失败，本方法抛出异常、事务整体回滚（库存扣减一并撤销）。
     */
    @Transactional
    public UserCoupon issue(ReceiveRequest req, LocalDateTime now) {
        // a. 读模板 + 可领性校验（审批通过/已发布/领取期内；状态竞态由下一步 SQL 守卫兜底）
        CouponTemplate template = templateRepository.findById(req.templateId())
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + req.templateId()));
        template.assertReceivable(now);

        // b. 条件扣库存：检查+写入一条 SQL 原子完成（check-then-act 竞态无从发生）
        int deducted = templateRepository.casDeductStock(req.templateId());
        if (deducted == 0) {
            // 0 行重读分类（ADR-0004 增补1）：区分"真售罄"与"校验后被下线/取消审批"
            CouponTemplate fresh = templateRepository.findById(req.templateId())
                    .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND));
            if (fresh.getIssuedCount() >= fresh.getTotalCount()) {
                throw new BizException(ErrorCode.STOCK_OUT);
            }
            throw new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE, "模板状态已变化（被下线/审批回退）");
        }

        // c. 落券：领取工厂内完成快照固化与全部不变量校验；撞唯一索引时抛 DuplicateKeyException
        UserCoupon saved = userCouponRepository.save(
                UserCoupon.issue(template, req.userId(), req.requestId(), now));

        // d. 发放事件（同事务；重放场景 event_no 撞唯一索引也会整体回滚）
        couponEventRepository.append(CouponEvent.issued(saved, now));
        return saved;
    }
}
