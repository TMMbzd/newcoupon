package com.newcoupon.application.service;

import com.newcoupon.application.dto.IssueTaskStatus;
import com.newcoupon.application.dto.ReceiveAsyncResult;
import com.newcoupon.application.dto.ReceiveAsyncStatusResult;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.DeadlineGuard;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.domain.risk.RiskScene;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import com.newcoupon.infrastructure.risk.RiskRequestAttributes;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Service;

/**
 * 异步发放受理编排（ADR-0019 第二层）。
 *
 * <p>流程：模板/归属/可领性校验 → 风控决策（V6 4B，REJECT 任务不建）→ Redis 准入预扣
 * → 同步写任务表 WAL → 202。它不发券；发券由 worker 在 C1 事务里执行。`stat_day`
 * 在受理时固定，worker 不重新取日期。
 *
 * <p>开关默认关闭。模板 allowlist 为空时拒绝启动异步入口；worker 未启用时只告警，
 * 因为 worker 是下一阶段才落地的组件，当前先验证受理/幂等/补偿。
 */
@Service
public class ReceiveAsyncAppService {

    private static final Logger log = LoggerFactory.getLogger(ReceiveAsyncAppService.class);
    private static final Duration REPAIR_PERMIT_TTL = Duration.ofSeconds(2);

    private final CachedTemplateReader cachedTemplateReader;
    private final RedisIssueGate issueGate;
    private final CouponIssueTaskService taskService;
    private final GateCounterRepairService counterRepairService;
    private final GateRebuildService gateRebuildService;
    private final GateRepairQueueService gateRepairQueueService;
    private final com.newcoupon.infrastructure.redis.SoldOutHint soldOutHint;
    private final MeterRegistry meterRegistry;
    private final RiskDecisionService riskDecisionService;
    private final com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers breakers;

    private final boolean asyncEnabled;
    private final boolean workerEnabled;
    private final Set<Long> asyncTemplateIds;
    private final boolean allTemplates;
    private final long idemTtlSeconds;
    private final long dayTtlSeconds;
    private final String redisDownPolicy;

    public ReceiveAsyncAppService(CachedTemplateReader cachedTemplateReader,
                                  RedisIssueGate issueGate,
                                  CouponIssueTaskService taskService,
                                  GateCounterRepairService counterRepairService,
                                  GateRebuildService gateRebuildService,
                                  GateRepairQueueService gateRepairQueueService,
                                  com.newcoupon.infrastructure.redis.SoldOutHint soldOutHint,
                                  MeterRegistry meterRegistry,
                                  RiskDecisionService riskDecisionService,
                                  com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers breakers,
                                  @Value("${coupon.issue.async-enabled:false}") boolean asyncEnabled,
                                  @Value("${coupon.issue.worker.enabled:false}") boolean workerEnabled,
                                  @Value("${coupon.issue.async-template-ids:}") String asyncTemplateIds,
                                  @Value("${coupon.issue.idem-ttl-seconds:604800}") long idemTtlSeconds,
                                  @Value("${coupon.issue.day-ttl-seconds:172800}") long dayTtlSeconds,
                                  @Value("${coupon.issue.gate.redis-down-policy:reject}") String redisDownPolicy) {
        this.cachedTemplateReader = cachedTemplateReader;
        this.issueGate = issueGate;
        this.taskService = taskService;
        this.counterRepairService = counterRepairService;
        this.gateRebuildService = gateRebuildService;
        this.gateRepairQueueService = gateRepairQueueService;
        this.soldOutHint = soldOutHint;
        this.meterRegistry = meterRegistry;
        this.riskDecisionService = riskDecisionService;
        this.breakers = breakers;
        this.asyncEnabled = asyncEnabled;
        this.workerEnabled = workerEnabled;
        this.allTemplates = "*".equals(asyncTemplateIds == null ? "" : asyncTemplateIds.trim());
        this.asyncTemplateIds = parseIds(asyncTemplateIds);
        this.idemTtlSeconds = idemTtlSeconds;
        this.dayTtlSeconds = dayTtlSeconds;
        this.redisDownPolicy = redisDownPolicy == null ? "reject" : redisDownPolicy.trim();
    }

