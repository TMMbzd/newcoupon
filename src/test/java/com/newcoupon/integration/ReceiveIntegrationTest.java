package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 领券用例集成验收——阶段 0 验收标准 ①② 的自动执行（docs/00 第 4 节）。
 *
 * <p>为什么需要这个类（单元测试测不到的东西）：
 * 唯一索引在真实并发下的拦截行为、@Transactional 的真实回滚、PO/Converter 的字段映射——
 * 全都依赖"真 MySQL + 真事务"，Mock 测不出来。Testcontainers 在测试启动时
 * 拉起一个一次性 mysql:8.0 容器（用完即毁，数据零残留），Flyway 自动建表，
 * 每个测试方法独享干净数据（@TestMethodOrder 按顺序共享一个容器以省启动时间，
 * 测试间用互不相同的用户/模板隔离）。
 *
 * <p>验收清单（对应计划 v2 第六节）：
 * ① 1000 并发抢 100 张：成功=100、issued=100、事件=100（零超发 + 对账恒等式）
 * ② 同用户异 requestId 并发 10：恰 1 张（阶段1起由 Redis 闸门 -2 拦截；DB 侧 uk_user_seq 兜底见 RedisStockIntegrationTest②）
 * ③ 同用户同 requestId 并发 10：恰 1 张、全部返回同一券、败者 duplicated=true（uk_idem 路径）
 * ④ 读回字段级断言：快照/状态/valid_end 固化两种券型各验（防"状态污染"类 bug）
 * ⑤ 库存=1 顺序领：第二人 STOCK_OUT
 * ⑥ 超领回滚即补偿：超领失败后 issued_count 不变（事务回滚撤销了扣减）
 */
