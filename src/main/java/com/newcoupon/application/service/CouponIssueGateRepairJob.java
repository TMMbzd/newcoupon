package com.newcoupon.application.service;

import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueGateRepairMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueGateRepairPO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Redis gate 修复队列的后台消费者（V36）。
 *
 * <p>请求路径只修一次；没修成或没抢到单飞许可的问题登记到数据库。本任务按 SKIP LOCKED 领取，
 * 用和请求路径相同的单飞许可做修复，失败指数退避，超过上限进死信并落漂移日志。
 * 队列在数据库而不是 Redis：Redis 坏了，修复任务仍然存在。
 */
@Service
public class CouponIssueGateRepairJob {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueGateRepairJob.class);

    private final GateRepairClaimService claimService;
    private final CouponIssueGateRepairMapper mapper;
    private final TemplateRepository templateRepository;
    private final GateCounterRepairService counterRepairService;
    private final GateRebuildService gateRebuildService;
    private final RedisIssueGate issueGate;
    private final StockDriftLogGateway driftLog;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final long idemTtlSeconds;
    private final long dayTtlSeconds;

    public CouponIssueGateRepairJob(GateRepairClaimService claimService,
                                    CouponIssueGateRepairMapper mapper,
                                    TemplateRepository templateRepository,
                                    GateCounterRepairService counterRepairService,
                                    GateRebuildService gateRebuildService,
                                    RedisIssueGate issueGate,
                                    StockDriftLogGateway driftLog,
                                    MeterRegistry meterRegistry,
                                    @Value("${coupon.issue.gate-repair.batch-size:50}") int batchSize,
                                    @Value("${coupon.issue.gate-repair.lease-seconds:30}") int leaseSeconds,
                                    @Value("${coupon.issue.gate-repair.max-attempts:8}") int maxAttempts,
                                    @Value("${coupon.issue.idem-ttl-seconds:604800}") long idemTtlSeconds,
                                    @Value("${coupon.issue.day-ttl-seconds:172800}") long dayTtlSeconds) {
        this.claimService = claimService;
        this.mapper = mapper;
        this.templateRepository = templateRepository;
        this.counterRepairService = counterRepairService;
        this.gateRebuildService = gateRebuildService;
        this.issueGate = issueGate;
        this.driftLog = driftLog;
        this.meterRegistry = meterRegistry;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.leaseSeconds = Math.max(5, leaseSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.idemTtlSeconds = idemTtlSeconds;
        this.dayTtlSeconds = dayTtlSeconds;
    }

    @PostConstruct
    void registerGauge() {
        try {
            Gauge.builder("issue_gate_repair_open", mapper,
                            m -> m.countOpen())
                    .description("Redis gate repair queue open rows")
                    .register(meterRegistry);
        } catch (RuntimeException ignore) {
            // 指标注册失败不能影响启动
        }
    }

    @XxlJob("couponIssueGateRepairJob")
    @Scheduled(fixedDelayString = "${coupon.issue.gate-repair.fixed-delay-ms:5000}",
            initialDelayString = "${coupon.issue.gate-repair.initial-delay-ms:5000}")
    public int runOnce() {
        String owner = "gate-repair-" + UUID.randomUUID();
        List<CouponIssueGateRepairPO> rows = claimService.claim(
                batchSize, owner, Duration.ofSeconds(leaseSeconds));
        int done = 0;
        for (CouponIssueGateRepairPO row : rows) {
            try {
                if (repairOne(row, owner)) {
                    done++;
                }
            } catch (RuntimeException e) {
                retryOrDead(row, owner, e);
            }
        }
        return done;
    }

    private boolean repairOne(CouponIssueGateRepairPO row, String owner) {
        Optional<CouponTemplate> templateOpt = templateRepository.findById(row.getTemplateId());
        if (templateOpt.isEmpty()) {
            mapper.markDone(row.getId(), owner);
            metric("template_missing");
            return true;
        }
        CouponTemplate template = templateOpt.get();
        RedisIssueGate.GateCommand cmd = new RedisIssueGate.GateCommand(
                row.getTemplateId(), template.getAppId(), row.getUserId(),
                "repair-" + row.getId(),
                template.getPerUserLimit(), template.getPerUserDailyLimit(), template.getDailyLimit(),
                row.getStatDay(), idemTtlSeconds, dayTtlSeconds);

        String permitKey = GateRepairKeys.permitKey(
                row.getTemplateId(), row.getUserId(), row.getErrorCode());
        String permitOwner = UUID.randomUUID().toString();
        if (!issueGate.tryAcquireRepairPermit(permitKey, permitOwner, Duration.ofSeconds(2))) {
            mapper.markRetry(row.getId(), owner, LocalDateTime.now().plusSeconds(2),
                    "repair permit busy");
            metric("busy");
            return false;
        }

        try {
            boolean repaired = repairByCode(row.getErrorCode(), cmd);
            if (!repaired) {
                mapper.markDone(row.getId(), owner);
                metric("noop");
                return true;
            }
            issueGate.releaseRepairPermit(permitKey, permitOwner);
            mapper.markDone(row.getId(), owner);
            metric("success");
            return true;
        } catch (RuntimeException e) {
            // 故意不释放许可：TTL 充当短冷却，避免失败后所有请求立刻重试。
            retryOrDead(row, owner, e);
            return false;
        }
    }

    private boolean repairByCode(int code, RedisIssueGate.GateCommand cmd) {
        if (code == RedisIssueGate.GATE_MISSING) {
            gateRebuildService.rebuild(cmd.templateId(), false);
            counterRepairService.repairAll(cmd);
            return true;
        }
        if (code == RedisIssueGate.STOCK_GATE_EMPTY) {
            gateRebuildService.rebuild(cmd.templateId(), true);
            counterRepairService.repairAll(cmd);
            return true;
        }
        if (code == RedisIssueGate.GATE_CORRUPT) {
            gateRebuildService.rebuild(cmd.templateId(), true);
            counterRepairService.repairAll(cmd);
            return true;
        }
        if (code == RedisIssueGate.COUNTER_CORRUPT || code == RedisIssueGate.COUNTER_MISSING) {
            counterRepairService.repairAll(cmd);
            return true;
        }
        if (code == RedisIssueGate.USER_TOTAL_LIMIT
                || code == RedisIssueGate.USER_DAILY_LIMIT
                || code == RedisIssueGate.TEMPLATE_DAILY_LIMIT) {
            return counterRepairService.repairLimitIfAllowed(cmd, code);
        }
        return false;
    }

    private void retryOrDead(CouponIssueGateRepairPO row, String owner, RuntimeException e) {
        int attempts = row.getAttempts() == null ? 0 : row.getAttempts();
        int next = attempts + 1;
        String error = truncate(e.getMessage());
        if (next >= maxAttempts) {
            mapper.markDead(row.getId(), owner, error);
            recordDeadLetter(row, error);
            metric("dead");
            return;
        }
        long delaySeconds = Math.min(60L, 1L << Math.min(next, 6));
        mapper.markRetry(row.getId(), owner, LocalDateTime.now().plusSeconds(delaySeconds), error);
        metric("retry");
    }

    private void recordDeadLetter(CouponIssueGateRepairPO row, String error) {
        String detail = "Redis gate repair dead: scope=" + row.getScopeType()
                + ", templateId=" + row.getTemplateId()
                + ", userId=" + row.getUserId()
                + ", errorCode=" + row.getErrorCode()
                + ", attempts=" + row.getAttempts()
                + ", lastError=" + error;
        try {
            driftLog.record(new StockDriftLogGateway.DriftEvent(
                    row.getTemplateId(), 0, 0, 0, 0, 0, null, null, 0,
                    "REDIS_GATE_REPAIR_DEAD", detail, null, null));
        } catch (RuntimeException e) {
            log.error("record gate repair dead letter failed: id={}", row.getId(), e);
        }
        log.error("Redis gate repair DEAD: id={}, templateId={}, userId={}, code={}, attempts={}",
                row.getId(), row.getTemplateId(), row.getUserId(), row.getErrorCode(), row.getAttempts());
    }

    @Scheduled(cron = "${coupon.issue.gate-repair.cleanup-cron:0 23 4 * * ?}")
    public void cleanup() {
        try {
            int deleted = mapper.deleteDoneBefore(LocalDateTime.now().minusDays(7), 1000);
            if (deleted > 0) {
                log.info("gate repair cleanup: deleted={}", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("gate repair cleanup failed", e);
        }
    }

    private void metric(String result) {
        try {
            meterRegistry.counter("issue_gate_repair_job_total", "result", result).increment();
        } catch (RuntimeException ignore) {
            // 指标失败不能影响修复
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 255 ? message : message.substring(0, 255);
    }
}
