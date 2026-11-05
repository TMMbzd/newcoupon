package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.FundingSplit;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.service.DailyBillService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.application.service.TradeTxService;
import com.newcoupon.domain.settle.SettleBackfillSql;
import com.newcoupon.domain.settle.SettleQualityFlags;
import com.newcoupon.infrastructure.persistence.mapper.CouponSettlePartyDailyBillMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponShareDetailMapper;
import com.newcoupon.infrastructure.persistence.po.CouponSettlePartyDailyBillPO;
import com.newcoupon.infrastructure.persistence.po.CouponShareDetailPO;
import com.newcoupon.open.api.dto.CalculateRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V6 3min 结算最小版 · 质量标记/账单可结算口径/hold 扣减/对账落表 验收。
 *
 * <p>本类默认灰度关（{@code coupon.trade.settle-party.enabled} 缺省 false）——
 * 正是要钉"灰度关期间写入的商家钱落 {@code __default__}"这条 L76 主路径。
 *
 * <p>验证四组语义：
 * <ol>
 *   <li><b>写入打标</b>：商家券灰度关 → 分账行 LEGACY_DEFAULT+不进真实结算；
 *       平台券 {@code __default__} 合法空主体不打标；订单级兼容行 NO_ITEMS 只降粒度；</li>
 *   <li><b>账单口径</b>：settle_eligible=0 的行两张账单同步排除（等式仍成立）、
 *       质量桶行数进 ineligible_*_count；</li>
 *   <li><b>hold 扣减</b>：ACTIVE hold 命中 (app,主体[,模板]) → hold_amount/payable 落列、
 *       释放后重跑恢复、负 payable 如实展示；</li>
 *   <li><b>对账落表</b>：差异写 stock_drift_log + 指标递增（现状只 ERROR 日志——先红点）。</li>
 * </ol>
 * 外加一段对 V47 回填 SQL 的解释性复验（迁移先于测试数据执行，端到端观察不可得）。
 */
class SettleQualityBillIntegrationTest extends ShardedIntegrationTestSupport {

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private TradeTxService tradeTxService;
    @Autowired private DailyBillService dailyBillService;
    @Autowired private CouponShareDetailMapper shareDetailMapper;
    @Autowired private com.newcoupon.infrastructure.persistence.mapper.CouponRefundDetailMapper refundDetailMapper;
    @Autowired private CouponSettlePartyDailyBillMapper partyBillMapper;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private DataSource dataSource;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private record Confirmed(long templateId, long couponId, String usageNo) {
    }

