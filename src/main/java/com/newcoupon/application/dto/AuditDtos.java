package com.newcoupon.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计查询域 DTO（V6 2C）：内部面四端点（query/detail/export/verify）的请求响应形状。
 *
 * <p>全部 POST（内部面纪律，与 {@link com.newcoupon.controller.FinanceQueryController}
 * 同一先例——参数在 body 里可携带复杂过滤，也不产 GET 缓存面）。
 *
 * <p>列表与详情分工：列表行**不带 before/after 全文**（大快照进列表会把一次查询
 * 变成快照洪水），只给 200 字符截断摘要；要看全文按 id 单查 detail。
 */
public final class AuditDtos {

    private AuditDtos() {
    }

    /**
     * 查询/导出共用的过滤条件。
     *
     * <p>时间窗规则（两个端点同一套校验，见 AuditQueryService.normalizeWindow）：
     * startTime/endTime 都空 = 默认最近 1 天；只给一个 = 参数错；窗口超 31 天 = 参数错。
     * actorType 与 actorId 成对过滤（只给 type = 按类型看全部，如 SYSTEM 巡检）。
     */
    public record AuditFilter(
            @Size(max = 32) String appId,
            @Size(max = 16) String actorType,
            @Size(max = 64) String actorId,
            @Size(max = 128) String action,
            @Size(max = 64) String resourceType,
            @Size(max = 64) String resourceId,
            Integer resultCode,
            LocalDateTime startTime,
            LocalDateTime endTime) {
    }

    /** POST /api/audit/query 请求：过滤条件 + 游标分页参数。 */
    public record AuditQueryRequest(
            Long cursor,
            @Min(1) @Max(200) Integer limit,
            AuditFilter filter) {

        /** filter 允许整个为空（= 默认窗口内全量翻页）。 */
        public static AuditQueryRequest of(Long cursor, Integer limit, AuditFilter filter) {
            return new AuditQueryRequest(cursor, limit, filter);
        }
    }

    /**
     * 列表行：beforeSummary/afterSnapshot 是 200 字符截断摘要（前 197 + "…"）。
     */
    public record AuditRowView(
            Long id, String appId, String actorType, String actorId, String sessionId,
            Long userId, String action, String resourceType, String resourceId,
            Integer resultCode, String ip, String traceId, LocalDateTime createdAt,
            String beforeSummary, String afterSummary) {
    }

    /** 查询响应：nextCursor 非空=还有下一页（传回 cursor 续翻），空=末页。 */
    public record AuditQueryResult(List<AuditRowView> items, Long nextCursor) {
    }

    /** POST /api/audit/detail 请求。 */
    public record AuditDetailRequest(@Min(1) Long id) {
    }

    /** 详情行：含快照全文与哈希链两列（运维核对单条时用）。 */
    public record AuditDetailView(
            Long id, String appId, Long userId, String traceId,
            String action, String resourceType, String resourceId,
            String beforeSnapshot, String afterSnapshot,
            String ip, Integer resultCode,
            String actorType, String actorId, String sessionId, Integer hashVersion,
            String prevHash, String recordHash, LocalDateTime createdAt) {
    }

    /** POST /api/audit/verify 响应：ok=true 全链一致；false 时 breakAtId/reason 给第一个断点。 */
    public record AuditVerifyResult(boolean ok, Long breakAtId, String reason, long checked) {
    }

    /** POST /api/audit/export 请求：与查询同一套过滤（导出=无游标的全量查询）。 */
    public record AuditExportRequest(AuditFilter filter) {
    }

    /** 导出任务视图：创建后轮询用（DONE 后凭 jobNo 一次性下载）。 */
    public record AuditExportJobView(String jobNo, String status,
                                     Long rowCount, String sha256, String error) {
    }

    /** POST /api/audit/export/download 请求。 */
    public record AuditExportDownloadRequest(@Size(max = 64) String jobNo) {
    }
}
