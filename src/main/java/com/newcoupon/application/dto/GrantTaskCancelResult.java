package com.newcoupon.application.dto;

/**
 * 批量发放任务取消受理结果。
 *
 * <p>accepted=false 表示 CAS 没打中（任务已终态）：取消被拒绝并回当前状态；
 * 取消 CANCELING/CANCELED 的任务是幂等的（不再受理，也不报错）。
 * 已发放的券不受取消影响——取消只阻止"还没发"的 item。
 */
public record GrantTaskCancelResult(
        String taskNo,
        int status,
        boolean accepted
) {
}
