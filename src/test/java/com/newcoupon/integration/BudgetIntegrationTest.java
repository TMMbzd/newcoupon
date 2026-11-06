package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.newcoupon.application.dto.ActivityCreateRequest;
import com.newcoupon.application.dto.ActivityResponse;
import com.newcoupon.application.dto.CouponCodeBatchCreateRequest;
import com.newcoupon.application.dto.CouponCodeBatchCreateResult;
import com.newcoupon.application.dto.CouponCodeRedeemRequest;
import com.newcoupon.application.dto.GrantTaskCreateRequest;
import com.newcoupon.application.dto.GrantTaskCreateResult;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.StockAdjustRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ActivityAppService;
import com.newcoupon.application.service.BudgetReconcileJob;
import com.newcoupon.application.service.BudgetService;
import com.newcoupon.application.service.CouponCodeService;
import com.newcoupon.application.service.GrantTaskService;
import com.newcoupon.application.service.GrantTaskWorker;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.StockShardAdjustService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetAccountMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetLedgerMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetReservationMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponCodeMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetAccountPO;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetLedgerPO;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetReservationPO;
import com.newcoupon.infrastructure.persistence.po.CouponCodeBatchPO;
import com.newcoupon.infrastructure.persistence.po.CouponCodePO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantTaskPO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 预算账本与发放对账（V6 阶段 1C）行为契约：预算硬顶（并发不超预算）、
 * 预占-确认-释放三段式、chunk 级批量结算、僵尸预占回收、对账四等式。
 *
 * <p>锚定口径：预算全部挂在活动 budget_enabled=1 之后——预算关=零变化由整个既有
 * 套件回归，本类另有一例显式钉住（预算关的活动预算额再小也不拦发放）。
 *
 * <p>本类上下文开了 {@code coupon.activity.check-enabled=true}：全部模板必须挂在
 * 真实活动下（helper 先建活动再建券）——顺带覆盖"建券活动校验"开关的两条分支
 * （活动不存在 / 跨应用活动）。
 *
 * <p>worker 与对账任务的调度全部拉长到 1 小时，用例手动 runOnce() 驱动
 * （沿用 AsyncIssueWorkerIntegrationTest 的手法），避免后台轮次干扰精确断言。
 */
