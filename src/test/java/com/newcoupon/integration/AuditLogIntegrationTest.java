package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.newcoupon.application.service.CachedTemplateReader;
import com.newcoupon.application.service.AuditService;
import com.newcoupon.application.service.discount.DiscountCalculatorService;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.AuditLogMapper;
import com.newcoupon.infrastructure.persistence.po.AuditLogPO;
import com.newcoupon.open.api.dto.CalculateRequest;
import com.newcoupon.open.api.dto.CalculateResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * 操作审计底座·第 1 环验收测试。
 *
 * <p>三个验收点（对应 V5 计划第 1 步 1-1 项）：
 * <ol>
 *   <li><b>六要素齐全</b>：调用带 @AuditLog 的方法后，审计表里出现一条记录，
 *       包含谁（appId）/何时/何处/做了什么（action+resource）/前后值快照/链路 ID。</li>
 *   <li><b>哈希链有效</b>：连续多条记录的 prev_hash 逐条衔接。</li>
 *   <li><b>篡改可检出</b>：直接修改数据库里一条记录的内容（模拟攻击者），重算哈希与存储值不匹配。</li>
 * </ol>
 *
 * <p>顺序注解：T3 篡改会破坏链，必须在 T2 链检查之后运行。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditLogIntegrationTest extends ShardedIntegrationTestSupport {

    @Autowired
    private DiscountCalculatorService calculatorService;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @MockBean
    private UserCouponRepository userCouponRepository;

    @MockBean
    private CachedTemplateReader templateReader;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final LocalDateTime CALC_NOW = LocalDateTime.of(2026, 10, 15, 12, 0, 0);

    private CalculateResponse doCalculate(long couponId, long userId) {
        CouponTemplate tpl = CouponTemplate.create(CouponTemplate.Creation.builder()
                .templateCode("AUDIT-T").activityId(1L).name("审计测试满减券")
                .couponType(CouponType.FULL_REDUCTION)
                .faceValue(2000L).discountCap(null).threshold(10000L)
                .totalCount(100L).perUserLimit(1).dailyLimit(0L).perUserDailyLimit(0)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.of(2026, 1, 1, 0, 0))
                .validEnd(LocalDateTime.of(2026, 12, 31, 23, 59, 59))
                .appId("audit-app")
                .build());
        UserCoupon c = UserCoupon.reconstitute(couponId, userId, 1L, "AUDIT-T",
                2000L, 10000L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59), CouponStatus.USABLE,
                LocalDateTime.of(2026, 10, 1, 12, 0), "idem-" + couponId, 1, "audit-app");
        when(userCouponRepository.findByIdForUser(couponId, userId)).thenReturn(Optional.of(c));
        when(templateReader.get(1L)).thenReturn(Optional.of(tpl));

        CalculateRequest req = new CalculateRequest(userId,
                List.of(new CalculateRequest.OrderItem("SKU1", "CAT1", 15000L, 1)),
                List.of(couponId));
        return calculatorService.calculate(req);
    }

    /** 等待异步审计写入完成（轮询，最多 5 秒） */
    private List<AuditLogPO> waitForAuditRecords(int minCount) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            List<AuditLogPO> records = auditLogMapper.selectList(null);
            if (records != null && records.size() >= minCount) {
                return records;
            }
            Thread.sleep(100);
        }
        return auditLogMapper.selectList(null);
    }

    @Test
    @Order(1)
    @DisplayName("T1: 调用带 @AuditLog 的方法 → 审计表出现记录，六要素齐全")
    void t1_auditRecordCreatedWithSixElements() throws Exception {
        long userId = System.nanoTime();
        CalculateResponse resp = doCalculate(9001L, userId);
        assertThat(resp.discountAmount()).as("业务正常（优惠 20 元）").isEqualTo(2000L);

        List<AuditLogPO> records = waitForAuditRecords(1);
        assertThat(records.size()).as("审计表至少有一条记录").isGreaterThanOrEqualTo(1);

        AuditLogPO latest = records.get(records.size() - 1);
        assertThat(latest.getAppId()).as("要素①谁：调用方 appId").isNotNull();
        assertThat(latest.getTraceId()).as("要素⑥链路 ID").isNotNull();
        assertThat(latest.getAction()).as("要素④何事：操作类型").isEqualTo("COUPON_CALCULATE");
        assertThat(latest.getResourceType()).as("要素④何事：资源类型").isEqualTo("COUPON");
        assertThat(latest.getCreatedAt()).as("要素②何时").isNotNull();
        assertThat(latest.getBeforeSnapshot()).as("要素⑤前值快照（入参含 userId）").contains(String.valueOf(userId));
        assertThat(latest.getAfterSnapshot()).as("要素⑤后值快照（出参）").isNotNull();
        assertThat(latest.getRecordHash()).as("哈希链：本条哈希").isNotBlank();
        assertThat(latest.getPrevHash()).as("哈希链：前条哈希").isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("T2: 连续多条审计记录 → prev_hash 逐条衔接")
    void t2_hashChainContinuous() throws Exception {
        doCalculate(9101L, System.nanoTime());
        doCalculate(9102L, System.nanoTime());
        List<AuditLogPO> records = waitForAuditRecords(2);
        assertThat(records.size()).isGreaterThanOrEqualTo(2);

        records.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (int i = 1; i < records.size(); i++) {
            assertThat(records.get(i).getPrevHash())
                    .as("第 %d 条的 prev_hash 应等于第 %d 条的 record_hash",
                            records.get(i).getId(), records.get(i - 1).getId())
                    .isEqualTo(records.get(i - 1).getRecordHash());
        }
    }

    @Test
    @Order(3)
    @DisplayName("T3: 篡改一条审计记录的内容 → 重算哈希与存储值不匹配（篡改可检出）")
    void t3_tamperingDetected() throws Exception {
        doCalculate(9201L, System.nanoTime());
        List<AuditLogPO> records = waitForAuditRecords(1);
        assertThat(records.size()).isGreaterThanOrEqualTo(1);

        AuditLogPO victim = records.get(records.size() - 1);

        // 直接改库（模拟攻击者改 action 字段）
        auditLogMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AuditLogPO>()
                        .eq(AuditLogPO::getId, victim.getId())
                        .set(AuditLogPO::getAction, "TAMPERED"));

        AuditLogPO tampered = auditLogMapper.selectById(victim.getId());
        assertThat(tampered.getAction()).as("确认篡改生效").isEqualTo("TAMPERED");

        // 重算哈希：用被篡改的内容 + 原始 prev_hash 重新计算，应与存储的 record_hash 不匹配
        String recomputed = AuditService.computeHash(tampered);
        assertThat(recomputed)
                .as("篡改后重算的哈希应与存储值不匹配（篡改可检出）")
                .isNotEqualTo(tampered.getRecordHash());
    }
}
