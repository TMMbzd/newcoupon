package com.newcoupon.domain.coupon;

/**
 * 占用单状态（coupon_usage.status）。占用单是交易链路的幂等锚点与回退裁判（docs/01a Q6）：
 * confirm/release 前先查占用单状态，已处理的重复消息直接返回成功。
 */
public enum UsageStatus {

    OCCUPYING(0, "占用中"),
    CONFIRMED(1, "已核销"),
    RELEASED(2, "已释放");

    private final int code;
    private final String desc;

    UsageStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
