package com.newcoupon.domain.coupon;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户券聚合：一个用户持有的一张具体券。
 *
 * <p>与模板的关系（docs/01 第 4.1 节"模板/实例分离"）：模板是"一类券的定义"，
 * 用户券是"一张券的个体"。本类在领取时固化【规则快照】——面额/门槛/上限从模板拷贝、
 * 过期时间按规则定死——此后模板再怎么改，这张券不受影响（防"历史券变性"资损）。
 *
 * <p>与 CouponTemplate 相同的两条构造路径纪律：
 * {@link #issue} = 出生（强制校验 + 快照固化）；{@link #reconstitute} = 从数据库复活（状态以 DB 为准，不重跑校验）。
 */
public final class UserCoupon {

    private final Long id;
    private final long userId;
    private final long templateId;
    private final String templateCode;
    // —— 规则快照（领取时从模板拷贝，此后免疫模板变更）——
    private final long faceValue;
    private final long threshold;
    private final Long discountCap;
    private final LocalDateTime validEnd;
    private final CouponStatus status;
    private final LocalDateTime receiveTime;
    private final String idempotentKey;
    private final int seq;   // 同用户同模板内的发放序号(1..N)，来自 Redis 计数——uk_user_seq 唯一索引的物理载体

    private UserCoupon(Long id, long userId, long templateId, String templateCode,
                       long faceValue, long threshold, Long discountCap,
                       LocalDateTime validEnd, CouponStatus status,
                       LocalDateTime receiveTime, String idempotentKey, int seq) {
        this.id = id;
        this.userId = userId;
        this.templateId = templateId;
        this.templateCode = templateCode;
        this.faceValue = faceValue;
        this.threshold = threshold;
        this.discountCap = discountCap;
        this.validEnd = validEnd;
        this.status = status;
        this.receiveTime = receiveTime;
        this.idempotentKey = idempotentKey;
        this.seq = seq;
    }

    /**
     * 领取工厂：模板可领性校验 → 参数校验 → 快照拷贝 → 过期时间固化。
     * 初始状态固定为"待使用"。
     */
    public static UserCoupon issue(CouponTemplate template, long userId,
                                   String idempotentKey, int seq, LocalDateTime now) {
        template.assertReceivable(now);
        require(userId > 0, "userId 必须为正");
        require(idempotentKey != null && !idempotentKey.isBlank(), "幂等键(requestId)必填");
        require(seq >= 1, "发放序号必须>=1（来自Redis用户计数）");
        return new UserCoupon(null, userId, template.getId(), template.getTemplateCode(),
                template.getFaceValue(), template.getThreshold(), template.getDiscountCap(),
                resolveValidEnd(template, now), CouponStatus.USABLE, now, idempotentKey, seq);
    }

    /**
     * 过期时间固化规则（docs/00 第 1.4 节血泪规范）：
     * 统一归一到【当日 23:59:59】——
     * - 固定区间券：取模板 validEnd 的日期，归一到当日末尾（运营把结束配成 00:00:00 也不至于当天不能用）；
     * - 相对有效期券：领取日 + validDays 天的当日末尾（杜绝"20:35 领的券 7 天后 20:35 过期、当晚用不了"的客诉）。
     */
    private static LocalDateTime resolveValidEnd(CouponTemplate template, LocalDateTime now) {
        LocalDate endDate = template.getValidType() == ValidType.FIXED
                ? template.getValidEnd().toLocalDate()
                : now.toLocalDate().plusDays(template.getValidDays());
        return endDate.atTime(23, 59, 59);
    }

    /** 从数据库复活：不重跑领取校验，全部字段以 DB 行为准 */
    public static UserCoupon reconstitute(Long id, long userId, long templateId, String templateCode,
                                          long faceValue, long threshold, Long discountCap,
                                          LocalDateTime validEnd, CouponStatus status,
                                          LocalDateTime receiveTime, String idempotentKey, int seq) {
        return new UserCoupon(id, userId, templateId, templateCode, faceValue, threshold,
                discountCap, validEnd, status, receiveTime, idempotentKey, seq);
    }

    /** insert 成功后回填主键 */
    public UserCoupon withId(long id) {
        return new UserCoupon(id, userId, templateId, templateCode, faceValue, threshold,
                discountCap, validEnd, status, receiveTime, idempotentKey, seq);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BizException(ErrorCode.PARAM_ERROR, message);
        }
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public long getTemplateId() { return templateId; }
    public String getTemplateCode() { return templateCode; }
    public long getFaceValue() { return faceValue; }
    public long getThreshold() { return threshold; }
    public Long getDiscountCap() { return discountCap; }
    public LocalDateTime getValidEnd() { return validEnd; }
    public CouponStatus getStatus() { return status; }
    public LocalDateTime getReceiveTime() { return receiveTime; }
    public String getIdempotentKey() { return idempotentKey; }
    public int getSeq() { return seq; }
}
