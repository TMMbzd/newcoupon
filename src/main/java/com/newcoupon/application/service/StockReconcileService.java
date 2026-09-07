package com.newcoupon.application.service;

import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 库存对账与重建（ADR-0006 第 5 条 + ADR-0007 在途治理）——"Redis 是算子，DB 是账本"的守夜人。
 *
 * <p><b>对账的头号难题（L21 实踩）</b>：领券写序是【先 Redis 后 DB】，请求在
 * "Redis 已扣、DB 未提交"的在途窗口内，Redis 必然比 total − DB已发 少"在途数"。
 * 旧实现（精确相等比较 + 无条件重建）的两个后果：
 * ① 流量期间必然误报（把正常时序差当漂移）；
 * ② 重建用 DB 过期快照 SET，与在途扣减/补偿竞态——<b>对账自己制造漂移</b>。
 * DB 双防线保证不超发（多放行的请求在 DB 被拒+补偿），故不资损；
 * 但代价是误告警刷屏、请求"过闸后被拒"、临售罄时"Redis 有量 DB 已满"窗口。
 *
 * <p><b>现行设计三件套（ADR-0007，每层杀死一种误报/误修）</b>：
 * <ol>
 *   <li><b>在途计数器精确扣除</b>：diff = 期望 − Redis余量，在途只能让 diff 偏正（Redis 偏低）；
 *       {@code diff ≤ 在途数 + 三方读余量} ⇒ 判定一致——把时序差从"误差"变成"可扣除的数"；</li>
 *   <li><b>二次复核</b>：疑似漂移先等 recheck-ms（默认 5s：DB 事务毫秒级、连接等待最长 3s，5s 覆盖）
 *       让在途落定再量一次，<b>两次都超界才重建</b>——瞬态差异自愈，绝不为"看起来不对"就覆盖 Redis；</li>
 *   <li><b>方向不对称</b>：在途只能让 Redis 偏低；<b>Redis 偏高没有任何合法形态</b>
 *       （旧重建竞态/补偿重复的征兆），任何幅度直接进复核。</li>
 * </ol>
 * key 整个缺失（Redis 重启/清库）没有在途歧义——仍立即重建。
 * 单实例口径（计数器在本 JVM）；多实例在 M2 换 Micrometer 汇总（L16）。
 */
@Service
public class StockReconcileService {

    private static final Logger log = LoggerFactory.getLogger(StockReconcileService.class);

    /**
     * 三方读（Redis / DB / 计数器）非原子，量测瞬间可能有微小错位，留的残差余量。
     * 覆盖的是"读与读之间的几十微秒"，不是在途（在途由计数器精确扣除）。
     */
    static final long RESIDUAL_TOLERANCE = 5;

    private final TemplateRepository templateRepository;
    private final RedisStockGateway stockGateway;
    private final ReceiveInFlightCounter inFlight;
    private final long recheckMillis;

    public StockReconcileService(TemplateRepository templateRepository,
                                RedisStockGateway stockGateway,
                                ReceiveInFlightCounter inFlight,
                                @Value("${coupon.reconcile.recheck-ms:5000}") long recheckMillis) {
        this.templateRepository = templateRepository;
        this.stockGateway = stockGateway;
        this.inFlight = inFlight;
        this.recheckMillis = recheckMillis;
    }

    /** 重建单个模板的 Redis 额度：可扣 = 审批总量 − DB 已发放（懒重建 -7 路径复用） */
    public void rebuildStock(long templateId) {
        CouponTemplate t = templateRepository.findById(templateId).orElse(null);
        if (t == null) {
            return;
        }
        long expected = t.getTotalCount() - t.getIssuedCount();
        stockGateway.initStock(templateId, expected);
        log.info("redis stock rebuilt: templateId={}, amount={}", templateId, expected);
    }

    /**
     * 每小时对账。返回重建的模板数（测试与运维观测用：>0 即发生了真漂移修复）。
     */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 3600_000)
    public int reconcileAll() {
        List<CouponTemplate> published = templateRepository.findPublished();
        int rebuilt = 0;
        for (CouponTemplate t : published) {
            if (reconcileOne(t.getId())) {
                rebuilt++;
            }
        }
        if (rebuilt == 0) {
            log.info("stock reconcile ok: {} published templates consistent, inFlight={}",
                    published.size(), inFlight.get());
        }
        return rebuilt;
    }

    /**
     * 单模板对账：量测 → （疑似漂移 → 等在途落定 → 二次量测）→ 仍超界才重建。
     *
     * @return 是否执行了重建
     */
    boolean reconcileOne(long templateId) {
        Verdict first = measure(templateId);
        if (first.consistent()) {
            return false;
        }
        if (first.keyMissing()) {
            log.warn("STOCK KEY MISSING: templateId={} → rebuild immediately", templateId);
            rebuildStock(templateId);
            return true;
        }
        // 疑似漂移：可能真漂移，也可能量测瞬间恰有大波在途/三方读错位。
        // 等 in-flight 落定后二次量测——两次都超界才动手（防"对账自己制造漂移"，L21 教训）。
        log.warn("stock diff suspected: templateId={}, redis={}, expected={}, inFlight={} → recheck in {}ms",
                templateId, first.remaining, first.expected, first.inFlight, recheckMillis);
        sleepQuietly(recheckMillis);
        Verdict second = measure(templateId);
        if (second.consistent()) {
            log.info("stock diff resolved on recheck (transient in-flight skew, no rebuild): templateId={}",
                    templateId);
            return false;
        }
        log.warn("STOCK DRIFT confirmed: templateId={}, redis={}, expected={}, inFlight={} → rebuild",
                templateId, second.remaining, second.expected, second.inFlight);
        rebuildStock(templateId);
        return true;
    }

    /**
     * 量测一次：读 DB 账本、Redis 余量、在途计数器，做带方向语义的判定。
     *
     * <p>diff = expected − remaining：
     * 在途窗口内 Redis 只会被多扣（先扣后提交），所以 <b>diff ≥ 0 且 ≤ 在途+余量 ⇒ 正常</b>；
     * diff &lt; 0（Redis 比 DB 口径还多）没有任何合法在途解释 ⇒ 必异常。
     */
    private Verdict measure(long templateId) {
        CouponTemplate t = templateRepository.findById(templateId).orElse(null);
        if (t == null) {
            return Verdict.CONSISTENT;   // 模板不存在（已删除），不归对账管
        }
        long expected = t.getTotalCount() - t.getIssuedCount();
        Long remaining = stockGateway.remaining(templateId);
        if (remaining == null) {
            return Verdict.KEY_MISSING;
        }
        long inFlight = this.inFlight.get();
        long diff = expected - remaining;
        boolean consistent = diff >= 0 && diff <= inFlight + RESIDUAL_TOLERANCE;
        return new Verdict(consistent, false, remaining, expected, inFlight);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一次量测的结果（remaining/expected/inFlight 仅供日志与测试断言） */
    record Verdict(boolean consistent, boolean keyMissing, Long remaining, long expected, long inFlight) {
        static final Verdict CONSISTENT = new Verdict(true, false, null, 0, 0);
        static final Verdict KEY_MISSING = new Verdict(false, true, null, 0, 0);
    }
}
