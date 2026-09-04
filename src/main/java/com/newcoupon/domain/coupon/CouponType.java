package com.newcoupon.domain.coupon;

/**
 * 券类型。一切券型归约为三种本质（docs/00 第 1.1 节）：
 * 满减、满折（无门槛 = 满0减X）。新品券/会员券等只是营销包装，不是新类型。
 * 面额口径：满减存"分"，满折存"折扣基点"（85 折 = 8500，禁止 8.5/85/0.85 三种填法并存）。
 */
public enum CouponType {

    FULL_REDUCTION(1, "满减"),
    DISCOUNT(2, "满折"),
    NO_THRESHOLD(3, "无门槛");

    private final int code;
    private final String desc;

    CouponType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 按 DB 存储值还原枚举；未知值直接抛错（脏数据第一时间暴露，不静默吞掉） */
    public static CouponType of(int code) {
        for (CouponType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的券类型码: " + code);
    }
}
