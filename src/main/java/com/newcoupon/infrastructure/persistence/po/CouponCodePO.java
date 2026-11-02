package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 兑换码行（V41，V6 阶段 1B）。
 *
 * <p>状态机：ACTIVE(0) --兑换 CAS--&gt; LOCKED(1) --发放成功--&gt; USED(2)；
 * LOCKED --发放失败/超时回收--&gt; ACTIVE（码可再兑，幂等键保证已发过券的用户不双发）。
 * 1000 个并发兑同一个 ACTIVE 码只有一个 CAS 赢家——输家全部统一文案失败。
 */
@TableName("coupon_code")
public class CouponCodePO {

    /** 0 待兑换（可被兑换 CAS 抢到） */
    public static final int STATUS_ACTIVE = 0;
    /** 1 兑换中（CAS 赢家持锁调 receive；超 5 分钟未结算由兜底回收回 ACTIVE） */
    public static final int STATUS_LOCKED = 1;
    /** 2 已兑换（终态：成功发券，回填 user_id/redeem_time） */
    public static final int STATUS_USED = 2;
    /** 3 已停用（单码下架；终态） */
    public static final int STATUS_DISABLED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    private String codeHash;
    private Integer status;
    private Long userId;
    private LocalDateTime redeemTime;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getRedeemTime() { return redeemTime; }
    public void setRedeemTime(LocalDateTime redeemTime) { this.redeemTime = redeemTime; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
