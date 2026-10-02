package com.newcoupon.domain.coupon;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import java.time.LocalDateTime;

/**
 * 券模板聚合根（M 端配置的"一类券"，券:模板 = N:1）。
 *
 * <p>设计原则（DDD 战术模式 + docs/01 第 4.1 节）：
 * <ul>
 *   <li><b>创建即校验</b>：唯一入口 {@link #create(Creation)} 强制全部业务不变量——
 *       不可能存在"字段非法但已构造出来"的模板对象。这是防御性编程的第一层：
 *       把非法状态挡在对象出生之前，而不是散落在各使用处 if 判空。</li>
 *   <li><b>贫血 vs 充血</b>：状态判断逻辑（如 {@link #checkPublishable()}）内聚在聚合里，
 *       而不是写在 Service 里对 getter 做一堆 if——Service 只编排，不承载规则。</li>
 *   <li><b>不可变</b>：字段 final，状态变更返回新实例（withId），杜绝共享可变状态。</li>
 * </ul>
 *
 * <p>不变量清单（违反任何一条 = 资损/事故风险，来源见 docs/00 第 1.4 节"血泪规范"）：
 * <ol>
 *   <li>满折券【必须】有优惠上限 discountCap——无上限满折遇大额订单一笔薅光营销预算（真实资损事故）；</li>
 *   <li>满折面额用基点制（8500 = 85 折），取值 1~9900——杜绝 8.5/85/0.85 三种填法并存的配置事故；</li>
 *   <li>满减券门槛必须 &gt; 0；无门槛券门槛必须 = 0（无门槛 = 满 0 减 X，是独立的券类型）；</li>
 *   <li>固定区间：开始 &lt; 结束；相对有效期：天数 1~365；</li>
 *   <li>面额、总量必须为正。</li>
 * </ol>
 */
public final class CouponTemplate {

    private final Long id;
    private final String templateCode;
    private final long activityId;
    private final String name;
    private final CouponType couponType;
    private final long faceValue;
    private final Long discountCap;   // 满折必填；满减/无门槛为 null
    private final long threshold;     // 使用门槛，分
    private final long totalCount;    // 审批总库存，只增不减
    private final int perUserLimit;
    private final long dailyLimit;            // 批次每日发放上限，0=不限
    private final int perUserDailyLimit;      // 每人每日限领，0=不限
    private final ValidType validType;
    private final LocalDateTime validStart;
    private final LocalDateTime validEnd;
    private final Integer validDays;
    private final ApproveStatus approveStatus;
    private final PublishStatus publishStatus;
    private final long issuedCount;
    /** S1：归属接入方（多租户隔离维度）。内部面创建 = 内置应用；券行写入时从这里冗余 */
    private final String appId;

    private CouponTemplate(Creation c, ApproveStatus approveStatus, PublishStatus publishStatus) {
        this.id = null;
        this.templateCode = c.templateCode();
        this.activityId = c.activityId();
        this.name = c.name();
        this.couponType = c.couponType();
        this.faceValue = c.faceValue();
        this.discountCap = c.discountCap();
        this.threshold = c.threshold();
        this.totalCount = c.totalCount();
        this.perUserLimit = c.perUserLimit();
        this.dailyLimit = c.dailyLimit();
        this.perUserDailyLimit = c.perUserDailyLimit();
        this.validType = c.validType();
        this.validStart = c.validStart();
        this.validEnd = c.validEnd();
        this.validDays = c.validDays();
        this.approveStatus = approveStatus;
        this.publishStatus = publishStatus;
        this.issuedCount = 0L;   // 新建模板已发放量为 0
        this.appId = c.appId();
    }

    private CouponTemplate(CouponTemplate src, Long id) {
        this(src, id, src.issuedCount);
    }

    private CouponTemplate(CouponTemplate src, Long id, long issuedCount) {
        this.id = id;
        this.issuedCount = issuedCount;
        this.templateCode = src.templateCode;
        this.activityId = src.activityId;
        this.name = src.name;
        this.couponType = src.couponType;
        this.faceValue = src.faceValue;
        this.discountCap = src.discountCap;
        this.threshold = src.threshold;
        this.totalCount = src.totalCount;
        this.perUserLimit = src.perUserLimit;
        this.dailyLimit = src.dailyLimit;
        this.perUserDailyLimit = src.perUserDailyLimit;
        this.validType = src.validType;
        this.validStart = src.validStart;
        this.validEnd = src.validEnd;
        this.validDays = src.validDays;
        this.approveStatus = src.approveStatus;
        this.publishStatus = src.publishStatus;
        this.appId = src.appId;
    }

