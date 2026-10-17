package com.newcoupon.application.dto;

import com.newcoupon.open.api.dto.CalculateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用券四段的请求/响应 DTO（一个文件收纳，字段少且同族）：
 * 锁券 → 核销/释放 → 退款（支持按行部分退款）。
 *
 * <p>锁券由订单服务在下单时调用：传订单号+券id，usageNo（幂等锚点）由本系统生成并返回——
 * 订单侧后续的核销/释放/退款只凭 usageNo 操作，不需要再拼装。
 */
public final class TradeDtos {

    private TradeDtos() {
    }

    public record LockRequest(
            @NotBlank(message = "orderNo 不能为空") @Size(max = 64) String orderNo,
            @NotNull(message = "userId 不能为空") @Min(1) Long userId,
            @NotNull(message = "couponId 不能为空") @Min(1) Long couponId) {
    }

    public record LockResult(String usageNo, Long couponId, String couponStatus, boolean duplicated) {
    }

    /**
     * 核销请求（第 2 环升级：支持平台算钱）。
     * 新调用方传 items（商品行明细）→ 平台自己算优惠金额（discountAmt 忽略）。
     * 老调用方传 discountAmt → 沿用调用方金额（过渡兼容，不写分账明细）。
     * 两者必须提供其一（服务层校验）。
     */
    public record ConfirmRequest(
            @NotBlank(message = "usageNo 不能为空") @Size(max = 64, message = "usageNo 最长 64 字符（= 存储列宽）") String usageNo,
            @Min(value = 1, message = "抵扣金额必须为正（单位分）") Long discountAmt,
            @Valid
            @Size(max = 100, message = "核销商品最多 100 行")
            List<CalculateRequest.OrderItem> items) {

        /** 过渡兼容构造器：老调用方只传 usageNo + discountAmt */
        public ConfirmRequest(String usageNo, Long discountAmt) {
            this(usageNo, discountAmt, null);
        }
    }

    /** 出资拆分结果（核销时由平台按模板的 budget_owner 计算） */
    public record FundingSplit(long platformBurden, long merchantBurden) {
        public long total() { return platformBurden + merchantBurden; }
    }

    /**
     * 核销时的一条行级分账（内部传递，不直接暴露给接入方）。
     * 守恒：同一占用单所有行的 discountAmt 之和 = 本券优惠；
     * 每行 platformBurden + merchantBurden = discountAmt；全体平台/商家之和 = FundingSplit。
     */
    public record LineShare(String lineNo, String skuId, long lineAmount, int quantity,
                            long discountAmt, long platformBurden, long merchantBurden) {
    }

    public record ConfirmResult(String usageNo, String couponStatus, Long discountAmt, boolean duplicated) {
    }

    public record ReleaseRequest(
            @NotBlank(message = "usageNo 不能为空") @Size(max = 64, message = "usageNo 最长 64 字符（= 存储列宽）") String usageNo,
            @Size(max = 128) String reason) {
    }

    public record ReleaseResult(String usageNo, String couponStatus, boolean duplicated) {
    }

    /** 券包列表项 */
    public record MyCoupon(Long id, String templateCode, Long faceValue, Long threshold,
                           Long discountCap, LocalDateTime validEnd, String status) {
    }

    public record MyCouponList(List<MyCoupon> coupons) {
    }

    /**
     * 退款请求（2026-10-18 行业标准版：支持按商品行 + 数量的部分退款）。
     *
     * <p>{@code refundNo} 是幂等锚点：同一退款单号重放返回首次结果，同号不同参数报冲突。
     * 不传时平台生成 {@code AUTO:<usageNo>}（只适合“整单退一次”的旧调用方）。
     *
     * <p>{@code items} 为空 = 退剩余全部（整单退的语义）；非空 = 只退指定行/数量。
     * 同一行累计退款数量不得超过原数量。
     */
    public record RefundRequest(
            @NotBlank(message = "usageNo 不能为空") @Size(max = 64) String usageNo,
            @Size(max = 64, message = "退款单号最长 64 字符（= 存储列宽）") String refundNo,
            @Valid @Size(max = 100, message = "一次最多退 100 行") List<RefundItem> items) {

        /** 兼容旧调用方：只传 usageNo，由服务层生成 AUTO 退款单号、整单退 */
        public RefundRequest(String usageNo) {
            this(usageNo, null, null);
        }
    }

    /** 退款商品行：行号 + 本次退款数量 */
    public record RefundItem(
            @NotBlank(message = "商品行号不能为空") @Size(max = 64) String lineNo,
            @NotNull(message = "退款数量不能为空") @Min(value = 1, message = "退款数量至少为1")
            Integer quantity) {
    }

    /** 退款结果 */
    public record RefundResult(
            String usageNo,
            String refundNo,
            String refundType,
            String couponStatus,
            String usageStatus,
            /** 本次退款冲回的优惠金额（不是该占用单累计退款额） */
            long discountAmt,
            boolean duplicated) {

        /** 兼容旧调用方/旧测试：只关心占用单、券状态与冲回金额 */
        public RefundResult(String usageNo, String couponStatus, long discountAmt, boolean duplicated) {
            this(usageNo, null, null, couponStatus, null, discountAmt, duplicated);
        }
    }
}
