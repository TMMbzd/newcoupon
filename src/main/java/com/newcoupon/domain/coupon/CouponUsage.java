package com.newcoupon.domain.coupon;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import java.time.LocalDateTime;

/**
 * 占用单聚合：记录"哪张券、为哪个订单、锁了多少、现在什么状态"。
 *
 * <p>它解决三个问题（study/04a 第 0 角色表讲过券与索引，这里同理）：
 * <ul>
 *   <li><b>幂等锚点</b>：usage_no（= 订单号+":"+券id，全表唯一）——支付回调/取消消息
 *       重复推送时，凭它判断"这单处理过没有"，重复消息不会二次改券状态；</li>
 *   <li><b>回退裁判</b>：取消订单时看占用单状态决定动作——占用中→释放；已核销→拒绝
 *       （防"已核销的券被取消消息打回可用"= 一张券花两次的资损事故）；</li>
 *   <li><b>金额载体</b>：本单实际抵扣多少（核销时写入，进事件表供财务对账）。</li>
 * </ul>
 */
public final class CouponUsage {

    private final Long id;
    private final String usageNo;
    private final long userId;
    private final long userCouponId;
    private final String orderNo;
    private final UsageStatus status;
    private final Long discountAmt;
    private final LocalDateTime occupyTime;
    private final LocalDateTime useTime;
    private final LocalDateTime releaseTime;

    private CouponUsage(Long id, String usageNo, long userId, long userCouponId, String orderNo,
                        UsageStatus status, Long discountAmt,
                        LocalDateTime occupyTime, LocalDateTime useTime, LocalDateTime releaseTime) {
        this.id = id;
        this.usageNo = usageNo;
        this.userId = userId;
        this.userCouponId = userCouponId;
        this.orderNo = orderNo;
        this.status = status;
        this.discountAmt = discountAmt;
        this.occupyTime = occupyTime;
        this.useTime = useTime;
        this.releaseTime = releaseTime;
    }

    /**
     * 占用工厂：下单锁券时创建，初始状态=占用中。
     * 校验：券必须属于该用户（防拿别人的券下单）。
     */
    public static CouponUsage occupy(UserCoupon coupon, String orderNo, String usageNo, LocalDateTime now) {
        require(coupon != null, "券不能为空");
        require(coupon.getUserId() > 0, "券未关联用户");
        require(orderNo != null && !orderNo.isBlank(), "订单号必填");
        require(usageNo != null && !usageNo.isBlank(), "占用单号必填");
        require(coupon.getStatus() == CouponStatus.USABLE, "仅待使用的券可被锁定，当前=" + coupon.getStatus().getDesc());
        return new CouponUsage(null, usageNo, coupon.getUserId(), coupon.getId(), orderNo,
                UsageStatus.OCCUPYING, null, now, null, null);
    }

    /** 核销前置校验（内存快检，CAS 兜底） */
    public void checkConfirmable() {
        if (status == UsageStatus.CONFIRMED) {
            return;   // 已核销——幂等场景由调用方直接当成功返回
        }
        if (status == UsageStatus.RELEASED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT, "占用单已释放，不可核销");
        }
    }

    /** 释放前置校验：已核销的占用单绝不允许释放（资损红线：一张券花两次） */
    public void checkReleasable() {
        if (status == UsageStatus.CONFIRMED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "已核销不可释放（已支付订单的退券走退款流程，不改占用单）");
        }
        // OCCUPYING / RELEASED 均可继续（RELEASED=幂等重放，调用方当成功返回）
    }

    /** 从数据库复活：状态以 DB 为准，不重跑校验 */
    public static CouponUsage reconstitute(Long id, String usageNo, long userId, long userCouponId,
                                           String orderNo, UsageStatus status, Long discountAmt,
                                           LocalDateTime occupyTime, LocalDateTime useTime,
                                           LocalDateTime releaseTime) {
        return new CouponUsage(id, usageNo, userId, userCouponId, orderNo, status,
                discountAmt, occupyTime, useTime, releaseTime);
    }

    public CouponUsage withId(long id) {
        return new CouponUsage(id, usageNo, userId, userCouponId, orderNo, status,
                discountAmt, occupyTime, useTime, releaseTime);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BizException(ErrorCode.PARAM_ERROR, message);
        }
    }

    public Long getId() { return id; }
    public String getUsageNo() { return usageNo; }
    public long getUserId() { return userId; }
    public long getUserCouponId() { return userCouponId; }
    public String getOrderNo() { return orderNo; }
    public UsageStatus getStatus() { return status; }
    public Long getDiscountAmt() { return discountAmt; }
    public LocalDateTime getOccupyTime() { return occupyTime; }
    public LocalDateTime getUseTime() { return useTime; }
    public LocalDateTime getReleaseTime() { return releaseTime; }
}