    @PostConstruct
    void validateConfig() {
        if (!asyncEnabled) {
            return;
        }
        if (!allTemplates && asyncTemplateIds.isEmpty()) {
            throw new IllegalStateException(
                    "coupon.issue.async-enabled=true 但 async-template-ids 为空：拒绝无 allowlist 的异步入口");
        }
        if (!workerEnabled) {
            // 受理了却没人执行 = 任务只进不出；必须和 worker 一起打开。
            throw new IllegalStateException(
                    "coupon.issue.async-enabled=true 必须同时设置 coupon.issue.worker.enabled=true");
        }
    }

    /**
     * 受理一次异步领券。返回 202 的语义由 Controller 落实；本方法只保证任务已持久化。
     */
    public ReceiveAsyncResult receive(ReceiveRequest req) {
        CouponTemplate template = cachedTemplateReader.get(req.templateId())
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND,
                        "id=" + req.templateId()));
        if (!asyncEnabled || !allowed(template.getId())) {
            throw new BizException(ErrorCode.DEGRADED, "异步发放未开放");
        }
        AppOwnership.assertAccessible(template.getAppId(), "template:" + req.templateId());
        template.assertReceivable(java.time.LocalDateTime.now());
        // V6 4B 风控决策（scene=RECEIVE，照同步入口同款模式）：受理层拦——REJECT 抛 7003，
        // 任务不建（202 是受理承诺，被拒的意图不构成承诺）。幂等豁免（审查 C2 同款）：
        // 同 requestId 已有任务=重放旧受理，不决策不拒绝。决策段（名单/日志=MySQL 访问）
        // 包进 mysql 依赖级熔断（4A C8 语义：慢故障计入熔断统计而不是无声拖慢受理 RT）。
        if (riskDecisionService.enabled(template.getAppId())) {
            withMysqlBreaker(() -> {
                if (taskService.findByUniqueKey(
                        template.getAppId(), req.userId(), req.requestId()) != null) {
                    return null;   // 重放：拿回既有任务的状态，不产生新决策
                }
                RiskDecisionService.RiskDecision risk = riskDecisionService.decide(
                        new RiskContext(RiskScene.RECEIVE, template.getAppId(), req.userId(),
                                RiskRequestAttributes.deviceId(), RiskRequestAttributes.clientIp(),
                                req.requestId(), template.getId()));
                if (risk.action() == RiskAction.REJECT) {
                    throw new BizException(ErrorCode.RISK_REJECTED);
                }
                return null;
            });
        }
        DeadlineGuard.requireBudget();

        // 受理日只算一次：Redis gate 和任务表必须使用同一个日期，worker 不再取 now。
        LocalDate statDay = LocalDate.now();
        RedisIssueGate.GateCommand cmd = new RedisIssueGate.GateCommand(
                req.templateId(), template.getAppId(), req.userId(), req.requestId(),
                template.getPerUserLimit(), template.getPerUserDailyLimit(), template.getDailyLimit(),
                statDay, idemTtlSeconds, dayTtlSeconds);

        RedisIssueGate.GateResult gate;
        try {
            gate = acquireWithRepair(cmd);
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            return handleRedisDown(req, template, statDay, e);
        } catch (RedisSystemException e) {
            // 主从切换/连接 reset 时，Spring Data Redis 可能把底层 IOException / Lettuce
            // 连接异常包成 RedisSystemException。这仍是"依赖不可用"，必须走 reject/db-fallback
            // 策略；否则 db-fallback 会在故障演练里被绕过，直接返回 5001。
            if (isRedisUnavailable(e)) {
                return handleRedisDown(req, template, statDay, e);
            }
            // 非连接类 Lua/类型错误本身可修复；走到这里说明修复后仍不可用，按可重试依赖故障处理。
            metric("redis_error");
            log.error("issue gate redis system error: templateId={}", req.templateId(), e);
            throw new BizException(ErrorCode.DEGRADED, "库存准入暂时不可用，请稍后重试");
        } catch (DataAccessException e) {
            // 计数修复需要读 DB；DB 也不可用时同样是可重试依赖故障，不能落成泛化 5000。
            metric("db_error");
            log.error("issue gate counter repair DB error: templateId={}", req.templateId(), e);
            throw new BizException(ErrorCode.DEGRADED, "库存准入暂时不可用，请稍后重试");
        }
        if (gate.code() == RedisIssueGate.IDEMPOTENT_HIT) {
            metric("idempotent");
            return existingTaskResult(req, template);
        }
        if (!gate.success()) {
            // Redis idem 丢失/计数漂移时，同一个 requestId 可能已有任务：
            // 这些拒绝码都在 INCR 之前返回，没有本次预扣；先认任务，避免把已受理请求误报成限领/售罄。
            if (gate.code() == RedisIssueGate.USER_TOTAL_LIMIT
                    || gate.code() == RedisIssueGate.USER_DAILY_LIMIT
                    || gate.code() == RedisIssueGate.TEMPLATE_DAILY_LIMIT
                    || gate.code() == RedisIssueGate.STOCK_GATE_EMPTY) {
                CouponIssueTaskPO existing = taskService.findByUniqueKey(
                        template.getAppId(), req.userId(), req.requestId());
                if (existing != null && existing.getTemplateId().equals(req.templateId())) {
                    return new ReceiveAsyncResult(req.requestId(), existing.getId(),
                            IssueTaskStatus.from(existing.getStatus()).name());
                }
            }
            metric("rejected_" + gate.code());
            throw mapGateReject(gate.code());
        }
        metric("ok");

        CouponIssueTaskService.AcceptResult accepted;
        try {
            accepted = taskService.acceptWithResult(req, template, statDay, gate.seq());
        } catch (RuntimeException e) {
            // 任务没落库：把这次预扣退掉，避免“无任务的 gate/计数泄漏”。
            // 补偿自身再失败不能掩盖原始异常，交 gate 重建/对账兜底。
            try {
                issueGate.compensate(cmd);
            } catch (RuntimeException ce) {
                log.error("issue gate compensate failed: templateId={}, requestId={}",
                        req.templateId(), req.requestId(), ce);
            }
            log.error("async issue task insert failed: templateId={}, userId={}, requestId={}",
                    req.templateId(), req.userId(), req.requestId(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        CouponIssueTaskPO task = accepted.task();

        if (!accepted.created()) {
            // 并发窗口/Redis idem 丢失：本次 tryAcquire 的预扣没有对应新任务，必须退掉。
            // 同一 requestId 的任务已经存在，直接返回它的真实状态。
            try {
                issueGate.compensate(cmd);
            } catch (RuntimeException ce) {
                log.warn("issue gate duplicate-accept compensate failed: templateId={}, requestId={}",
                        req.templateId(), req.requestId(), ce);
            }
            if (!task.getTemplateId().equals(req.templateId())) {
                throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                        "该 requestId 已用于 templateId=" + task.getTemplateId() + " 的领取");
            }
            return new ReceiveAsyncResult(req.requestId(), task.getId(),
                    IssueTaskStatus.from(task.getStatus()).name());
        }

        if (!task.getTemplateId().equals(req.templateId())) {
            // 同一 app+user+requestId 已用于另一个模板：本次预扣不能留。
            issueGate.compensate(cmd);
            throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                    "该 requestId 已用于 templateId=" + task.getTemplateId() + " 的领取");
        }

        try {
            issueGate.markAccepted(cmd);
        } catch (RuntimeException e) {
            // 任务已持久化，Redis 标记失败不影响最终发券；worker/查询以任务表为准。
            log.warn("issue gate markAccepted failed: templateId={}, requestId={}",
                    req.templateId(), req.requestId(), e);
        }
        return new ReceiveAsyncResult(req.requestId(), task.getId(),
                IssueTaskStatus.from(task.getStatus()).name());
    }

    /**
     * 状态查询：必须按 appId + userId + requestId 三元组查，不能只按 requestId。
     * 开放面 appId 从签名上下文取；内部面没有上下文，必须显式传。
     */
    public ReceiveAsyncStatusResult status(String requestedAppId, Long userId, String requestId) {
        String currentAppId = AppOwnership.currentAppId();
        if (currentAppId != null && requestedAppId != null && !currentAppId.equals(requestedAppId)) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "requestId 不属于当前应用");
        }
        String appId = currentAppId != null ? currentAppId : requestedAppId;
        if (appId == null || appId.isBlank() || userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "查询任务必须提供 appId 和 userId");
        }
        CouponIssueTaskPO task = taskService.findByUniqueKey(appId, userId, requestId);
        if (task == null) {
            throw new BizException(ErrorCode.ISSUE_TASK_NOT_FOUND);
        }
        return new ReceiveAsyncStatusResult(task.getRequestId(),
                IssueTaskStatus.from(task.getStatus()).name(),
                task.getCouponId(), task.getErrorCode(), task.getErrorMsg());
    }

    private ReceiveAsyncResult existingTaskResult(ReceiveRequest req, CouponTemplate template) {
        CouponIssueTaskPO existing = taskService.findByUniqueKey(
                template.getAppId(), req.userId(), req.requestId());
        if (existing == null) {
            // Redis 说幂等命中但任务表没有：可能是旧键/任务被清理；给可重试的重复码。
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "请求正在处理中，请勿重复提交");
        }
        if (!existing.getTemplateId().equals(req.templateId())) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                    "该 requestId 已用于 templateId=" + existing.getTemplateId() + " 的领取");
        }
        return new ReceiveAsyncResult(req.requestId(), existing.getId(),
                IssueTaskStatus.from(existing.getStatus()).name());
    }

    /**
     * 准入 + 可恢复错误自愈。
     *
     * <p>修复放在单飞许可后面：同一个作用域（模板或模板+用户）同时只有一个请求能修，
     * 其他请求立即返回当前错误，避免坏 key 时 N 个请求一起打数据库。
     * 只修一次、只重试一次；修复失败时不释放许可，让 TTL 充当短冷却。
     * 连续修复交给下一次请求或后台修复任务，不在同步请求里循环。
     */
    private RedisIssueGate.GateResult acquireWithRepair(RedisIssueGate.GateCommand cmd) {
        RedisIssueGate.GateResult gate = issueGate.tryAcquire(cmd);
        int code = gate.code();
        if (!isRepairable(code)) {
            return gate;
        }

        // 售罄提示短路（方案 A）：窗口内（提示 TTL 10s）的请求不抢许可、不重建、不登记，
        // 直接以 -7 返回——洪峰停留在两次 Redis 操作。提示由"赢家重建确认 remaining==0"
        // 或同步链路的售罄确认写入（GateRebuildService/SoldOutHint 是唯一写点）。
        if (code == RedisIssueGate.STOCK_GATE_EMPTY && soldOutHint.isSet(cmd.templateId())) {
            metric("sold_out_hint");
            return gate;
        }

        String owner = UUID.randomUUID().toString();
        String permitKey = repairPermitKey(cmd, code);
        if (!issueGate.tryAcquireRepairPermit(permitKey, owner, REPAIR_PERMIT_TTL)) {
            // 输家不登记修复队列（方案 B 推广，2026-10-30）：六格表推演证明输家登记在
            // "赢家修好/赢家自己会登记"的格子里是冗余或噪音，独有贡献仅"赢家没修好或
            // 死了 + 流量恰好停止"两格的 5 秒恢复——可由计数对账（每小时）兜底。
            // 防击穿是许可的功劳、事故期间用户体验与队列无关，都不依赖这里的登记。
            metric(code == RedisIssueGate.STOCK_GATE_EMPTY ? "sold_out_busy" : "repair_busy");
            return gate;
        }

        String repairType = repairOnce(cmd, code);
        if (repairType == null) {
            return gate;
        }
        metric("repair_" + repairType);

        RedisIssueGate.GateResult retried = issueGate.tryAcquire(cmd);
        if (retried.success() || retried.code() == RedisIssueGate.IDEMPOTENT_HIT) {
            issueGate.releaseRepairPermit(permitKey, owner);
        } else if (isRepairable(retried.code())) {
            // 登记只留赢家分支（修复确实没治好）——它承载队列的真正价值：
            // 死信/attempts/退避是全系统唯一的"修复持续失败"探测器。
            // -7 例外：赢家重建已顺手写了售罄提示，后续请求走顶部短路；
            // 许可不释放，2 秒冷却继续摊销重建。
            if (retried.code() != RedisIssueGate.STOCK_GATE_EMPTY) {
                gateRepairQueueService.enqueue(cmd, retried.code());
            }
        }
        return retried;
    }

    private static boolean isRepairable(int code) {
        return code == RedisIssueGate.GATE_MISSING
                || code == RedisIssueGate.STOCK_GATE_EMPTY
                || code == RedisIssueGate.GATE_CORRUPT
                || code == RedisIssueGate.COUNTER_CORRUPT
                || code == RedisIssueGate.COUNTER_MISSING
                || code == RedisIssueGate.USER_TOTAL_LIMIT
                || code == RedisIssueGate.USER_DAILY_LIMIT
                || code == RedisIssueGate.TEMPLATE_DAILY_LIMIT;
    }

    /**
     * 只执行一次修复。返回修复类型；返回 null 表示不需要修复（例如数据库确认真的达限）。
     */
    private String repairOnce(RedisIssueGate.GateCommand cmd, int code) {
        if (code == RedisIssueGate.GATE_MISSING) {
            rebuildGate(cmd.templateId(), false);
            counterRepairService.repairAll(cmd);
            return "gate_missing";
        }
        if (code == RedisIssueGate.STOCK_GATE_EMPTY) {
            rebuildGate(cmd.templateId(), true);
            counterRepairService.repairAll(cmd);
            return "gate_empty";
        }
        if (code == RedisIssueGate.GATE_CORRUPT) {
            rebuildGate(cmd.templateId(), true);
            counterRepairService.repairAll(cmd);
            return "gate_corrupt";
        }
        if (code == RedisIssueGate.COUNTER_CORRUPT || code == RedisIssueGate.COUNTER_MISSING) {
            counterRepairService.repairAll(cmd);
            return code == RedisIssueGate.COUNTER_CORRUPT ? "counter_corrupt" : "counter_missing";
        }
        if (code == RedisIssueGate.USER_TOTAL_LIMIT
                || code == RedisIssueGate.USER_DAILY_LIMIT
                || code == RedisIssueGate.TEMPLATE_DAILY_LIMIT) {
            if (!counterRepairService.repairLimitIfAllowed(cmd, code)) {
                return null;
            }
            return "limit_" + code;
        }
        return null;
    }

    private static String repairPermitKey(RedisIssueGate.GateCommand cmd, int code) {
        return GateRepairKeys.permitKey(cmd.templateId(), cmd.userId(), code);
    }

    /**
     * 判断异常链是否属于"Redis 不可用"（连接断开/超时），用于把被包装成
     * {@link RedisSystemException} 的依赖故障重新归类到 reject/db-fallback 策略。
     *
     * <p>为什么沿 cause 链匹配：Spring Data Redis 在不同版本/Lettuce 路径下会把底层
     * {@code Connection reset by peer} 包成多层 {@code RedisSystemException →
     * RedisConnectionException → IOException}；只看最外层类型会漏判。匹配项：
     * {@link RedisConnectionFailureException}、{@link QueryTimeoutException}、
     * {@link RedisCommandTimeoutException}、{@link IOException}（连接 reset/closed）。
     *
     * <p>用 identity set 防 cause 链自环（异常链在代理/动态代理下可能出现循环），
     * 避免故障时反而把请求线程卡死。
     */
    static boolean isRedisUnavailable(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && seen.add(current)) {
            if (current instanceof RedisConnectionFailureException
                    || current instanceof QueryTimeoutException
                    || current instanceof RedisCommandTimeoutException
                    || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Redis 连接/超时：默认 fail-closed；可配置 db-fallback 只写 WAL 返回 202，
     * worker 仍由 DB 条件更新终审。fallback 任务 source 标记，worker 终态后失效 gate。
     */
    private ReceiveAsyncResult handleRedisDown(ReceiveRequest req, CouponTemplate template,
                                               LocalDate statDay, RuntimeException error) {
        metric("redis_down_" + redisDownPolicy);
        log.error("issue gate redis unavailable: templateId={}, requestId={}, policy={}",
                req.templateId(), req.requestId(), redisDownPolicy, error);
        if (!"db-fallback".equalsIgnoreCase(redisDownPolicy)) {
            throw new BizException(ErrorCode.DEGRADED, "库存准入暂时不可用，请稍后重试");
        }
        try {
            CouponIssueTaskService.AcceptResult accepted = taskService.acceptWithResult(
                    req, template, statDay, 0, "async-redis-fallback");
            CouponIssueTaskPO task = accepted.task();
            if (!task.getTemplateId().equals(req.templateId())) {
                throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                        "该 requestId 已用于 templateId=" + task.getTemplateId() + " 的领取");
            }
            return new ReceiveAsyncResult(req.requestId(), task.getId(),
                    IssueTaskStatus.from(task.getStatus()).name());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("redis-down fallback task insert failed: templateId={}, requestId={}",
                    req.templateId(), req.requestId(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
    }

    private void metric(String result) {
        try {
            meterRegistry.counter("issue_gate_result_total", "result", result).increment();
        } catch (RuntimeException ignore) {
            // 指标失败不能影响受理
        }
    }

    private void rebuildGate(long templateId, boolean force) {
        gateRebuildService.rebuild(templateId, force);
    }

    private BizException mapGateReject(int code) {
        return switch (code) {
            case RedisIssueGate.TEMPLATE_DAILY_LIMIT -> new BizException(ErrorCode.DAILY_LIMIT_EXCEEDED);
            case RedisIssueGate.USER_TOTAL_LIMIT -> new BizException(ErrorCode.USER_LIMIT_EXCEEDED);
            case RedisIssueGate.USER_DAILY_LIMIT ->
                    new BizException(ErrorCode.USER_LIMIT_EXCEEDED, "已达每日限领上限");
            case RedisIssueGate.STOCK_GATE_EMPTY -> new BizException(ErrorCode.STOCK_OUT);
            case RedisIssueGate.GATE_MISSING -> new BizException(ErrorCode.DEGRADED,
                    "库存 gate 初始化中，请稍后重试");
            case RedisIssueGate.GATE_CORRUPT, RedisIssueGate.COUNTER_CORRUPT,
                 RedisIssueGate.COUNTER_MISSING -> new BizException(ErrorCode.DEGRADED,
                    "库存准入计数修复中，请稍后重试");
            default -> new BizException(ErrorCode.SYSTEM_ERROR, "库存闸门未知返回: " + code);
        };
    }

    private boolean allowed(long templateId) {
        return allTemplates || asyncTemplateIds.contains(templateId);
    }

    /**
     * 风控决策段的 mysql 依赖级熔断包裹（4A C8 语义的受理层版）：慢而不失败的 DB
     * 故障计入熔断统计并快速失败，而不是无声拖慢每个受理；7003 是 BizException，
     * 不在“依赖故障”白名单谓词里，不计失败（黑名单风暴不会误开熔断）。
     */
    private void withMysqlBreaker(java.util.function.Supplier<Void> call) {
        try {
            breakers.dependency(com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers.MYSQL)
                    .executeSupplier(call);
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.warn("依赖熔断打开，风控决策快速失败");
            throw new BizException(ErrorCode.SYSTEM_ERROR, "依赖暂时不可用（风控决策），请稍后重试");
        }
    }

    private static Set<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank() || "*".equals(raw.trim())) {
            return Set.of();
        }
        try {
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("coupon.issue.async-template-ids 格式错误: " + raw, e);
        }
    }
}
