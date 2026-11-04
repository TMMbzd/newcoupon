package com.newcoupon.controller;

import com.newcoupon.application.dto.SettlementHoldDtos.HoldPlaceRequest;
import com.newcoupon.application.dto.SettlementHoldDtos.HoldQueryRequest;
import com.newcoupon.application.dto.SettlementHoldDtos.HoldQueryResult;
import com.newcoupon.application.dto.SettlementHoldDtos.HoldTransitionRequest;
import com.newcoupon.application.dto.SettlementHoldDtos.HoldView;
import com.newcoupon.application.service.SettlementHoldService;
import com.newcoupon.common.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 券侧应付冻结端点（V47，仅内部面 /api）。全部 POST + {@code @PreAuthorize}——
 * 与 FinanceQueryController / AuditQueryController 同一先例；权限码用 V44 既有种子
 * （settlement:read / settlement:write，PLATFORM_OPERATOR 双码、AUDITOR 只读，无需补种）。
 *
 * <p>端点一览（runbook：docs/runbook/结算最小版.md）：
 * <ul>
 *   <li>POST /api/settlement/holds——建冻结（settlement:write；holdNo 幂等）</li>
 *   <li>POST /api/settlement/holds/release——ACTIVE→RELEASED（settlement:write；重放幂等）</li>
 *   <li>POST /api/settlement/holds/void——ACTIVE→VOIDED（settlement:write；终态互斥）</li>
 *   <li>POST /api/settlement/holds/query——游标分页查询（settlement:read）</li>
 * </ul>
 * 审计在**服务层**挂 AdminActionAuditor（建/释/作废留痕；query 不记）——挂服务层是为了
 * 阶段 4B 风控规则直接调 {@link SettlementHoldService}（非 HTTP 上下文）时同样留痕。
 * hold 语义：只改账单 payable 口径，不动真实资金/不影响用户退款（服务类注释有完整裁定）。
 */
@RestController
@RequestMapping("/api/settlement/holds")
public class SettlementHoldController {

    private final SettlementHoldService holdService;

    public SettlementHoldController(SettlementHoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('settlement:write')")
    public Result<HoldView> place(@Valid @RequestBody HoldPlaceRequest req) {
        return Result.success(holdService.place(req));
    }

    @PostMapping("/release")
    @PreAuthorize("hasAuthority('settlement:write')")
    public Result<HoldView> release(@Valid @RequestBody HoldTransitionRequest req) {
        return Result.success(holdService.release(req.holdNo()));
    }

    @PostMapping("/void")
    @PreAuthorize("hasAuthority('settlement:write')")
    public Result<HoldView> voidHold(@Valid @RequestBody HoldTransitionRequest req) {
        return Result.success(holdService.voidHold(req.holdNo()));
    }

    @PostMapping("/query")
    @PreAuthorize("hasAuthority('settlement:read')")
    public Result<HoldQueryResult> query(@Valid @RequestBody HoldQueryRequest req) {
        return Result.success(holdService.query(req));
    }
}
