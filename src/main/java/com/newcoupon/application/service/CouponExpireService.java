package com.newcoupon.application.service;

import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.CouponExpireTaskGateway;
import com.newcoupon.infrastructure.persistence.po.CouponExpireTaskPO;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 券过期执行器与兜底扫描（L2，2026-09-12，docs/06 v2 兜底层 + 谓词防线）。
 *
 * <p>三层分工（全链路的三条不变式）：**核销/锁定的 SQL 谓词是资损防线**（与本服务无关、
 * 零窗口）；**本扫描是状态修复者**——把"显示过期但状态列还没变"的券收敛到真实终态，
 * 时效分钟级；实时触发层（消息队列）留待 M5，谓词与扫描不依赖它。
 *
 * <p>执行纪律：每批 ≤500、单轮 ≤20 批（防饿死其他任务）；全部流转是条件 UPDATE
 * （守卫"当前状态+确已到期"），重复触发影响 0 行——三种触发源共用同一语义；
 * 单券失败只推进 next_scan_time（指数退避），重试满 8 次置死信等人工——
 * "晚一点再扫"永远不会成为漏扫死角。
 */
@Service
public class CouponExpireService {

    private static final Logger log = LoggerFactory.getLogger(CouponExpireService.class);

    private final UserCouponRepository userCouponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final CouponEventRepository couponEventRepository;
    private final CouponExpireTaskGateway taskGateway;

    public CouponExpireService(UserCouponRepository userCouponRepository,
                               CouponUsageRepository couponUsageRepository,
                               CouponEventRepository couponEventRepository,
                               CouponExpireTaskGateway taskGateway) {
        this.userCouponRepository = userCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.couponEventRepository = couponEventRepository;
        this.taskGateway = taskGateway;
    }

    /** 兜底扫描：每分钟捞到期待办小批量处理。异常只记日志下一轮重试（调度不中断） */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void scan() {
        try {
            int processed = scanOnce();
            if (processed > 0) {
                log.info("expire scan: {} tasks processed", processed);
            }
        } catch (Exception e) {
            log.error("expire scan round failed (next round retries)", e);
        }
    }

    /**
     * 单轮扫描（测试与人工触发可直接调）：最多 20 批 × 每批 500 条。
     *
     * @return 本轮处理的任务数
     */
    public int scanOnce() {
        int processed = 0;
        for (int round = 0; round < 20; round++) {
            List<CouponExpireTaskPO> batch = taskGateway.scanDue(500);
            if (batch.isEmpty()) {
                break;
            }
            for (CouponExpireTaskPO task : batch) {
                processOne(task.getCouponId());
                processed++;
            }
        }
        return processed;
    }

    /** 单任务执行：按券当前状态选流转（幂等 CAS），真实流转者落事件；任何结局任务终结 */
    private void processOne(long couponId) {
        try {
            UserCoupon coupon = userCouponRepository.findById(couponId).orElse(null);
            if (coupon == null) {
                taskGateway.finish(couponId);   // 券已不存在（人工清理等）：任务意义消失
                return;
            }
            boolean expired = coupon.getValidEnd() != null
                    && !coupon.getValidEnd().isAfter(LocalDateTime.now());
            boolean transitioned = false;
            if (expired && coupon.getStatus() == CouponStatus.USABLE) {
                transitioned = userCouponRepository.casExpireUsable(couponId);
            } else if (expired && coupon.getStatus() == CouponStatus.LOCKED) {
                // 锁定中过期：券收终态 + 占用单过期关闭（用户不再来核销/取消时的兜底收场）
                transitioned = userCouponRepository.casExpireOnLock(couponId);
                if (transitioned) {
                    couponUsageRepository.casExpireByCouponId(couponId);
                }
            }
            if (transitioned) {
                couponEventRepository.append(CouponEvent.expired(coupon, null, LocalDateTime.now()));
            }
            // 已核销/已过期/未到期的券：CAS 影响 0 行，任务同样终结（存在意义已消失）
            taskGateway.finish(couponId);
        } catch (Exception e) {
            log.error("expire task failed → backoff: couponId={}", couponId, e);
            taskGateway.backoff(couponId);
        }
    }

    /**
     * T+1 审计（L2）：仍为"待使用"但过期超 10 分钟的券 = 有券无任务（旁路漏注册）——
     * 自动补注册，下一轮扫描收走。差异超阈值说明注册口被绕过，告警。
     */
    @Scheduled(cron = "${coupon.expire.audit-cron:0 23 4 * * ?}", zone = "Asia/Shanghai")
    public void dailyAudit() {
        try {
            List<Long> leaked = userCouponRepository.selectExpiredUsableIds(1000);
            for (Long couponId : leaked) {
                UserCoupon coupon = userCouponRepository.findById(couponId).orElse(null);
                if (coupon != null) {
                    taskGateway.registerIfAbsent(couponId, coupon.getUserId(), coupon.getValidEnd());
                }
            }
            if (!leaked.isEmpty()) {
                log.warn("expire audit: {} leaked coupons re-registered (旁路漏注册？)", leaked.size());
            }
            log.info("expire daily audit done");
        } catch (Exception e) {
            log.error("expire daily audit failed", e);
        }
    }
}
