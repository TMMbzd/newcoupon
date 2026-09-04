package com.newcoupon.domain.coupon;

/**
 * 券事件类型（coupon_event.event_type）。事件表是唯一事实源：
 * 领取/锁定/核销/释放/过期/作废全部落事件，统计与对账从这里回放（docs/01 第 4.1 节）。
 */
public enum EventType {

    ISSUED(1, "发放"),
    LOCKED(2, "锁定"),
    USED(3, "核销"),
    RELEASED(4, "释放回退"),
    EXPIRED(5, "过期"),
    INVALIDATED(6, "作废");

    private final int code;
    private final String desc;

    EventType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static EventType of(int code) {
        for (EventType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的事件类型码: " + code);
    }
}
