package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.service.DailyBillService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.infrastructure.persistence.mapper.CouponSettlePartyDailyBillMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 反驳者探针（审查用，跑完即删）：整组排除日——(app,__default__,商家模板) 当日全部
 * settle_eligible=0 时，ineligible 桶计数是否真的没有宿主账单行可挂。
 */
class ProbeFullExclusionBillTest extends ShardedIntegrationTestSupport {

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private DailyBillService dailyBillService;
    @Autowired private CouponSettlePartyDailyBillMapper partyBillMapper;

    @DynamicPropertySource
    static void shardingProperties(DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Test
    @DisplayName("P1: 商家券灰度关全天核销 → 无 eligible 行 → ineligible 桶无宿主行，计数丢失")
    void fullyExcludedGroup_dropsIneligibleCounts() {
        // 商家券（funder=2，主体 M）灰度关（测试默认）核销：所有分账行 __default__ + settle_eligible=0
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateCreateRequest req = new TemplateCreateRequest(
                "探针-全排除-" + System.nanoTime(), 3400L, 1, 2000L, null, 10000L,
                10L, 1, 1, start, end, null, null, null,
                null, null, null, null,
                2, null, "M");
        TemplateAppService.CreatedTemplate created = templateAppService.create(req);
        templateAppService.publish(created.id());

        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("probe-full-exclusion-" + System.nanoTime(), userId, created.id()));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-PROBE-FULL-" + System.nanoTime(), userId, rcv.couponId()));
        tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), null, List.of(
                new com.newcoupon.open.api.dto.CalculateRequest.OrderItem(
                        "SKU_A", "CAT1", 8000L, 1, "L1", "M"),
                new com.newcoupon.open.api.dto.CalculateRequest.OrderItem(
                        "SKU_B", "CAT1", 2000L, 1, "L2", null))));
        long tpl = created.id();
        LocalDate today = LocalDate.now();

        // 前置确认：countIneligibleByDate 确实为 (app,__default__,tpl) 算出了桶（两条排除行）
        List<CouponSettlePartyDailyBillMapper.IneligibleAgg> buckets = partyBillMapper.countIneligibleByDate(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        CouponSettlePartyDailyBillMapper.IneligibleAgg myBucket = buckets.stream()
                .filter(b -> b.getTemplateId() == tpl && "__default__".equals(b.getSettlePartyId()))
                .findFirst().orElse(null);
        assertThat(myBucket).as("前置：该组当日确有被排除行，桶被算出").isNotNull();
        assertThat(myBucket.getIneligibleShareCount()).isEqualTo(2L);

        // 跑账单（含主体账单+对账）
        dailyBillService.generateAll(today);

        // 发现主张：该组无 eligible 行 → aggregateByDate 不产行 → 桶无宿主 → 主体账单无此行
        var rows = partyBillMapper.selectByFilters(today, null, "__default__", tpl, 10, 0);
        assertThat(rows).as("全排除组：主体账单应无行（若非空则发现被反驳）").isEmpty();
    }
}
