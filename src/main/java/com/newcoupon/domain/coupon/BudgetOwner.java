package com.newcoupon.domain.coupon;

/**
 * 券的出资方（五维模型第五维）——优惠的钱由谁承担。
 *
 * <p>编码与 V9 迁移的 funder_type 列对齐（1/2/3，非 0 起）：
 * <ul>
 *   <li>1 = 平台全出（V9 存量行的默认值）</li>
 *   <li>2 = 商家全出</li>
 *   <li>3 = 联合共担（需配 budget_ratio，V19 新列）</li>
 * </ul>
 *
 * <p>业界参照：美团"含本店补贴 3 元"（平台 11 + 商家 3 = 总优惠 14）。
 */
public enum BudgetOwner {
    PLATFORM(1, "平台"),
    MERCHANT(2, "商家"),
    SPLIT(3, "共担");

    private final int code;
    private final String desc;

    BudgetOwner(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }

    public int getCode() {
        return code;
    }

    public static BudgetOwner of(int code) {
        return switch (code) {
            case 1 -> PLATFORM;
            case 2 -> MERCHANT;
            case 3 -> SPLIT;
            default -> throw new IllegalArgumentException("未知的出资方编码: " + code);
        };
    }
}
