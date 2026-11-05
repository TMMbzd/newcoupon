package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.domain.risk.RiskScene;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.metrics.RiskMetrics;
import com.newcoupon.infrastructure.persistence.mapper.RiskDecisionLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V6 4B 审查 m3 的单测层钉子：决策日志写失败时 decisionId 是"预铸"的（指向不存在的行）
 * ——RiskDecision 必须如实暴露 logWritten=false，退款 hold 的 reason 换 traceId 兜底。
 */
@DisplayName("决策日志落库状态暴露 + 冻结 reason 兜底线索")
class RiskDecisionLogWrittenTest {

    private static RiskDecisionService service(RiskDecisionLogMapper mapper) {
        SwitchService switchService = mock(SwitchService.class);
        when(switchService.isOn(SwitchKey.RISK_DECISION, "1")).thenReturn(true);
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        when(idGenerator.next()).thenReturn(1234567890L);
        return new RiskDecisionService(switchService, List.of(), mapper,
                idGenerator, mock(RiskMetrics.class), new ObjectMapper());
    }

    @Test
    @DisplayName("写日志失败 → logWritten=false 且 decisionId 仍在（holdNo 用它但 reason 兜底）")
    void logWriteFailureExposed() {
        RiskDecisionLogMapper mapper = mock(RiskDecisionLogMapper.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(mapper).insert(any(com.newcoupon.infrastructure.persistence.po.RiskDecisionLogPO.class));
        RiskDecisionService.RiskDecision decision = service(mapper).decide(
                new RiskContext(RiskScene.REFUND, "1", 42L, null, null, "RF-1"));

        assertThat(decision.action()).isEqualTo(RiskAction.PASS);   // 规则空=放行（fail-open）
        assertThat(decision.logWritten()).as("写失败必须可见——decisionId 指向不存在的行").isFalse();
        assertThat(decision.decisionId()).isNotNull();
    }

    @Test
    @DisplayName("写日志成功 → logWritten=true")
    void logWriteSuccessExposed() {
        RiskDecisionLogMapper mapper = mock(RiskDecisionLogMapper.class);
        org.mockito.Mockito.doReturn(1)
                .when(mapper).insert(any(com.newcoupon.infrastructure.persistence.po.RiskDecisionLogPO.class));
        RiskDecisionService.RiskDecision decision = service(mapper).decide(
                new RiskContext(RiskScene.REFUND, "1", 42L, null, null, "RF-2"));

        assertThat(decision.logWritten()).isTrue();
        assertThat(decision.decisionId()).isNotNull();
    }

    @Test
    @DisplayName("riskHoldReason：日志在=基础文案；缺失=带 traceId（取证线索进 hold 行本身）")
    void holdReasonCarriesTraceIdWhenLogMissing() {
        String base = TradeAppService.riskHoldReason(true, "trace-abc");
        assertThat(base).isEqualTo("风控拦截退款决策降级+冻结结算");

        assertThat(TradeAppService.riskHoldReason(false, "trace-abc"))
                .contains("决策日志缺失").contains("traceId=trace-abc");
        assertThat(TradeAppService.riskHoldReason(false, null))
                .as("无 traceId 也如实标注缺失（老调用路径 MDC 为空）")
                .contains("决策日志缺失").doesNotContain("traceId=");
        assertThat(TradeAppService.riskHoldReason(false, "trace-abc").length())
                .as("reason 拼接后仍在列宽 255 内").isLessThan(255);
    }
}
