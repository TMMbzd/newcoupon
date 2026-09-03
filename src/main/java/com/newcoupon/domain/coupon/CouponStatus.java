package com.newcoupon.domain.coupon;

/**
 * 用户券实例状态（对应 user_coupon.status 列，取值即 code）。
 *
 * <p>状态机的三条资损红线固化在这里（docs/01 第 4.3 节）：
 * <ul>
 *   <li>USED/EXPIRED/INVALID 是终态（terminal=true），终态无出边——已核销的券绝不允许被任何流程打回；</li>
 *   <li>LOCKED 中间态必须有——"下单未支付"期间券既不能给别的订单用、又不能算核销；</li>
 *   <li>所有流转必须经 {@link com.newcoupon.domain.statemachine.CouponStateMachine} 校验，
 *       禁止代码里随手 UPDATE status。</li>
 * </ul>
 */
public enum CouponStatus {

    USABLE(0, "待使用", false),
    LOCKED(1, "已锁定", false),
    USED(2, "已核销", true),
    EXPIRED(3, "已过期", true),
    INVALID(4, "已作废", true);

    private final int code;
    private final String desc;
    private final boolean terminal;

    CouponStatus(int code, String desc, boolean terminal) {
        this.code = code;
        this.desc = desc;
        this.terminal = terminal;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 终态判定：终态没有任何合法出边 */
    public boolean isTerminal() {
        return terminal;
    }

    /** 按 DB 存储值还原枚举（DB 里存的是 TINYINT code） */
    public static CouponStatus of(int code) {
        for (CouponStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的券状态码: " + code);
    }
}
