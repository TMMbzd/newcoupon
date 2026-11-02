package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.CachedTemplateReader;
import com.newcoupon.application.service.CouponIssueTxService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.StockLayoutChangedException;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V37 layout_version Slice A 的真实库契约：
 *
 * <ul>
 *   <li>发布建 v1 active 行（layout_version=1、reserved=0、Σquota=total）；</li>
 *   <li>stale version 在事务内被 fence 拒绝，库存/日计数零变更；</li>
 *   <li>缓存里的旧版本被事务 fence 拒绝后，同步入口失效缓存、重读一次并按 DB 新版本重路由。</li>
 * </ul>
 *
 * <p>Slice A 仍保留 `uk_tpl_shard(template_id, shard_no)`，所以本类**不插入 v2 行**
 * （真实 reshard 与 V38 属 Slice B）。第三个用例只把模板版本号推进到 2 来验证
 * “缓存失效 + 重读一次”的控制流：v2 没有行，因此最终返回可重试 5001，而不是静默扣旧版本。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.stock.shard-count=8",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class StockLayoutVersionIntegrationTest extends ShardedIntegrationTestSupport {

    private static final AtomicLong USER = new AtomicLong(500_000);

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private TemplateAppService templateAppService;
    @Autowired private TemplateRepository templateRepository;
    @Autowired private CouponStockShardMapper shardMapper;
    @Autowired private CouponIssueTxService issueTxService;
    @Autowired private CachedTemplateReader cachedTemplateReader;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private javax.sql.DataSource dataSource;

    @Test
    @Timeout(60)
    @DisplayName("发布：建立 layout_version=1 的 active 行，reserved=0，Σquota=total")
    void publishInitializesVersionOneLayout() {
        long templateId = createAndPublish(53, 1);

        CouponTemplate template = templateRepository.findById(templateId).orElseThrow();
        assertThat(template.getStockLayoutVersion()).isEqualTo(1);

        List<CouponStockShardPO> rows = shardMapper.selectByTemplateVersion(templateId, 1);
        assertThat(rows).hasSize(8);   // min(配置 8, total 53)
        assertThat(rows).allMatch(r -> Integer.valueOf(1).equals(r.getLayoutVersion()));
        assertThat(rows).allMatch(r -> Long.valueOf(0L).equals(r.getReserved()));
        assertThat(rows.stream().mapToLong(CouponStockShardPO::getQuota).sum()).isEqualTo(53);
        assertThat(shardMapper.sumQuota(templateId)).isEqualTo(53);
        assertThat(shardMapper.sumUsed(templateId)).isZero();
        assertThat(shardMapper.countByTemplate(templateId)).isEqualTo(8);
    }

    @Test
    @Timeout(60)
    @DisplayName("stale layoutVersion：事务在计数/扣库存前拒绝，Σused 保持 0")
    void staleLayoutVersion_doesNotDeduct() {
        long templateId = createAndPublish(10, 1);
        CouponTemplate current = templateRepository.findById(templateId).orElseThrow();
        long userId = USER.incrementAndGet();

        assertThatThrownBy(() -> issueTxService.issueC1(
                new ReceiveRequest("layout-stale-" + userId, userId, templateId),
                current, 1, current.getStockLayoutVersion() + 1, 0,
                LocalDateTime.now(), LocalDate.now()))
                .isInstanceOf(StockLayoutChangedException.class);

        assertThat(shardMapper.sumUsed(templateId)).as("旧版本不得扣库存").isZero();
        assertThat(shardMapper.sumUsedVersion(templateId, 1)).isZero();
    }

    @Test
    @Timeout(60)
    @DisplayName("缓存旧版本 → fence 拒绝后失效缓存、重读一次；DB 新版本无行时返回 5001 而非扣旧版本")
    void staleCachedTemplate_invalidatesCacheAndRetriesOnce() throws Exception {
        long templateId = createAndPublish(10, 1);
        CouponTemplate cached = cachedTemplateReader.get(templateId).orElseThrow();
        assertThat(cached.getStockLayoutVersion()).isEqualTo(1);
        try {
            // 模拟 reshard 已推进版本号（Slice A 不建 v2 行，仅验证控制流）。
            setLayoutVersion(templateId, 2);

            long userId = USER.incrementAndGet();
            assertThatThrownBy(() -> receiveAppService.receive(
                    new ReceiveRequest("layout-cache-retry-" + userId, userId, templateId)))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DEGRADED);

            assertThat(cachedTemplateReader.get(templateId).orElseThrow().getStockLayoutVersion())
                    .as("重试路径必须失效旧缓存并读回 DB 的新版本").isEqualTo(2);
            assertThat(shardMapper.sumUsed(templateId)).as("不得在旧版本行上扣减").isZero();
        } finally {
            // 共享容器/共享库：必须复原，否则同 JVM 后续类的 reconcile 会把这条
            // "版本 2 没有行"的模板当成真实漂移（组合跑时实测 3 个用例连锁失败）。
            setLayoutVersion(templateId, 1);
            cachedTemplateReader.invalidate(templateId);
        }
    }

    private void setLayoutVersion(long templateId, int version) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE coupon_template SET stock_layout_version = ? WHERE id = ?")) {
            ps.setInt(1, version);
            ps.setLong(2, templateId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------

    private long createAndPublish(long total, int perUserLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest("布局版本验证券", 1101L, 1, 1000L, null, 10_000L,
                        total, perUserLimit, 1, LocalDateTime.now().minusDays(1),
                        LocalDateTime.now().plusDays(30), null, 0L, 0));
        templateAppService.publish(created.id());
        return created.id();
    }
}
