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
        if (status == UsageStatus.EXPIRED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT, "占用单已过期关闭，不可核销");
        }
        // 2026-10-15 补（用户追问"状态一变就让重试、调用方会不会无限重试"时发现）：
        // 退款后到达的重复核销此前没有分支——它会一路走到 usage CAS 砍空、被编排层分类成
        // 4002"请刷新后重试"。但 REFUNDED 是死状态（核销永远不可能再成功），
        // 可重试文案会诱使调用方对死状态无限重试。补明确答复：4004，不可重试。
        if (status == UsageStatus.REFUNDED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT, "占用单已退款，不可核销");
        }
        if (status == UsageStatus.PARTIALLY_REFUNDED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT, "占用单已部分退款，不可再次核销");
        }
        // L5（2026-09-15）：超时关闭后迟到的核销——独立错误码而非笼统冲突：
        // 订单侧拿到 4006 走无券价/整单退款（券可能已被别的订单用掉，放行=一券两花）
        if (status == UsageStatus.TIMEOUT_CLOSED) {
            throw new BizException(ErrorCode.USAGE_TIMEOUT_CLOSED);
        }
    }

    /**
     * 释放前置校验：已核销/已退款/部分退款的占用单绝不允许释放。
     * 已支付订单要退钱走退款流程；把售后中的合同当“取消”返回成功，会让调用方误以为订单已取消。
     * 其余终态（已释放/过期关闭/超时关闭）由调用方按幂等成功处理（取消意图已达成）。
     */
    public void checkReleasable() {
        if (status == UsageStatus.CONFIRMED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "已核销不可释放（已支付订单的退券走退款流程，不改占用单）");
        }
        if (status == UsageStatus.REFUNDED || status == UsageStatus.PARTIALLY_REFUNDED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "占用单状态=" + status.getDesc() + "，已支付订单不可释放（售后走退款流程）");
        }
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
