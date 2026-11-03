package com.newcoupon.controller;

import com.newcoupon.application.dto.FinanceDtos.BillQueryRequest;
import com.newcoupon.application.dto.FinanceDtos.PageResult;
import com.newcoupon.application.dto.FinanceDtos.RefundDetail;
import com.newcoupon.application.dto.FinanceDtos.RefundDetailRequest;
import com.newcoupon.application.dto.FinanceDtos.RefundQueryRequest;
import com.newcoupon.application.dto.FinanceDtos.RefundSummary;
import com.newcoupon.application.dto.FinanceDtos.SettlePartyBillView;
import com.newcoupon.application.dto.FinanceDtos.TemplateBillView;
import com.newcoupon.application.service.FinanceQueryService;
import com.newcoupon.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 财务/客服查询（环 3，仅内部面）。
 *
 * <p>为什么全部用 POST：`LegacyAdminTokenAuthenticationFilter`（旧名 AdminTokenFilter）只保护非 GET 的 `/api/**`；
 * 财务数据走 GET 会在现有过滤器下无鉴权。这些路径不在业务写白名单里，
 * 因此自动要求 `X-Admin-Token`（判据反转，新增管理写接口无需登记）。
 *
 * <p>没有开放面版本：租户自助查询需要额外的租户隔离/脱敏/限流，留到环 4。
 */
@RestController
@RequestMapping("/api")
public class FinanceQueryController {

    private final FinanceQueryService service;

    public FinanceQueryController(FinanceQueryService service) {
        this.service = service;
    }

    /** 退款单列表：按 usageNo/refundNo/orderNo/appId/主体/日期（至少一个过滤条件） */
    @PostMapping("/refunds/query")
    public Result<PageResult<RefundSummary>> queryRefunds(
            @Valid @RequestBody RefundQueryRequest req) {
        return Result.success(service.queryRefunds(req));
    }

    /** 退款单明细：订单头 + 按行冲回明细（老式退款无明细时 items 为空） */
    @PostMapping("/refunds/detail")
    public Result<RefundDetail> refundDetail(@Valid @RequestBody RefundDetailRequest req) {
        return Result.success(service.refundDetail(req.refundNo()));
    }

    /** 按结算主体的日账单（日期必填；appId/主体/模板可选） */
    @PostMapping("/bills/settle-party/query")
    public Result<PageResult<SettlePartyBillView>> querySettlePartyBills(
            @Valid @RequestBody BillQueryRequest req) {
        return Result.success(service.querySettlePartyBills(req));
    }

    /** 模板日账单（日期必填；appId/模板可选；join 模板补名称与租户） */
    @PostMapping("/bills/template/query")
    public Result<PageResult<TemplateBillView>> queryTemplateBills(
            @Valid @RequestBody BillQueryRequest req) {
        return Result.success(service.queryTemplateBills(req));
    }
}
