package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponIssueGateRepairMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueGateRepairPO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把“请求路径没修成”的 gate 问题登记到数据库。
 *
 * <p>队列放数据库而不是 Redis：Redis 正是可能损坏的组件，修复任务本身不能依赖它。
 * 登记失败只记日志/指标，不能掩盖原始错误。
 */
@Service
public class GateRepairQueueService {

    private static final Logger log = LoggerFactory.getLogger(GateRepairQueueService.class);

    private final CouponIssueGateRepairMapper mapper;
    private final MeterRegistry meterRegistry;

    public GateRepairQueueService(CouponIssueGateRepairMapper mapper, MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 按错误码决定修复作用域：gate 类错误和模板日限是模板级，其他是用户级。
     * 重复登记只提前下次重试时间，不堆行。
     */
    public void enqueue(RedisIssueGate.GateCommand cmd, int errorCode) {
        boolean templateWide = GateRepairKeys.templateWide(errorCode);
        try {
            CouponIssueGateRepairPO po = new CouponIssueGateRepairPO();
            po.setScopeType(templateWide
                    ? CouponIssueGateRepairPO.SCOPE_TEMPLATE
                    : CouponIssueGateRepairPO.SCOPE_USER);
            po.setTemplateId(cmd.templateId());
            po.setUserId(templateWide ? 0L : cmd.userId());
            // 模板日限是模板级，但修复 dly 需要真实 stat_day；gate 类错误没有日期归属。
            po.setStatDay(errorCode == RedisIssueGate.TEMPLATE_DAILY_LIMIT
                    ? cmd.day() : (templateWide ? CouponIssueGateRepairPO.NO_DAY : cmd.day()));
            po.setErrorCode(errorCode);
            po.setNextRetryTime(LocalDateTime.now().plusSeconds(2));
            mapper.upsert(po);
            metric("enqueued");
        } catch (RuntimeException e) {
            metric("enqueue_failed");
            log.warn("enqueue gate repair failed: templateId={}, userId={}, code={}",
                    cmd.templateId(), cmd.userId(), errorCode, e);
        }
    }

    private void metric(String result) {
        try {
            meterRegistry.counter("issue_gate_repair_queue_total", "result", result).increment();
        } catch (RuntimeException ignore) {
            // 指标失败不能影响业务
        }
    }
}