    /**
     * 创建并校验。所有不变量在此强制，任何一条不满足抛 {@link BizException}(PARAM_ERROR)。
     */
    public static CouponTemplate create(Creation c) {
        require(c != null, "创建参数不能为空");
        require(notBlank(c.name()), "券名称必填");
        require(c.name().length() <= 128, "券名称最长 128 字符");
        require(c.activityId() > 0, "activityId 必须为正");
        require(c.templateCode() != null && !c.templateCode().isBlank(), "templateCode 必填");
        require(c.couponType() != null, "券类型必填");
        require(c.faceValue() > 0, "面额必须为正");
        require(c.totalCount() > 0, "总库存必须为正");
        require(c.perUserLimit() >= 1 && c.perUserLimit() <= 99, "每人限领 1~99 张");
        require(c.dailyLimit() >= 0, "批次日限不能为负（0=不限）");
        require(c.perUserDailyLimit() >= 0, "每人日限不能为负（0=不限）");
        require(c.perUserDailyLimit() == 0 || c.perUserDailyLimit() <= c.perUserLimit(),
                "每人日限不能超过每人总限领");
        require(c.validType() != null, "有效期类型必填");

        // —— 券类型相关不变量（资损防线）——
        switch (c.couponType()) {
            case FULL_REDUCTION -> {
                require(c.threshold() > 0, "满减券门槛必须大于 0（无门槛请用 NO_THRESHOLD 类型）");
                require(c.discountCap() == null, "满减券不设折扣上限（面额本身就是上限）");
            }
            case DISCOUNT -> {
                require(c.faceValue() >= 1 && c.faceValue() <= 9900,
                        "满折面额为折扣基点，取值 1~9900（85折=8500）");
                require(c.discountCap() != null && c.discountCap() > 0,
                        "满折券必须设置优惠上限（无上限会被大额订单一笔薅光预算）");
                require(c.threshold() >= 0, "满折门槛不能为负");
            }
            case NO_THRESHOLD -> {
                require(c.threshold() == 0, "无门槛券门槛必须为 0");
                require(c.discountCap() == null, "无门槛券不设折扣上限");
            }
        }

        // —— 有效期不变量 ——
        switch (c.validType()) {
            case FIXED -> {
                require(c.validStart() != null && c.validEnd() != null, "固定区间有效期必须提供起止时间");
                require(c.validStart().isBefore(c.validEnd()), "有效期开始必须早于结束");
            }
            case AFTER_RECEIVE -> {
                require(c.validDays() != null && c.validDays() >= 1 && c.validDays() <= 365,
                        "相对有效期天数必须为 1~365");
            }
        }

        // 阶段策略：创建即视为审批通过（省略审批流，ADR-0005→M6 兑现），发布状态固定未发布
        return new CouponTemplate(c, ApproveStatus.APPROVED, PublishStatus.UNPUBLISHED);
    }

    /**
     * 发布前置校验：审批通过 + 未发布。已发布/已下线的模板不能再发布。
     * 与仓储层的条件 UPDATE（CAS）配合：这里是内存快检（错误信息友好），
     * 数据库层再挡一次并发（两个请求同时通过这里，也只有一个 UPDATE 能成功）。
     */
    public void checkPublishable() {
        if (approveStatus != ApproveStatus.APPROVED) {
            throw new BizException(ErrorCode.TEMPLATE_STATE_INVALID,
                    "审批状态=" + approveStatus.getDesc() + "，仅已通过可发布");
        }
        if (publishStatus != PublishStatus.UNPUBLISHED) {
            throw new BizException(ErrorCode.TEMPLATE_STATE_INVALID,
                    "生命周期状态=" + publishStatus.getDesc() + "，仅未发布可发布");
        }
    }

    /** insert 成功后回填数据库生成的主键，返回携带 id 的新实例（不可变风格） */
    public CouponTemplate withId(long id) {
        return new CouponTemplate(this, id);
    }

