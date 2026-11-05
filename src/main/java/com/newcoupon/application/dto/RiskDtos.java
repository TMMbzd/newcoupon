package com.newcoupon.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 风控 DTO（V48，V6 4A，内部面 POST）。名单管理与决策查询/回放共用本文件。
 *
 * <p>视图口径：ListView.appId 为 null = 平台级（存储层是空串，见 RiskListEntryPO）；
 * DecisionView.matchedRules 恒非 null（未命中=空列表）。
 */
public final class RiskDtos {

    private RiskDtos() {
    }

    // ------------------------------------------------------------------
    // 名单管理
    // ------------------------------------------------------------------

    /** POST /api/risk/lists 增名单条目（同自然键重放=幂等返回已存在行） */
    public record ListAddRequest(
            /** BLACK / WHITE / GRAY */
            @NotBlank(message = "listType 不能为空") String listType,
            /** USER_ID / DEVICE_ID / IP */
            @NotBlank(message = "valueType 不能为空") String valueType,
            @NotBlank(message = "value 不能为空") @Size(max = 128, message = "value 最长 128 字符") String value,
            /** 可空 = 平台级（对所有租户生效）；非空必须真实存在（服务校验） */
            @Size(max = 32) String appId,
            @NotBlank(message = "reason 不能为空") @Size(max = 255) String reason,
            /** 可空 = 永久；必须晚于当前时间（建出来就过期没有意义，服务校验） */
            LocalDateTime expiresAt) {
    }

    /** POST /api/risk/lists/disable 停用（软删；重复停用幂等） */
    public record ListDisableRequest(@NotNull(message = "id 不能为空") Long id) {
    }

    /** 名单分页查询（游标=上一页最后一条 id；禁 OFFSET，任意深度同价） */
    public record ListQueryRequest(
            @Size(max = 8) String listType,
            @Size(max = 16) String valueType,
            @Size(max = 128) String value,
            @Size(max = 32) String appId,
            /** true = 只看平台级条目（appId 过滤与它互斥使用） */
            Boolean platformOnly,
            @Size(max = 16) String status,
            Long cursor,
            @Min(1) @Max(200) Integer limit) {
    }

    /** 名单条目视图（duplicated=true 幂等重放命中已存在行） */
    public record ListView(
            Long id,
            String listType,
            String valueType,
            String value,
            /** null = 平台级 */
            String appId,
            String reason,
            /** null = 永久 */
            LocalDateTime expiresAt,
            String status,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean duplicated) {
    }

    public record ListQueryResult(List<ListView> items, Long nextCursor) {
    }

    // ------------------------------------------------------------------
    // 决策查询与回放
    // ------------------------------------------------------------------

    /** 决策日志分页查询：时间窗可选，但起止必须成对且跨度 ≤31 天（索引 idx_scene_created 的扫描范围保护） */
    public record DecisionQueryRequest(
            /** RECEIVE / LOCK / CONFIRM / REFUND；空 = 全部 */
            @Size(max = 16) String scene,
            /** PASS / CHALLENGE / REVIEW / REJECT；空 = 全部 */
            @Size(max = 16) String action,
            @Size(max = 32) String appId,
            /** 与 endTime 成对出现；跨度 > 31 天拒绝（1000） */
            LocalDateTime startTime,
            LocalDateTime endTime,
            Long cursor,
            @Min(1) @Max(200) Integer limit) {
    }

    /** 决策日志行视图（factorsJson 原样返回——它是回放快照，不做二次加工） */
    public record DecisionView(
            Long id,
            String scene,
            String appId,
            Long userId,
            String deviceId,
            String ip,
            String factorsJson,
            List<String> matchedRules,
            String action,
            boolean degraded,
            String traceId,
            String requestId,
            LocalDateTime createdAt) {
    }

    public record DecisionQueryResult(List<DecisionView> items, Long nextCursor) {
    }

    /**
     * 回放结果（只读重算，不改原日志行）：
     * originalAction=日志行的最终动作；replayAction=当前规则集重算动作；
     * ruleDiff=逐规则差异（"R_LIST_BLACK:命中→未命中"式）；skippedRules=有副作用
     * 被跳过的规则（设备日限——回放不能替用户多计一次数）。
     */
    public record ReplayResult(
            Long id,
            String originalAction,
            String replayAction,
            List<String> ruleDiff,
            List<String> replayedRules,
            List<SkippedRuleView> skippedRules) {
    }

    /** 被跳过的回放规则（ruleId + 原因——"没算"要说明白，不能静默缺席） */
    public record SkippedRuleView(String ruleId, String reason) {
    }
}
