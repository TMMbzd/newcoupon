package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.C1ReceiveAppService;
import com.newcoupon.application.service.GrantTaskService;
import com.newcoupon.application.service.GrantTaskTxService;
import com.newcoupon.application.service.GrantTaskWorker;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.StockShardAdjustService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.application.dto.GrantTaskCancelResult;
import com.newcoupon.application.dto.GrantTaskCreateRequest;
import com.newcoupon.application.dto.GrantTaskCreateResult;
import com.newcoupon.application.dto.GrantTaskRetryResult;
import com.newcoupon.application.dto.StockAdjustRequest;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantChunkMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantItemMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.po.CouponGrantChunkPO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantItemPO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantTaskPO;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 批量发放任务域（V6 阶段 1A 第一刀）行为契约：任务编排复用 C1 发放核心，
 * 本域只证明三件事——库存不超发、计数守恒、取消/崩溃不破坏前两者。
 *
 * <p>五条用例对应设计文档的五个验收点：
 * <ol>
 *   <li>核心不变量：批量任务与线上领券**同时**抢同一份库存，成功数恰好=total，
 *       任务计数守恒 total=success+fail+skip+pending（终态 pending=0）；</li>
 *   <li>requestId 幂等：同 app+requestId 建两次只建一个任务；</li>
 *   <li>取消守恒：跑一半取消，终态 CANCELED、未执行 item=CANCELED、已发券不受影响；</li>
 *   <li>崩溃恢复：把 RUNNING chunk 的租约改成过期，下一轮重新领取续跑，
 *       item 级幂等键保证"发过券但没记账"的 item 重放不重复发放；</li>
 *   <li>越权：乙应用用甲模板建任务 → 403 + 6011。</li>
 * </ol>
 *
 * <p>1A 收尾补三例（retry-failed / failures CSV 两个端点）：售罄后补库存重发全部到齐
 * 且二次受理幂等；失败明细导出的 BOM/转义/公式注入防护；新端点的越权与状态守卫。
 *
 * <p>worker 的 @Scheduled 初始延迟拉长到 1 小时（沿用 AsyncIssueWorkerIntegrationTest 的
 * 手法），测试手动调用 runOnce()/runChunks()，避免后台轮次抢走用例想精确控制的 chunk。
 */
