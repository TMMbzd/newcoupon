package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ActivityCreateRequest;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.RefundRequest;
import com.newcoupon.application.dto.TradeDtos.RefundResult;
import com.newcoupon.application.service.ActivityAppService;
import com.newcoupon.application.service.CouponCodeService;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.ReceiveAsyncAppService;
import com.newcoupon.application.service.RiskListService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.application.dto.CouponCodeBatchCreateRequest;
import com.newcoupon.application.dto.CouponCodeBatchCreateResult;
import com.newcoupon.application.dto.CouponCodeRedeemRequest;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.open.api.dto.CalculateRequest;
import com.newcoupon.infrastructure.persistence.mapper.CouponCodeBatchMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponCodeMapper;
import com.newcoupon.infrastructure.persistence.po.CouponCodePO;
import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * V6 4B · 风控场景接线 + 预算燃烧信号 + 结算冻结（先红后绿：接线前全量跑红取证）。
 *
 * <p>覆盖语义钉子（docs/runbook/风控最小版.md §4B）：
 * <ol>
 *   <li>锁券/核销 REJECT → 7003；**已成功操作的幂等重试不吃 7003**（拉黑后重试旧请求
 *       拿回旧结果，不是 7003——审查 C2 同款豁免在交易场景的延伸）；</li>
 *   <li><b>退款合规红线</b>：REJECT 一律不拒绝退款——退款照做 + 决策日志如实记 REJECT +
 *       结算冻结（hold_no=RISK-{decisionId} 幂等，同 decisionId 不双建）；REVIEW 只留痕不冻结；</li>
 *   <li>异步 /receive-async 与兑换码 redeem 接入（4A 遗留边界）：REJECT 在受理层/抢占前拦截，
 *       异步的 REJECT 任务不建；</li>
 *   <li>预算燃烧信号 R_BUDGET_BURN：当日账本流水超阈 → REVIEW 放行留痕（预算信号是
 *       “可疑加速”不是“确定攻击”）；阈值内 PASS；活动不开预算 → degraded 跳过；</li>
 *   <li>熔断段语义：新接线点的 7003 是业务结果，不计 mysql 熔断失败。</li>
 * </ol>
 *
 * <p>数据纪律（共享容器）：userId/名单值/requestId 全部 UUID/nanoTime 独有；
 * 名单走服务层（管理面能力与 HTTP 面同源）。
 */
