package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.RefundItem;
import com.newcoupon.application.dto.TradeDtos.RefundRequest;
import com.newcoupon.application.service.DailyBillService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.open.api.dto.CalculateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 环 3 · 财务查询内部面验收。
 *
 * <p>验证：
 * <ol>
 *   <li>无 X-Admin-Token 的 POST 查询 → 401/7002；</li>
 *   <li>带令牌 → 退款列表/明细、主体账单/模板账单都能查出刚造的数据；</li>
 *   <li>无过滤条件的退款查询 → 1000（不是全表扫描）。</li>
 * </ol>
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168",
                "coupon.admin.token=test-admin-token",
                "coupon.trade.settle-party.enabled=true"
        })
class FinanceQueryIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private DailyBillService dailyBillService;
    @Autowired private TestRestTemplate rest;

    private static final String ADMIN_TOKEN = "test-admin-token";
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();
    private static long templateId;
    private static String usageNo;
    private static String refundNo;
    private static LocalDate billDate;

    @BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest(
                        "财务查询测试", 3600L, 1, 1000L, null, 5000L,
                        10L, 1, 1, start, end, null, null, null));
        templateAppService.publish(created.id());
        templateId = created.id();

        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("finance-query-receive", userId, templateId));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-FINANCE-QUERY", userId, rcv.couponId()));
        tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), null, List.of(
                new CalculateRequest.OrderItem("SKU1", "CAT1", 10000L, 2, "L1", "A"))));
        usageNo = lock.usageNo();
        refundNo = "RF-FINANCE-QUERY-1";
        tradeAppService.refund(new RefundRequest(usageNo, refundNo,
                List.of(new RefundItem("L1", 1))));

        billDate = LocalDate.now();
        dailyBillService.generateAll(billDate);
    }

    @Test
    @DisplayName("Q1: 无管理令牌的退款查询 → 401/7002")
    void refundQueryWithoutTokenRejected() {
        ResponseEntity<String> r = post("/api/refunds/query",
                "{\"usageNo\":\"" + usageNo + "\"}", null);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("7002");
    }

    @Test
    @DisplayName("Q2: 带令牌按 usageNo 查退款 → 返回刚造的退款单")
    void refundQueryWithTokenReturnsRows() {
        ResponseEntity<String> r = post("/api/refunds/query",
                "{\"usageNo\":\"" + usageNo + "\"}", ADMIN_TOKEN);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"code\":0")
                .contains(refundNo).contains(usageNo);
    }

    @Test
    @DisplayName("Q3: 带令牌查退款明细 → 返回行级主体/冲回金额")
    void refundDetailWithTokenReturnsLines() {
        ResponseEntity<String> r = post("/api/refunds/detail",
                "{\"refundNo\":\"" + refundNo + "\"}", ADMIN_TOKEN);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"code\":0")
                .contains("\"lineNo\":\"L1\"")
                .contains("\"settlePartyId\":\"A\"")
                .contains("\"refundDiscountAmt\":500");
    }

    @Test
    @DisplayName("Q4: 带令牌查主体日账单 → 返回 A 主体行")
    void settlePartyBillQueryWithTokenReturnsRows() {
        ResponseEntity<String> r = post("/api/bills/settle-party/query",
                "{\"billDate\":\"" + billDate + "\",\"appId\":\"1\",\"settlePartyId\":\"A\"}",
                ADMIN_TOKEN);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"code\":0")
                .contains("\"settlePartyId\":\"A\"")
                .contains("\"templateId\":" + templateId);
    }

    @Test
    @DisplayName("Q5: 带令牌查模板日账单 → join 出模板名与租户")
    void templateBillQueryWithTokenReturnsRows() {
        ResponseEntity<String> r = post("/api/bills/template/query",
                "{\"billDate\":\"" + billDate + "\",\"templateId\":" + templateId + "}",
                ADMIN_TOKEN);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"code\":0")
                .contains("\"templateName\":\"财务查询测试\"")
                .contains("\"appId\":\"1\"");
    }

    @Test
    @DisplayName("Q0: 查询路径没有 GET 映射——财务数据不能走无鉴权 GET")
    void queryPathsHaveNoGetMapping() {
        ResponseEntity<String> r = rest.getForEntity("/api/refunds/query", String.class);
        assertThat(r.getStatusCode().value()).isNotEqualTo(200);
    }

    @Test
    @DisplayName("Q7: 按主体查询不带 appId → 1000，禁止跨租户同名主体合并")
    void settlePartyQueryWithoutAppIdRejected() {
        ResponseEntity<String> r = post("/api/bills/settle-party/query",
                "{\"billDate\":\"" + billDate + "\",\"settlePartyId\":\"A\"}", ADMIN_TOKEN);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("1000").contains("必须同时提供 appId");
    }

    @Test
    @DisplayName("Q6: 退款查询没有过滤条件 → 1000，不做全表扫描")
    void refundQueryWithoutFilterRejected() {
        ResponseEntity<String> r = post("/api/refunds/query", "{}", ADMIN_TOKEN);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("1000")
                .contains("至少提供");
    }

    // ------------------------------------------------------------------

    private ResponseEntity<String> post(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("X-Admin-Token", token);
        }
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }
}
