package com.newcoupon.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 兑换码 LOCKED 残留独立回收器（V6 阶段 1B）。默认关闭——
 * {@code coupon.grant.worker.enabled=true} 的部署里 {@link GrantTaskWorker}
 * 每轮领取前已顺带回收（两域都是低频后台作业，一套调度够用）；
 * 只有"没开 grant worker 但用兑换码"的部署才需要单独打开本调度
 * （{@code coupon.code.reap-enabled=true}），否则崩窗残留的码要等
 * 到 5 分钟阈值后**没有任何人**回收——码卡 LOCKED，统一文案拒绝兑换。
 *
 * <p>正确性不依赖单飞：回收 UPDATE 自带 status=1 条件，多实例同跑只多扫不重复回收。
 */
@Service
@ConditionalOnProperty(prefix = "coupon.code", name = "reap-enabled", havingValue = "true")
public class CouponCodeReaper {

    private static final Logger log = LoggerFactory.getLogger(CouponCodeReaper.class);

    private final CouponCodeService codeService;

    public CouponCodeReaper(CouponCodeService codeService) {
        this.codeService = codeService;
    }

    /** 60 秒一轮：回收阈值本身 5 分钟，扫描粒度无需更细。 */
    @Scheduled(fixedDelayString = "${coupon.code.reap-fixed-delay-ms:60000}",
            initialDelayString = "${coupon.code.reap-initial-delay-ms:120000}")
    public void scheduledTick() {
        try {
            int reaped = codeService.reapStaleLockedCodes();
            if (reaped > 0) {
                log.warn("code reap: {} stale LOCKED code(s) restored to ACTIVE", reaped);
            }
        } catch (RuntimeException e) {
            // 后台任务不许抛：一轮失败下一轮再来，残留码的回收只是延后不是丢失
            log.error("code reap round failed", e);
        }
    }
}
