package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.DeadlineGuard;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 领券入口（C1 唯一库存模型）。
 *
 * <p>旧 Redis 桶/世代/守恒链路已废弃，本类只保留所有模板共用的前置校验，然后委托
 * {@link C1ReceiveAppService}。它不直接碰数据库事务——事务边界仍在
 * {@link CouponIssueTxService#issueC1}，保证“扣库存 + 落券 + 事件 + outbox + 过期任务”同成同败。
 *
 * <p>异步入口 {@link ReceiveAsyncAppService} 复用同一套 C1 交易服务，只是把受理和落库拆开。
 */
@Service
public class ReceiveAppService {

    private static final Logger log = LoggerFactory.getLogger(ReceiveAppService.class);

    private final CachedTemplateReader cachedTemplateReader;
    private final C1ReceiveAppService c1ReceiveAppService;
    private final ReceiveMetrics metrics;
    private final DependencyCircuitBreakers breakers;

    public ReceiveAppService(CachedTemplateReader cachedTemplateReader,
                             C1ReceiveAppService c1ReceiveAppService,
                             ReceiveMetrics metrics,
                             DependencyCircuitBreakers breakers) {
        this.cachedTemplateReader = cachedTemplateReader;
        this.c1ReceiveAppService = c1ReceiveAppService;
        this.metrics = metrics;
        this.breakers = breakers;
    }

    public ReceiveResult receive(ReceiveRequest req) {
        tagGovernanceContext(req);
        var sample = metrics.start();
        try {
            CouponTemplate template = cachedTemplateReader.get(req.templateId())
                    .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND,
                            "id=" + req.templateId()));
            AppOwnership.assertAccessible(template.getAppId(), "template:" + req.templateId());
            template.assertReceivable(LocalDateTime.now());
            DeadlineGuard.requireBudget();

            ReceiveResult result = withBreakers("mysql", ErrorCode.SYSTEM_ERROR, "领券落库",
                    () -> c1ReceiveAppService.receive(req, template));
            metrics.recordSuccess();
            return result;
        } catch (BizException e) {
            recordOutcome(e);
            throw e;
        } finally {
            metrics.stop(sample);
        }
    }

    /**
     * 两层熔断：外层租户级（分闸），内层依赖级（总闸）。内部面无 appId，只用依赖级。
     * C1 的库存/落库都走 MySQL，因此这里包的是 mysql 依赖；Redis 只做缓存/提示，不阻断领券。
     */
    private <T> T withBreakers(String dependency, ErrorCode code, String what, Supplier<T> call) {
        String appId = AppOwnership.currentAppId();
        if (appId == null) {
            return withBreaker(breakers.dependency(dependency), code, what, call);
        }
        return withBreaker(breakers.forTenant(dependency, appId), code, what,
                () -> withBreaker(breakers.dependency(dependency), code, what, call));
    }

    private <T> T withBreaker(io.github.resilience4j.circuitbreaker.CircuitBreaker breaker,
                              ErrorCode code, String what, Supplier<T> call) {
        try {
            return breaker.executeSupplier(call);
        } catch (CallNotPermittedException e) {
            log.warn("依赖熔断打开，快速失败：{}", what);
            throw new BizException(code, "依赖暂时不可用（" + what + "），请稍后重试");
        }
    }

    /** S1 治理上下文：userId/template 维度在应用层补齐（内部面 EMPTY 自动 no-op）。 */
    private static void tagGovernanceContext(ReceiveRequest req) {
        RequestContext ctx = RequestContext.current();
        ctx.user(req.userId());
        ctx.dimension("template", req.templateId());
    }

    private void recordOutcome(BizException e) {
        switch (e.getErrorCode()) {
            case STOCK_OUT -> metrics.recordSoldOut();
            case FROZEN -> metrics.recordFrozen();
            case USER_LIMIT_EXCEEDED, DAILY_LIMIT_EXCEEDED -> metrics.recordUserLimit();
            case DEGRADED -> metrics.recordDegraded();
            default -> { }
        }
    }
}
