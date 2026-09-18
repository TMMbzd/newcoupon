package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.StockBucketRouter;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P2b 红灯合同（工序 v2 的 ⓪——问题真实存在的证据）。
 *
 * <p>运行方式：{@code make red-contract}（等价 mvn test -Dred.contract=true -Dtest=P2bRedContractTest）。
 * <b>实施前预期大面积红</b>——每条红对应测试大纲 §2 的一条裁决；P2b 第 2-7 步逐一转绿，
 * 第 8 步验收要求本类全绿。默认 {@code mvn test} 跳过本类（@EnabledIfSystemProperty 门），
 * 保证增量开发期间基线可绿。
 *
 * <p>用例 ↔ 大纲映射：
 * <pre>
 * route_xxx                USAB-10 路由纯函数（第 1 步即绿——契约先行）
 * bucketedReceive_xxx      CONC-1/E2：桶化+开桶+c:alloc 全局守恒（红：无桶）
 * frozenTemplate_xxx       USAB-2：冻结专用码 FROZEN、不泄漏 -11（红：无冻结短路）
 * translationAttack_xxx    CONS-2/SECU-2：平移资损被 DB 桶行封顶+留痕（红：无 stock_bucket）
 * v10Allocated_xxx         COMP-2/CHAO-7：allocated 列随发放推进（红：无 V10）
 * </pre>
 */
