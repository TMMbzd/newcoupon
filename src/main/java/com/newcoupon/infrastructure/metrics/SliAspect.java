package com.newcoupon.infrastructure.metrics;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * SLI 切面（V6 5A）：拦截 {@link SliJourney @SliJourney} 标记的方法，在服务边界记
 * {@code coupon_sli_events_total}（按业务码判定 good/bad/excluded）与
 * {@code coupon_sli_duration_seconds}（成败路径都计时，finally 停表）。
 *
 * <p><b>判定规则</b>（与 {@link SliMetrics#classify}、docs/operations/SLO.md 三处同步）：
 * 正常返回=good；抛 {@link BizException}=按错误码分类（5xxx→bad，业务拒绝→excluded）；
 * 其他未捕获异常=bad。切面自身只在 finally 里做两件事（记数/停表），两者都有
 * safeIncrement/内部兜底，绝不向业务抛指标故障。
 */
@Aspect
@Component
public class SliAspect {

    private final SliMetrics sliMetrics;

    public SliAspect(SliMetrics sliMetrics) {
        this.sliMetrics = sliMetrics;
    }

    @Around("@annotation(sliJourney)")
    public Object around(ProceedingJoinPoint pjp, SliJourney sliJourney) throws Throwable {
        SliMetrics.Journey journey = sliJourney.value();
        Timer.Sample sample = sliMetrics.start();
        try {
            Object result = pjp.proceed();
            sliMetrics.recordOutcome(journey, ErrorCode.SUCCESS);
            return result;
        } catch (BizException e) {
            sliMetrics.recordOutcome(journey, e.getErrorCode());
            throw e;
        } catch (Throwable t) {
            sliMetrics.recordUncaught(journey);
            throw t;
        } finally {
            // 计时覆盖全部出口（good/bad/excluded 的耗时都算链路延迟）；停表失败不影响业务
            try {
                sliMetrics.stop(sample, journey);
            } catch (RuntimeException ignored) {
                // Timer.stop 只做内存累加，这里属于理论兜底
            }
        }
    }
}