    /**
     * 可领取性校验：审批通过 + 已发布 + 在领取期内。
     * 领取期规则（ADR-0004 增补2，M1 前口径）：
     * - FIXED 固定区间券：领取期 = 使用有效期区间 [validStart, validEnd]；
     * - AFTER_RECEIVE 相对有效期券：无领取窗口列（valid_start/valid_end 为 NULL），只校验状态——
     *   "校验后一瞬过期仍领到"的时间窗竞态：原承诺"阶段1补窗口列"未兑现，
     *   现登记为 STATUS L13，随 M1 过期全链路一并补列消除。
     * 状态竞态（校验后被下线）由扣库存 SQL 的 WHERE 守卫兜底（ADR-0004 增补1）。
     */
    public void assertReceivable(LocalDateTime now) {
        if (approveStatus != ApproveStatus.APPROVED) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE,
                    "审批状态=" + approveStatus.getDesc() + "，仅已通过可领取");
        }
        if (publishStatus != PublishStatus.PUBLISHED) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE,
                    "生命周期状态=" + publishStatus.getDesc() + "，仅已发布可领取");
        }
        if (validType == ValidType.FIXED) {
            if (now.isBefore(validStart)) {
                throw new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE, "领取未开始");
            }
            if (now.isAfter(validEnd)) {
                throw new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE, "领取已结束");
            }
        }
    }

    /**
     * 重建（reconstitution）：从持久化数据还原聚合。
     *
     * <p>与 {@link #create} 的区别是 DDD 的重要区分：
     * create 是"出生"——强制当前全部创建规则；reconstitute 是"复活"——
     * 数据落库时已通过当时的校验，<b>不重跑创建校验</b>（否则规则升级后，历史数据全部加载失败，
     * 这是真实事故模式："加了条新校验，线上查询全 500"），也<b>不重置状态</b>——
     * 状态以 DB 为准（曾被误写成走 create()，导致读回来的模板永远是"未发布"）。
     */
    public static CouponTemplate reconstitute(Long id, Creation c, long issuedCount,
                                              ApproveStatus approveStatus, PublishStatus publishStatus) {
        CouponTemplate t = new CouponTemplate(c, approveStatus, publishStatus);
        return new CouponTemplate(t, id, issuedCount);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BizException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public Long getId() {
        return id;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public long getActivityId() {
        return activityId;
    }

    public String getName() {
        return name;
    }

    public CouponType getCouponType() {
        return couponType;
    }

    public long getFaceValue() {
        return faceValue;
    }

    public Long getDiscountCap() {
        return discountCap;
    }

    public long getThreshold() {
        return threshold;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getIssuedCount() {
        return issuedCount;
    }

    /** S1：归属接入方（多租户隔离维度） */
    public String getAppId() {
        return appId;
    }

    public int getPerUserLimit() {
        return perUserLimit;
    }

    public long getDailyLimit() {
        return dailyLimit;
    }

    public int getPerUserDailyLimit() {
        return perUserDailyLimit;
    }

    public ValidType getValidType() {
        return validType;
    }

    public LocalDateTime getValidStart() {
        return validStart;
    }

    public LocalDateTime getValidEnd() {
        return validEnd;
    }

    public Integer getValidDays() {
        return validDays;
    }

    public ApproveStatus getApproveStatus() {
        return approveStatus;
    }

    public PublishStatus getPublishStatus() {
        return publishStatus;
    }

    /**
     * 领域命令对象（注意：不是 Controller 的 DTO——领域自己定义入参形状，
     * 上层负责把外部协议翻译过来，这是"防腐"的第一步）。
     */
    public record Creation(
            String templateCode,
            long activityId,
            String name,
            CouponType couponType,
            long faceValue,
            Long discountCap,
            long threshold,
            long totalCount,
            int perUserLimit,
            long dailyLimit,
            int perUserDailyLimit,
            ValidType validType,
            LocalDateTime validStart,
            LocalDateTime validEnd,
            Integer validDays,
            /** S1：归属接入方（多租户隔离维度）。默认内置应用——内部面与既有代码无需显式传 */
            String appId) {

        /** 手写 Builder：15 个字段的长构造器可读性极差（"第 7 个参数是啥"），测试里只改一个字段的写法依赖它 */
        public static CreationBuilder builder() {
            return new CreationBuilder();
        }

        public static final class CreationBuilder {
            private String templateCode;
            private long activityId;
            private String name;
            private CouponType couponType;
            private long faceValue;
            private Long discountCap;
            private long threshold;
            private long totalCount;
            private int perUserLimit;
            private long dailyLimit;
            private int perUserDailyLimit;
            private ValidType validType;
            private LocalDateTime validStart;
            private LocalDateTime validEnd;
            private Integer validDays;
            /** 默认归属内置应用：内部面创建与单元测试不传也合法（S1 多租户隔离的兼容策略） */
            private String appId = com.newcoupon.domain.open.OpenApp.BUILTIN_APP_ID;

            private CreationBuilder() {
            }

            public CreationBuilder templateCode(String v) { this.templateCode = v; return this; }
            public CreationBuilder activityId(long v) { this.activityId = v; return this; }
            public CreationBuilder name(String v) { this.name = v; return this; }
            public CreationBuilder couponType(CouponType v) { this.couponType = v; return this; }
            public CreationBuilder faceValue(long v) { this.faceValue = v; return this; }
            public CreationBuilder discountCap(Long v) { this.discountCap = v; return this; }
            public CreationBuilder threshold(long v) { this.threshold = v; return this; }
            public CreationBuilder totalCount(long v) { this.totalCount = v; return this; }
            public CreationBuilder perUserLimit(int v) { this.perUserLimit = v; return this; }
            public CreationBuilder dailyLimit(long v) { this.dailyLimit = v; return this; }
            public CreationBuilder perUserDailyLimit(int v) { this.perUserDailyLimit = v; return this; }
            public CreationBuilder validType(ValidType v) { this.validType = v; return this; }
            public CreationBuilder validStart(LocalDateTime v) { this.validStart = v; return this; }
            public CreationBuilder validEnd(LocalDateTime v) { this.validEnd = v; return this; }
            public CreationBuilder validDays(Integer v) { this.validDays = v; return this; }
            public CreationBuilder appId(String v) { this.appId = v; return this; }

            public Creation build() {
                return new Creation(templateCode, activityId, name, couponType, faceValue,
                        discountCap, threshold, totalCount, perUserLimit, dailyLimit,
                        perUserDailyLimit, validType, validStart, validEnd, validDays, appId);
            }
        }
    }
}