    /** 建券→领→锁→核销。funder：1=平台（budgetOwner null）/2=商家（主体 M）；灰度关下所有行 __default__ */
    private Confirmed confirmCoupon(String suffix, Integer funder) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateCreateRequest req = new TemplateCreateRequest(
                "质量标记-" + suffix, 3400L, 1, 2000L, null, 10000L,
                10L, 1, 1, start, end, null, null, null,
                null, null, null, null,
                funder, null,
                funder == null ? null : "M");
        TemplateAppService.CreatedTemplate created = templateAppService.create(req);
        templateAppService.publish(created.id());

        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("quality-" + suffix, userId, created.id()));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-QUALITY-" + suffix, userId, rcv.couponId()));
        tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), null, List.of(
                new CalculateRequest.OrderItem("SKU_A", "CAT1", 8000L, 1, "L1", "M"),
                new CalculateRequest.OrderItem("SKU_B", "CAT1", 2000L, 1, "L2", null))));
        return new Confirmed(created.id(), rcv.couponId(), lock.usageNo());
    }

    // ------------------------------------------------------------------
    // ① 写入路径打标
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Q1: 商家券灰度关→分账行 LEGACY_DEFAULT+不进真实结算；平台券 __default__ 不打标")
    void merchantCouponGrayOff_taggedLegacyDefault() {
        Confirmed merchant = confirmCoupon("Q1m", 2);
        List<CouponShareDetailPO> rows = shareDetailMapper
                .selectPositiveByUsageNo(merchant.usageNo());
        assertThat(rows).as("两行商品两条分账").hasSize(2);
        for (CouponShareDetailPO row : rows) {
            assertThat(row.getQualityFlags())
                    .as("灰度关期间商家钱落 __default__ = 主体丢失（L76）→ bit0")
                    .isEqualTo(SettleQualityFlags.LEGACY_DEFAULT);
            assertThat(row.getSettleEligible()).as("主体丢失的钱不进真实结算").isEqualTo(0);
            assertThat(row.getSettlePartyId()).isEqualTo("__default__");
        }

        Confirmed platform = confirmCoupon("Q1p", null);
        for (CouponShareDetailPO row : shareDetailMapper.selectPositiveByUsageNo(platform.usageNo())) {
            assertThat(row.getQualityFlags())
                    .as("平台券 __default__ 是合法空主体，不打标").isZero();
            assertThat(row.getSettleEligible()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Q2: 订单级兼容分账行（无行号）→ NO_ITEMS 只降观测粒度，仍可结算")
    void orderLevelShare_flaggedNoItemsButEligible() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateCreateRequest req = new TemplateCreateRequest(
                "订单级-Q3", 3400L, 1, 2000L, null, 10000L,
                10L, 1, 1, start, end, null, null, null);
        TemplateAppService.CreatedTemplate created = templateAppService.create(req);
        templateAppService.publish(created.id());
        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("quality-order-level", userId, created.id()));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-QUALITY-OL", userId, rcv.couponId()));

        // split 有、行级分摊空 → 订单级兼容路径（order_line_no=NULL 的单行）
        tradeTxService.confirm(new ConfirmRequest(lock.usageNo(), 2000L),
                LocalDateTime.now(), new FundingSplit(2000L, 0L), List.of());

        List<CouponShareDetailPO> rows = shareDetailMapper
                .selectPositiveByUsageNo(lock.usageNo());
        assertThat(rows).hasSize(1);
        CouponShareDetailPO row = rows.get(0);
        assertThat(row.getOrderLineNo()).isNull();
        assertThat(row.getQualityFlags())
                .as("老式订单级行标 NO_ITEMS（出资拆分真实）")
                .isEqualTo(SettleQualityFlags.NO_ITEMS);
        assertThat(row.getSettleEligible())
                .as("NO_ITEMS 只降观测粒度——与 LEGACY_DEFAULT 的关键差别：不排除")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ② 账单可结算口径 + 质量桶计数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Q3: settle_eligible=0 的行两张账单同步排除；主体账单记 ineligible 计数")
    void ineligibleRowsExcludedFromBothBills() {
        Confirmed platform = confirmCoupon("Q3", null);   // 平台券 2000 分（灰度关全 __default__）
        LocalDate today = LocalDate.now();

        // 手工把其中一行打成"历史质量排除"（模拟 V47 回填过的老行——不必依赖写入打标）
        JdbcTemplate jdbc = jdbc();
        jdbc.update("UPDATE coupon_share_detail SET settle_eligible = 0, quality_flags = 1 "
                + "WHERE usage_no = ? AND order_line_no = 'L1'", platform.usageNo());

        dailyBillService.generateAll(today);

        // 主体账单：只剩 L2 一行 400 分（L1 1600 被排除），质量桶计数=1
        CouponSettlePartyDailyBillPO partyBill = partyBill(today, platform.templateId());
        assertThat(partyBill.getConfirmCount()).isEqualTo(1);
        assertThat(partyBill.getDiscountTotal()).isEqualTo(400L);
        assertThat(partyBill.getIneligibleShareCount())
                .as("被排除的分账行数进质量桶计数（可观测）").isEqualTo(1);
        assertThat(partyBill.getIneligibleRefundCount()).isZero();

        // 模板账单同口径排除（等式仍成立——两侧 WHERE 一致）
        assertThat(dailyBillService.reconcile(today)).isZero();

        // 平台券的 __default__ 行本身合法：排除前它是干净的（计数 1 全部来自手工打标）
        dailyBillService.generateAll(today);
        assertThat(partyBill(today, platform.templateId()).getDiscountTotal()).isEqualTo(400L);
    }

    private CouponSettlePartyDailyBillPO partyBill(LocalDate date, long templateId) {
        return partyBillMapper.selectByFilters(date, null, null, templateId, 10, 0).stream()
                .filter(b -> "__default__".equals(b.getSettlePartyId()))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // ③ hold 扣减（bill 生成只读 coupon_settlement_hold，行由测试直插）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Q4: ACTIVE hold 扣 payable（模板级只扣对应行）；释放后重跑恢复；负 payable 如实展示")
    void holdDeductsPayableAndRecoversAfterRelease() {
        Confirmed platform = confirmCoupon("Q4", null);   // __default__ 行 2000 分
        LocalDate today = LocalDate.now();
        dailyBillService.generateAll(today);
        CouponSettlePartyDailyBillPO before = partyBill(today, platform.templateId());
        assertThat(before.getDiscountTotal()).isEqualTo(2000L);
        assertThat(before.getHoldAmount()).isZero();
        assertThat(before.getPayableAmount()).isEqualTo(2000L);

        // 主体级 hold 500 + 模板级 hold（别的模板——不得命中本行）
        JdbcTemplate jdbc = jdbc();
        jdbc.update("INSERT INTO coupon_settlement_hold "
                + "(id, hold_no, app_id, settle_party_id, template_id, amount, reason, source, "
                + " status, created_by, created_at) VALUES "
                + "(?, 'HOLD-Q4-PARTY', ?, '__default__', NULL, 500, '测试主体级', 'MANUAL', 'ACTIVE', 'it', NOW())",
                System.nanoTime(), before.getAppId());
        jdbc.update("INSERT INTO coupon_settlement_hold "
                + "(id, hold_no, app_id, settle_party_id, template_id, amount, reason, source, "
                + " status, created_by, created_at) VALUES "
                + "(?, 'HOLD-Q4-OTHER-TPL', ?, '__default__', -999, 9999, '别的模板', 'MANUAL', 'ACTIVE', 'it', NOW())",
                System.nanoTime() + 1, before.getAppId());

        dailyBillService.generateAll(today);
        CouponSettlePartyDailyBillPO held = partyBill(today, platform.templateId());
        assertThat(held.getHoldAmount())
                .as("主体级 hold 全额命中；别的模板的 hold 不得渗入").isEqualTo(500L);
        assertThat(held.getPayableAmount()).isEqualTo(1500L);
        assertThat(held.getDiscountTotal()).as("hold 不改账面净额").isEqualTo(2000L);

        // 释放（条件 UPDATE 语义在 hold 服务测试里钉；这里直接改列验证账单侧重算）
        jdbc.update("UPDATE coupon_settlement_hold SET status='RELEASED', released_at=NOW() "
                + "WHERE hold_no='HOLD-Q4-PARTY'");
        dailyBillService.generateAll(today);
        CouponSettlePartyDailyBillPO released = partyBill(today, platform.templateId());
        assertThat(released.getHoldAmount()).as("释放后重跑：hold 归零").isZero();
        assertThat(released.getPayableAmount()).isEqualTo(2000L);

        // 负 payable：hold 超过当期净额 → 如实展示负数（不钳制——钳制会掩盖问题）
        jdbc.update("INSERT INTO coupon_settlement_hold "
                + "(id, hold_no, app_id, settle_party_id, template_id, amount, reason, source, "
                + " status, created_by, created_at) VALUES "
                + "(?, 'HOLD-Q4-BIG', ?, '__default__', NULL, 99999, '超额hold', 'MANUAL', 'ACTIVE', 'it', NOW())",
                System.nanoTime() + 2, before.getAppId());
        dailyBillService.generateAll(today);
        assertThat(partyBill(today, platform.templateId()).getPayableAmount())
                .as("负 payable=未释放 hold 超过当期净额，财务需人工关注").isEqualTo(2000L - 99999L);
    }

    // ------------------------------------------------------------------
    // ④ 对账差异落表 + 指标
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Q5: 篡改主体账单→reconcile 差异落 stock_drift_log（两侧金额进 detail）+ 指标递增")
    void reconcileDiffWritesDriftLogAndMetric() {
        Confirmed platform = confirmCoupon("Q5", null);
        LocalDate today = LocalDate.now();
        dailyBillService.generateAll(today);
        assertThat(dailyBillService.reconcile(today)).isZero();

        double before = counter("PARTY_VS_TEMPLATE");
        jdbc().update("UPDATE coupon_settle_party_daily_bill SET discount_total = discount_total + 1 "
                        + "WHERE bill_date = ? AND template_id = ? AND settle_party_id = '__default__'",
                today, platform.templateId());

        assertThat(dailyBillService.reconcile(today)).isEqualTo(1);

        // 差异落表：action=SETTLE_BILL_RECONCILE，detail 里两侧金额可对（现状只打 ERROR 日志——先红点）
        Map<String, Object> drift = jdbc().queryForMap(
                "SELECT action, detail FROM stock_drift_log "
                        + "WHERE action = 'SETTLE_BILL_RECONCILE' AND detail LIKE ? "
                        + "ORDER BY id DESC LIMIT 1",
                "%" + platform.templateId() + "%");
        assertThat(drift.get("action")).isEqualTo("SETTLE_BILL_RECONCILE");
        String detail = String.valueOf(drift.get("detail"));
        assertThat(detail).as("detail 记两侧金额（模板侧 2000）").contains("2000");
        assertThat(detail).as("detail 记模板 id").contains(String.valueOf(platform.templateId()));

        assertThat(counter("PARTY_VS_TEMPLATE"))
                .as("指标 coupon_settle_reconcile_diff_total{type=PARTY_VS_TEMPLATE} 递增")
                .isGreaterThan(before);

        // 重跑收敛：upsert 覆盖后差异消失（既有语义不回归）
        dailyBillService.generateAll(today);
        assertThat(dailyBillService.reconcile(today)).isZero();
    }

    private double counter(String type) {
        io.micrometer.core.instrument.Counter c = meterRegistry.find("coupon_settle_reconcile_diff_total")
                .tag("type", type).counter();
        return c == null ? 0.0 : c.count();
    }

    // ------------------------------------------------------------------
    // ⑤ 退款镜像打标（审查 A 簇：退款明细是第四个写入点，不能漏）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Q7: 被排除分账行的退款明细镜像同标——从未计入的钱不能被冲回压低净额")
    void refundMirrorsExcludedShareRowTags() {
        Confirmed merchant = confirmCoupon("Q7", 2);   // 灰度关 → 两行 LEGACY_DEFAULT+eligible=0
        LocalDate today = LocalDate.now();
        dailyBillService.generateAll(today);
        // 前置：整组被排除，账面无该模板金额（金额口径；行是否存在由 Q8 的可观测行为钉）

        // 整单退款（无 items = 退剩余全部）→ 两条退款明细
        tradeAppService.refund(new com.newcoupon.application.dto.TradeDtos.RefundRequest(
                merchant.usageNo(), "RF-QUALITY-Q7", null));

        List<com.newcoupon.infrastructure.persistence.po.CouponRefundDetailPO> details =
                refundDetailMapper.selectByRefundNo("RF-QUALITY-Q7");
        assertThat(details).as("两行商品两条退款明细").hasSize(2);
        for (com.newcoupon.infrastructure.persistence.po.CouponRefundDetailPO d : details) {
            assertThat(d.getQualityFlags())
                    .as("退款明细从原分账行镜像质量标（分账侧丢失主体，退款侧同样丢失）")
                    .isEqualTo(SettleQualityFlags.LEGACY_DEFAULT);
            assertThat(d.getSettleEligible()).as("镜像排除——冲回不得进真实结算").isEqualTo(0);
        }

        // 再跑账单：净额不得出现 -2000（被排除的核销从未计入，其冲回计入=净额无声压低）
        dailyBillService.generateAll(today);
        List<CouponSettlePartyDailyBillPO> partyRows = partyBillMapper.selectByFilters(
                today, null, "__default__", merchant.templateId(), 10, 0);
        assertThat(partyRows.stream()
                .anyMatch(r -> r.getDiscountTotal() != 0 || r.getRefundCount() > 0))
                .as("主体账单不得出现被排除组的冲回金额。rows=" + partyRows)
                .isFalse();
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT COALESCE(SUM(discount_total), 0) FROM coupon_daily_bill "
                        + "WHERE bill_date = ? AND template_id = ?", Long.class,
                today, merchant.templateId()))
                .as("模板账单同理（同口径过滤）").isZero();
    }

    // ------------------------------------------------------------------
    // ⑦ 审查 C 簇：全排除组的可观测性（L76 主场景不能是账单上的黑洞）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Q8: 整组零合格行 → 主体账单出 0 金额行且 ineligible 计数>0（不是无行蒸发）")
    void fullyExcludedGroupStillProducesObservableRow() {
        Confirmed merchant = confirmCoupon("Q8", 2);   // 灰度关 → 两行全 LEGACY_DEFAULT+0
        LocalDate today = LocalDate.now();

        dailyBillService.generateAll(today);

        List<CouponSettlePartyDailyBillPO> rows = partyBillMapper.selectByFilters(
                today, null, "__default__", merchant.templateId(), 10, 0);
        assertThat(rows).as("全排除组也必须有宿主行承载质量桶计数").hasSize(1);
        CouponSettlePartyDailyBillPO row = rows.get(0);
        assertThat(row.getConfirmCount()).isZero();
        assertThat(row.getRefundCount()).isZero();
        assertThat(row.getDiscountTotal()).as("0 金额 + 计数>0 = 可观测信号").isZero();
        assertThat(row.getHoldAmount()).isZero();
        assertThat(row.getPayableAmount()).isZero();
        assertThat(row.getIneligibleShareCount()).isEqualTo(2);
        // 金额等式不受影响：0 贡献，模板账单无该模板行、reconcile 仍绿
        assertThat(dailyBillService.reconcile(today)).isZero();
    }

    // ------------------------------------------------------------------
    // ⑧ 审查 D 簇：重跑覆盖必须留痕（改明细+重跑=账单静默重述的威胁路径）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Q9: 生成→改明细→重跑 → drift log 记旧值新值 + 指标递增；账单仍收敛到新值")
    void rerunChangeLeavesDriftTrace() {
        Confirmed platform = confirmCoupon("Q9", null);   // __default__ 2000 分
        LocalDate today = LocalDate.now();
        dailyBillService.generateAll(today);
        assertThat(partyBill(today, platform.templateId()).getDiscountTotal()).isEqualTo(2000L);

        double before = meterRegistry.find("coupon_settle_bill_rerun_changed_total")
                .counter() == null ? 0.0
                : meterRegistry.find("coupon_settle_bill_rerun_changed_total").counter().count();

        // 改明细：多插一条今天的合格分账行（同模板同主体 +400）
        JdbcTemplate jdbc = jdbc();
        jdbc.update("INSERT INTO coupon_share_detail "
                        + "(id, user_coupon_id, user_id, template_id, app_id, settle_party_id, "
                        + " usage_no, order_no, order_line_no, platform_burden, merchant_burden, share_total) "
                        + "VALUES (?, ?, ?, ?, '1', '__default__', ?, 'O-Q9', 'L9', 400, 0, 400)",
                System.nanoTime(), platform.couponId(), System.nanoTime(),
                platform.templateId(), "U-Q9-" + System.nanoTime());

        dailyBillService.generateAll(today);

        // 账单收敛到新值（重跑覆盖仍是运维目的）
        assertThat(partyBill(today, platform.templateId()).getDiscountTotal()).isEqualTo(2400L);
        // 变化必留痕：drift log 记两侧读数 + 指标递增（现状静默覆盖——先红点）
        Map<String, Object> drift = jdbc.queryForMap(
                "SELECT action, detail FROM stock_drift_log "
                        + "WHERE action = 'SETTLE_BILL_RERUN_CHANGED' AND detail LIKE ? "
                        + "ORDER BY id DESC LIMIT 1",
                "%" + platform.templateId() + "%");
        assertThat(String.valueOf(drift.get("detail")))
                .as("detail 记旧值与新值").contains("\"old\"").contains("\"new\"");
        double after = meterRegistry.find("coupon_settle_bill_rerun_changed_total").counter().count();
        assertThat(after).as("指标 coupon_settle_bill_rerun_changed_total 递增")
                .isGreaterThan(before);
    }

    // ------------------------------------------------------------------
    // ⑨ V47 回填 SQL 解释性复验（迁移先于测试数据执行——手工跑同款语句验证判定口径）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Q6: 回填判定口径——商家__default__打标排除/平台__default__不动/NO_ITEMS只标不排除/FUNDING_UNKNOWN排除")
    void backfillJudgmentSqlVerifiedByReplay() {
        JdbcTemplate jdbc = jdbc();
        long merchantTpl = confirmCoupon("Q6m", 2).templateId();
        long platformTpl = confirmCoupon("Q6p", null).templateId();

        // 造"迁移前形状"的行：quality_flags=0/settle_eligible=1（列默认值），主体/有无行号按判定维度摆
        long uid = System.nanoTime();
        insertShare(jdbc, uid, merchantTpl, "__default__", "L1");   // 商家钱落哨兵 → 必须打标排除
        insertShare(jdbc, uid + 1, merchantTpl, "M", "L2");          // 商家钱有真实主体 → 不动
        insertShare(jdbc, uid + 2, platformTpl, "__default__", "L1");// 平台合法空主体 → 不动
        insertShare(jdbc, uid + 3, platformTpl, "__default__", null);// 平台订单级行 → 只加 NO_ITEMS

        jdbc.update(SettleBackfillSql.SHARE_LEGACY_DEFAULT);
        jdbc.update(SettleBackfillSql.SHARE_NO_ITEMS);

        assertThat(shareFlags(jdbc, uid)).isEqualTo(SettleQualityFlags.LEGACY_DEFAULT);
        assertThat(shareEligible(jdbc, uid)).isEqualTo(0);
        assertThat(shareFlags(jdbc, uid + 1)).as("真实主体的商家钱不碰").isZero();
        assertThat(shareEligible(jdbc, uid + 1)).isEqualTo(1);
        assertThat(shareFlags(jdbc, uid + 2)).as("平台券 __default__ 合法空主体不碰").isZero();
        assertThat(shareEligible(jdbc, uid + 2)).isEqualTo(1);
        assertThat(shareFlags(jdbc, uid + 3)).isEqualTo(SettleQualityFlags.NO_ITEMS);
        assertThat(shareEligible(jdbc, uid + 3))
                .as("NO_ITEMS 只标不排除").isEqualTo(1);

        // 退款明细：funding_known=0 的关联行 → FUNDING_UNKNOWN+排除；funding_known=1 不动
        jdbc.update("INSERT INTO coupon_refund_order "
                + "(refund_no, usage_no, user_coupon_id, user_id, template_id, app_id, order_no, "
                + " refund_type, refund_discount_amt, refund_platform_amt, refund_merchant_amt, "
                + " request_digest, item_count, funding_known) VALUES "
                + "('RF-Q6-UNK', 'U-Q6-UNK', 1, 1, ?, '1', 'O-Q6', 'PARTIAL', 100, 0, 0, 'd1', 1, 0), "
                + "('RF-Q6-KN',  'U-Q6-KN',  1, 1, ?, '1', 'O-Q6', 'PARTIAL', 100, 100, 0, 'd2', 1, 1)",
                platformTpl, platformTpl);
        jdbc.update("INSERT INTO coupon_refund_detail "
                + "(refund_no, usage_no, order_line_no, user_coupon_id, user_id, template_id, app_id, "
                + " settle_party_id, refund_qty, refund_discount_amt, refund_platform_amt, refund_merchant_amt) VALUES "
                + "('RF-Q6-UNK', 'U-Q6-UNK', 'L1', 1, 1, ?, '1', '__default__', 1, 100, 0, 0), "
                + "('RF-Q6-KN',  'U-Q6-KN',  'L1', 1, 1, ?, '1', '__default__', 1, 100, 100, 0)",
                platformTpl, platformTpl);

        jdbc.update(SettleBackfillSql.REFUND_FUNDING_UNKNOWN);

        Map<String, Object> unknown = refundRow(jdbc, "RF-Q6-UNK");
        assertThat(((Number) unknown.get("quality_flags")).intValue())
                .isEqualTo(SettleQualityFlags.FUNDING_UNKNOWN);
        assertThat(((Number) unknown.get("settle_eligible")).intValue()).isZero();
        Map<String, Object> known = refundRow(jdbc, "RF-Q6-KN");
        assertThat(((Number) known.get("quality_flags")).intValue()).isZero();
        assertThat(((Number) known.get("settle_eligible")).intValue()).isEqualTo(1);
    }

    private void insertShare(JdbcTemplate jdbc, long userId, long templateId,
                             String party, String lineNo) {
        jdbc.update("INSERT INTO coupon_share_detail "
                        + "(id, user_coupon_id, user_id, template_id, app_id, settle_party_id, "
                        + " usage_no, order_no, order_line_no, platform_burden, merchant_burden, share_total) "
                        + "VALUES (?, ?, ?, ?, '1', ?, ?, ?, ?, 100, 0, 100)",
                System.nanoTime(), System.nanoTime(), userId, templateId, party,
                "U-Q6-" + userId, "O-Q6-" + userId, lineNo);
    }

    private int shareFlags(JdbcTemplate jdbc, long userId) {
        return jdbc.queryForObject(
                "SELECT quality_flags FROM coupon_share_detail WHERE user_id = ?", Integer.class, userId);
    }

    private int shareEligible(JdbcTemplate jdbc, long userId) {
        return jdbc.queryForObject(
                "SELECT settle_eligible FROM coupon_share_detail WHERE user_id = ?", Integer.class, userId);
    }

    private Map<String, Object> refundRow(JdbcTemplate jdbc, String refundNo) {
        return jdbc.queryForMap(
                "SELECT quality_flags, settle_eligible FROM coupon_refund_detail WHERE refund_no = ?",
                refundNo);
    }
}
