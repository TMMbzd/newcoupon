package com.newcoupon.domain.coupon;

/**
 * 占用单状态（coupon_usage.status）。占用单是交易链路的幂等锚点与回退裁判（docs/01a Q6）：
 * confirm/release 前先查占用单状态，已处理的重复消息直接返回成功。
 *
 * <p>L2（2026-09-12）新增第三种终结方式 EXPIRED(3)：**锁定期间券过期**的收场——
 * 既不是核销（无补贴，不该付钱）也不是释放（券已死，不回可用态）。
 * L5（2026-09-15）新增第四种终结方式 TIMEOUT_CLOSED(4)：**锁定超时**的收场——
 * 订单在窗口（默认30分钟）内既未核销也未释放（取消/支付消息丢失、调用方死亡），
 * 系统兜底收场：占用单→超时关闭、券→(未过期?待使用:已过期)。
 * 四种终结对财务是四种不同的账目形态（用了/退了/拖着拖死/订单死了系统收场），
 * 对账与客服归因必须可分辨，不能塞进前几种凑合。
 */
public enum UsageStatus {

    OCCUPYING(0, "占用中"),
    CONFIRMED(1, "已核销"),
    RELEASED(2, "已释放"),
    EXPIRED(3, "过期关闭"),
    TIMEOUT_CLOSED(4, "超时关闭");

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

    public static UsageStatus of(int code) {
        for (UsageStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的占用单状态码: " + code);
    }
}
