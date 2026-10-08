package com.newcoupon.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 集群 flowId 的生成（第 9 步，2026-10-08）。
 *
 * <p><b>为什么这三条性质必须钉住</b>：flowId 是客户端与服务端之间**唯一**的规则纽带
 * （token 报文字段只有 flowId/count/priority，**没有资源名**），而且它在两端各算一次、
 * 没有任何同步通道。任何一条性质被破坏，后果都是**静默的**：
 * <ul>
 *   <li>不稳定 → 两端对不上 → 服务端说"没有这条规则" → 客户端回退本地（跨实例精确失效）；</li>
 *   <li>非正数 → Sentinel 直接把整条规则丢掉（只写它自己的 WARN 日志）；</li>
 *   <li>冲突 → **两个租户共用一份额度**，而且两端都不会报错。</li>
 * </ul>
 */
class ClusterFlowIdTest {

    @Test
    @DisplayName("稳定：同样的 (resource, limitApp) 永远算出同一个值（跨进程/跨重启都成立）")
    void stableAcrossCalls() {
        long first = ClusterFlowId.of("open_api", "app-a");
        long second = ClusterFlowId.of("open_api", "app-a");

        assertThat(first).as("两端各算一次，值必须一致——不一致就等于规则没配").isEqualTo(second);
    }

    @Test
    @DisplayName("恒为正数（0 或负数不是合法 flowId，Sentinel 会把整条规则丢掉）")
    void alwaysPositive() {
        for (int i = 0; i < 500; i++) {
            assertThat(ClusterFlowId.of("open_api", "app-" + i))
                    .as("flowId 必须 > 0：非正数会让 Sentinel 直接丢弃整条规则（只写它自己的日志）")
                    .isPositive();
        }
    }

    @Test
    @DisplayName("不同租户不冲突（冲突=两个租户共用一份额度，且两端都不报错）")
    void distinctPerTenant() {
        Set<Long> ids = new HashSet<>();
        int n = 2000;
        for (int i = 0; i < n; i++) {
            ids.add(ClusterFlowId.of("open_api", "t-cluster-" + i));
        }
        assertThat(ids)
                .as("用 64 位摘要（不是 32 位 hashCode）：冲突的后果是「两个租户互相吃额度」且毫无告警，"
                        + "所以宁可放宽到长期看不出碰撞的宽度")
                .hasSize(n);
    }

    @Test
    @DisplayName("资源名也参与：同一个 appId 在不同资源上是不同的 flowId")
    void resourceIsPartOfTheKey() {
        assertThat(ClusterFlowId.of("open_api", "app-a"))
                .isNotEqualTo(ClusterFlowId.of("open_api_ip", "app-a"));
    }
}
