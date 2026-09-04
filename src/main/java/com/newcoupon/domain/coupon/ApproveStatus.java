package com.newcoupon.domain.coupon;

/**
 * 模板审批状态（coupon_template.approve_status）。
 * 与发布状态正交（双状态机，docs/01 第 4.3 节）：审批管"内容对不对"，发布管"能不能领"。
 * 阶段0省略审批流（创建即视为审批通过），见 docs/adr/ADR-0005；交叉审核在阶段3补齐。
 */
public enum ApproveStatus {

    PENDING(0, "待审"),
    APPROVED(1, "已通过"),
    REJECTED(2, "已拒绝");

    private final int code;
    private final String desc;

    ApproveStatus(int code, String desc) {
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
