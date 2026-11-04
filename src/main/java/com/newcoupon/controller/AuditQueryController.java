package com.newcoupon.controller;

import com.newcoupon.application.dto.AuditDtos.AuditDetailRequest;
import com.newcoupon.application.dto.AuditDtos.AuditDetailView;
import com.newcoupon.application.dto.AuditDtos.AuditExportDownloadRequest;
import com.newcoupon.application.dto.AuditDtos.AuditExportJobView;
import com.newcoupon.application.dto.AuditDtos.AuditQueryRequest;
import com.newcoupon.application.dto.AuditDtos.AuditQueryResult;
import com.newcoupon.application.dto.AuditDtos.AuditVerifyResult;
import com.newcoupon.application.service.AuditExportService;
import com.newcoupon.application.service.AuditQueryService;
import com.newcoupon.application.service.AuditVerifyService;
import com.newcoupon.common.Result;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计查询（V6 2C，仅内部面 /api）。全部 POST + {@code @PreAuthorize}——
 * 与 FinanceQueryController 同一先例；权限码用 2A 的既有体系
 * （V44 种子：audit:read / audit:export，PLATFORM_AUDITOR 角色两者皆有）。
 *
 * <p>端点一览（runbook：docs/runbook/审计查询.md）：
 * <ul>
 *   <li>POST /api/audit/query——游标分页 + 过滤（audit:read）</li>
 *   <li>POST /api/audit/detail——单条全文（audit:read）</li>
 *   <li>POST /api/audit/verify——全链重算，返回第一个断点（audit:read）</li>
 *   <li>POST /api/audit/export——异步导出任务受理/轮询/下载（audit:export）</li>
 * </ul>
 * query/detail/export 自身会写审计（AUDIT_QUERY/AUDIT_EXPORT）；verify 不写——
 * 它是只读完整性检查、无数据出域，按 2C 裁定不在留痕清单（矩阵见 runbook）。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditQueryController {

    private final AuditQueryService queryService;
    private final AuditVerifyService verifyService;
    private final AuditExportService exportService;

    public AuditQueryController(AuditQueryService queryService,
                                AuditVerifyService verifyService,
                                AuditExportService exportService) {
        this.queryService = queryService;
        this.verifyService = verifyService;
        this.exportService = exportService;
    }

    /** 游标分页查询（limit≤200 默认 50；时间窗≤31 天默认最近 1 天；快照只给 200 字摘要）。 */
    @PostMapping("/query")
    @PreAuthorize("hasAuthority('audit:read')")
    public Result<AuditQueryResult> query(@Valid @RequestBody AuditQueryRequest req) {
        return Result.success(queryService.query(req));
    }

    /** 单条详情：快照全文 + 哈希链两列。 */
    @PostMapping("/detail")
    @PreAuthorize("hasAuthority('audit:read')")
    public Result<AuditDetailView> detail(@Valid @RequestBody AuditDetailRequest req) {
        return Result.success(queryService.detail(req));
    }

    /** 全链重算校验：ok=true 或第一个断点 {breakAtId, reason}。一次性运维动作，全表扫描。 */
    @PostMapping("/verify")
    @PreAuthorize("hasAuthority('audit:read')")
    public Result<AuditVerifyResult> verify() {
        return Result.success(verifyService.verifyChain());
    }

    /** 受理导出任务：过滤与查询同构，立即回 jobNo（构建异步进行）。 */
    @PostMapping("/export")
    @PreAuthorize("hasAuthority('audit:export')")
    public Result<AuditExportJobView> export(@RequestBody AuditQueryRequest req) {
        return Result.success(exportService.create(req));
    }

    /** 轮询导出任务状态（DONE 后凭 jobNo 下载；rowCount/sha256 与 manifest 同源）。 */
    @PostMapping("/export/status")
    @PreAuthorize("hasAuthority('audit:export')")
    public Result<AuditExportJobView> exportStatus(@Valid @RequestBody AuditExportDownloadRequest req) {
        return Result.success(exportService.status(req.jobNo()));
    }

    /** 一次性下载：UTF-8 BOM 的 CSV（首行 manifest=行数+SHA-256+时间窗+过滤）。 */
    @PostMapping("/export/download")
    @PreAuthorize("hasAuthority('audit:export')")
    public ResponseEntity<byte[]> download(@Valid @RequestBody AuditExportDownloadRequest req) {
        byte[] csv = exportService.download(req);
        String jobNo = req.jobNo();
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=audit-export-" + jobNo + ".csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }
}
