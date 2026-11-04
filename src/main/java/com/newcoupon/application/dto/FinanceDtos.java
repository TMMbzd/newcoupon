package com.newcoupon.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 财务/客服查询 DTO（环 3）。只开内部面：POST + X-Admin-Token，
 * 避免 GET 在现有过滤器下裸奔。返回到业务标识（userId/userCouponId）供客服定位，
 * 不含姓名/手机号/银行卡/支付信息。
 */
public final class FinanceDtos {

    private FinanceDtos() {
    }

    /**
     * 退款查询。至少提供 usageNo/refundNo/orderNo/fromDate 之一；
     * 日期范围最多 31 天（防止全表扫描/深分页）。
     */
    public record RefundQueryRequest(
            @Size(max = 64) String usageNo,
            @Size(max = 64) String refundNo,
            @Size(max = 64) String orderNo,
            @Size(max = 32) String appId,
            @Size(max = 64) String settlePartyId,
            LocalDate fromDate,
            LocalDate toDate,
            @Min(1) @Max(500) Integer page,
            @Min(1) @Max(200) Integer size) {
    }

    public record RefundDetailRequest(
            @NotBlank @Size(max = 64) String refundNo) {
    }

    public record RefundSummary(
            String refundNo,
            String usageNo,
            String orderNo,
            Long userId,
            Long userCouponId,
            Long templateId,
            String appId,
            String refundType,
            Long refundDiscountAmt,
            Long refundPlatformAmt,
            Long refundMerchantAmt,
            Integer itemCount,
            Integer couponReturned,
            /** false=老式退款无出资明细（platform/merchant=0 不可用于结算） */
            Boolean fundingKnown,
            String couponStatus,
            String usageStatus,
            LocalDateTime createTime) {
    }

    public record RefundLine(
            String lineNo,
            Integer refundQty,
            Long refundDiscountAmt,
            Long refundPlatformAmt,
            Long refundMerchantAmt,
            String appId,
            String settlePartyId,
            LocalDateTime createTime) {
    }

    public record RefundDetail(RefundSummary order, List<RefundLine> items) {
    }

    /**
     * 账单查询：按单个账单日；主体维度可选 appId/settlePartyId/templateId，
     * 模板维度可选 appId/templateId。
     */
    public record BillQueryRequest(
            @NotNull(message = "billDate 不能为空") LocalDate billDate,
            @Size(max = 32) String appId,
            @Size(max = 64) String settlePartyId,
            Long templateId,
            @Min(1) @Max(500) Integer page,
            @Min(1) @Max(200) Integer size) {
    }

    /** V47 起新增四列（老调用方可忽略）：hold 扣减额/应付净额/质量排除行数（可观测） */
    public record SettlePartyBillView(
            LocalDate billDate,
            String appId,
            String settlePartyId,
            Long templateId,
            Integer confirmCount,
            Integer refundCount,
            Long discountTotal,
            Long platformBurden,
            Long merchantBurden,
            Long holdAmount,
            Long payableAmount,
            Integer ineligibleShareCount,
            Integer ineligibleRefundCount) {
    }

    public record TemplateBillView(
            LocalDate billDate,
            Long templateId,
            String templateName,
            String appId,
            Integer confirmCount,
            Integer refundCount,
            Long discountTotal,
            Long platformBurden,
            Long merchantBurden) {
    }

    /** 统一分页结果（page 从 1 开始） */
    public record PageResult<T>(long total, int page, int size, List<T> items) {
    }
}