@Timeout(300)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.stock.shard-count=8",
                "coupon.grant.worker.enabled=true",
                "coupon.grant.worker.initial-delay-ms=3600000",
                "coupon.grant.worker.fixed-delay-ms=3600000",
                // 退避基线压短：可重试 item（5000/5001 类）在用例时间内能收敛到终态
                "coupon.grant.worker.retry-backoff-base-ms=200",
                "coupon.circuit-breaker.min-calls=10000",
                "coupon.circuit-breaker.window-size=10000",
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class GrantTaskIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    /**
     * 用户号段。全量套件共享一个库，段必须与其它用例类错开：
     * 300_000/500_000/700_000/810_000/900_000/910_000 都已被占
     * （900_000 是 AsyncIssueWorkerRawBulkDisabledIntegrationTest——它按
     * user_id 全局数 coupon_expire_task，撞段会让它的"每人一条过期任务"多数一次）。
     */
    private static final AtomicInteger USER = new AtomicInteger(940_000);

    @Autowired
    private GrantTaskService grantTaskService;
    @Autowired
    private GrantTaskTxService grantTaskTxService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTpl;
    @Autowired
    private GrantTaskWorker worker;
    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private C1ReceiveAppService c1ReceiveAppService;
    @Autowired
    private StockShardAdjustService adjustService;
    @Autowired
    private UserCouponRepository userCouponRepository;
    @Autowired
    private CouponGrantTaskMapper taskMapper;
    @Autowired
    private CouponGrantChunkMapper chunkMapper;
    @Autowired
    private CouponGrantItemMapper itemMapper;
    @Autowired
    private CouponStockShardMapper shardMapper;
    @Autowired
    private OpenAppService appService;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired
    private javax.sql.DataSource dataSource;

    @Value("${local.server.port}")
    private int port;

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private long createPublishedTemplate(long total, int perUserLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "批量发放验证券", 1001L, 1, 1000L, null, 10000L, total, perUserLimit,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, 0L, 0));
        templateAppService.publish(created.id());
        return created.id();
    }

    private static List<Long> newUsers(int n) {
        return IntStream.range(0, n).mapToObj(i -> (long) USER.incrementAndGet()).toList();
    }

    /** 任务当前状态码；非终态（2/3/5）返回原值，任务不存在抛断言。 */
    private int statusOf(String taskNo) {
        CouponGrantTaskPO task = taskMapper.selectByTaskNo(taskNo);
        assertThat(task).as("任务必须存在: " + taskNo).isNotNull();
        return task.getStatus();
    }

    /** 驱动 worker 直到任务终态（2 SUCCESS / 3 FAILED / 5 CANCELED）或超时。 */
    private CouponGrantTaskPO drainUntilTerminal(String taskNo, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int status = statusOf(taskNo);
        while (status != 2 && status != 3 && status != 5 && System.currentTimeMillis() < deadline) {
            worker.runOnce();
            Thread.sleep(50);
            status = statusOf(taskNo);
        }
        assertThat(status).as("任务应在时限内到终态，实际 status=" + status).isIn(2, 3, 5);
        return taskMapper.selectByTaskNo(taskNo);
    }

    /** 守恒断言：total = success+fail+skip+pending（任何时刻都必须成立）。 */
    private void assertConservation(CouponGrantTaskPO task) {
        assertThat(task.getTotalCount())
                .as("计数守恒: total=%s success=%s fail=%s skip=%s pending=%s",
                        task.getTotalCount(), task.getSuccessCount(), task.getFailCount(),
                        task.getSkipCount(), task.getPendingCount())
                .isEqualTo(task.getSuccessCount() + task.getFailCount()
                        + task.getSkipCount() + task.getPendingCount());
    }

    /**
     * uk 终审：逐用户点查（userCouponRepository 按 (userId,templateId) 精确路由到物理分表），
     * 没有用户拿到超过限领（=1）的券——重复发放的最终防线。user_coupon 是 64 张物理分表，
     * 不能用一条裸 SQL 跨片 GROUP BY（ShardingSphere 联邦查询在这里既慢又不必需）。
     */
    private long usersOverLimit(long templateId, List<Long> users) {
        return users.stream()
                .filter(u -> userCouponRepository.countByUserAndTemplate(u, templateId) > 1)
                .count();
    }

    // ------------------------------------------------------------------
    // ① 核心不变量：批量与线上并发抢库存，恰好发完、不超发、守恒
    // ------------------------------------------------------------------

    @Test
    @Timeout(180)
    @DisplayName("核心不变量：150 用户批量任务 + 50 并发线上领券抢 100 库存，恰好 100 张、无重复、计数守恒")
    void batchAndOnlineCompete_noOversell_conserved() throws Exception {
        long templateId = createPublishedTemplate(100, 1);
        List<Long> taskUsers = newUsers(150);
        List<Long> onlineUsers = newUsers(50);
        String requestId = "gt-core-" + templateId;

        GrantTaskCreateResult created = grantTaskService.createTask(
                new GrantTaskCreateRequest(requestId, templateId, taskUsers, "tester"));
        assertThat(created.idempotent()).isFalse();
        assertThat(created.totalCount()).isEqualTo(150);

        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> online = onlineUsers.stream()
                    .map(u -> pool.submit(() -> {
                        go.await();
                        try {
                            receiveAppService.receive(
                                    new ReceiveRequest("gt-online-" + u, u, templateId));
                            return "OK";
                        } catch (BizException e) {
                            return String.valueOf(e.getErrorCode().getCode());
                        }
                    }))
                    .toList();
            go.countDown();

            // 主线程与线上领券**同时**跑 worker：两条路径从第一张券起就在竞争同一组分片行
            long deadline = System.currentTimeMillis() + 120_000;
            int status = statusOf(created.taskNo());
            while (status != 2 && status != 3 && status != 5 && System.currentTimeMillis() < deadline) {
                worker.runOnce();
                Thread.sleep(50);
                status = statusOf(created.taskNo());
            }

            List<String> outcomes = online.stream()
                    .map(f -> {
                        try {
                            return f.get(60, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            return "EX:" + e.getClass().getSimpleName();
                        }
                    })
                    .toList();
            long onlineOk = outcomes.stream().filter("OK"::equals).count();

            CouponGrantTaskPO task = taskMapper.selectByTaskNo(created.taskNo());
            assertThat(task.getStatus()).as("库存充足路径任务应到 SUCCESS").isEqualTo(2);

            // 恰好 100：两条路径合计领取数必须等于库存，多一张=超发，少一张=丢单
            assertThat(userCouponRepository.countByTemplate(templateId))
                    .as("任务 success=%s + 线上 OK=%s 应恰好等于库存 100",
                            task.getSuccessCount(), onlineOk)
                    .isEqualTo(100);
            assertThat(task.getSuccessCount() + onlineOk).isEqualTo(100);
            assertThat(shardMapper.sumUsed(templateId)).isEqualTo(100);

            // 无重复券：任何用户在该模板下最多 1 张（perUserLimit=1，uk 终审）
            List<Long> allUsers = new java.util.ArrayList<>(taskUsers);
            allUsers.addAll(onlineUsers);
            assertThat(usersOverLimit(templateId, allUsers)).as("不得有用户拿到超过限领的券").isZero();

            // 计数守恒：终态 pending=0，total=success+fail+skip
            assertConservation(task);
            assertThat(task.getPendingCount()).isZero();
            assertThat(task.getSuccessCount() + task.getFailCount() + task.getSkipCount())
                    .isEqualTo(150);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // ② requestId 幂等
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("requestId 幂等：同 app+requestId 建两次只建一个任务，第二次返回现状")
    void sameRequestId_createsSingleTask() throws Exception {
        long templateId = createPublishedTemplate(20, 1);
        List<Long> users = newUsers(5);
        String requestId = "gt-idem-" + templateId;

        GrantTaskCreateResult first = grantTaskService.createTask(
                new GrantTaskCreateRequest(requestId, templateId, users, "tester"));
        assertThat(first.idempotent()).isFalse();

        GrantTaskCreateResult second = grantTaskService.createTask(
                new GrantTaskCreateRequest(requestId, templateId, users, "tester"));
        assertThat(second.taskNo()).isEqualTo(first.taskNo());
        assertThat(second.idempotent()).isTrue();
        assertThat(second.totalCount()).isEqualTo(5);

        CouponGrantTaskPO task = taskMapper.selectByTaskNo(first.taskNo());
        CouponGrantTaskPO byRequest = taskMapper.selectByAppAndRequest(task.getAppId(), requestId);
        assertThat(byRequest).isNotNull();
        assertThat(byRequest.getId()).isEqualTo(task.getId());
        assertThat(itemMapper.selectByTask(task.getId())).hasSize(5);
        assertThat(chunkMapper.selectByTask(task.getId())).isNotEmpty();

        CouponGrantTaskPO done = drainUntilTerminal(first.taskNo(), 60_000);
        assertThat(done.getSuccessCount()).isEqualTo(5);
        assertThat(done.getPendingCount()).isZero();
        assertConservation(done);
    }

    @Test
    @DisplayName("幂等三分支补口：并发同 requestId 双写 uk 终审归一；同键换模板拒绝；模板 id>127 重放不被 Long 缓存掩盖")
    void requestIdEdgeConcurrent_andBoxedComparison() throws Exception {
        // 模板自增键推过 Long 缓存区（>127）：否则下面同模板重放用 != 比较也碰巧相等，
        // 审查发现的引用比较缺陷会被缓存掩盖（单类新库跑 id 都很小）
        jdbcTpl.execute("ALTER TABLE coupon_template AUTO_INCREMENT = 2000");

        long templateId = createPublishedTemplate(30, 1);
        List<Long> users = newUsers(4);
        String requestId = "gt-idem-edge-" + templateId;

        // ① 并发双写：两个线程同 requestId 同时受理——uk(app_id, request_id) 终审归一
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<GrantTaskCreateResult> results =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    results.add(grantTaskService.createTask(
                            new GrantTaskCreateRequest(requestId, templateId, users, "tester")));
                } catch (RuntimeException e) {
                    results.add(null);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertThat(results).hasSize(2);
        GrantTaskCreateResult r0 = results.get(0);
        GrantTaskCreateResult r1 = results.get(1);
        assertThat(r0).as("并发双受理至少一个成功").isNotNull();
        assertThat(r1).isNotNull();
        assertThat(r1.taskNo()).as("并发同键必须归一到同一任务").isEqualTo(r0.taskNo());
        CouponGrantTaskPO byRequest = taskMapper.selectByAppAndRequest(
                taskMapper.selectByTaskNo(r0.taskNo()).getAppId(), requestId);
        assertThat(itemMapper.selectByTask(byRequest.getId()))
                .as("item 不因并发受理翻倍").hasSize(4);

        // ② 同模板重放（模板 id > 127）：必须返回现状而不是误判"键被别的模板占用"
        GrantTaskCreateResult replay = grantTaskService.createTask(
                new GrantTaskCreateRequest(requestId, templateId, users, "tester"));
        assertThat(replay.taskNo()).isEqualTo(r0.taskNo());
        assertThat(replay.idempotent()).isTrue();

        // ③ 同键换模板：拒绝并指回原模板
        long other = createPublishedTemplate(30, 1);
        try {
            grantTaskService.createTask(
                    new GrantTaskCreateRequest(requestId, other, users, "tester"));
            fail("同 requestId 换模板必须被拒绝");
        } catch (BizException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        }
        drainUntilTerminal(r0.taskNo(), 60_000);
    }

    @Test
    @DisplayName("CAS 输家分支：对已终态 item 再记账，计数不动（并发双跑的守恒锚点）")
    void completedItemReplay_doesNotDoubleCount() throws Exception {
        long templateId = createPublishedTemplate(50, 1);
        List<Long> users = newUsers(2);
        GrantTaskCreateResult created = grantTaskService.createTask(
                new GrantTaskCreateRequest("gt-cas-" + templateId, templateId, users, "tester"));
        CouponGrantTaskPO task = drainUntilTerminal(created.taskNo(), 60_000);
        long successBefore = task.getSuccessCount();
        assertThat(successBefore).isEqualTo(2);

        // 模拟双跑输家：item 已 SUCCESS 后再次走成功记账路径（幂等键重放的账面路径）
        CouponGrantChunkPO chunk = chunkMapper.selectByTask(task.getId()).get(0);
        CouponGrantItemPO done0 = itemMapper.selectByTask(task.getId()).get(0);
        grantTaskTxService.completeItemSuccess(chunk, task.getId(), done0.getId(), 12345L);

        CouponGrantTaskPO after = taskMapper.selectByTaskNo(created.taskNo());
        assertThat(after.getSuccessCount()).as("CAS 输家不得二次计数").isEqualTo(successBefore);
        assertThat(after.getPendingCount()).isZero();
        assertConservation(after);
    }

    // ------------------------------------------------------------------
    // ③ 取消守恒
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("取消守恒：跑一个 chunk 后取消，终态 CANCELED、未执行 item=CANCELED、已发券不受影响")
    void cancelHalfway_conserved_couponsIntact() throws Exception {
        long templateId = createPublishedTemplate(100, 1);
        List<Long> users = newUsers(30);
        GrantTaskCreateResult created = grantTaskService.createTask(
                new GrantTaskCreateRequest("gt-cancel-" + templateId, templateId, users, "tester"));
        CouponGrantTaskPO task = taskMapper.selectByTaskNo(created.taskNo());

        // 只跑第一个 chunk：其余 chunk 保持 PENDING，取消语义可精确断言
        int processed = worker.runChunks(1);
        assertThat(processed).isEqualTo(1);
        CouponGrantChunkPO first = chunkMapper.selectByTaskAndChunk(task.getId(), 0);
        assertThat(first.getStatus()).isEqualTo(2);   // DONE
        List<CouponGrantItemPO> firstItems = itemMapper.selectByTask(task.getId()).stream()
                .filter(i -> i.getChunkNo() == 0).toList();

        GrantTaskCancelResult cancel = grantTaskService.cancel(created.taskNo());
        assertThat(cancel.accepted()).isTrue();
        assertThat(cancel.status()).isEqualTo(4);     // CANCELING

        CouponGrantTaskPO done = drainUntilTerminal(created.taskNo(), 60_000);
        assertThat(done.getStatus()).isEqualTo(5);    // CANCELED

        // 未执行 item 全部 CANCELED；第一个 chunk 的成果不受影响
        List<CouponGrantItemPO> all = itemMapper.selectByTask(task.getId());
        assertThat(all).hasSize(30);
        assertThat(all.stream().filter(i -> i.getChunkNo() == 0)
                .allMatch(i -> i.getStatus() == 1)).isTrue();
        assertThat(all.stream().filter(i -> i.getChunkNo() > 0)
                .allMatch(i -> i.getStatus() == 4)).isTrue();

        // 已发的券不受影响：第一个 chunk 的用户券还在，其余用户没有券
        for (CouponGrantItemPO item : firstItems) {
            assertThat(userCouponRepository.countByUserAndTemplate(item.getUserId(), templateId))
                    .isEqualTo(1);
        }
        for (CouponGrantItemPO item : all) {
            if (item.getChunkNo() > 0) {
                assertThat(userCouponRepository.countByUserAndTemplate(item.getUserId(), templateId))
                        .as("被取消的 item 不应有券").isZero();
            }
        }

        // 取消守恒：pending 清零，取消的 item 归入 skip；total 不变
        assertConservation(done);
        assertThat(done.getPendingCount()).isZero();
        assertThat(done.getSuccessCount()).isEqualTo(firstItems.size());
        assertThat(done.getSkipCount()).isEqualTo(30 - firstItems.size());

        // 终态后再取消：拒绝（accepted=false），状态保持 CANCELED
        GrantTaskCancelResult again = grantTaskService.cancel(created.taskNo());
        assertThat(again.accepted()).isFalse();
        assertThat(again.status()).isEqualTo(5);
    }

    // ------------------------------------------------------------------
    // ④ 崩溃恢复
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("崩溃恢复：租约过期的 RUNNING chunk 被重新领取续跑，item 幂等键保证不重复发放")
    void expiredLease_reclaimed_noDuplicateIssue() throws Exception {
        long templateId = createPublishedTemplate(20, 1);
        List<Long> users = newUsers(10);
        GrantTaskCreateResult created = grantTaskService.createTask(
                new GrantTaskCreateRequest("gt-crash-" + templateId, templateId, users, "tester"));
        CouponGrantTaskPO task = taskMapper.selectByTaskNo(created.taskNo());

        // 模拟"worker 领取 chunk 后对第一个 item 发券成功、还没记账就崩溃"：
        // 直接用领取事务拿走 chunk（ghost worker），再手工调用一次 receive（不写任何任务表）。
        CouponGrantChunkPO claimed = grantTaskTxService.claimChunk("ghost-worker-crash", Duration.ofMinutes(5));
        assertThat(claimed).as("库里不应有其它可领 chunk（前序用例都已到终态）").isNotNull();
        assertThat(claimed.getTaskId()).isEqualTo(task.getId());

        CouponGrantItemPO firstItem = itemMapper
                .selectPendingByChunk(task.getId(), claimed.getChunkNo()).get(0);
        CouponTemplate template = templateRepository.findById(templateId).orElseThrow();
        String idemKey = "grant-" + created.taskNo() + "-" + firstItem.getSeq();
        ReceiveResult beforeCrash = c1ReceiveAppService.receive(
                new ReceiveRequest(idemKey, firstItem.getUserId(), templateId), template);
        assertThat(beforeCrash.duplicated()).isFalse();

        // 崩溃：租约到期（手工把 lease_until 改到过去），chunk 卡在 RUNNING 带 PENDING item
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE coupon_grant_chunk SET lease_until = '2000-01-01 00:00:00.000' "
                             + "WHERE id = ?")) {
            ps.setLong(1, claimed.getId());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        // 下一轮：worker 重新领取续跑。第一个 item 的 receive 命中幂等键（duplicated），
        // 返回崩溃前那张券并记账一次——不重复发放。
        CouponGrantTaskPO done = drainUntilTerminal(created.taskNo(), 60_000);
        assertThat(done.getStatus()).isEqualTo(2);
        assertThat(done.getSuccessCount()).isEqualTo(10);
        assertThat(done.getPendingCount()).isZero();
        assertConservation(done);

        CouponGrantItemPO replayed = itemMapper.selectByTask(task.getId()).stream()
                .filter(i -> i.getId().equals(firstItem.getId())).findFirst().orElseThrow();
        assertThat(replayed.getStatus()).isEqualTo(1);
        assertThat(replayed.getUserCouponId()).isEqualTo(beforeCrash.couponId());
        assertThat(userCouponRepository.countByUserAndTemplate(firstItem.getUserId(), templateId))
                .as("崩溃重放后该用户必须仍然只有 1 张券").isEqualTo(1);
        assertThat(userCouponRepository.countByTemplate(templateId)).isEqualTo(10);
        assertThat(userCouponRepository.findByIdempotentKey(
                firstItem.getUserId(), template.getAppId(), idemKey)).isPresent();
    }

    // ------------------------------------------------------------------
    // ⑤ 越权与守卫（开放面 HTTP）
    // ------------------------------------------------------------------

    private static final String APP_A = "t-grant-a";
    private static final String APP_B = "t-grant-b";
    private static OpenApiTestClient client;
    private static String secretA;
    private static String secretB;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    private void provisionOnce() throws Exception {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        client = new OpenApiTestClient(port);
        secretA = appService.create(new OpenAppService.CreateAppRequest(APP_A, "甲应用", null, null, null)).secret();
        secretB = appService.create(new OpenAppService.CreateAppRequest(APP_B, "乙应用", null, null, null)).secret();
    }

    @Test
    @Timeout(120)
    @DisplayName("越权：乙应用用甲模板建任务/查任务 → 403+6011；甲建自己的任务 → 200")
    void crossAppGrant_forbidden() throws Exception {
        provisionOnce();
        // 甲：建模板并发布（全部走开放面）
        long tplA = readLong(client.post("/open/v1/templates", """
                {"name":"甲的批量券","activityId":1001,"couponType":1,"faceValue":1000,
                 "threshold":10000,"totalCount":100,"perUserLimit":1,
                 "validType":2,"validDays":7}""", APP_A, secretA, "gt-own-tpl-1").body(), "/data/id");
        client.post("/open/v1/templates/" + tplA + "/publish", "", APP_A, secretA, "gt-own-pub-1");

        // 乙用甲的模板建任务 → 403 + 6011
        OpenApiTestClient.Response asB = client.post("/open/v1/grant/tasks",
                "{\"requestId\":\"gt-own-b-1\",\"templateId\":" + tplA + ",\"userIds\":[940051,940052]}",
                APP_B, secretB, "gt-own-b-tok");
        assertThat(asB.status()).as(asB.body()).isEqualTo(403);
        assertThat(asB.body()).contains("6011");

        // 甲建自己的任务 → 200，拿到 taskNo（正例：防线不是把门焊死）
        OpenApiTestClient.Response asA = client.post("/open/v1/grant/tasks",
                "{\"requestId\":\"gt-own-a-1\",\"templateId\":" + tplA + ",\"userIds\":[940061]}",
                APP_A, secretA, "gt-own-a-tok");
        assertThat(asA.status()).as(asA.body()).isEqualTo(200);
        String taskNo = objectMapper.readTree(asA.body()).at("/data/taskNo").asText();
        assertThat(taskNo).startsWith("GT");

        // 乙查甲的任务 → 403 + 6011（任务归属也按 app 校验）
        OpenApiTestClient.Response statusAsB = client.get(
                "/open/v1/grant/tasks/" + taskNo, null, APP_B, secretB);
        assertThat(statusAsB.status()).as(statusAsB.body()).isEqualTo(403);
        assertThat(statusAsB.body()).contains("6011");

        // 收尾：取消并跑空，避免这个 PENDING 任务遗留可领 chunk 干扰前序用例的 claimChunk
        grantTaskService.cancel(taskNo);
        drainUntilTerminal(taskNo, 60_000);
    }

    // ------------------------------------------------------------------
    // ⑥ retry-failed：补库存后失败项重发（V6 阶段 1A 收尾）
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("retry-failed：售罄失败 2 项补库存后重发全部到齐；再次受理幂等（accepted=false）")
    void retryFailed_reissuesAfterRestock() throws Exception {
        // 库存 3 张、5 个用户：第一轮恰好 3 成 2 败（败因 3001 售罄，FAIL 路径从未发券）
        long templateId = createPublishedTemplate(3, 1);
        List<Long> users = newUsers(5);
        GrantTaskCreateResult created = grantTaskService.createTask(
                new GrantTaskCreateRequest("gt-retry-" + templateId, templateId, users, "tester"));

        CouponGrantTaskPO soldOut = drainUntilTerminal(created.taskNo(), 60_000);
        assertThat(soldOut.getStatus()).isEqualTo(2);
        assertThat(soldOut.getSuccessCount()).isEqualTo(3);
        assertThat(soldOut.getFailCount()).isEqualTo(2);
        assertConservation(soldOut);

        // 内部面直调补库存 3 → 10（expected=当前总量 3；售罄提示清了，重发才可能成功）
        adjustService.adjust(templateId, new StockAdjustRequest(
                "gt-retry-adj-" + templateId, 3L, 10L, "retry-failed 验证补库存", "tester"));

        GrantTaskRetryResult retry = grantTaskService.retryFailed(created.taskNo());
        assertThat(retry.accepted()).isTrue();
        assertThat(retry.retried()).isEqualTo(2);
        assertThat(retry.status()).isEqualTo(1);      // 任务已重开为 RUNNING

        // worker 下一轮自然领取重开 chunk：幂等键不变（grant-{taskNo}-{seq}），
        // 上轮失败未落幂等记录，这一轮对同键真实发券
        CouponGrantTaskPO done = drainUntilTerminal(created.taskNo(), 60_000);
        assertThat(done.getStatus()).isEqualTo(2);
        assertThat(done.getSuccessCount()).isEqualTo(5);
        assertThat(done.getFailCount()).isZero();
        assertThat(done.getPendingCount()).isZero();
        assertConservation(done);
        assertThat(userCouponRepository.countByTemplate(templateId))
                .as("3 张首发 + 2 张重发 = 5").isEqualTo(5);
        assertThat(usersOverLimit(templateId, users)).isZero();

        // 幂等：全部成功后再受理——没有 FAIL item，accepted=false 且不再发券
        GrantTaskRetryResult replay = grantTaskService.retryFailed(created.taskNo());
        assertThat(replay.accepted()).isFalse();
        assertThat(replay.retried()).isZero();
        assertThat(userCouponRepository.countByTemplate(templateId)).isEqualTo(5);
    }

    // ------------------------------------------------------------------
    // ⑦ failures CSV：BOM / 转义 / 公式注入防护（开放面 HTTP）
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("failures CSV：BOM 开头+表头正确；=公式注入前置单引号；逗号/引号 RFC4180 转义；0 失败只回 BOM+表头")
    void failuresCsv_bomEscapingAndInjectionGuard() throws Exception {
        provisionOnce();
        // 甲应用经开放面建模板+任务（failures 按任务归属校验 app，任务必须真的属于 APP_A）
        long tpl = readLong(client.post("/open/v1/templates", """
                {"name":"CSV导出验证券","activityId":1001,"couponType":1,"faceValue":1000,
                 "threshold":10000,"totalCount":10,"perUserLimit":1,
                 "validType":2,"validDays":7}""", APP_A, secretA, "gt-csv-tpl-1").body(), "/data/id");
        client.post("/open/v1/templates/" + tpl + "/publish", "", APP_A, secretA, "gt-csv-pub-1");
        List<Long> users = newUsers(2);
        OpenApiTestClient.Response created = client.post("/open/v1/grant/tasks",
                "{\"requestId\":\"gt-csv-task-1\",\"templateId\":" + tpl
                        + ",\"userIds\":[" + users.get(0) + "," + users.get(1) + "]}",
                APP_A, secretA, "gt-csv-task-tok");
        assertThat(created.status()).as(created.body()).isEqualTo(200);
        String taskNo = objectMapper.readTree(created.body()).at("/data/taskNo").asText();
        drainUntilTerminal(taskNo, 60_000);
        long taskId = taskMapper.selectByTaskNo(taskNo).getId();

        // 阶段一（0 失败）：响应恰为 BOM + 表头，无数据行、无结尾换行
        OpenApiTestClient.Response empty = client.get(
                "/open/v1/grant/tasks/" + taskNo + "/failures", null, APP_A, secretA);
        assertThat(empty.status()).as(empty.body()).isEqualTo(200);
        assertThat(empty.header("Content-Type")).contains("text/csv");
        assertThat(empty.header("Content-Disposition")).contains("attachment");
        assertThat(empty.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .as("CSV 必须以 UTF-8 BOM (EF BB BF) 开头").startsWith(
                        (byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(empty.body()).isEqualTo("\uFEFFuserId,seq,failCode,failMsg,attempt");

        // 阶段二：直改 DB 造失败明细。真实路径的 fail_msg 是"已售罄"这类固定文案，
        // 逗号/引号/公式载荷只能直改（这里验的是 CSV 转义器，不是失败路径本身）
        jdbcTpl.update(
                "UPDATE coupon_grant_item SET status = 2, fail_code = 3001, attempt = 1, "
                        + "fail_msg = ? WHERE task_id = ? AND seq = 0",
                "=SUM(A1)", taskId);
        jdbcTpl.update(
                "UPDATE coupon_grant_item SET status = 2, fail_code = 3001, attempt = 1, "
                        + "fail_msg = ? WHERE task_id = ? AND seq = 1",
                "补发失败,原因:\"库存\"不足", taskId);

        OpenApiTestClient.Response csv = client.get(
                "/open/v1/grant/tasks/" + taskNo + "/failures", null, APP_A, secretA);
        assertThat(csv.status()).as(csv.body()).isEqualTo(200);
        assertThat(csv.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String[] lines = csv.body().substring(1).split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("userId,seq,failCode,failMsg,attempt");
        // seq=0 行：注入载荷被前置单引号，Excel 打开是文本不是公式
        assertThat(lines[1]).isEqualTo(users.get(0) + ",0,3001,'=SUM(A1),1");
        // seq=1 行：含逗号/引号 → 整格引号包裹 + 内部引号翻倍（RFC 4180）
        assertThat(lines[2]).isEqualTo(
                users.get(1) + ",1,3001,\"补发失败,原因:\"\"库存\"\"不足\",1");
    }

    @Test
    @Timeout(120)
    @DisplayName("retry/failures 守卫：乙应用碰甲任务 → 403+6011；RUNNING/CANCELED 任务 retry → 4002")
    void retryAndFailures_ownershipAndStateGuards() throws Exception {
        provisionOnce();
        // 甲应用经开放面建模板+任务（归属校验要拦"乙碰甲"，任务得先属于甲）
        long tpl = readLong(client.post("/open/v1/templates", """
                {"name":"守卫验证券","activityId":1001,"couponType":1,"faceValue":1000,
                 "threshold":10000,"totalCount":20,"perUserLimit":1,
                 "validType":2,"validDays":7}""", APP_A, secretA, "gt-guard-tpl-1").body(), "/data/id");
        client.post("/open/v1/templates/" + tpl + "/publish", "", APP_A, secretA, "gt-guard-pub-1");
        List<Long> users = newUsers(3);
        OpenApiTestClient.Response created = client.post("/open/v1/grant/tasks",
                "{\"requestId\":\"gt-guard-task-1\",\"templateId\":" + tpl
                        + ",\"userIds\":[" + users.get(0) + "," + users.get(1) + "," + users.get(2) + "]}",
                APP_A, secretA, "gt-guard-task-tok");
        assertThat(created.status()).as(created.body()).isEqualTo(200);
        String taskNo = objectMapper.readTree(created.body()).at("/data/taskNo").asText();
        CouponGrantTaskPO task = taskMapper.selectByTaskNo(taskNo);

        // 越权：乙应用对甲任务调 retry-failed / failures → 403 + 6011
        OpenApiTestClient.Response retryAsB = client.post(
                "/open/v1/grant/tasks/" + taskNo + "/retry-failed", "", APP_B, secretB, "gt-guard-b-tok");
        assertThat(retryAsB.status()).as(retryAsB.body()).isEqualTo(403);
        assertThat(retryAsB.body()).contains("6011");
        OpenApiTestClient.Response csvAsB = client.get(
                "/open/v1/grant/tasks/" + taskNo + "/failures", null, APP_B, secretB);
        assertThat(csvAsB.status()).as(csvAsB.body()).isEqualTo(403);
        assertThat(csvAsB.body()).contains("6011");

        // RUNNING：用领取事务拿走一个 chunk（任务 PENDING→RUNNING），不执行不释放
        CouponGrantChunkPO claimed = grantTaskTxService.claimChunk("guard-runner", Duration.ofMinutes(5));
        assertThat(claimed).as("库里不应有其它可领 chunk（前序用例都已终态）").isNotNull();
        assertThat(claimed.getTaskId()).isEqualTo(task.getId());
        assertThat(statusOf(taskNo)).isEqualTo(1);
        OpenApiTestClient.Response retryRunning = client.post(
                "/open/v1/grant/tasks/" + taskNo + "/retry-failed", "", APP_A, secretA, "gt-guard-run-tok");
        // 4xxx 业务冲突走"HTTP 200 + 业务码"的全站既有约定（只有 6xxx 映射真实 HTTP 状态）
        assertThat(retryRunning.status()).as(retryRunning.body()).isEqualTo(200);
        assertThat(retryRunning.body()).contains("4002");
        assertThat(retryRunning.body()).as("消息带当前状态，调用方能区分'还在跑'与'已取消'")
                .contains("status=1");

        // 过期租约后取消跑空：否则 5 分钟内 worker 领不回该 chunk，任务到不了终态
        jdbcTpl.update(
                "UPDATE coupon_grant_chunk SET lease_until = '2000-01-01 00:00:00.000' WHERE id = ?",
                claimed.getId());
        grantTaskService.cancel(taskNo);
        CouponGrantTaskPO canceled = drainUntilTerminal(taskNo, 60_000);
        assertThat(canceled.getStatus()).isEqualTo(5);

        // CANCELED：retry 明确拒绝（4002）
        OpenApiTestClient.Response retryCanceled = client.post(
                "/open/v1/grant/tasks/" + taskNo + "/retry-failed", "", APP_A, secretA, "gt-guard-cxl-tok");
        assertThat(retryCanceled.status()).as(retryCanceled.body()).isEqualTo(200);
        assertThat(retryCanceled.body()).contains("4002");

        // failures 任意状态可查：CANCELED 任务也能导出（0 失败 = BOM+表头）
        OpenApiTestClient.Response csvCanceled = client.get(
                "/open/v1/grant/tasks/" + taskNo + "/failures", null, APP_A, secretA);
        assertThat(csvCanceled.status()).as(csvCanceled.body()).isEqualTo(200);
        assertThat(csvCanceled.body()).isEqualTo("\uFEFFuserId,seq,failCode,failMsg,attempt");
    }

    private long readLong(String json, String pointer) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json).at(pointer);
            assertThat(node.isMissingNode() || node.isNull())
                    .as("响应里找不到 " + pointer + "：" + json).isFalse();
            return node.asLong();
        } catch (Exception e) {
            throw new AssertionError("响应无法解析: " + json, e);
        }
    }

    // ------------------------------------------------------------------
    // ⑧ 定时发放（V6 阶段 1B）：到期门在 chunk 领取 SQL，不是任务状态机——
    //     未到期任务照常建出 PENDING chunk，只是领不走；到点自然开跑
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("定时门：未来任务 runOnce 跑了也不发（全 PENDING、券 0、状态响应带 scheduleTime）；改到过去后补跑发完")
    void scheduledTask_gatedUntilDue_thenCatchUp() throws Exception {
        long templateId = createPublishedTemplate(20, 1);
        List<Long> users = newUsers(3);
        // 截到秒：DATETIME(3) 存毫秒，纳秒往返比较会假失败；整秒值存储无损
        LocalDateTime scheduled = LocalDateTime.now()
                .plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        GrantTaskCreateResult created = grantTaskService.createTask(
                new GrantTaskCreateRequest("gt-sched-" + templateId, templateId,
                        users, "tester", scheduled));
        assertThat(created.idempotent()).isFalse();

        // schedule_time 原样落库；GET 状态响应带 scheduleTime（1B 契约）
        CouponGrantTaskPO task = taskMapper.selectByTaskNo(created.taskNo());
        assertThat(task.getScheduleTime()).isEqualTo(scheduled);
        assertThat(grantTaskService.getStatus(created.taskNo()).scheduleTime())
                .isEqualTo(scheduled);

        // 到期前：worker 全速跑一轮也不领这个任务的 chunk——item 全 PENDING、零发放
        int processed = worker.runOnce();
        assertThat(processed).as("库里不应有可领 chunk（未到期 + 前序用例都已终态）").isZero();
        assertThat(statusOf(created.taskNo())).as("未到期任务保持 PENDING").isEqualTo(0);
        assertThat(itemMapper.selectByTask(task.getId()))
                .as("未到期任务的 item 必须全部 PENDING")
                .allMatch(i -> i.getStatus() == CouponGrantItemPO.STATUS_PENDING);
        assertThat(userCouponRepository.countByTemplate(templateId))
                .as("未到期任务不得发出任何券").isZero();

        // schedule_time 改到过去（等价"过去时间受理=立即执行"的补跑语义）→ 下一轮发完
        jdbcTpl.update(
                "UPDATE coupon_grant_task SET schedule_time = '2000-01-01 00:00:00.000' WHERE id = ?",
                task.getId());
        CouponGrantTaskPO done = drainUntilTerminal(created.taskNo(), 60_000);
        assertThat(done.getStatus()).isEqualTo(2);
        assertThat(done.getSuccessCount()).isEqualTo(3);
        assertThat(done.getPendingCount()).isZero();
        assertConservation(done);
        assertThat(userCouponRepository.countByTemplate(templateId)).isEqualTo(3);
    }

    @Test
    @Timeout(120)
    @DisplayName("定时任务未到期也可取消：取消的 chunk 立即可领做取消收尾，不必等到定时点")
    void scheduledTask_cancelBeforeDue_immediatelyCanceled() throws Exception {
        long templateId = createPublishedTemplate(20, 1);
        List<Long> users = newUsers(3);
        GrantTaskCreateResult created = grantTaskService.createTask(
                new GrantTaskCreateRequest("gt-sched-cxl-" + templateId, templateId, users,
                        "tester", LocalDateTime.now()
                        .plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));

        GrantTaskCancelResult cancel = grantTaskService.cancel(created.taskNo());
        assertThat(cancel.accepted()).isTrue();

        // 到期门对 CANCELING 任务有豁免（领取 SQL 的 OR t.status=4）：被领的 chunk 只做
        // 取消收尾不发放。没有这条豁免本用例会卡在 CANCELING 直到 30 秒后定时点
        CouponGrantTaskPO done = drainUntilTerminal(created.taskNo(), 10_000);
        assertThat(done.getStatus()).isEqualTo(5);
        assertThat(done.getSuccessCount()).as("未到期任务取消不得发券").isZero();
        assertThat(done.getSkipCount()).isEqualTo(3);
        assertThat(userCouponRepository.countByTemplate(templateId)).isZero();
        assertConservation(done);
    }
}
