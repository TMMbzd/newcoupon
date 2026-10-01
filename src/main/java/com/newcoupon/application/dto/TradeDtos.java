package com.newcoupon.application.dto;

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

    public record ConfirmRequest(
            @NotBlank(message = "usageNo 不能为空") @Size(max = 64, message = "usageNo 最长 64 字符（= 存储列宽）") String usageNo,
            @NotNull(message = "discountAmt 不能为空") @Min(value = 1, message = "抵扣金额必须为正（单位分）")
            Long discountAmt) {
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
}
