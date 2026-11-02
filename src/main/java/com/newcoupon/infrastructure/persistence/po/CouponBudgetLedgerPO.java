package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 预算账本流水 PO（V42，V6 阶段 1C）。只追加、永不 UPDATE/DELETE：
 * 账户每次变动一条，带变动后的 (reserved, consumed) 快照。同账户的变更都发生在
 * 账户行锁内 → 流水按 id 严格有序 → 对账 = 每账户末条快照 vs 账户行现值。
 */
@TableName("coupon_budget_ledger")
public class CouponBudgetLedgerPO {

    public static final String OP_RESERVE = "RESERVE";
    public static final String OP_CONFIRM = "CONFIRM";
    public static final String OP_RELEASE = "RELEASE";
    public static final String OP_EXPIRE = "EXPIRE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long reservationId;     // 引发变动的预占凭证 id；无凭证的调整=0
    private String op;              // RESERVE/CONFIRM/RELEASE/EXPIRE
    private Long amount;            // 有符号金额（分）
    private Long reservedAfter;
    private Long consumedAfter;
    private String note;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public Long getReservationId() { return reservationId; }
    public void setReservationId(Long reservationId) { this.reservationId = reservationId; }
    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public Long getReservedAfter() { return reservedAfter; }
    public void setReservedAfter(Long reservedAfter) { this.reservedAfter = reservedAfter; }
    public Long getConsumedAfter() { return consumedAfter; }
    public void setConsumedAfter(Long consumedAfter) { this.consumedAfter = consumedAfter; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
