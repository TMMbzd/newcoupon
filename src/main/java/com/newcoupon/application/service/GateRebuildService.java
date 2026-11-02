package com.newcoupon.application.service;

import com.newcoupon.infrastructure.redis.RedisIssueGate;
import com.newcoupon.infrastructure.redis.SoldOutHint;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 从 C1 分片重建 Redis 库存 gate。
 *
 * <p>公式：Σquota − Σused − 在途任务。在途任务已经预扣过 gate，不扣会把同一份库存放两次。
 * 请求路径和后台修复任务共用这一份计算，避免两处公式漂移。
 *
 * <p>公式的三次读由 {@link GateRebuildTxService} 在同一事务快照内完成（讨论 02 核对结论 P1）：
 * 跨快照读会把"在途→已发"瞬间迁移的券双算或漏算；Redis 写回留在事务外，不在持 DB 连接时做网络 IO。
 */
@Service
public class GateRebuildService {

    private static final Logger log = LoggerFactory.getLogger(GateRebuildService.class);
    private static final Duration GATE_TTL = Duration.ofDays(7);

    private final GateRebuildTxService txService;
    private final RedisIssueGate issueGate;
    private final SoldOutHint soldOutHint;

    public GateRebuildService(GateRebuildTxService txService, RedisIssueGate issueGate,
                              SoldOutHint soldOutHint) {
        this.txService = txService;
        this.issueGate = issueGate;
        this.soldOutHint = soldOutHint;
    }

    /** @return 重建后的剩余额度；force=true 无条件覆盖，否则只在键不存在时写入。 */
    public long rebuild(long templateId, boolean force) {
        long remaining = txService.computeRemaining(templateId);
        // 售罄提示（方案 A 写点）：数据库核实 remaining==0 → 标记 10 秒，受理入口据此快速失败；
        // remaining>0 → 防御性清除（补货后即使运营路径的删除失败，下一次重建也能自愈误报）。
        if (remaining == 0) {
            soldOutHint.mark(templateId);
        } else {
            soldOutHint.clear(templateId);
        }
        Duration ttl = jitter(GATE_TTL);
        if (force) {
            issueGate.resetGate(templateId, remaining, ttl);
            return remaining;
        }
        if (!issueGate.ensureGate(templateId, remaining, ttl)) {
            log.warn("issue gate rebuild lost race: templateId={}", templateId);
        }
        return remaining;
    }

    /** TTL 抖动：避免大量 gate 在同一秒集中过期，触发 Redis 过期扫描的延迟尖峰。 */
    private static Duration jitter(Duration base) {
        long seconds = base.toSeconds();
        if (seconds <= 2) {
            return base;
        }
        long delta = Math.max(1, seconds / 10);
        long offset = ThreadLocalRandom.current().nextLong(-delta, delta + 1);
        return Duration.ofSeconds(Math.max(1, seconds + offset));
    }
}
