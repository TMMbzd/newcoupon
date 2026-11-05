package com.newcoupon.controller;

import com.newcoupon.application.dto.RiskDtos.DecisionQueryRequest;
import com.newcoupon.application.dto.RiskDtos.DecisionQueryResult;
import com.newcoupon.application.dto.RiskDtos.ListAddRequest;
import com.newcoupon.application.dto.RiskDtos.ListDisableRequest;
import com.newcoupon.application.dto.RiskDtos.ListQueryRequest;
import com.newcoupon.application.dto.RiskDtos.ListQueryResult;
import com.newcoupon.application.dto.RiskDtos.ListView;
import com.newcoupon.application.dto.RiskDtos.ReplayResult;
import com.newcoupon.application.service.RiskDecisionQueryService;
import com.newcoupon.application.service.RiskListService;
import com.newcoupon.common.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 风控内部面端点（V6 4A 最小版，全部 POST——与财务/结算/审计查询同一先例）。
 * 权限码用 V44 既有种子（risk:read / risk:write：PLATFORM_ADMIN 全量、
 * OPERATOR 读写、AUDITOR 只读——已核实无需补种）。
 *
 * <p>端点一览（runbook：docs/runbook/风控最小版.md）：
 * <ul>
 *   <li>POST /api/risk/lists——增名单条目（risk:write；自然键幂等）</li>
 *   <li>POST /api/risk/lists/disable——停用/软删（risk:write；重复停用幂等）</li>
 *   <li>POST /api/risk/lists/query——分页查询（risk:read）</li>
 *   <li>POST /api/risk/decisions/query——决策日志分页查询（risk:read；时间窗 ≤31 天）</li>
 *   <li>POST /api/risk/decisions/{id}/replay——只读回放（risk:read；不改原日志）</li>
 * </ul>
 * 审计在服务层挂 AdminActionAuditor（增/停用留痕，2C 模式——刀 2 风控规则
 * 自动加名单时同样留痕）；query/replay 只读不记。
 */
@RestController
@RequestMapping("/api/risk")
public class RiskController {

    private final RiskListService listService;
    private final RiskDecisionQueryService decisionQueryService;

    public RiskController(RiskListService listService,
                          RiskDecisionQueryService decisionQueryService) {
        this.listService = listService;
        this.decisionQueryService = decisionQueryService;
    }

    @PostMapping("/lists")
    @PreAuthorize("hasAuthority('risk:write')")
    public Result<ListView> add(@Valid @RequestBody ListAddRequest req) {
        return Result.success(listService.add(req));
    }

    @PostMapping("/lists/disable")
    @PreAuthorize("hasAuthority('risk:write')")
    public Result<ListView> disable(@Valid @RequestBody ListDisableRequest req) {
        return Result.success(listService.disable(req));
    }

    @PostMapping("/lists/query")
    @PreAuthorize("hasAuthority('risk:read')")
    public Result<ListQueryResult> queryLists(@Valid @RequestBody ListQueryRequest req) {
        return Result.success(listService.query(req));
    }

    @PostMapping("/decisions/query")
    @PreAuthorize("hasAuthority('risk:read')")
    public Result<DecisionQueryResult> queryDecisions(@Valid @RequestBody DecisionQueryRequest req) {
        return Result.success(decisionQueryService.query(req));
    }

    @PostMapping("/decisions/{id}/replay")
    @PreAuthorize("hasAuthority('risk:read')")
    public Result<ReplayResult> replay(@PathVariable Long id) {
        return Result.success(decisionQueryService.replay(id));
    }
}
