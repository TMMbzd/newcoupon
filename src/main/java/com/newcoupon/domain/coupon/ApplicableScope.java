package com.newcoupon.domain.coupon;

/**
 * 券的适用范围（五维模型第二维）——这张券能在哪些商品上用。
 * 编码与 V9 迁移的 scope_type 列对齐（0/1/2）。
 */
public enum ApplicableScope {
    ALL(0, "全场"),
    CATEGORY(1, "品类"),
    SKU(2, "单品");

    private final int code;
    private final String desc;

    ApplicableScope(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getDesc() { return desc; }
    public int getCode() { return code; }

    public static ApplicableScope of(int code) {
        return switch (code) {
            case 0 -> ALL;
            case 1 -> CATEGORY;
            case 2 -> SKU;
            default -> throw new IllegalArgumentException("未知的适用范围编码: " + code);
        };
    }
}
