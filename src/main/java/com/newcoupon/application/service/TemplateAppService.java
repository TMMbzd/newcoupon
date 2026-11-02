package com.newcoupon.application.service;

import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TemplateResponse;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.BudgetOwner;
import com.newcoupon.domain.coupon.StackingLayer;
import com.newcoupon.domain.coupon.ApplicableScope;
import com.newcoupon.domain.repository.TemplateRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 模板应用服务（用例编排层）：一个方法 = 一个完整用例。
 *
 * <p>职责边界（DDD 分工）：
 * <ul>
 *   <li>这里做：业务编码生成、调用聚合与仓储、DTO 组装、发布时初始化 Redis 库存额度；</li>
 *   <li>这里不做：业务规则判断（在聚合）、SQL/MyBatis 细节（在仓储适配器）、HTTP 语义（在 Controller）。</li>
 * </ul>
 *
 * <p>关于 @Transactional：本类用例均为单条 SQL（数据库自带原子性），不加事务注解；
 * 多写用例的事务收在独立的 TxService（见 application/package-info 的分层铁律）。
 * 随处 @Transactional 不是稳健，是噪音+长事务风险。
 */
@Service
public class TemplateAppService {

    private static final Logger log = LoggerFactory.getLogger(TemplateAppService.class);
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TemplateRepository templateRepository;
    private final CachedTemplateReader cachedTemplateReader;
    private final SettlePartyPolicy settlePartyPolicy;
    private final TemplatePublishTxService templatePublishTxService;

    /** C1 默认分片数；实际 N_eff=min(N,total_count)。字段初始化同时服务无 Spring 的单测。 */
    @Value("${coupon.stock.shard-count:16}")
    private int c1ShardCount = 16;

    // 构造器注入（而非字段 @Autowired）：依赖显式、不可变、单测可直接 new——企业规范
    public TemplateAppService(TemplateRepository templateRepository,
                              CachedTemplateReader cachedTemplateReader,
                              SettlePartyPolicy settlePartyPolicy,
                              TemplatePublishTxService templatePublishTxService) {
        this.templateRepository = templateRepository;
        this.cachedTemplateReader = cachedTemplateReader;
        this.settlePartyPolicy = settlePartyPolicy;
        this.templatePublishTxService = templatePublishTxService;
    }

    /** 建券：生成业务编码 → 聚合工厂校验 → 落库（通用限领 1~99 自 ADR-0006 开放） */
    public CreatedTemplate create(TemplateCreateRequest req) {
        BudgetOwner owner = req.budgetOwner() == null
                ? BudgetOwner.PLATFORM : BudgetOwner.of(req.budgetOwner());
        // 灰度开启时，商家/共担券必须绑定主体；关闭时保持旧行为（允许空，运行期按 __default__ 处理）
        if (settlePartyPolicy.isEnabled()
                && (owner == BudgetOwner.MERCHANT || owner == BudgetOwner.SPLIT)
                && (req.settlePartyId() == null || req.settlePartyId().isBlank())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "商家出资/共担券必须绑定结算主体（settlePartyId）");
        }
        String templateCode = generateCode();
        CouponTemplate template = CouponTemplate.create(CouponTemplate.Creation.builder()
                .templateCode(templateCode)
                .activityId(req.activityId())
                .name(req.name())
                .couponType(CouponType.of(req.couponType()))
                .faceValue(req.faceValue())
                .discountCap(req.discountCap())
                .threshold(req.threshold() == null ? 0L : req.threshold())
                .totalCount(req.totalCount())
                .perUserLimit(req.perUserLimit())
                .dailyLimit(req.dailyLimit() == null ? 0L : req.dailyLimit())
                .perUserDailyLimit(req.perUserDailyLimit() == null ? 0 : req.perUserDailyLimit())
                .validType(ValidType.of(req.validType()))
                .validStart(req.validStart())
                .validEnd(req.validEnd())
                .validDays(req.validDays())
                .appId(AppOwnership.ownerForNewResource())   // S1：开放面=调用方，内部面=内置应用
                // ---- 五维后四维（第 2 环运营入口，2026-10-15）：null=存量默认（全场/店铺级/平台出资）----
                .applicableScope(req.applicableScope() == null
                        ? ApplicableScope.ALL : ApplicableScope.of(req.applicableScope()))
                .scopeIds(req.scopeIds())
                .stackingLayer(req.stackingLayer() == null
                        ? StackingLayer.SHOP : StackingLayer.of(req.stackingLayer()))
                .exclusionGroup(req.exclusionGroup())
                .budgetOwner(owner)
                .budgetRatio(req.budgetRatio())
                .settlePartyId(req.settlePartyId())
                // 唯一库存模型：C1。
                .stockShardCount(c1ShardCount)
                .stockLayoutVersion(1)
                .build());
        CouponTemplate saved = templateRepository.save(template);
        log.info("template created: id={}, code={}, name={}, totalCount={}",
                saved.getId(), templateCode, saved.getName(), saved.getTotalCount());
        cachedTemplateReader.invalidate(saved.getId());   // 防御：清同 id 负缓存
        return new CreatedTemplate(saved.getId(), templateCode);
    }

    /**
     * 发布：聚合内存快检（错误信息友好）→ 仓储 CAS 更新（数据库层挡并发）。
     * 两层校验缺一不可：只有内存检查，两个并发发布会都通过；只有 CAS，输的是干巴巴的"0行受影响"。
     */
    public void publish(long id) {
        CouponTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + id));
        AppOwnership.assertAccessible(template.getAppId(), "template:" + id);   // S1 越权防线
        template.checkPublishable();
        // C1：发布与建分片必须在同一个事务里——否则会出现“已发布但没有库存分片”的
        // 中间态，领券只能报降级。旧 Redis 开桶链路已废弃。
        templatePublishTxService.publishC1(id);
        cachedTemplateReader.invalidate(id);
    }

    /** 查询模板详情 */
    public TemplateResponse get(long id) {
        CouponTemplate t = templateRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + id));
        AppOwnership.assertAccessible(t.getAppId(), "template:" + id);   // S1 越权防线
        return TemplateResponse.from(t);
    }

    /**
     * 业务编码：T + 秒级时间戳 + 6位随机。对外暴露编码而非自增id（防爬虫枚举业务量级）。
     * 随机从3位扩到6位：同秒创建两个模板的相撞概率从1/900（集成测试实测撞过）
     * 降到1/90万——低频M端操作下可接受；若未来编码冲突成为问题，改用"实例序号+原子计数器"。
     */
    private String generateCode() {
        return "T" + LocalDateTime.now().format(CODE_DATE)
                + ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
    }

    /** 建券结果 */
    public record CreatedTemplate(Long id, String templateCode) {
    }
}
