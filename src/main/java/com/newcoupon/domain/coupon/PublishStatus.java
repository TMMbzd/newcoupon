package com.newcoupon.domain.coupon;

/**
 * 模板生命周期状态（coupon_template.publish_status）。
 * 只有「审批通过 + 已发布 + 在领取期」的模板才可被领取（docs/01 第 4.3 节）。
 */
public enum PublishStatus {

    UNPUBLISHED(0, "未发布"),
    PUBLISHED(1, "已发布"),
    OFFLINE(2, "已下线");

    private final int code;
    private final String desc;

    PublishStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static PublishStatus of(int code) {
        for (PublishStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的发布状态码: " + code);
    }
}
