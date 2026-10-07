package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppConfigLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppConfigMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppConfigPO;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租户配置缓存的两条底线（S2 第 10 步 P2 修复）——<b>红绿灯</b>。
 *
 * <p><b>红</b>：本类先于修复写就，两条都会失败，且失败原因**不是实现细节而是行为**：
 *
 * <ol>
 *   <li><b>TTL 永不生效</b>：{@code Expiry.expireAfterCreate} 的契约是返回**持续时长**，
 *       Caffeine 内部自己会加 {@code now}；但三处实现都写成 {@code return now + 时长}，
 *       于是条目实际存活 ≈ <b>JVM 已运行时长 + TTL</b>——JVM 跑 1 小时，缓存项就活 1 小时。
 *       本用例把 TTL 压到 300ms 来证明"它其实不会过期"。</li>
 *   <li><b>回源失败会穿到调用方</b>：{@code load} 没有兜底，DB 抖动 + 缓存硬过期时
 *       异常直接抛给请求——而本类承载的"读不到就用默认值"承诺正是不让配置变成故障放大器。</li>
 * </ol>
 *
 * <p><b>为什么用单元测试</b>：这两条都是缓存组件自己的行为，不需要容器；
 * 秒级反馈让"先红后绿"的循环快得多，也不占 WSL 的容器资源。
 *
 * <p><b>为什么用轮询而不是 sleep 固定值</b>：本项目铁律③——不用不可控的量卡边界。
 * 负载高时 300ms 的 TTL 可能迟迟等不到，固定 sleep 会变成 flaky；
 * 轮询 + 上限把"慢"与"错"区分开。
 */
class CachedTenantConfigServiceTest {

    private static final String APP = "app-cache-test";

    private final OpenAppConfigMapper mapper = mock(OpenAppConfigMapper.class);
    private final OpenAppConfigLogMapper logMapper = mock(OpenAppConfigLogMapper.class);
    private final OpenAppRepository openAppRepository = mock(OpenAppRepository.class);

    /** TTL 很短（300ms）、refresh 很长（10 分钟）——把两个机制**分开**测，避免 refresh 掩盖 TTL 的问题 */
    private CachedTenantConfigService serviceWithTtl(long ttlMillis) {
        return new CachedTenantConfigService(mapper, logMapper, openAppRepository,
                ttlMillis, 600_000L);
    }

    private static OpenAppConfigPO row(String key, String value) {
        OpenAppConfigPO po = new OpenAppConfigPO();
        po.setAppId(APP);
        po.setConfigKey(key);
        po.setConfigValue(value);
        return po;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("TTL 到期后必须重新回源（当前红：条目实际永不硬过期）")
    void ttlExpires_reloadsFromSource() throws Exception {
        // 第一次回源给 v1，之后给 v2——于是"是否重新回源"可以从读到的值直接看出来
        when(mapper.selectList(any()))
                .thenReturn(List.of(row("rate_limit.qps", "10")))
                .thenReturn(List.of(row("rate_limit.qps", "20")));

        CachedTenantConfigService service = serviceWithTtl(300);

        assertThat(service.allOf(APP))
                .as("首次读应当装载 v1").containsEntry("rate_limit.qps", "10");

        // 轮询等到 TTL 过期（上限 5 秒，远大于 300ms）——把"慢"与"错"分开
        String seen = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            seen = service.allOf(APP).get("rate_limit.qps");
            if ("20".equals(seen)) {
                break;
            }
            Thread.sleep(50);
        }

        assertThat(seen)
                .as("TTL=300ms 到期后必须重新回源。读到 %s 说明条目没有按 TTL 过期——"
                        + "Expiry 返回的必须是**持续时长**，不是 now+时长", seen)
                .isEqualTo("20");
    }

    @Test
    @DisplayName("回源失败 → 回落该键默认值、**不抛异常**（配置读不到不该变成请求失败）")
    void loadFailure_fallsBackToDefaultWithoutThrowing() {
        when(mapper.selectList(any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down"));

        CachedTenantConfigService service = serviceWithTtl(60_000);

        assertThat(service.allOf(APP))
                .as("读不到配置 = 该租户没有任何配置（走默认值），不是异常")
                .isEmpty();
        assertThat(service.get(APP, TenantConfigKey.RATE_LIMIT_QPS))
                .as("读不到就用该键的默认值——这正是 S2 第 1 步定下的三条铁律第 1 条")
                .isEqualTo(TenantConfigKey.RATE_LIMIT_QPS.defaultValue());
    }

    @Test
    @DisplayName("对照：缓存本身是生效的——TTL 内重复读不回源（证明上一条不是「缓存根本没建」）")
    void withinTtl_servesFromCache() {
        when(mapper.selectList(any())).thenReturn(List.of(row("rate_limit.qps", "10")));

        CachedTenantConfigService service = serviceWithTtl(60_000);

        assertThat(service.allOf(APP)).containsEntry("rate_limit.qps", "10");
        assertThat(service.allOf(APP))
                .as("第二次读应当命中缓存；若这里也变了，说明问题在缓存装配而不是 TTL")
                .containsEntry("rate_limit.qps", "10");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(1)).selectList(any());
    }
}
