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

    // 构造器注入（而非字段 @Autowired）：依赖显式、不可变、单测可直接 new——企业规范
    public TemplateAppService(TemplateRepository templateRepository,
                              RedisStockGateway redisStockGateway) {
        this.templateRepository = templateRepository;
        this.redisStockGateway = redisStockGateway;
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
        // 发布=整本重置协议（ADR-0009 §2）：stk=total−ic、dbi:=ic、DEL pend、void:=0、DEL so——
        // 重新上线必须走此协议（否则触发"囚禁告警"：剩余量被 void 吞掉）
        CouponTemplate published = templateRepository.findById(id).orElseThrow();
        redisStockGateway.resetAll(id, published.getTotalCount(), published.getIssuedCount());
        log.info("template published (resetAll): id={}, stk={}, dbi={}",
                id, published.getTotalCount() - published.getIssuedCount(), published.getIssuedCount());
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
