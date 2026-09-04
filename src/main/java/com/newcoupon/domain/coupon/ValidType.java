package com.newcoupon.domain.coupon;

/**
 * 有效期类型（coupon_template.valid_type）。
 */
public enum ValidType {

    FIXED(1, "固定区间"),
    AFTER_RECEIVE(2, "领取后N天");

    private final int code;
    private final String desc;

    ValidType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ValidType of(int code) {
        for (ValidType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的有效期类型: " + code);
    }
}