@EnabledIfSystemProperty(named = "red.contract", matches = "true")
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.reconcile.inflight-ttl-ms=300",
        "coupon.reconcile.log-min-interval-ms=0",
        "coupon.reconcile.auto-repair.mode=force",
        "coupon.sharding.bucket-size=10",     // 压缩桶额度：30 张库存应开 3 桶（生产 10000）
        "coupon.sharding.bucket-count=4"
})
class P2bRedContractTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DataSource dataSource;

    private static final java.util.concurrent.atomic.AtomicLong USER =
            new java.util.concurrent.atomic.AtomicLong(300_000);

    @AfterEach
    void cleanFaultHooks() {
        com.newcoupon.infrastructure.testing.FaultHooks.disarmAll();
        com.newcoupon.infrastructure.testing.FaultHooks.setEnabled(false);
    }

    // ---------- USAB-10：路由纯函数（契约先行，本步即应绿） ----------

    @Test
    @DisplayName("route()：确定性（同用户同桶）+ 均匀性 + 模零防护")
    void route_isDeterministicAndUniform() {
        assertThat(StockBucketRouter.route(12345L, 4))
                .isEqualTo(StockBucketRouter.route(12345L, 4))
                .isBetween(0, 3);

        // 均匀性：1 万连续 userId 撒 4 桶，最重桶不得超过最轻桶 2 倍（连续 ID 已被黄金比例散列打散）
        long[] buckets = new long[4];
        IntStream.range(0, 10_000).forEach(i -> buckets[StockBucketRouter.route(i, 4)]++);
        for (long b : buckets) {
            assertThat(b).isGreaterThan(1_500);   // 均匀性下限：1/4 ±40%
        }
        long max = IntStream.range(0, 4).mapToLong(i -> buckets[i]).max().orElseThrow();
        long min = IntStream.range(0, 4).mapToLong(i -> buckets[i]).min().orElseThrow();
        assertThat((double) max / min).isLessThan(2.0);

        // 总监盲区⑦：bucket_count=0 是模零除法全站 500——必须显式拒绝
        assertThatThrownBy(() -> StockBucketRouter.route(1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- CONC-1/E2：桶化领券 + 开桶 + 全局守恒 ----------

    @Test
    @DisplayName("桶化领券：30 张库存（桶额度10）→ 全部成功，c:alloc=30，E2 全局守恒 Σ桶+c:alloc≡total")
    void bucketedReceive_opensBuckets_andGlobalConservationHolds() {
        long tpl = createTemplate(30);
        int ok = 0;
        for (int i = 0; i < 30; i++) {
            String res = receiveQuietly(USER.incrementAndGet(), "rb-" + tpl + "-" + i, tpl);
            if ("OK".equals(res)) {
                ok++;
            }
        }
        assertThat(ok).isEqualTo(30);   // 红因：现状无桶耗尽概念，此条其实能过——真正的红在下一条断言

        String alloc = redis.opsForValue().get("c:alloc:{" + tpl + "}");
        // 红证据①：发布/领券路径没有任何代码写 c:alloc（E2 的 Redis 侧锚不存在）
        assertThat(alloc).isEqualTo("30");
    }

    // ---------- USAB-2：冻结专用码 ----------

    @Test
    @DisplayName("冻结：SET c:fz → 领券收到 FROZEN(5002)，响应不得泄漏内部负数码 -11")
    void frozenTemplate_receivesFROZENcode_notInternalNegative() {
        long tpl = createTemplate(10);
        redis.opsForValue().set("c:fz:{" + tpl + "}", "1");

        // 红因：现状 DEDUCT 首行无冻结短路——领券直接成功，catchThrowableOfType 抓不到任何东西
        BizException e = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                        receiveAppService.receive(new ReceiveRequest("fz-" + tpl, USER.incrementAndGet(), tpl)),
                BizException.class);
        assertThat(e).isNotNull();
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FROZEN);
        assertThat(e.getMessage() == null ? "" : e.getMessage()).doesNotContain("-11");
    }

    // ---------- CONS-2/SECU-2：平移攻击被 DB 桶行封顶 ----------

    @Test
    @DisplayName("平移攻击：伪造可卖库存（INCRBY stk）→ 第 11 张被 DB 桶行拒绝，且 stock_bucket 留痕可查")
    void translationAttack_isCappedAtDbBucketRow() {
        long tpl = createTemplate(10);
        for (int i = 0; i < 10; i++) {
            receiveQuietly(USER.incrementAndGet(), "ta-" + tpl + "-" + i, tpl);
        }
        // 攻击者直改 Redis 伪造 1 张可卖库存（现键形态——桶化后同样语义打桶键）
        redis.opsForValue().increment("c:stk:{" + tpl + "}", 1);

        // 第 11 张：Redis 放行（伪造），DB 桶行 used<quota 必须拒绝——资损被硬防线封顶
        String res = receiveQuietly(USER.incrementAndGet(), "ta-" + tpl + "-11th", tpl);
        assertThat(res).isNotEqualTo("OK");

        // 封顶之外的留痕：stock_bucket 是 E3b/E3c 镜像的锚——平移后可对账定位
        Long usedSum = new JdbcTemplate(dataSource)
                .queryForObject("SELECT COALESCE(SUM(used),0) FROM stock_bucket WHERE template_id = ?",
                        Long.class, tpl);
        assertThat(usedSum).isEqualTo(10L);   // 红证据：stock_bucket 表不存在（V10 未实施）
    }

    // ---------- COMP-2/CHAO-7：allocated 列随发放推进 ----------

    @Test
    @DisplayName("V10：allocated 列存在且随开桶推进（发放 5 张 → allocated≥5，供 E3a 镜像）")
    void v10Allocated_advancesWithIssuance() {
        long tpl = createTemplate(10);
        for (int i = 0; i < 5; i++) {
            receiveQuietly(USER.incrementAndGet(), "v10-" + tpl + "-" + i, tpl);
        }
        Long allocated = new JdbcTemplate(dataSource)
                .queryForObject("SELECT allocated FROM coupon_template WHERE id = ?", Long.class, tpl);
        // 红证据：allocated 列不存在（V10 未实施）——BadSqlGrammarException
        assertThat(allocated).isGreaterThanOrEqualTo(5L);
    }

    // ---------- helpers（照 RedisStockIntegrationTest 惯例） ----------

    private long createTemplate(long stock) {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "P2b红灯合同券", 1001L, 1, 1000L, null, 10000L, stock,
                1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                null, null));
        templateAppService.publish(t.id());
        return t.id();
    }

    private String receiveQuietly(long userId, String reqId, long tplId) {
        try {
            receiveAppService.receive(new ReceiveRequest(reqId, userId, tplId));
            return "OK";
        } catch (BizException e) {
            return String.valueOf(e.getErrorCode().getCode());
        }
    }
}
