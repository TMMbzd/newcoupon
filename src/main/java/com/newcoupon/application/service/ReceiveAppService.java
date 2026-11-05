package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.DeadlineGuard;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.domain.risk.RiskScene;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetReservationPO;
import com.newcoupon.infrastructure.risk.RiskRequestAttributes;
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
    private final CachedOpenAppReader cachedOpenAppReader;
    private final C1ReceiveAppService c1ReceiveAppService;
    private final ReceiveMetrics metrics;
    private final DependencyCircuitBreakers breakers;
    private final BudgetService budgetService;
    private final UserCouponRepository userCouponRepository;
    private final RiskDecisionService riskDecisionService;

    public ReceiveAppService(CachedTemplateReader cachedTemplateReader,
                             CachedOpenAppReader cachedOpenAppReader,
                             C1ReceiveAppService c1ReceiveAppService,
                             ReceiveMetrics metrics,
                             DependencyCircuitBreakers breakers,
                             BudgetService budgetService,
                             UserCouponRepository userCouponRepository,
                             RiskDecisionService riskDecisionService) {
        this.cachedTemplateReader = cachedTemplateReader;
        this.cachedOpenAppReader = cachedOpenAppReader;
        this.c1ReceiveAppService = c1ReceiveAppService;
        this.metrics = metrics;
        this.breakers = breakers;
        this.budgetService = budgetService;
        this.userCouponRepository = userCouponRepository;
        this.riskDecisionService = riskDecisionService;
    }

    public ReceiveResult receive(ReceiveRequest request) {
        // V43 事件归因：同步入口补链路标记 SYNC。用 ifAbsent 语义——其余三条链路
        // （异步/批量/兑换码）不经过本方法，它们构造 ReceiveRequest 时已自带 source；
        // 若未来有调用方显式传了 source，以它的为准，这里不覆盖。重绑成新局部变量
        // 而不是改参数：方法体后面的 lambda（withBreakers）要求捕获量 effectively final。
        ReceiveRequest req = request.withSourceIfAbsent(CouponEvent.BizContext.SOURCE_SYNC);
        tagGovernanceContext(req);
        var sample = metrics.start();
        // 预算租约（V6 阶段 1C）：活动没开预算时 tryReserveSingle 内部一次缓存判断返回
        // null，以下对 null 的收口全是 no-op——存量链路零变化。开预算时先于库存闸门
        // 预占一张（预算比库存更稀缺，先挡便宜的资源），成败在 finally 式收口里结清。
        CouponBudgetReservationPO budgetLease = null;
        boolean leaseHeld = false;
        try {
            CouponTemplate template = cachedTemplateReader.get(req.templateId())
                    .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND,
                            "id=" + req.templateId()));
            AppOwnership.assertAccessible(template.getAppId(), "template:" + req.templateId());
            // 审查修正⑦：C 端受理口（平台身份，appId=null）此前不查租户生命周期——
            // /open 面有 6013 闸门而这里没有，停用租户的券仍可经平台面继续新发。
            // 已受理的在途任务不回收（202 是受理承诺），登记在 runbook §6.2
            assertTenantIssuable(template.getAppId());
            template.assertReceivable(LocalDateTime.now());
            // V6 4A 风控决策（scene=RECEIVE）：模板校验后、一切资源消耗（时间预算/预算租约/
            // 库存/落库）之前。appId 用**券模板归属租户**而不是调用方身份——黑名单/开关
            // 按"这张券是谁的"生效，内部面（/api）与开放面（/open）行为一致。
            // 开关关=整段跳过且零额外查询（enabled 前置门，审查 C2：后续的幂等快查只在
            // 开时发生）；REJECT 的处置由场景定：领券=抛 7003，退款（刀 2）=照做+打标的
            // 合规红线——所以 decide 只返回结果不抛。设备号/IP 由 RiskContextFilter 透传
            // （服务层不摸 HttpServletRequest）。
            if (riskDecisionService.enabled(template.getAppId())) {
                // 名单三查/日志写入/幂等快查全是 MySQL 访问——包进既有 mysql 熔断段
                // （审查 C8）：慢而不失败的 DB 故障会推高领券 RT 并计入熔断统计，而不是
                // 无声地拖慢。7003 是 BizException，走熔断器的依赖故障白名单谓词，不计失败
                // （黑名单风暴不会误开 mysql 熔断——那会让全量领券 5000）。
                withBreakers("mysql", ErrorCode.SYSTEM_ERROR, "风控决策", () -> {
                    // 幂等重放豁免（审查 C2）：同 requestId 已成功发过券=既有事实的重放，
                    // 不是新的领券意图——拉黑后重试旧请求必须拿回旧结果（duplicated），
                    // 而不是 7003。查询与 C1 幂等判断同键同源。
                    boolean replayed = userCouponRepository
                            .findByIdempotentKey(req.userId(), template.getAppId(), req.requestId())
                            .isPresent();
                    if (replayed) {
                        return null;
                    }
                    RiskDecisionService.RiskDecision risk = riskDecisionService.decide(new RiskContext(
                            RiskScene.RECEIVE, template.getAppId(), req.userId(),
                            RiskRequestAttributes.deviceId(), RiskRequestAttributes.clientIp(),
                            req.requestId(), template.getId()));
                    if (risk.action() == RiskAction.REJECT) {
                        throw new BizException(ErrorCode.RISK_REJECTED);
                    }
                    return null;
                });
            }
            DeadlineGuard.requireBudget();

            budgetLease = reserveBudgetIfNeeded(req, template);
            leaseHeld = budgetLease != null;

            ReceiveResult result = withBreakers("mysql", ErrorCode.SYSTEM_ERROR, "领券落库",
                    () -> c1ReceiveAppService.receive(req, template));
            // duplicated=true 的券是之前那次请求发的、预算当时已确认——本次预占释放，
            // 同一张券绝不吃两份预算；false 才把预占额确认成消耗
            budgetService.settleSingle(budgetLease, !result.duplicated());
            budgetLease = null;   // 已结清，catch 路径不得再释放
            metrics.recordSuccess();
            return result;
        } catch (BizException e) {
            if (budgetLease != null) {
                budgetService.releaseSingle(budgetLease);
                budgetLease = null;
            }
            recordOutcome(e);
            throw e;
        } catch (RuntimeException e) {
            if (budgetLease != null) {
                budgetService.releaseSingle(budgetLease);
                budgetLease = null;
            }
            throw e;
        } finally {
            // 静态担保：上面任何路径漏掉的租约（理论不可达）宁可释放也不留在池里
            if (leaseHeld && budgetLease != null) {
                log.error("budget lease not settled in receive path, releasing: reserveNo={}",
                        budgetLease.getReserveNo());
                budgetService.releaseSingle(budgetLease);
            }
            metrics.stop(sample);
        }
    }

    /**
     * 预算预占（活动开预算才走）：幂等重放（同 requestId 已有券）跳过——那张券的
     * 预算在它首次发放时已确认，这里再预占会在重放上白白占住一份池子（并发重放
     * 甚至会把池子占满）。查询与 C1 内部的幂等判断同键同源。
     */
    private CouponBudgetReservationPO reserveBudgetIfNeeded(ReceiveRequest req,
                                                            CouponTemplate template) {
        if (!budgetService.gateOn(template)) {
            return null;
        }
        boolean replayed = userCouponRepository
                .findByIdempotentKey(req.userId(), template.getAppId(), req.requestId())
                .isPresent();
        if (replayed) {
            return null;
        }
        return budgetService.tryReserveSingle(template,
                CouponBudgetReservationPO.BIZ_SINGLE, req.requestId());
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
    /** 租户非 ACTIVE/SANDBOX_ACTIVE → 6013（与 /open 面同一码同一文案）。缓存读 30s 滞后可接受（停用是分钟级运营动作）。 */
    private void assertTenantIssuable(String appId) {
        cachedOpenAppReader.find(appId).ifPresent(auth -> {
            if (!com.newcoupon.domain.open.AppLifecycle.callableOnOpenFace(auth.lifecycle())) {
                throw new BizException(ErrorCode.TENANT_NOT_ACTIVE);
            }
        });
    }

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
