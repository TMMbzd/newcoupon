package com.newcoupon.application.service;

import com.newcoupon.application.dto.ActivityCreateRequest;
import com.newcoupon.application.dto.ActivityResponse;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.mapper.CouponActivityMapper;
import com.newcoupon.infrastructure.persistence.po.CouponActivityPO;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 营销活动应用服务（V6 阶段 1C）——激活 V9 建表但一直无 Service 的 coupon_activity。
 *
 * <p><b>预算账户不在此建</b>：账户行由第一次预占懒建（BudgetTxService.ensureAccount，
 * total 取活动 budget_amount）。理由：预算开关可以事后直改 DB 打开，没有可靠的
 * "建账户时机"锚点——懒建对"创建即开"与"事后开"两种路径一视同仁，也不会给
 * 不开预算的活动留一堆空账户行。
 *
 * <p><b>归属</b>：内部面创建归属内置应用（app_id='1'）；开放面若将来开放活动创建，
 * {@link AppOwnership#ownerForNewResource()} 自然落到调用方——建券时的活动归属
 * 校验（TemplateAppService）两侧同源。
 */
@Service
public class ActivityAppService {

    private static final Logger log = LoggerFactory.getLogger(ActivityAppService.class);
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CouponActivityMapper activityMapper;
    private final CachedActivityReader activityReader;
    private final SnowflakeIdGenerator idGenerator;

    public ActivityAppService(CouponActivityMapper activityMapper,
                              CachedActivityReader activityReader,
                              SnowflakeIdGenerator idGenerator) {
        this.activityMapper = activityMapper;
        this.activityReader = activityReader;
        this.idGenerator = idGenerator;
    }

    /** 创建活动：服务端生成 activityCode（对外暴露它而非自增 id），归属取当前调用方。 */
    public ActivityResponse create(ActivityCreateRequest req) {
        if (!req.startTime().isBefore(req.endTime())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "活动开始时间必须早于结束时间");
        }
        // 活动编码：A+秒级时间戳+雪花尾——比模板的随机后缀更不易撞（活动量小，撞了换一个）
        String activityCode = "A" + LocalDateTime.now().format(CODE_DATE)
                + Math.abs(idGenerator.next() % 1_000_000L);

        CouponActivityPO po = new CouponActivityPO();
        po.setActivityCode(activityCode);
        po.setName(req.name());
        po.setBudgetAmount(req.budgetAmount());
        po.setFunderType(1);
        po.setStartTime(req.startTime());
        po.setEndTime(req.endTime());
        po.setStatus(1);   // 创建即进行中（草稿流不在 1C 范围）
        po.setAppId(AppOwnership.ownerForNewResource());
        po.setBudgetEnabled(Boolean.TRUE.equals(req.budgetEnabled())
                ? CouponActivityPO.BUDGET_ENABLED : CouponActivityPO.BUDGET_DISABLED);
        po.setCreatedBy(req.operator());
        po.setVersion(0L);
        activityMapper.insert(po);
        // 新活动无缓存条目，invalidate 只是防御（创建与首次读取之间不会有旧值）
        activityReader.invalidate(po.getId());
        log.info("activity created: id={}, code={}, budgetAmount={}, budgetEnabled={}, appId={}",
                po.getId(), activityCode, po.getBudgetAmount(), po.getBudgetEnabled(), po.getAppId());
        return ActivityResponse.from(po);
    }

    /** 查询活动：贴着资源读取点做归属校验（照模板/任务的模式）。 */
    public ActivityResponse get(long id) {
        CouponActivityPO po = activityMapper.selectById(id);
        if (po == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND, "id=" + id);
        }
        AppOwnership.assertAccessible(po.getAppId(), "activity:" + id);
        return ActivityResponse.from(po);
    }
}
