package com.newcoupon.infrastructure.ratelimit;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 集群流控的 **flowId 生成**（第 9 步，2026-10-08）。
 *
 * <p><b>为什么需要自己生成</b>：Sentinel 的 {@code ClusterFlowConfig.flowId} **没有任何运行期组件
 * 会替你生成**——官方部署里它是**控制台**的产物（控制台的规则存储把 flowId 设成规则行自身的
 * 自增 id）。而客户端与服务端之间传的 token 请求里**只有 flowId**（报文字段就三个：
 * flowId / count / priority，**没有资源名**），服务端拿它去查**自己的**规则表
 * （{@code ClusterFlowRuleManager.getFlowRuleById}）。所以：
 * **两端必须对同一个 flowId 持有同一份规则**——这就是官方说"必须改造控制台"的真实含义
 * （1.8.10 的控制台甚至根本不向 Token Server 推集群规则）。
 *
 * <p>我们不用控制台，规则两端都从同一个 MySQL 来，所以 flowId 也由**同一个纯函数**算出来
 * ——两端算出来的必然一致，不需要任何同步通道。
 *
 * <p><b>三条不变式</b>（写在注释里，也在测试里钉住）：① 恒为正数（0 或负数不是合法 flowId）；
 * ② (resource, limitApp) 相同 → 永远相同（跨进程、跨重启、跨 JVM 都成立，否则两端对不上）；
 * ③ 不同 (resource, limitApp) 之间**尽量**不冲突——用 SHA-256 取 64 位（不是 32 位 hashCode：
 * 上百个租户时 32 位的碰撞概率已不可忽略，而**冲突的后果是"两个租户共用一份额度"且毫无告警**，
 * 所以这里宁可用一个更宽的摘要）。
 */
public final class ClusterFlowId {

    private ClusterFlowId() {
    }

    /**
     * @param resource 资源名（如 {@code open_api}）
     * @param limitApp 限流对象（租户维度就是 appId）
     */
    public static long of(String resource, String limitApp) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest((resource + "|" + limitApp).getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(digest).getLong();     // 取前 8 字节
            value &= Long.MAX_VALUE;                            // 抹掉符号位：flowId 必须为正
            return value == 0 ? 1L : value;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境坏了——不要静默降级成弱哈希，
            // 那会让两端算出不同的 flowId（后果是"规则配了但不生效"，极难查）。
            throw new IllegalStateException("JDK 缺少 SHA-256（无法生成集群 flowId）", e);
        }
    }
}
