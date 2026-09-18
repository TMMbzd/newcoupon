package com.newcoupon.application.service;

import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TemplateResponse;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final RedisStockGateway redisStockGateway;
    private final com.newcoupon.infrastructure.persistence.StockDriftLogGateway driftLog;
    private final StockBucketRouter stockBucketRouter;

    // 构造器注入（而非字段 @Autowired）：依赖显式、不可变、单测可直接 new——企业规范
    public TemplateAppService(TemplateRepository templateRepository,
                              RedisStockGateway redisStockGateway,
                              StockDriftLogGateway driftLog,
                              StockBucketRouter stockBucketRouter) {
        this.templateRepository = templateRepository;
        this.redisStockGateway = redisStockGateway;
        this.driftLog = driftLog;
        this.stockBucketRouter = stockBucketRouter;
    }

    /** 建券：生成业务编码 → 聚合工厂校验 → 落库（通用限领 1~99 自 ADR-0006 开放） */
    public CreatedTemplate create(TemplateCreateRequest req) {
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
                .build());
        CouponTemplate saved = templateRepository.save(template);
        log.info("template created: id={}, code={}, name={}, totalCount={}",
                saved.getId(), templateCode, saved.getName(), saved.getTotalCount());
        return new CreatedTemplate(saved.getId(), templateCode);
    }

    /**
     * 发布：聚合内存快检（错误信息友好）→ 仓储 CAS 更新（数据库层挡并发）。
     * 两层校验缺一不可：只有内存检查，两个并发发布会都通过；只有 CAS，输的是干巴巴的"0行受影响"。
     */
    public void publish(long id) {
        CouponTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + id));
        template.checkPublishable();
        boolean updated = templateRepository.casUpdatePublishStatus(
                id, PublishStatus.UNPUBLISHED, PublishStatus.PUBLISHED);
        if (!updated) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "发布失败：模板状态已被并发变更，请刷新后重试");
        }
        // 发布=开桶协议（P2b v2 §7）：casAllocate 逐桶分配（allocated 单调不减）+ 建桶行 + Redis 开桶 Lua。
        // 取代 ADR-0009 的 resetAll 整本重置——发布是状态机一次性迁移（UNPUBLISHED→PUBLISHED CAS 在上），
        // 重发布对账协议（v2 §9）属 P7 appendStock/重新上线链路，届时配 COUNT 对账后开新代桶。
        CouponTemplate published = templateRepository.findById(id).orElseThrow();
        // 评审#5 精神保留：开桶前读旧读数落审计行（能整本改写桶键的路径不得在对账黑匣子里）
        var before = redisStockGateway.conservation(id, 180_000);
        stockBucketRouter.openInitialBuckets(id);
        driftLog.record(new StockDriftLogGateway.DriftEvent(id, 0, before.dbi(), before.pend(),
                published.getTotalCount(), published.getIssuedCount(), null,
                before.stk(), before.sum() - published.getTotalCount(),
                "OPEN_BUCKETS", "发布开桶（前读数留档；旧形态 RESET 已退役）", before.voidRetired(), null));
        log.info("template published (openBuckets): id={}, total={}, 前读数 stk={} pend={} dbi={} void={}",
                id, published.getTotalCount(),
                before.stk(), before.pend(), before.dbi(), before.voidRetired());
    }

    /** 查询模板详情 */
    public TemplateResponse get(long id) {
        CouponTemplate t = templateRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + id));
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
