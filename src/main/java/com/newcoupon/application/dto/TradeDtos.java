package com.newcoupon.application.dto;

import com.newcoupon.open.api.dto.CalculateRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用券三段的请求/响应 DTO（一个文件收纳，字段少且同族）。
 *
 * <p>锁券由订单服务在下单时调用：传订单号+券id，usageNo（幂等锚点）由本系统生成并返回——
 * 订单侧后续的核销/释放只凭 usageNo 操作，不需要再拼装。
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

    /** 退款请求（第 2 环：退款冲销） */
    public record RefundRequest(
            @NotBlank(message = "usageNo 不能为空") @Size(max = 64) String usageNo) {
    }

    /** 退款结果 */
    public record RefundResult(
            String usageNo,
            String couponStatus,
            long discountAmt,
            boolean duplicated) {
    }
}