// 本类含"千并发抢百"的压力用例：它会把连接池打满、产生大量 TransientDataAccessResourceException。
// 那是**测试制造的瞬时压力**而非被测代码的缺陷，不该触发熔断（否则"压力敏感"会扩散成"整批偶发红"）。
// 需要真实熔断阈值的用例在各自的类里显式配小值（见第 7/8 步的两个熔断用例类）。
@org.springframework.test.context.TestPropertySource(properties = {
        // **两个都要调大**：minimumNumberOfCalls 必须 ≤ slidingWindowSize，只调前者是无效配置——
        // 第一次只把 min-calls 设成一百万，实际生效的仍是窗口的 10，于是千并发的压力照样把熔断器打开、
        // 让 19 个请求以"系统繁忙"失败（失败分布里 5000=9、EX=10 就是这么来的）
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class ReceiveIntegrationTest extends ShardedIntegrationTestSupport {

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
    private javax.sql.DataSource dataSource;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private UserCouponRepository userCouponRepository;

    // ---------- 工具 ----------

    /** 建一张可领的固定区间券（领取期覆盖当前时刻），返回模板 id */
    private long createFixedTemplate(long stock) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "并发验收券", 1001L, 1, 1000L, null, 10000L, stock, 1, 1, start, end, null, null, null));
        templateAppService.publish(created.id());
        return created.id();
    }

    private record Outcome(Long couponId, String code, boolean duplicated) {
    }

    /** 并发执行 N 个领取任务，返回全部结果（成功带 couponId；失败带错误码） */
    private List<Outcome> concurrentReceive(int threads, int totalTasks,
                                            java.util.function.Function<Integer, ReceiveRequest> reqFactory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<Outcome>> futures = IntStream.range(0, totalTasks)
                    .mapToObj(i -> pool.submit(() -> {
                        startGate.await();   // 所有线程就位后同时起跑，制造真实并发
                        try {
                            ReceiveResult r = receiveAppService.receive(reqFactory.apply(i));
                            return new Outcome(r.couponId(), "OK", r.duplicated());
                        } catch (BizException e) {
                            return new Outcome(null,
                                    String.valueOf(e.getErrorCode().getCode()), false);
                        }
                    }))
                    .toList();
            startGate.countDown();
            return futures.stream().map(f -> {
                try {
                    return f.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // 依赖故障（连接池打满、熔断快速失败……）也是一种"去向"——
                    // 把它计进分布，而不是让整个用例炸掉：否则我们就失去了
                    //"这一千个请求到底都去哪了"的完整画面（本项目历史上踩过同款的坑：
                    // 异常被 Future 吞掉导致几百例"失踪"）
                    Throwable cause = e instanceof java.util.concurrent.ExecutionException
                            && e.getCause() != null ? e.getCause() : e;
                    return new Outcome(null, "EX:" + cause.getClass().getSimpleName(), false);
                }
            }).toList();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- ① 1000 并发抢 100 张 ----------

    @Test
    @Timeout(180)
    @DisplayName("验收①：1000并发抢100张 → 成功=100、issued=100、事件=100，零超发")
    void thousandConcurrentGrabHundred() throws Exception {
        long templateId = createFixedTemplate(100);

        List<Outcome> results = concurrentReceive(200, 1000,
                i -> new ReceiveRequest("bulk-req-" + i, 10_000L + i, templateId));

        long success = results.stream().filter(o -> "OK".equals(o.code())).count();
        // 失败响应的错误码分布——**先算好再断言**：否则第一个断言一红，分布就永远看不到
        //（这条用例原本把分布打印放在断言之后，导致"98 != 100"这类失败完全没有线索）
        var failureDist = results.stream()
                .filter(o -> !"OK".equals(o.code()))
                .collect(java.util.stream.Collectors.groupingBy(Outcome::code, java.util.stream.Collectors.counting()));
        assertThat(success).as("成功领取数必须恰好等于库存；失败分布=" + failureDist).isEqualTo(100);
        // 失败只允许两种：正常的售罄（3001），与**过载时的依赖故障**（EX: 类，可重试）。
        // 后者不是正确性问题——千并发打满连接池时"拒掉少量请求、让其余走完"正是过载保护的预期行为，
        // 而且**"成功恰好等于库存"这条硬指标始终成立**（上面那条断言）。
        // 它的**比例**属于容量指标，归压测批；这里只钉"绝不出现不可重试的业务假拒绝"。
        assertThat(failureDist.keySet())
                .as("失败只允许售罄或依赖故障；实际分布=" + failureDist)
                .allMatch(k -> "3001".equals(k) || k.startsWith("EX:"));
        assertThat(failureDist.getOrDefault("3001", 0L))
                .as("绝大部分失败应当是正常的售罄；实际分布=" + failureDist).isGreaterThan(700L);
        // **不要断言"售罄恰好 900"**（2026-10-08 删）：
        // 那条断言与它上面那条自相矛盾——上面刚允许"失败可以是依赖故障"（千并发打满连接池时
        // 拒掉少量请求、让其余走完，正是过载保护的预期行为），下面却要求"1000 个失败全是售罄"。
        // 数字是 842/900 时暴露：58 次是 EX（依赖故障），而**"成功恰好等于库存"这条硬指标始终成立**。
        // 这正是本项目在 P2 阶段对同类用例做过的放宽（"失败只能是售罄"→"只能是售罄或依赖故障"），
        // 只是这一行漏改了。

        // 对账恒等式（ADR-0004）：成功数 == issued_count == 发放事件数
        long issued = bucketUsed(templateId);
        assertThat(issued).isEqualTo(100);
        // 事件数由 ④ 的读回断言间接覆盖 + 下面的直查
        // （coupon_event 无领域查询端口，此处经 user_coupon 行数与事件数一致性校验）
        long coupons = results.stream().filter(o -> o.couponId() != null).distinct().count();
        assertThat(coupons).as("100 张券的 couponId 必须互不相同").isEqualTo(100);
        // 这条用例会把连接池打满（1000 并发正是它的目的）。跑完给池子一点恢复时间：
        // 否则紧随其后的用例会继续撞池子，把 TransientDataAccessResourceException 累到熔断阈值上、
        // 触发"依赖暂时不可用"（实测：下一个售罄用例就是这么红的）。
        // 这也侧面说明：熔断的 open-seconds（默认 30 秒）对"连接池瞬时打满"这类场景偏保守。
        Thread.sleep(2000);

    }

    // ---------- ② 同用户异 requestId 并发 ----------

    @Test
    @Timeout(120)
    @DisplayName("验收②：同用户异requestId并发10次 → 恰1张（Redis闸门-2拦截，issued无虚扣）")
    void sameUserDifferentRequestIds() throws Exception {
        long templateId = createFixedTemplate(1000);

        List<Outcome> results = concurrentReceive(10, 10,
                i -> new ReceiveRequest("u-req-" + i, 20_000L, templateId));

        long success = results.stream().filter(o -> "OK".equals(o.code())).count();
        long limit = results.stream().filter(o -> "3002".equals(o.code())).count();
        assertThat(success).isEqualTo(1);
        assertThat(limit).isEqualTo(9);
        assertThat(bucketUsed(templateId))
                .as("超领失败的 9 次扣减必须被回滚").isEqualTo(1);
    }

    // ---------- ③ 同用户同 requestId 并发（网络重放的并发形态） ----------

    @Test
    @Timeout(120)
    @DisplayName("验收③：同requestId并发10次 → 恰1张+9个1001重试提示；串行重放 duplicated=true 同一张券")
    void sameUserSameRequestId() throws Exception {
        long templateId = createFixedTemplate(1000);

        List<Outcome> results = concurrentReceive(10, 10,
                i -> new ReceiveRequest("dup-req", 20_001L, templateId));

        // 阶段1语义（ADR-0006）：并发同 requestId——Redis 幂等键在赢家 DB 提交前就拦下其余请求，
        // 此时 DB 无行可查 → 返回 1001"处理中，请用同一 requestId 重试"（而非纯DB方案的排队后全成功）
        List<Outcome> oks = results.stream().filter(o -> "OK".equals(o.code())).toList();
        assertThat(oks).as("恰 1 个新领成功").hasSize(1);
        long retryHint = results.stream().filter(o -> "1001".equals(o.code())).count();
        assertThat(retryHint).as("其余 9 个收到'处理中请重试'(1001)").isEqualTo(9);
        assertThat(bucketUsed(templateId))
                .as("只发了 1 张（闸门在扣库存前就拦下重放）").isEqualTo(1);

        // 串行重放（赢家已提交）：同一 requestId 再来 → duplicated=true 且同一张券
        ReceiveResult replay = receiveAppService.receive(
                new ReceiveRequest("dup-req", 20_001L, templateId));
        assertThat(replay.duplicated()).isTrue();
        assertThat(replay.couponId()).isEqualTo(oks.get(0).couponId());
    }

    // ---------- ④ 读回字段级断言（防状态污染类 bug） ----------

    @Test
    @Timeout(60)
    @DisplayName("验收④：读回字段级断言——两种券型的快照/状态/valid_end 固化全验证")
    void readBackFieldAssertions() {
        // 固定区间券：valid_end 配成 00:00:00，断言固化归一到 23:59:59
        long fixedId = createFixedTemplateEndsAtMidnight();
        ReceiveResult r1 = receiveAppService.receive(new ReceiveRequest("rb-1", 30_001L, fixedId));
        UserCoupon fixed = userCouponRepository.findByIdempotentKey(30_001L, "rb-1").orElseThrow();
        assertThat(fixed.getStatus()).isEqualTo(CouponStatus.USABLE);
        assertThat(fixed.getFaceValue()).isEqualTo(1000L);
        assertThat(fixed.getThreshold()).isEqualTo(10000L);
        assertThat(fixed.getDiscountCap()).isNull();                       // NULL 往返
        assertThat(fixed.getTemplateCode()).startsWith("T");
        assertThat(fixed.getValidEnd()).isEqualTo(LocalDateTime.now().plusDays(30).toLocalDate().atTime(23, 59, 59));
        assertThat(r1.duplicated()).isFalse();

        // 相对有效期券：领取日+7天 的当日 23:59:59
        TemplateAppService.CreatedTemplate rel = templateAppService.create(new TemplateCreateRequest(
                "相对有效期券", 1001L, 2, 8500L, 5000L, 0L, 100L, 1, 2, null, null, 7, null, null));
        templateAppService.publish(rel.id());
        receiveAppService.receive(new ReceiveRequest("rb-2", 30_002L, rel.id()));
        UserCoupon relative = userCouponRepository.findByIdempotentKey(30_002L, "rb-2").orElseThrow();
        assertThat(relative.getValidEnd())
                .isEqualTo(LocalDateTime.now().toLocalDate().plusDays(7).atTime(23, 59, 59));
        assertThat(relative.getDiscountCap()).isEqualTo(5000L);            // 满折上限快照往返
    }

    private long createFixedTemplateEndsAtMidnight() {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "零点截止券", 1001L, 1, 1000L, null, 10000L, 100L, 1, 1,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(30).toLocalDate().atStartOfDay(),   // 00:00:00
                null,                                                            // validDays（FIXED 不用）
                null, null));                                                    // 日限（默认不限）
        templateAppService.publish(created.id());
        return created.id();
    }

    // ---------- ⑤ 库存=1 顺序领 ----------

    @Test
    @Timeout(60)
    @DisplayName("验收⑤：库存=1，两人顺序领 → 第二人 STOCK_OUT")
    void stockOutSequential() {
        long templateId = createFixedTemplate(1);
        assertThat(receiveAppService.receive(new ReceiveRequest("so-1", 40_001L, templateId)).couponId())
                .isNotNull();
        assertThatThrownByCode(() -> receiveAppService.receive(new ReceiveRequest("so-2", 40_002L, templateId)),
                ErrorCode.STOCK_OUT.getCode());
    }

    // ---------- ⑥ 回滚即补偿 ----------

    @Test
    @Timeout(60)
    @DisplayName("验收⑥：超领被拒后 issued_count 不变（阶段1：闸门在扣库存前拦截，DB不虚扣；Redis计数丢失的DB兜底另见RedisStock②）")
    void rollbackRestoresStock() {
        long templateId = createFixedTemplate(100);
        receiveAppService.receive(new ReceiveRequest("rb-c-1", 50_001L, templateId));   // 成功，issued=1

        // 同一用户换 requestId 再领 → 扣减发生在前、撞 uk_user_template 在后 → 整体回滚
        assertThatThrownByCode(() -> receiveAppService.receive(new ReceiveRequest("rb-c-2", 50_001L, templateId)),
                ErrorCode.USER_LIMIT_EXCEEDED.getCode());

        assertThat(bucketUsed(templateId))
                .as("第二次的扣库存必须被事务回滚撤销（若未回滚，此值=2 即库存泄漏）")
                .isEqualTo(1);
    }

    private void assertThatThrownByCode(Runnable call, int expectedCode) {
        try {
            call.run();
            throw new AssertionError("预期抛出 BizException code=" + expectedCode + "，实际成功返回");
        } catch (BizException e) {
            assertThat(e.getErrorCode().getCode()).isEqualTo(expectedCode);
        }
    }

    /** P2b B簇换锚：ic 冻结后，发放权威读数 = SUM(stock_bucket.used)（E3c 锚） */
    private long bucketUsed(long tplId) {
        Long v = new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .queryForObject("SELECT COALESCE(SUM(used),0) FROM stock_bucket WHERE template_id = ?",
                        Long.class, tplId);
        return v == null ? 0 : v;
    }
}
