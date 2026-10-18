package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.RefundItem;
import com.newcoupon.application.dto.TradeDtos.RefundRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.infrastructure.persistence.mapper.CouponRefundDetailMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponShareDetailMapper;
import com.newcoupon.infrastructure.persistence.po.CouponRefundDetailPO;
import com.newcoupon.infrastructure.persistence.po.CouponShareDetailPO;
import com.newcoupon.open.api.dto.CalculateRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 环 1 结算主体 · 集成验收（2026-10-18）。
 *
 * <p>验证：
 * <ol>
 *   <li>商家券只命中绑定主体的行，其他主体不能帮凑门槛；</li>
 *   <li>平台券跨主体按行分摊，每行落自己的主体；</li>
 *   <li>退款明细从原分账行复制主体，不读退款请求；</li>
 *   <li>商家券 + 缺主体行/旧模板 → 不可用，禁止 __default__ 兜底；</li>
 *   <li>灰度开启时，商家/共担建券必须带主体。</li>
 * </ol>
 *
 * <p>本类用全站属性打开灰度（测试直接调 service，没有开放面 RequestContext）。
 * 默认关的旧行为由既有测试（TemplateFiveDimensions/RefundIntegration）继续守护。
 */
@TestPropertySource(properties = "coupon.trade.settle-party.enabled=true")
class SettlePartyIntegrationTest extends ShardedIntegrationTestSupport {

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private CouponShareDetailMapper shareDetailMapper;
    @Autowired private CouponRefundDetailMapper refundDetailMapper;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private record Setup(long templateId, long couponId, String usageNo) {
    }

    private TemplateCreateRequest template(String name, int owner, String party,
                                           long threshold, long faceValue) {
        return template(name, 1, owner, party, threshold, faceValue);
    }

    private TemplateCreateRequest template(String name, int couponType, int owner, String party,
                                           long threshold, long faceValue) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        return new TemplateCreateRequest(name, 9200L, couponType, faceValue, null, threshold,
                100L, 1, 1, start, end, null, 0L, 0,
                null, null, null, null, owner, null, party);
    }

    private Setup issue(TemplateCreateRequest req, String suffix) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(req);
        templateAppService.publish(created.id());
        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("party-" + suffix, userId, created.id()));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-PARTY-" + suffix, userId, rcv.couponId()));
        return new Setup(created.id(), rcv.couponId(), lock.usageNo());
    }

    private static CalculateRequest.OrderItem item(String sku, long price, String lineNo, String party) {
        return new CalculateRequest.OrderItem(sku, "CAT1", price, 1, lineNo, party);
    }

    @Test
    @DisplayName("S1: 商家 A 的券只按 A 的行凑门槛，B 不能帮凑")
    void merchantCouponCannotUseOtherPartyAmount() {
        Setup s = issue(template("商家券-A-门槛", 2, "A", 10000L, 1000L), "S1");

        assertThatThrownBy(() -> tradeAppService.confirm(new ConfirmRequest(s.usageNo(), null, List.of(
                item("SKU_A", 6000L, "L1", "A"),
                item("SKU_B", 6000L, "L2", "B")))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未达使用门槛");
    }

    @Test
    @DisplayName("S2: 商家 A 的券只命中 A 行；分账行落 app_id + settle_party_id=A")
    void merchantCouponOnlyHitsBoundParty() {
        Setup s = issue(template("商家券-A-分摊", 2, "A", 5000L, 1000L), "S2");
        assertThat(templateAppService.get(s.templateId()).settlePartyId()).isEqualTo("A");

        tradeAppService.confirm(new ConfirmRequest(s.usageNo(), null, List.of(
                item("SKU_A", 6000L, "L1", "A"),
                item("SKU_B", 4000L, "L2", "B"))));

        List<CouponShareDetailPO> shares = shareDetailMapper.selectPositiveByUsageNo(s.usageNo());
        assertThat(shares).hasSize(1);
        CouponShareDetailPO share = shares.get(0);
        assertThat(share.getOrderLineNo()).isEqualTo("L1");
        assertThat(share.getShareTotal()).isEqualTo(1000L);
        assertThat(share.getSettlePartyId()).isEqualTo("A");
        assertThat(share.getAppId()).isEqualTo("1");
    }

    @Test
    @DisplayName("S3: 平台券跨主体按行分摊，每行落自己的主体")
    void platformCouponSplitsAcrossParties() {
        Setup s = issue(template("平台券-跨主体", 3, 1, null, 0L, 1000L), "S3");

        tradeAppService.confirm(new ConfirmRequest(s.usageNo(), null, List.of(
                item("SKU_A", 6000L, "L1", "A"),
                item("SKU_B", 4000L, "L2", "B"))));

        List<CouponShareDetailPO> shares = shareDetailMapper.selectPositiveByUsageNo(s.usageNo());
        assertThat(shares).hasSize(2);
        assertThat(shares).extracting(CouponShareDetailPO::getSettlePartyId)
                .containsExactlyInAnyOrder("A", "B");
        assertThat(shares.stream().mapToLong(CouponShareDetailPO::getShareTotal).sum())
                .isEqualTo(1000L);
    }

    @Test
    @DisplayName("S4: 退款明细从原分账行复制主体，不读退款请求")
    void refundDetailCopiesPartyFromShare() {
        Setup s = issue(template("商家券-A-退款", 2, "A", 5000L, 1000L), "S4");
        tradeAppService.confirm(new ConfirmRequest(s.usageNo(), null, List.of(
                item("SKU_A", 6000L, "L1", "A"),
                item("SKU_B", 4000L, "L2", "B"))));

        tradeAppService.refund(new RefundRequest(s.usageNo(), "RF-PARTY-S4",
                List.of(new RefundItem("L1", 1))));

        List<CouponRefundDetailPO> details = refundDetailMapper.selectByRefundNo("RF-PARTY-S4");
        assertThat(details).hasSize(1);
        assertThat(details.get(0).getSettlePartyId()).isEqualTo("A");
        assertThat(details.get(0).getAppId()).isEqualTo("1");
    }

    @Test
    @DisplayName("S5: 商家券 + 缺主体行 → 不可用，禁止 __default__ 兜底")
    void merchantCouponWithMissingPartyIsRejected() {
        Setup s = issue(template("商家券-A-缺主体", 2, "A", 5000L, 1000L), "S5");

        assertThatThrownBy(() -> tradeAppService.confirm(new ConfirmRequest(s.usageNo(), null, List.of(
                item("SKU_X", 6000L, "L1", null)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于券绑定的结算主体");
    }

    @Test
    @DisplayName("S6: 旧商家模板（__default__）在灰度开启时拒绝核销")
    void legacyMerchantTemplateWithoutRealPartyIsRejected() {
        Setup s = issue(template("商家券-旧模板", 2, "__default__", 5000L, 1000L), "S6");

        assertThatThrownBy(() -> tradeAppService.confirm(new ConfirmRequest(s.usageNo(), null, List.of(
                item("SKU_A", 6000L, "L1", "A")))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未绑定结算主体");
    }

    @Test
    @DisplayName("S7: 灰度开启时，商家/共担建券必须带主体")
    void merchantOrSplitTemplateRequiresPartyWhenEnabled() {
        assertThatThrownBy(() -> templateAppService.create(
                template("商家券-缺主体-建券", 2, null, 5000L, 1000L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须绑定结算主体");

        assertThatThrownBy(() -> templateAppService.create(
                template("共担券-缺主体-建券", 3, null, 5000L, 1000L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须绑定结算主体");
    }
}