@Timeout(300)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.stock.shard-count=8",
                "coupon.grant.worker.enabled=true",
                "coupon.grant.worker.initial-delay-ms=3600000",
                "coupon.grant.worker.fixed-delay-ms=3600000",
                "coupon.grant.worker.retry-backoff-base-ms=200",
                // 建券活动校验：本类的模板全部先建活动，开关开着验证两条拒绝分支
                "coupon.activity.check-enabled=true",
                // 对账任务装配进上下文（默认关），用例直调 runOnce()
                "coupon.budget.reconcile.enabled=true",
                "coupon.budget.reconcile.initial-delay-ms=3600000",
                "coupon.budget.reconcile.fixed-delay-ms=3600000",
                "coupon.circuit-breaker.min-calls=10000",
                "coupon.circuit-breaker.window-size=10000",
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class BudgetIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    /**
     * 用户号段（全量套件共享库）：940/950 已被 GrantTask/CouponCode 占，本类用 960_000。
     *
     * <p><b>同分片用户的步长必须=模板的"有效分片数"</b>：publish 会把模板的
     * stock_shard_count 改写成 N_eff=min(配置 8, total_count)——库存 3 的模板
     * 实际只有 3 片，按 8 步长造的用户会散成多个 chunk，chunk 级断言就没形状了。
     * 所以步长由调用方按 min(8, total) 传入。
     */
    private static final AtomicLong USER = new AtomicLong(960_001);

    private static long nextUser(int shards) {
        return USER.getAndAdd(shards);
    }

    /** n 个全部落在同一库存分片的用户（步长=有效分片数 → mod 恒等）。 */
    private static List<Long> sameShardUsers(int n, int shards) {
        return IntStream.range(0, n).mapToObj(i -> nextUser(shards)).toList();
    }

    @Autowired
    private ActivityAppService activityAppService;
    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private GrantTaskService grantTaskService;
    @Autowired
    private GrantTaskWorker worker;
    @Autowired
    private CouponCodeService codeService;
    @Autowired
    private BudgetService budgetService;
    @Autowired
    private BudgetReconcileJob reconcileJob;
    @Autowired
    private StockShardAdjustService adjustService;
    @Autowired
    private CouponBudgetAccountMapper accountMapper;
    @Autowired
    private CouponBudgetReservationMapper reservationMapper;
    @Autowired
    private CouponBudgetLedgerMapper ledgerMapper;
    @Autowired
    private CouponGrantTaskMapper taskMapper;
    @Autowired
    private CouponCodeMapper codeMapper;
    @Autowired
    private JdbcTemplate jdbcTpl;
    @Autowired
    private OpenAppService appService;

    @Value("${local.server.port}")
    private int port;

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    /** 建活动（budgetAmount 分；enabled=是否开预算闸门）。 */
    private long createActivity(long budgetAmount, boolean enabled) {
        ActivityResponse created = activityAppService.create(new ActivityCreateRequest(
                "预算验证活动", budgetAmount, enabled,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), "tester"));
        assertThat(created.id()).as("活动创建必须成功").isNotNull();
        return created.id();
    }

    /** 在活动下建券并发布：面额=unitCost（满减），库存=total。 */
    private long createTemplateUnder(long activityId, long unitCost, long total, int perUserLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "预算验证券", activityId, 1, unitCost, null, unitCost * 10, total, perUserLimit,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, 0L, 0));
        templateAppService.publish(created.id());
        return created.id();
    }

    /** 活动预算账户行（不存在返回 null——预算关的活动从未预占过就不会有账户）。 */
    private CouponBudgetAccountPO accountOf(long activityId) {
        return accountMapper.selectByScope(CouponBudgetAccountPO.SCOPE_ACTIVITY, activityId,
                CouponBudgetAccountPO.PERIOD_TOTAL, CouponBudgetAccountPO.PERIOD_KEY_TOTAL);
    }

    /** 同步领一张券；异常原样抛（用例按错误码断言）。 */
    private void receive(long userId, long templateId, String requestId) {
        receiveAppService.receive(new ReceiveRequest(requestId, userId, templateId));
    }

    /** 驱动 worker 到任务终态（照 GrantTaskIntegrationTest 的模式）。 */
    private CouponGrantTaskPO drainUntilTerminal(String taskNo, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        CouponGrantTaskPO task = taskMapper.selectByTaskNo(taskNo);
        int status = task.getStatus();
        while (status != 2 && status != 3 && status != 5 && System.currentTimeMillis() < deadline) {
            worker.runOnce();
            Thread.sleep(50);
            task = taskMapper.selectByTaskNo(taskNo);
            status = task.getStatus();
        }
        assertThat(status).as("任务应在时限内到终态，实际 status=" + status).isIn(2, 3, 5);
        return taskMapper.selectByTaskNo(taskNo);
    }

    // ------------------------------------------------------------------
    // ① 并发 3 个批量任务抢预算：恰 1 个建得成、5 张券、账户守恒
    // ------------------------------------------------------------------

    @Test
    @Timeout(180)
    @DisplayName("批量抢预算：3 任务×5 张（各需 100 分）抢 100 分预算，恰 1 个建成并发出 5 张，2 个 BUDGET_EXHAUSTED")
    void concurrentTasks_contendForBudget_exactlyOneWins() throws Exception {
        long activityId = createActivity(100, true);          // 100 分 ÷ 20 分/张 = 5 张
        long templateId = createTemplateUnder(activityId, 20, 100, 1);
        List<Long> users = sameShardUsers(15, 8);              // 有效 8 片 → 5 人一 chunk

        CountDownLatch go = new CountDownLatch(1);
        List<Object> outcomes = new CopyOnWriteArrayList<>();
        List<Thread> threads = IntStream.range(0, 3).mapToObj(i -> new Thread(() -> {
            try {
                go.await();
                outcomes.add(grantTaskService.createTask(new GrantTaskCreateRequest(
                        "bud-race-" + i + "-" + templateId, templateId,
                        users.subList(i * 5, i * 5 + 5), "tester")));
            } catch (BizException e) {
                outcomes.add(e);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // 环境级异常也计入结论：断言 hasSize(3) 会把它揪出来而不是静默丢线程
                outcomes.add(e);
            }
        })).peek(Thread::start).toList();
        go.countDown();
        for (Thread t : threads) {
            t.join(30_000);
        }

        long created = outcomes.stream().filter(o -> o instanceof GrantTaskCreateResult).count();
        long rejected = outcomes.stream()
                .filter(o -> o instanceof BizException e
                        && e.getErrorCode() == ErrorCode.BUDGET_EXHAUSTED).count();
        assertThat(outcomes).as("三个并发受理都要有结论").hasSize(3);
        assertThat(created).as("预算只够一个任务，建成的必须恰 1 个").isEqualTo(1);
        assertThat(rejected).as("其余 2 个必须被预算闸门拒绝（3009）").isEqualTo(2);

        GrantTaskCreateResult winner = (GrantTaskCreateResult) outcomes.stream()
                .filter(o -> o instanceof GrantTaskCreateResult).findFirst().orElseThrow();
        CouponGrantTaskPO done = drainUntilTerminal(winner.taskNo(), 120_000);
        assertThat(done.getSuccessCount()).as("赢家任务的 5 张全部发出").isEqualTo(5);
        assertThat(done.getPendingCount()).isZero();

        // 账户守恒：100 分全部消耗、无悬挂预占；10 个用户的券没有（他们属于被拒的任务）
        CouponBudgetAccountPO account = accountOf(activityId);
        assertThat(account.getConsumed()).isEqualTo(100);
        assertThat(account.getReserved()).isZero();
        assertThat(reservationMapper.sumOpenByAccount(account.getId())).isZero();
        CouponBudgetLedgerPO last = ledgerMapper.selectLastByAccount(account.getId());
        assertThat(last.getConsumedAfter()).as("末条流水快照=账户现值").isEqualTo(100);
        assertThat(last.getReservedAfter()).isZero();
    }

    // ------------------------------------------------------------------
    // ② 同步领取：预算耗尽被拦
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("同步领取第 6 张被拦：预算 100÷20=5 张，第 6 人 BUDGET_EXHAUSTED(3009)")
    void syncReceive_blockedWhenBudgetExhausted() {
        long activityId = createActivity(100, true);
        long templateId = createTemplateUnder(activityId, 20, 100, 1);

        for (int i = 0; i < 5; i++) {
            receive(nextUser(8), templateId, "bud-sync-ok-" + i);
        }
        try {
            receive(nextUser(8), templateId, "bud-sync-blocked");
            fail("第 6 张必须被预算闸门拦截");
        } catch (BizException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BUDGET_EXHAUSTED);
        }

        CouponBudgetAccountPO account = accountOf(activityId);
        assertThat(account.getConsumed()).isEqualTo(100);
        assertThat(account.getReserved()).as("被拦的预占必须当场释放").isZero();
    }

    // ------------------------------------------------------------------
    // ③ 库存失败 → 预算释放回池 → 补库存后再领成功
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("库存失败预算回池：售罄的 3001 不吃预算；补库存后同 requestId 再领成功")
    void stockFailure_releasesBudget_thenSucceedsAfterRestock() {
        long activityId = createActivity(100, true);
        long templateId = createTemplateUnder(activityId, 20, 2, 1);   // 库存只有 2

        long u1 = nextUser(8);
        long u2 = nextUser(8);
        long u3 = nextUser(8);
        receive(u1, templateId, "bud-stock-1");
        receive(u2, templateId, "bud-stock-2");
        try {
            receive(u3, templateId, "bud-stock-3");
            fail("库存 2 已发完，第 3 人必须售罄");
        } catch (BizException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_OUT);
        }

        // 售罄路径不吃预算：40 分消耗、0 预占（被拒的那次预占已释放）
        CouponBudgetAccountPO account = accountOf(activityId);
        assertThat(account.getConsumed()).isEqualTo(40);
        assertThat(account.getReserved()).isZero();

        // 补库存 2→5：同 requestId 重试（失败时没发过券，幂等键干净），这次成功并确认预算
        adjustService.adjust(templateId, new StockAdjustRequest(
                "bud-stock-adj-" + templateId, 2L, 5L, "预算回池验证补库存", "tester"));
        receive(u3, templateId, "bud-stock-3");
        account = accountOf(activityId);
        assertThat(account.getConsumed()).as("补库存后的重试消耗第 3 份预算").isEqualTo(60);
        assertThat(account.getReserved()).isZero();
    }

    // ------------------------------------------------------------------
    // ④ 兑换码过预算：BUDGET_EXHAUSTED（3009），不是 3007；码回池
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("兑换码过预算：预算耗尽后兑码 → 3009（预算语义，不是 3007 防枚举语义）；码保持可兑")
    void codeRedeem_overBudget_gets3009AndCodeStaysActive() {
        long activityId = createActivity(100, true);
        long templateId = createTemplateUnder(activityId, 20, 100, 1);

        CouponCodeBatchCreateResult batch = codeService.createBatch(
                new CouponCodeBatchCreateRequest("bud-code-batch-" + templateId,
                        templateId, 2, 1, "tester"));
        assertThat(batch.idempotent()).isFalse();
        String code = batch.codes().get(0);

        // 先用同步领取把预算吃光（5 张=100 分）
        for (int i = 0; i < 5; i++) {
            receive(nextUser(8), templateId, "bud-code-drain-" + i);
        }

        try {
            codeService.redeem(new CouponCodeRedeemRequest("bud-code-req-1", code, nextUser(8)));
            fail("预算耗尽后兑换必须被拦");
        } catch (BizException e) {
            // 预算是业务面失败（与码本身无关）：必须原样 3009，绝不能被吞成防枚举的 3007
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BUDGET_EXHAUSTED);
        }

        // 码回 ACTIVE（BizException 分支的 reactivateWithLog），补预算语义下可再兑
        CouponCodeBatchPO batchRow = batchMapper.selectByBatchNo(batch.batchNo());
        CouponCodePO codeRow = codeMapper.selectByBatch(batchRow.getId()).get(0);
        assertThat(codeRow.getStatus()).as("预算被拒的码必须回池保持可兑").isEqualTo(0);
        assertThat(accountOf(activityId).getReserved()).isZero();
    }

    // ------------------------------------------------------------------
    // ⑤ chunk 部分失败：确认=成功张数、失败部分回池
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("chunk 部分失败：库存 3 发 5 人，结算确认 3×20=60 分、释放 40 分；ledger 可回放")
    void chunkPartialFailure_confirmsSuccesses_releasesRest() throws Exception {
        long activityId = createActivity(200, true);           // 10 张的池子
        long templateId = createTemplateUnder(activityId, 20, 3, 1);   // 库存 3
        // 有效分片数=min(8, 库存3)=3：步长 3 的用户全部同片 → 单 chunk 预占 100 分
        List<Long> users = sameShardUsers(5, 3);

        GrantTaskCreateResult created = grantTaskService.createTask(
                new GrantTaskCreateRequest("bud-partial-" + templateId, templateId, users, "tester"));
        CouponGrantTaskPO done = drainUntilTerminal(created.taskNo(), 120_000);

        assertThat(done.getSuccessCount()).isEqualTo(3);
        assertThat(done.getFailCount()).as("库存不足的 2 人终态失败(3001)").isEqualTo(2);

        CouponBudgetAccountPO account = accountOf(activityId);
        assertThat(account.getConsumed()).as("只确认成功张数的预算 3×20").isEqualTo(60);
        assertThat(account.getReserved()).as("失败部分的预占必须回池").isZero();

        // 预占行：status=2 释放（开口 40 回池），confirmed=60 留痕
        List<CouponBudgetReservationPO> reservations = reservationMapper.selectByBiz(
                CouponBudgetReservationPO.BIZ_CHUNK, created.taskNo() + "-0");
        assertThat(reservations).hasSize(1);
        CouponBudgetReservationPO reservation = reservations.get(0);
        assertThat(reservation.getAmount()).isEqualTo(100);       // 5 张 × 20
        assertThat(reservation.getConfirmedAmount()).isEqualTo(60);
        assertThat(reservation.getStatus()).isEqualTo(CouponBudgetReservationPO.STATUS_RELEASED);

        // ledger 回放：RESERVE +100 → CONFIRM +60 → RELEASE -40，末条快照=账户现值
        List<CouponBudgetLedgerPO> ledger = ledgerMapper.selectByAccount(account.getId());
        assertThat(ledger.stream().map(CouponBudgetLedgerPO::getOp).toList())
                .containsExactly("RESERVE", "CONFIRM", "RELEASE");
        assertThat(ledger.get(0).getReservedAfter()).isEqualTo(100);
        assertThat(ledger.get(1).getConsumedAfter()).isEqualTo(60);
        assertThat(ledger.get(2).getAmount()).isEqualTo(-40);
        assertThat(ledger.get(2).getReservedAfter()).isZero();
    }

    // ------------------------------------------------------------------
    // ⑥ 僵尸预占过期回收
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("僵尸预占回收：把 expire_time 改到过去，expireStale 回池置 status=3；预算可再次被领")
    void staleReservation_reaped_budgetReusable() {
        long activityId = createActivity(100, true);
        long templateId = createTemplateUnder(activityId, 20, 100, 1);
        long user = nextUser(8);

        // 直接预占一张模拟"崩在预占后、收口前"的僵尸（receive 从未被调用）
        com.newcoupon.domain.coupon.CouponTemplate template =
                templateRepositoryField.findById(templateId).orElseThrow();
        CouponBudgetReservationPO zombie = budgetService.tryReserveSingle(
                template, CouponBudgetReservationPO.BIZ_SINGLE, "bud-zombie-req");
        assertThat(zombie).isNotNull();
        CouponBudgetAccountPO account = accountOf(activityId);
        assertThat(account.getReserved()).isEqualTo(20);

        // 过期时刻改到过去 → 回收：回池 20、status=3、EXPIRE 流水
        jdbcTpl.update("UPDATE coupon_budget_reservation SET expire_time = '2000-01-01 00:00:00.000' "
                + "WHERE id = ?", zombie.getId());
        int reaped = budgetService.expireStale();
        assertThat(reaped).isGreaterThanOrEqualTo(1);

        account = accountOf(activityId);
        assertThat(account.getReserved()).as("僵尸预占必须回池").isZero();
        assertThat(reservationMapper.selectByReserveNo(zombie.getReserveNo()).getStatus())
                .isEqualTo(CouponBudgetReservationPO.STATUS_EXPIRED);
        assertThat(ledgerMapper.selectLastByAccount(account.getId()).getOp()).isEqualTo("EXPIRE");

        // 回池后的预算可以再次被用：领取成功（预占→确认 20 分）
        receive(user, templateId, "bud-zombie-after");
        account = accountOf(activityId);
        assertThat(account.getConsumed()).isEqualTo(20);
        assertThat(account.getReserved()).isZero();
    }

    // ------------------------------------------------------------------
    // ⑦ 对账：注入破坏 → drift log 告警；健康时零 drift
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("对账：健康轮零 drift；直改 account.reserved 后 runOnce 报 BUDGET_RECONCILE_* 告警")
    void reconcile_detectsInjectedAccountDrift() {
        long activityId = createActivity(100, true);
        long templateId = createTemplateUnder(activityId, 20, 100, 1);
        for (int i = 0; i < 3; i++) {
            receive(nextUser(8), templateId, "bud-recon-" + i);
        }

        // 健康轮：本用例之前的全部数据都该是守恒的（drift 行数不增加）
        long driftBefore = countBudgetDrifts();
        int healthy = reconcileJob.runOnce();
        assertThat(healthy).as("健康轮不得发现差异（历史用例数据都应守恒）").isZero();
        assertThat(countBudgetDrifts()).isEqualTo(driftBefore);

        // 注入破坏：绕过账本直改账户 reserved（模拟人工改库/写库 bug）
        CouponBudgetAccountPO account = accountOf(activityId);
        jdbcTpl.update("UPDATE coupon_budget_account SET reserved = reserved + 5 WHERE id = ?",
                account.getId());

        int drifts = reconcileJob.runOnce();
        assertThat(drifts).as("注入后必须查出差异").isGreaterThanOrEqualTo(1);
        long newDrifts = countBudgetDrifts() - driftBefore;
        assertThat(newDrifts).as("差异必须落 stock_drift_log（等式①与②各一条）").isGreaterThanOrEqualTo(1);
        String actions = String.join(",", jdbcTpl.queryForList(
                "SELECT action FROM stock_drift_log WHERE action LIKE 'BUDGET_RECONCILE%' "
                        + "ORDER BY id DESC LIMIT 5", String.class));
        assertThat(actions).contains("BUDGET_RECONCILE_LEDGER");
    }

    // ------------------------------------------------------------------
    // ⑧ 预算关（budget_enabled=0）：现行为完全不变
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("预算关=零变化：budget_enabled=0 的活动预算额再小也不拦，账户表根本不出现")
    void budgetDisabled_behaviorUnchanged() {
        long activityId = createActivity(20, false);           // 20 分=1 张的额，但开关关着
        long templateId = createTemplateUnder(activityId, 20, 100, 1);

        for (int i = 0; i < 3; i++) {
            receive(nextUser(8), templateId, "bud-off-" + i);   // 全部成功，无人被拦
        }
        assertThat(accountOf(activityId)).as("预算关的活动不产生任何账本行").isNull();
    }

    // ------------------------------------------------------------------
    // ⑨ 建券活动校验（本类上下文 check-enabled=true）
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("建券活动校验：activityId 不存在 → 2004；内部活动被开放面应用引用 → 2004（归属不符）")
    void templateCreate_activityCheckRejects() throws Exception {
        // ① 不存在的活动
        try {
            createTemplateUnder(9_999_999L, 20, 10, 1);
            fail("校验开着：幽灵活动必须被拒");
        } catch (BizException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACTIVITY_NOT_FOUND);
        }

        // ② 跨应用：内部面建的活动（app=内置应用），开放面应用引用它建券 → 拒
        provisionOnce();
        long internalActivity = createActivity(100, true);
        OpenApiTestClient.Response resp = client.post("/open/v1/templates", """
                {"name":"跨应用活动券","activityId":%d,"couponType":1,"faceValue":1000,
                 "threshold":10000,"totalCount":10,"perUserLimit":1,
                 "validType":2,"validDays":7}""".formatted(internalActivity),
                APP_A, secretA, "bud-cross-act-1");
        assertThat(resp.status()).as(resp.body()).isEqualTo(200);   // 业务错=HTTP 200+码（全站约定）
        assertThat(resp.body()).contains("2004");
    }

    // ------------------------------------------------------------------
    // ⑩ 兑换码正常路径过预算闸门（正例：预算内兑换成功并确认）
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("兑换码预算正例：预算内兑换成功，预占确认成消耗（一发一收）")
    void codeRedeem_withinBudget_confirms() {
        long activityId = createActivity(100, true);
        long templateId = createTemplateUnder(activityId, 20, 100, 1);
        CouponCodeBatchCreateResult batch = codeService.createBatch(
                new CouponCodeBatchCreateRequest("bud-code-ok-" + templateId,
                        templateId, 1, 1, "tester"));
        long user = nextUser(8);

        codeService.redeem(new CouponCodeRedeemRequest(
                "bud-code-ok-req", batch.codes().get(0), user));

        CouponBudgetAccountPO account = accountOf(activityId);
        assertThat(account.getConsumed()).as("兑换成功的预算必须确认").isEqualTo(20);
        assertThat(account.getReserved()).isZero();
        List<CouponBudgetReservationPO> reservations = reservationMapper.selectByBiz(
                CouponBudgetReservationPO.BIZ_CODE, "bud-code-ok-req");
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus())
                .as("一发一收：全额确认后关成 CONFIRMED")
                .isEqualTo(CouponBudgetReservationPO.STATUS_CONFIRMED);
        assertThat(reservations.get(0).getConfirmedAmount()).isEqualTo(20);
    }

    // ------------------------------------------------------------------
    // 共用：开放面应用（越权用例）
    // ------------------------------------------------------------------

    private static final String APP_A = "t-budget-a";
    private static OpenApiTestClient client;
    private static String secretA;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    private void provisionOnce() throws Exception {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        client = new OpenApiTestClient(port);
        secretA = appService.create(new OpenAppService.CreateAppRequest(APP_A, "甲应用", null, null, null)).secret();
    }

    @Autowired
    private com.newcoupon.domain.repository.TemplateRepository templateRepositoryField;
    @Autowired
    private com.newcoupon.infrastructure.persistence.mapper.CouponCodeBatchMapper batchMapper;

    private long countBudgetDrifts() {
        Long count = jdbcTpl.queryForObject(
                "SELECT COUNT(*) FROM stock_drift_log WHERE action LIKE 'BUDGET_RECONCILE%'",
                Long.class);
        return count == null ? 0 : count;
    }

    @Test
    @Timeout(120)
    @DisplayName("定向发放接预算：grant_type=2 的 chunk 预占按目标人数，发完确认消耗")
    void targetedGrant_reservesAndConfirmsChunkBudget() throws Exception {
        long activityId = createActivity(100, true);                 // 5 张 × 20 分
        long templateId = createTemplateUnder(activityId, 20, 3, 1); // 库存 3 → 有效 3 片
        List<Long> users = sameShardUsers(3, 3);                     // 单 chunk

        GrantTaskCreateResult created = grantTaskService.createTargetedTask(
                new GrantTaskCreateRequest("bud-targeted-" + templateId, templateId,
                        users, "tester"));
        CouponGrantTaskPO task = taskMapper.selectByTaskNo(created.taskNo());
        assertThat(task.getGrantType()).isEqualTo(2);
        assertThat(accountOf(activityId).getReserved())
                .as("定向任务按 chunk 预占：3 张 × 20 分").isEqualTo(60);

        CouponGrantTaskPO done = drainUntilTerminal(created.taskNo(), 60_000);
        assertThat(done.getSuccessCount()).isEqualTo(3);
        assertThat(accountOf(activityId).getReserved()).isZero();
        assertThat(accountOf(activityId).getConsumed()).isEqualTo(60);
    }
}