@Timeout(300)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "coupon.switch.risk.decision.enabled=true",
        // 异步受理接线（T4）：async 开必须 worker 开（启动校验），轮询间隔拉到 1 小时=测试期间不跑批
        "coupon.issue.async-enabled=true",
        "coupon.issue.worker.enabled=true",
        "coupon.issue.worker.initial-delay-ms=3600000",
        "coupon.issue.worker.fixed-delay-ms=3600000",
        "coupon.issue.async-template-ids=*",
        "coupon.grant.worker.enabled=false",
        // 预算燃烧阈值压到 3：两次领券（各 2 行流水）即可越过，测 REVIEW 分支不用造大量数据
        "coupon.risk.budget-burn.daily-ledger-threshold=3",
        // 熔断统计抬窗：测试内零星调用不触发熔断判据（只验证“7003 不计失败”，不开闸）
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class Risk4BIntegrationTest extends ShardedIntegrationTestSupport {

    private static final String REJECT_REASON = "R4B 拉黑-测试";

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private ReceiveAsyncAppService receiveAsyncAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private ActivityAppService activityAppService;
    @Autowired private CouponCodeService codeService;
    @Autowired private RiskListService riskListService;
    @Autowired private OpenAppService appService;
    @Autowired private CouponCodeMapper codeMapper;
    @Autowired private CouponCodeBatchMapper batchMapper;
    @Autowired private DependencyCircuitBreakers breakers;
    @Autowired private DataSource dataSource;

    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();
    private static volatile long templateId;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        // 内置面建模板并发布（归属内置 app '1'，与 4A RiskDecisionIntegrationTest 同款）
        templateId = createPublishedTemplate(3101L);
    }

    /** 建并发布“满10000减2000”模板（14 参便利构造，活动挂靠不校验时可用假 activityId）。 */
    private long createPublishedTemplate(long activityId) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "风控4B验证券-" + UUID.randomUUID().toString().substring(0, 8),
                activityId, 1, 2000L, null, 10000L, 100L, 50,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30),
                null, 0L, 0));
        templateAppService.publish(created.id());
        return created.id();
    }

    private static long uniqueUserId() {
        return 97_000_000L + (System.nanoTime() % 1_000_000L);
    }

    private void blacklist(long userId) {
        riskListService.add(new com.newcoupon.application.dto.RiskDtos.ListAddRequest(
                "BLACK", "USER_ID", String.valueOf(userId), null, REJECT_REASON, null));
    }

    private void graylist(long userId) {
        riskListService.add(new com.newcoupon.application.dto.RiskDtos.ListAddRequest(
                "GRAY", "USER_ID", String.valueOf(userId), null, "R4B 灰名单-只留痕", null));
    }

    /** 领→锁（两行订单形态，优惠 2000 分）。返回 usageNo。 */
    private String receiveAndLock(long userId, String suffix) {
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("rk4b-" + suffix, userId, templateId));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-R4B-" + suffix, userId, rcv.couponId()));
        return lock.usageNo();
    }

    /** 领→锁→核销（平台整栈复算，两行 L1/L2 各 2 件，订单 20000 分、优惠 2000 分）。 */
    private String confirmedUsage(long userId, String suffix) {
        String usageNo = receiveAndLock(userId, suffix);
        tradeAppService.confirm(new ConfirmRequest(usageNo, null, List.of(
                new CalculateRequest.OrderItem("SKU_A", "CAT1", 6000L, 2, "L1"),
                new CalculateRequest.OrderItem("SKU_B", "CAT1", 4000L, 2, "L2"))));
        return usageNo;
    }

    // ------------------------------------------------------------------
    // ① 锁券 / 核销：REJECT → 7003；幂等重试豁免
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T1: 拉黑后锁券 7003（scene=LOCK）；未拉黑前的既有领券不受影响")
    void lockRejectedAfterBlacklist() {
        long userId = uniqueUserId();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("rk4b-t1-" + UUID.randomUUID(), userId, templateId));

        blacklist(userId);
        assertThatThrownBy(() -> tradeAppService.lock(
                        new LockRequest("ORD-R4B-T1-" + UUID.randomUUID(), userId, rcv.couponId())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RISK_REJECTED);
        Integer rows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log WHERE user_id = ? AND scene = 'LOCK' "
                        + "AND action = 'REJECT'", Integer.class, userId);
        assertThat(rows).as("锁券决策要留 REFUND 之外的独立场景行（scene=LOCK）").isEqualTo(1);
    }

    @Test
    @DisplayName("T2: 拉黑后核销新占用单 7003（scene=CONFIRM）；已成功核销的重试拿回 duplicated 不是 7003")
    void confirmRejectedButReplayExempt() {
        long userId = uniqueUserId();
        String usageNo = confirmedUsage(userId, "t2-" + UUID.randomUUID());

        // 已成功核销的重试：拉黑后同 usageNo 再核销=既有事实的重放，必须拿回首次结果
        blacklist(userId);
        var replay = tradeAppService.confirm(new ConfirmRequest(usageNo, null, List.of(
                new CalculateRequest.OrderItem("SKU_A", "CAT1", 6000L, 2, "L1"),
                new CalculateRequest.OrderItem("SKU_B", "CAT1", 4000L, 2, "L2"))));
        assertThat(replay.duplicated()).as("重放返回首次结果，不吃 7003").isTrue();

        // 未核销的新占用单：拉黑后核销 → REJECT（这单从没成功过，不是重放）
        long other = uniqueUserId();
        String fresh = receiveAndLock(other, "t2b-" + UUID.randomUUID());
        blacklist(other);
        assertThatThrownBy(() -> tradeAppService.confirm(new ConfirmRequest(fresh, null, List.of(
                        new CalculateRequest.OrderItem("SKU_A", "CAT1", 6000L, 2, "L1")))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RISK_REJECTED);
        Integer rows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log WHERE user_id = ? AND scene = 'CONFIRM' "
                        + "AND action = 'REJECT'", Integer.class, other);
        assertThat(rows).as("核销决策留 scene=CONFIRM 行").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ② 退款合规红线：REJECT 不拒绝退款——照做 + 留痕 + 结算冻结
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T3: 拉黑后退款照做成功；日志如实 REJECT；hold=RISK-{decisionId} 主体级；重试不双冻结；灰名单不冻结")
    void refundRedLineDecideThenHold() {
        long userId = uniqueUserId();
        String usageNo = confirmedUsage(userId, "t3-" + UUID.randomUUID());
        String refundNo = "RF-R4B-T3-" + UUID.randomUUID();

        blacklist(userId);
        RefundResult refund = tradeAppService.refund(new RefundRequest(usageNo, refundNo, null));
        assertThat(refund.duplicated()).as("合规红线：REJECT 不拒绝退款——退款照做").isFalse();
        assertThat(refund.discountAmt()).isEqualTo(2000L);

        // 决策日志：action 如实记 REJECT（打标留痕），request_id=refundNo 可定位
        var decision = jdbc().queryForMap(
                "SELECT id, action, matched_rules FROM risk_decision_log "
                        + "WHERE user_id = ? AND scene = 'REFUND' AND request_id = ?", userId, refundNo);
        assertThat(decision.get("action")).isEqualTo("REJECT");
        assertThat((String) decision.get("matched_rules")).contains("R_LIST_BLACK");

        // 结算冻结：RISK-{decisionId} 主体级 hold（template_id 空），金额=本单退款额
        String holdNo = "RISK-" + decision.get("id");
        var hold = jdbc().queryForMap(
                "SELECT app_id, settle_party_id, template_id, amount, source, status "
                        + "FROM coupon_settlement_hold WHERE hold_no = ?", holdNo);
        assertThat(hold.get("app_id")).isEqualTo("1");
        assertThat(hold.get("settle_party_id")).isEqualTo("__default__");
        assertThat(hold.get("template_id")).as("主体级冻结（template_id 空=该主体全部模板行都扣）").isNull();
        assertThat(((Number) hold.get("amount")).longValue()).isEqualTo(2000L);
        assertThat(hold.get("source")).isEqualTo("RISK_RULE");
        assertThat(hold.get("status")).isEqualTo("ACTIVE");

        // 幂等：同 refundNo 重试=退款重放（豁免风控）→ 不新增决策行、不双建 hold
        RefundResult retried = tradeAppService.refund(new RefundRequest(usageNo, refundNo, null));
        assertThat(retried.duplicated()).isTrue();
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log WHERE user_id = ? AND scene = 'REFUND' "
                        + "AND request_id = ?", Integer.class, userId, refundNo)).isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_settlement_hold WHERE hold_no = ?",
                Integer.class, holdNo)).isEqualTo(1);

        // 灰名单（REVIEW）：退款照做、只留痕、不冻结
        long grayUser = uniqueUserId();
        String grayUsage = confirmedUsage(grayUser, "t3g-" + UUID.randomUUID());
        String grayRefundNo = "RF-R4B-T3G-" + UUID.randomUUID();
        graylist(grayUser);
        RefundResult grayRefund = tradeAppService.refund(
                new RefundRequest(grayUsage, grayRefundNo, null));
        assertThat(grayRefund.duplicated()).as("REVIEW 同样不拒绝退款").isFalse();
        var grayDecision = jdbc().queryForMap(
                "SELECT id, action FROM risk_decision_log WHERE user_id = ? AND scene = 'REFUND' "
                        + "AND request_id = ?", grayUser, grayRefundNo);
        assertThat(grayDecision.get("action")).isEqualTo("REVIEW");
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_settlement_hold WHERE hold_no = ?",
                Integer.class, "RISK-" + grayDecision.get("id")))
                .as("REVIEW 只留痕不冻结").isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // ③ 异步领券 / 兑换码（4A 遗留边界）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T4: 拉黑后 receive-async 7003（受理层拦）；REJECT 任务不建（无 coupon_issue_task 行）")
    void asyncReceiveRejectedBeforeTaskCreation() {
        long userId = uniqueUserId();
        blacklist(userId);
        String requestId = "rk4b-t4-" + UUID.randomUUID();

        assertThatThrownBy(() -> receiveAsyncAppService.receive(
                        new ReceiveRequest(requestId, userId, templateId)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RISK_REJECTED);
        Integer tasks = jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_issue_task WHERE user_id = ? AND request_id = ?",
                Integer.class, userId, requestId);
        assertThat(tasks).as("REJECT 在 202 之前拦——受理承诺（任务行）不成立").isZero();
        Integer rows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log WHERE user_id = ? AND scene = 'RECEIVE' "
                        + "AND request_id = ? AND action = 'REJECT'", Integer.class, userId, requestId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("T5: 拉黑后兑换码 7003（casLock 前）；码不烧（保持 ACTIVE 可在解除后重兑）")
    void redeemRejectedBeforeCasLock() {
        long template = createPublishedTemplate(3102L);
        CouponCodeBatchCreateResult batch = codeService.createBatch(new CouponCodeBatchCreateRequest(
                "rk4b-t5-batch-" + UUID.randomUUID(), template, 3, 1, "tester"));
        String code = batch.codes().get(0);
        long userId = uniqueUserId();
        blacklist(userId);

        assertThatThrownBy(() -> codeService.redeem(
                        new CouponCodeRedeemRequest("rk4b-t5-" + UUID.randomUUID(), code, userId)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RISK_REJECTED);
        Long batchId = batchMapper.selectByBatchNo(batch.batchNo()).getId();
        Integer active = jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_code WHERE batch_id = ? AND status = ?",
                Integer.class, batchId, CouponCodePO.STATUS_ACTIVE);
        assertThat(active).as("REJECT 在抢占前拦——码不置 USED/LOCKED").isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // ④ 预算燃烧信号（R_BUDGET_BURN → REVIEW）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T6: 预算燃烧——阈值内 PASS；当日流水超阈 REVIEW 放行留痕；活动无预算 degraded 跳过")
    void budgetBurnSignalReview() {
        // 真实活动（开预算，账户懒建）：面额 100 分=单张成本
        var activity = activityAppService.create(new ActivityCreateRequest(
                "风控4B燃烧活动-" + UUID.randomUUID().toString().substring(0, 8),
                1_000_000L, true,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), "tester"));
        long tpl = createPublishedTemplate(activity.id());

        // 第 1、2 次（不同用户）：账户流水 0 行 → 2 行，都 ≤ 阈值 3 → PASS
        long first = uniqueUserId();
        long second = uniqueUserId();
        receiveAppService.receive(new ReceiveRequest(
                "rk4b-t6-1-" + UUID.randomUUID(), first, tpl));
        receiveAppService.receive(new ReceiveRequest(
                "rk4b-t6-2-" + UUID.randomUUID(), second, tpl));

        // 第 3 次：决策时点已有 4 行当日流水（每次领券=RESERVE+CONFIRM 两行）> 3 → REVIEW，放行
        long third = uniqueUserId();
        ReceiveResult thirdReceive = receiveAppService.receive(new ReceiveRequest(
                "rk4b-t6-3-" + UUID.randomUUID(), third, tpl));
        assertThat(thirdReceive.duplicated()).as("预算信号是可疑加速不是确定攻击：REVIEW 放行").isFalse();
        var review = jdbc().queryForMap(
                "SELECT action, matched_rules, factors_json FROM risk_decision_log "
                        + "WHERE user_id = ? AND scene = 'RECEIVE' ORDER BY id DESC LIMIT 1", third);
        assertThat(review.get("action")).isEqualTo("REVIEW");
        assertThat((String) review.get("matched_rules")).contains("R_BUDGET_BURN");
        assertThat((String) review.get("factors_json")).contains("budget.ledgerToday");

        // 前两次的决策也记了“未超阈”的因子（PASS 路径同样落因子，回放可解释）
        var firstFactors = jdbc().queryForMap(
                "SELECT action, factors_json FROM risk_decision_log "
                        + "WHERE user_id = ? AND scene = 'RECEIVE' ORDER BY id DESC LIMIT 1", first);
        assertThat(firstFactors.get("action")).isEqualTo("PASS");
        assertThat((String) firstFactors.get("factors_json")).contains("budget.ledgerToday");

        // 活动不开预算：因子缺失 degraded 跳过（预算闸门都没开，没有“加速”可言）
        var noBudgetActivity = activityAppService.create(new ActivityCreateRequest(
                "风控4B无预算活动-" + UUID.randomUUID().toString().substring(0, 8),
                1_000_000L, false,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), "tester"));
        long noBudgetTpl = createPublishedTemplate(noBudgetActivity.id());
        long plain = uniqueUserId();
        receiveAppService.receive(new ReceiveRequest(
                "rk4b-t6-nb-" + UUID.randomUUID(), plain, noBudgetTpl));
        var skipped = jdbc().queryForMap(
                "SELECT action, degraded, factors_json FROM risk_decision_log "
                        + "WHERE user_id = ? AND scene = 'RECEIVE' ORDER BY id DESC LIMIT 1", plain);
        assertThat(skipped.get("action")).isEqualTo("PASS");
        assertThat(((Number) skipped.get("degraded")).intValue())
                .as("活动不开预算=因子缺失，degraded=1 且放行").isEqualTo(1);
        assertThat((String) skipped.get("factors_json")).contains("budget.skipped");
    }

    // ------------------------------------------------------------------
    // ⑤ 熔断段语义：7003 是业务结果，不计 mysql 熔断失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T7: 连续 7003 不推高 mysql 熔断失败计数、状态保持 CLOSED")
    void rejectDoesNotCountAsBreakerFailure() {
        CircuitBreaker mysql = breakers.dependency("mysql");
        long failedBefore = mysql.getMetrics().getNumberOfFailedCalls();

        for (int i = 0; i < 3; i++) {
            long userId = uniqueUserId();
            ReceiveResult rcv = receiveAppService.receive(
                    new ReceiveRequest("rk4b-t7-" + i + "-" + UUID.randomUUID(), userId, templateId));
            blacklist(userId);
            long couponId = rcv.couponId();
            assertThatThrownBy(() -> tradeAppService.lock(
                            new LockRequest("ORD-R4B-T7-" + UUID.randomUUID(), userId, couponId)))
                    .isInstanceOf(BizException.class);
        }
        assertThat(mysql.getMetrics().getNumberOfFailedCalls())
                .as("7003 是风控业务结果，不是 mysql 依赖故障——不进熔断统计")
                .isEqualTo(failedBefore);
        assertThat(mysql.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
