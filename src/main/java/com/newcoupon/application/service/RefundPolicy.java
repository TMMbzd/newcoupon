package com.newcoupon.application.service;

/**
 * 一次退款使用的租户政策（环 3）。
 *
 * @param maxRefundTimes          单个占用单最多退款次数
 * @param maxReturnTimes          一券最多退券次数（0=整单退也不退券）
 * @param fullRefundReturnCoupon  整单退完且券有效时，是否退回待使用
 */
public record RefundPolicy(int maxRefundTimes, int maxReturnTimes,
                           boolean fullRefundReturnCoupon) {
}
