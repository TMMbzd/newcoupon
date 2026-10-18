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
 *
 * <p>2026-10-18 行级退款新增 PARTIALLY_REFUNDED(6)：订单只退了一部分商品时停在“部分退款”，
 * 全部退完才转 REFUNDED(5)。两者都是售后态，但一个还能继续退、一个已终结。
 */
public enum UsageStatus {

    OCCUPYING(0, "占用中"),
    CONFIRMED(1, "已核销"),
    RELEASED(2, "已释放"),
    EXPIRED(3, "过期关闭"),
    TIMEOUT_CLOSED(4, "超时关闭"),
    REFUNDED(5, "已退款"),
    /** 2026-10-18 行级退款：订单只退了一部分商品，占用单进入“部分退款”；全部退完才转 REFUNDED */
    PARTIALLY_REFUNDED(6, "部分退款"),
    /**
     * 2026-10-18 环 2：支付后核销异常。
     *
     * <p>用户已支付，但带 pricingId 的核销因快照过期/归属不符/券集合不一致/行不一致
     * 而无法完成。占用单进入本状态后：券保持 LOCKED、**不进入 30 分钟超时释放**
     * （否则“已付款 + 券回可用”= 双重优惠），由人工核实订单后按快照原价核销，
     * 或确认订单已退款后释放。
     */
    CONFIRM_EXCEPTION(7, "核销异常");

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
