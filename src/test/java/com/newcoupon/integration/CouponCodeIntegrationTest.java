package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.newcoupon.application.dto.CouponCodeBatchCreateRequest;
import com.newcoupon.application.dto.CouponCodeBatchCreateResult;
import com.newcoupon.application.dto.CouponCodeRedeemRequest;
import com.newcoupon.application.dto.CouponCodeRedeemResult;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.StockAdjustRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.C1ReceiveAppService;
import com.newcoupon.application.service.CouponCodeService;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.StockShardAdjustService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponCodeBatchMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponCodeMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponCodeRedeemLogMapper;
import com.newcoupon.infrastructure.persistence.po.CouponCodeBatchPO;
import com.newcoupon.infrastructure.persistence.po.CouponCodePO;
import com.newcoupon.infrastructure.persistence.po.CouponCodeRedeemLogPO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 兑换码域（V6 阶段 1B）行为契约：明文一次性、DB 无明文、兑换复用 C1 不超发、
 * 防枚举统一文案、崩窗自愈、越权口径。
 *
 * <p>用例对照任务书 a~g（g 的定时两例按交付一的要求放在 GrantTaskIntegrationTest）：
 * <ol>
 *   <li>a 建批→明文一次性（响应一次 + CSV 一次）+ DB 无明文；</li>
 *   <li>b 兑换成功/同人同 requestId 重放回原券/他人统一失败/售罄回池后补库存可再兑；</li>
 *   <li>c 并发兑同一 ACTIVE 码恰 1 成功（200 并发 × 5 轮 = 1000 次——
 *       WSL ~6.6G 内存不开 1000 线程，按任务书允许的降级口径注明）；</li>
 *   <li>d 防枚举：错码 20 次与限流第 11 次同一句文案（不暴露限流）；</li>
 *   <li>e 崩窗自愈：LOCKED 残留被回收后同人重兑幂等回原券（码不结算、保持 ACTIVE——审查修正：幂等命中无法归因本码，不烧码更守恒）；</li>
 *   <li>f 越权：乙用甲模板建批 403+6011；乙兑甲的码按"不存在"处理（3007，非 6011）。</li>
 * </ol>
 *
 * <p>并发用例直调服务层（签名/HTTP 一千次太慢），HTTP 面由 a/f 覆盖；
 * 开放面应用照 GrantTaskIntegrationTest 的 provisionOnce 模式。
 */
@Timeout(300)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.stock.shard-count=8",
                // 本类不需要 grant worker（没有批量任务要跑）；保持默认关，
                // 也避免后台轮次在并发用例中间触碰共享库
                "coupon.grant.worker.enabled=false",
                "coupon.circuit-breaker.min-calls=10000",
                "coupon.circuit-breaker.window-size=10000",
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class CouponCodeIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    /**
     * 用户号段：全量套件共享一个库，段必须错开——300/500/700/810/900/910/940 都已被占，
     * 本类用 950_000。
     */
    private static final AtomicInteger USER = new AtomicInteger(950_000);

    /** 测试侧独立复制的 Crockford 字母表：不 import 生产类，校验才算独立复核。 */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    @Autowired
    private CouponCodeService codeService;
    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private C1ReceiveAppService c1ReceiveAppService;
    @Autowired
    private StockShardAdjustService adjustService;
    @Autowired
    private UserCouponRepository userCouponRepository;
    @Autowired
    private CouponCodeBatchMapper batchMapper;
    @Autowired
    private CouponCodeMapper codeMapper;
    @Autowired
    private CouponCodeRedeemLogMapper logMapper;
    @Autowired
    private OpenAppService appService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTpl;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${local.server.port}")
    private int port;

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private long createPublishedTemplate(long total, int perUserLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "兑换码验证券", 1001L, 1, 1000L, null, 10000L, total, perUserLimit,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, 0L, 0));
        templateAppService.publish(created.id());
        return created.id();
    }

    private static List<Long> newUsers(int n) {
        return IntStream.range(0, n).mapToObj(i -> (long) USER.incrementAndGet()).toList();
    }

    /** 内部面建批（快；归属=内置应用，与内部面建模板一致）。 */
    private CouponCodeBatchCreateResult createBatchInternal(String requestId, long templateId,
                                                            int count, int codeType) {
        return codeService.createBatch(new CouponCodeBatchCreateRequest(
                requestId, templateId, count, codeType, "tester"));
    }

    /** 独立复核手工码校验符：前 15 位**位置加权**和 mod 32 应等于第 16 字符（与生产同款，测试侧实现）。 */
    private static boolean checksumMatches(String code) {
        int sum = 0;
        for (int i = 0; i < 15; i++) {
            int idx = ALPHABET.indexOf(code.charAt(i));
            if (idx < 0) {
                return false;
            }
            sum += idx * (i + 1);
        }
        return code.charAt(15) == ALPHABET.charAt(Math.floorMod(sum, 32));
    }

    /** 造一个形态合法但（几乎必然）不存在的 16 字符手工码——探测用。 */
    private static String nonexistentCode(int seed) {
        StringBuilder sb = new StringBuilder(16);
        int sum = 0;
        for (int i = 0; i < 15; i++) {
            int idx = Math.floorMod(seed * 31 + i * 7, 32);
            sb.append(ALPHABET.charAt(idx));
            sum += idx;
        }
        return sb.append(ALPHABET.charAt(sum % 32)).toString();
    }

    /**
     * 按生成序定位明文对应的码行：明文列表与 coupon_code 的 id 升序一一对应
     * （生成顺序=插入顺序=自增序；导出 CSV 用同一约定拼行）。测试不重算哈希——
     * 哈希正确性由"兑换能命中"本身证明，这里只看点查状态。
     */
    private CouponCodePO codeRowOf(CouponCodeBatchCreateResult batch, String plaintext) {
        int index = batch.codes().indexOf(plaintext);
        assertThat(index).as("明文必须来自该批次: " + plaintext).isGreaterThanOrEqualTo(0);
        List<CouponCodePO> rows = codeMapper.selectByBatch(
                batchMapper.selectByBatchNo(batch.batchNo()).getId());
        assertThat(rows).hasSize(batch.codes().size());
        return rows.get(index);
    }

    // ------------------------------------------------------------------
    // a. 建批：明文一次性 + DB 无明文 + 导出一次（开放面 HTTP）
    // ------------------------------------------------------------------

    private static final String APP_A = "t-code-a";
    private static final String APP_B = "t-code-b";
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
    @DisplayName("建批 a：明文只在响应出现一次（重放不回）；DB 只见 64 位 hex 哈希；CSV 导出一次后 4002")
    void createBatch_plaintextOnce_dbHashOnly_exportOnce() throws Exception {
        provisionOnce();
        long tpl = readLong(client.post("/open/v1/templates", """
                {"name":"甲的兑换码券","activityId":1001,"couponType":1,"faceValue":1000,
                 "threshold":10000,"totalCount":50,"perUserLimit":1,
                 "validType":2,"validDays":7}""", APP_A, secretA, "cc-a-tpl-1").body(), "/data/id");
        client.post("/open/v1/templates/" + tpl + "/publish", "", APP_A, secretA, "cc-a-pub-1");

        OpenApiTestClient.Response created = client.post("/open/v1/codes/batches",
                "{\"requestId\":\"cc-a-batch-1\",\"templateId\":" + tpl
                        + ",\"count\":10,\"codeType\":1,\"operator\":\"tester\"}",
                APP_A, secretA, "cc-a-tok-1");
        assertThat(created.status()).as(created.body()).isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode data =
                objectMapper.readTree(created.body()).at("/data");
        String batchNo = data.at("/batchNo").asText();
        assertThat(batchNo).startsWith("CB");
        assertThat(data.at("/total").asInt()).isEqualTo(10);
        List<String> codes = new ArrayList<>();
        data.at("/codes").forEach(n -> codes.add(n.asText()));
        assertThat(codes).hasSize(10);

        // 明文形态：16 字符、全 Crockford 字母表、批内唯一、校验符独立复核吻合
        for (String code : codes) {
            assertThat(code).hasSize(16);
            for (char c : code.toCharArray()) {
                assertThat(ALPHABET.indexOf(c)).as("字符 %s 必须在字母表内", c).isGreaterThanOrEqualTo(0);
            }
            assertThat(checksumMatches(code)).as("校验符必须吻合: %s", code).isTrue();
        }
        assertThat(new HashSet<>(codes)).hasSize(10);

        // DB 无明文：coupon_code 只有 64 位 hex 哈希，且与任何明文不同
        Long batchId = batchMapper.selectByBatchNo(batchNo).getId();
        List<String> hashes = jdbcTpl.queryForList(
                "SELECT code_hash FROM coupon_code WHERE batch_id = ?", String.class, batchId);
        assertThat(hashes).hasSize(10);
        assertThat(new HashSet<>(hashes)).hasSize(10);
        for (String hash : hashes) {
            assertThat(hash).matches(HEX64);
        }
        assertThat(hashes).as("库里的哈希不得等于任何明文").noneMatch(codes::contains);

        // 二维码类型：26 字符、无校验符（快检，内部面）
        CouponCodeBatchCreateResult qr = createBatchInternal(
                "cc-a-qr-" + tpl, tpl, 5, 2);
        assertThat(qr.codes()).hasSize(5);
        for (String code : qr.codes()) {
            assertThat(code).hasSize(26);
        }

        // 首次 CSV 导出：BOM + 表头 + 10 行，code 列与建批响应的明文集合一致（全部未用：user 空、status 0）
        OpenApiTestClient.Response csv1 = client.post(
                "/open/v1/codes/batches/" + batchNo + "/codes", "", APP_A, secretA, "cc-export-tok-1");
        assertThat(csv1.status()).as(csv1.body()).isEqualTo(200);
        assertThat(csv1.header("Content-Type")).contains("text/csv");
        assertThat(csv1.header("Content-Disposition")).contains("attachment");
        assertThat(csv1.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String[] lines = csv1.body().substring(1).split("\r\n");
        assertThat(lines).hasSize(11);
        assertThat(lines[0]).isEqualTo("code,user,status,redeemTime");
        Set<String> csvCodes = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",", -1);
            assertThat(cells).as("未用码行=code,空user,0,空redeemTime: " + lines[i]).hasSize(4);
            csvCodes.add(cells[0]);
            assertThat(cells[1]).isEmpty();
            assertThat(cells[2]).isEqualTo("0");
            assertThat(cells[3]).isEmpty();
        }
        assertThat(csvCodes).isEqualTo(new HashSet<>(codes));

        // 二次导出 → 4002（4xxx 走 HTTP 200 + 业务码的全站约定）
        OpenApiTestClient.Response csv2 = client.post(
                "/open/v1/codes/batches/" + batchNo + "/codes", "", APP_A, secretA, "cc-export-tok-2");
        assertThat(csv2.status()).as(csv2.body()).isEqualTo(200);
        assertThat(csv2.body()).contains("4002");
        assertThat(csv2.body()).contains("一次");

        // 幂等重放：返回现状但**不回明文**（codes 为空）
        OpenApiTestClient.Response replay = client.post("/open/v1/codes/batches",
                "{\"requestId\":\"cc-a-batch-1\",\"templateId\":" + tpl
                        + ",\"count\":10,\"codeType\":1}",
                APP_A, secretA, "cc-a-tok-2");
        assertThat(replay.status()).as(replay.body()).isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode replayData =
                objectMapper.readTree(replay.body()).at("/data");
        assertThat(replayData.at("/batchNo").asText()).isEqualTo(batchNo);
        assertThat(replayData.at("/idempotent").asBoolean()).isTrue();
        assertThat(replayData.at("/codes").size()).as("幂等重放不得再次返回明文").isZero();
    }

    // ------------------------------------------------------------------
    // b. 兑换：成功/同人重放回原券/他人统一失败/售罄回池再兑
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("兑换 b：成功置 USED 记流水；同码同人同 requestId 重放回原券不双发；他人统一文案")
    void redeemSuccess_replayReturnsOriginalCoupon_othersUnified() {
        long templateId = createPublishedTemplate(20, 2);
        CouponCodeBatchCreateResult batch = createBatchInternal(
                "cc-b-batch-" + templateId, templateId, 3, 1);
        long userA = USER.incrementAndGet();
        long userB = USER.incrementAndGet();
        String code0 = batch.codes().get(0);

        // 成功：券发出 + 码 USED + 兑换人/时间回填 + 成功流水
        CouponCodeRedeemResult first = codeService.redeem(
                new CouponCodeRedeemRequest("cc-b-r1", code0, userA));
        assertThat(first.duplicated()).isFalse();
        assertThat(first.couponId()).isPositive();
        assertThat(first.batchNo()).isEqualTo(batch.batchNo());
        CouponCodePO row = codeRowOf(batch, code0);
        assertThat(row.getStatus()).isEqualTo(CouponCodePO.STATUS_USED);
        assertThat(row.getUserId()).isEqualTo(userA);
        assertThat(row.getRedeemTime()).isNotNull();
        List<CouponCodeRedeemLogPO> logs = logMapper.selectByBatch(
                batchMapper.selectByBatchNo(batch.batchNo()).getId());
        assertThat(logs.stream().filter(l -> l.getResult() == 1)).hasSize(1);
        assertThat(userCouponRepository.countByUserAndTemplate(userA, templateId)).isEqualTo(1);

        // 同码同人同 requestId 重放（响应丢失后的合法重试）：回原券 duplicated=true，不双发不加流水
        CouponCodeRedeemResult replay = codeService.redeem(
                new CouponCodeRedeemRequest("cc-b-r1", code0, userA));
        assertThat(replay.duplicated()).isTrue();
        assertThat(replay.couponId()).isEqualTo(first.couponId());
        assertThat(userCouponRepository.countByUserAndTemplate(userA, templateId))
                .as("重放不得双发").isEqualTo(1);
        assertThat(logMapper.selectByBatch(batchMapper.selectByBatchNo(batch.batchNo()).getId())
                .stream().filter(l -> l.getResult() == 1)).hasSize(1);

        // 同码他人：统一失败文案，无券
        try {
            codeService.redeem(new CouponCodeRedeemRequest("cc-b-other", code0, userB));
            fail("他人兑已用码必须失败");
        } catch (BizException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CODE_INVALID);
            assertThat(e.getMessage()).isEqualTo("兑换失败：码无效或已使用");
        }
        assertThat(userCouponRepository.countByUserAndTemplate(userB, templateId)).isZero();

        // 规范化：小写 + 易混字符（o→0、i→1、l→1）输入仍能兑同一个码
        String code1 = batch.codes().get(1);
        String mangled = code1.toLowerCase().replace('0', 'o')
                .replace('1', 'l');
        CouponCodeRedeemResult viaMangled = codeService.redeem(
                new CouponCodeRedeemRequest("cc-b-r2", mangled, userA));
        assertThat(viaMangled.duplicated()).isFalse();
        assertThat(codeRowOf(batch, code1).getStatus()).isEqualTo(CouponCodePO.STATUS_USED);
    }

    @Test
    @Timeout(120)
    @DisplayName("售罄回池：receive 抛售罄后码回 ACTIVE 记失败流水；补库存后同 requestId 重兑成功")
    void soldOutReactivatesCode_thenRestockRedeems() {
        long templateId = createPublishedTemplate(1, 1);
        CouponCodeBatchCreateResult batch = createBatchInternal(
                "cc-b2-batch-" + templateId, templateId, 2, 1);
        long userC = USER.incrementAndGet();
        long userD = USER.incrementAndGet();
        String code0 = batch.codes().get(0);
        String code1 = batch.codes().get(1);

        // 第一张券把库存（=1）耗光
        assertThat(codeService.redeem(
                new CouponCodeRedeemRequest("cc-b2-r1", code0, userC)).duplicated()).isFalse();

        // 第二码兑换：receive 抛 3001 → 码必须回 ACTIVE、记失败流水、原样抛售罄
        try {
            codeService.redeem(new CouponCodeRedeemRequest("cc-b2-r2", code1, userD));
            fail("售罄时兑换必须失败");
        } catch (BizException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_OUT);
        }
        CouponCodePO row = codeRowOf(batch, code1);
        assertThat(row.getStatus()).as("售罄后码必须回 ACTIVE").isEqualTo(CouponCodePO.STATUS_ACTIVE);
        assertThat(row.getUserId()).isNull();
        List<CouponCodeRedeemLogPO> logs = logMapper.selectByBatch(
                batchMapper.selectByBatchNo(batch.batchNo()).getId());
        assertThat(logs.stream().filter(l -> l.getResult() == 2
                && l.getFailReason() != null && l.getFailReason().contains("3001")))
                .as("失败流水须记真实原因（含 3001），对外才统一文案").hasSize(1);

        // 补库存后同 requestId 重兑：上次失败没落幂等券，这次真实发券
        adjustService.adjust(templateId, new StockAdjustRequest(
                "cc-b2-adj-" + templateId, 1L, 5L, "兑换码售罄回池后补库存", "tester"));
        CouponCodeRedeemResult retried = codeService.redeem(
                new CouponCodeRedeemRequest("cc-b2-r2", code1, userD));
        assertThat(retried.duplicated()).isFalse();
        assertThat(codeRowOf(batch, code1).getStatus()).isEqualTo(CouponCodePO.STATUS_USED);
        assertThat(userCouponRepository.countByUserAndTemplate(userD, templateId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // c. 并发：200 并发 × 5 轮兑同一 ACTIVE 码 → 恰 1 成功、999 统一文案
    // ------------------------------------------------------------------

    @Test
    @Timeout(180)
    @DisplayName("并发 c：200 并发×5 轮（共 1000 次）兑同一码，恰 1 成功、999 统一文案、used=1 不超发")
    void concurrentRedeem_exactlyOneWinner() throws Exception {
        // 任务书口径 1000 并发；WSL ~6.6G 内存不开 1000 线程，按允许的降级口径执行
        // 200 并发 × 5 轮（后 4 轮打的是 USED 码的统一失败路径，第 1 轮 200 打 CAS 竞争路径）
        final int concurrency = 200;
        final int rounds = 5;
        long templateId = createPublishedTemplate(10, 1);
        CouponCodeBatchCreateResult batch = createBatchInternal(
                "cc-c-batch-" + templateId, templateId, 1, 1);
        String code = batch.codes().get(0);
        List<Long> users = newUsers(concurrency);

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            int successes = 0;
            Set<String> failureMessages = new HashSet<>();
            for (int round = 0; round < rounds; round++) {
                CountDownLatch go = new CountDownLatch(1);
                List<Callable<String>> tasks = new ArrayList<>();
                for (int i = 0; i < concurrency; i++) {
                    final int idx = i;
                    final String requestId = "cc-c-r" + round + "-" + idx;
                    tasks.add(() -> {
                        go.await();
                        try {
                            codeService.redeem(new CouponCodeRedeemRequest(
                                    requestId, code, users.get(idx)));
                            return "OK";
                        } catch (BizException e) {
                            return e.getErrorCode().getCode() + "|" + e.getMessage();
                        }
                    });
                }
                List<Future<String>> futures = new ArrayList<>();
                for (Callable<String> task : tasks) {
                    futures.add(pool.submit(task));
                }
                go.countDown();
                for (Future<String> future : futures) {
                    String outcome = future.get(60, TimeUnit.SECONDS);
                    if ("OK".equals(outcome)) {
                        successes++;
                    } else {
                        failureMessages.add(outcome);
                    }
                }
            }
            assertThat(successes).as("1000 次并发兑换恰 1 次成功").isEqualTo(1);
            assertThat(failureMessages).as("999 次失败必须同码同文案")
                    .containsExactly(ErrorCode.CODE_INVALID.getCode() + "|兑换失败：码无效或已使用");

            CouponCodePO row = codeRowOf(batch, code);
            assertThat(row.getStatus()).isEqualTo(CouponCodePO.STATUS_USED);
            CouponCodeBatchPO batchRow = batchMapper.selectByBatchNo(batch.batchNo());
            assertThat(batchRow.getUsed()).as("批次 used 计数恰 1").isEqualTo(1);
            assertThat(userCouponRepository.countByTemplate(templateId))
                    .as("并发兑换不超发：恰 1 张券").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // d. 防枚举：错码 20 次 + 限流第 11 次 → 同一句文案
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("防枚举 d：20 个不存在的合法形态码逐次同一文案；同用户第 11 次/分被限流仍同文案（不暴露限流）")
    void enumerationAndRateLimit_sameUnifiedCopy() {
        long templateId = createPublishedTemplate(10, 1);
        createBatchInternal("cc-d-batch-" + templateId, templateId, 1, 1);
        long prober = USER.incrementAndGet();
        Set<String> messages = new HashSet<>();

        // 20 个形态合法但不存在的码：每次都是同一句"码无效或已使用"
        for (int i = 0; i < 20; i++) {
            try {
                codeService.redeem(new CouponCodeRedeemRequest(
                        "cc-d-" + i, nonexistentCode(i + 1), prober));
                fail("不存在的码必须失败");
            } catch (BizException e) {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CODE_INVALID);
                messages.add(e.getMessage());
            }
        }
        assertThat(messages).as("探测失败必须同文案").containsExactly("兑换失败：码无效或已使用");

        // 限流：新用户 10 次内放行（用垃圾码），第 11 次被拦——文案不变（不提示限流）
        long limited = USER.incrementAndGet();
        Set<String> limitedMessages = new HashSet<>();
        for (int i = 0; i < 11; i++) {
            try {
                codeService.redeem(new CouponCodeRedeemRequest(
                        "cc-d-l" + i, "ZZ-NO-SUCH-" + i, limited));
                fail("垃圾码必须失败");
            } catch (BizException e) {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CODE_INVALID);
                limitedMessages.add(e.getMessage());
            }
        }
        assertThat(limitedMessages)
                .as("限流拦截与其它失败同文案——多一种文案=多一个探测信号")
                .containsExactly("兑换失败：码无效或已使用");
        // 真实原因只在流水里：该用户 11 条失败流水，最后一条是 rate_limited
        List<CouponCodeRedeemLogPO> logs = jdbcTpl.query(
                "SELECT * FROM coupon_code_redeem_log WHERE user_id = ? ORDER BY id",
                (rs, n) -> {
                    CouponCodeRedeemLogPO row = new CouponCodeRedeemLogPO();
                    row.setFailReason(rs.getString("fail_reason"));
                    return row;
                }, limited);
        assertThat(logs).hasSize(11);
        assertThat(logs.get(10).getFailReason()).isEqualTo("rate_limited");
    }

    // ------------------------------------------------------------------
    // e. 崩窗自愈：LOCKED 残留回收 → 同人重兑幂等回原券、码归 USED
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("崩窗 e：券已发码未置 USED（LOCKED 残留）→ 兜底回收回 ACTIVE → 同人重兑幂等回原券自愈")
    void crashWindow_reapThenReplaySelfHeals() {
        long templateId = createPublishedTemplate(5, 1);
        CouponCodeBatchCreateResult batch = createBatchInternal(
                "cc-e-batch-" + templateId, templateId, 1, 1);
        long userA = USER.incrementAndGet();
        String code = batch.codes().get(0);
        long codeId = codeRowOf(batch, code).getId();
        CouponTemplate template = templateRepository.findById(templateId).orElseThrow();

        // 手工造崩窗：CAS 抢占成功 + receive 已发券，然后"进程崩溃"（不结算）。
        // 码卡 LOCKED、券已在用户名下——真实事故里这就是 kill -9 的现场
        assertThat(codeMapper.casLock(codeId)).isEqualTo(1);
        ReceiveResult issued = c1ReceiveAppService.receive(
                new ReceiveRequest("redeem-cc-e1", userA, templateId), template);
        assertThat(issued.duplicated()).isFalse();

        // 残留超过回收阈值（update_time 推到 6 分钟前，DB 时钟）→ 兜底回收回 ACTIVE
        jdbcTpl.update(
                "UPDATE coupon_code SET update_time = DATE_SUB(NOW(3), INTERVAL 6 MINUTE) WHERE id = ?",
                codeId);
        int reaped = codeService.reapStaleLockedCodes();
        assertThat(reaped).as("至少回收本用例的残留码").isGreaterThanOrEqualTo(1);
        assertThat(codeRowOf(batch, code).getStatus())
                .isEqualTo(CouponCodePO.STATUS_ACTIVE);

        // 同人同 requestId 重兑：CAS ACTIVE→LOCKED→receive 幂等回原券——**不结算本码**
        // （审查修正语义：幂等命中的券无法证明是本码新发的——跨码复用会烧码。
        // 返回原券（幂等承诺成立）+ 码回 ACTIVE：没发过券的码不该是 USED，
        // used 计数与实际发券数保持一致，比"补记 USED"更守恒）
        CouponCodeRedeemResult healed = codeService.redeem(
                new CouponCodeRedeemRequest("cc-e1", code, userA));
        assertThat(healed.duplicated()).as("幂等命中=返回崩溃前那张券").isTrue();
        assertThat(healed.couponId()).isEqualTo(issued.couponId());
        CouponCodePO row = codeRowOf(batch, code);
        assertThat(row.getStatus()).as("未结算的码保持 ACTIVE（不烧码）")
                .isEqualTo(CouponCodePO.STATUS_ACTIVE);
        assertThat(codeRowOf(batch, code).getRedeemTime()).isNull();
        assertThat(userCouponRepository.countByUserAndTemplate(userA, templateId))
                .as("整场事故用户始终只有 1 张券").isEqualTo(1);
        assertThat(userCouponRepository.countByTemplate(templateId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // f. 越权：乙用甲模板建批 403；乙兑甲的码按"不存在"处理
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("越权 f：乙用甲模板建批 403+6011；乙兑甲的码 → 3007 统一文案（6011 会承认码存在）；甲自兑成功")
    void crossAppBatch_forbidden_crossAppRedeemUnified() throws Exception {
        provisionOnce();
        long tplA = readLong(client.post("/open/v1/templates", """
                {"name":"甲的越权验证券","activityId":1001,"couponType":1,"faceValue":1000,
                 "threshold":10000,"totalCount":20,"perUserLimit":1,
                 "validType":2,"validDays":7}""", APP_A, secretA, "cc-f-tpl-1").body(), "/data/id");
        client.post("/open/v1/templates/" + tplA + "/publish", "", APP_A, secretA, "cc-f-pub-1");

        // 乙用甲模板建批 → 403 + 6011（模板归属在服务层判）
        OpenApiTestClient.Response asB = client.post("/open/v1/codes/batches",
                "{\"requestId\":\"cc-f-b-1\",\"templateId\":" + tplA
                        + ",\"count\":5,\"codeType\":1}",
                APP_B, secretB, "cc-f-b-tok");
        assertThat(asB.status()).as(asB.body()).isEqualTo(403);
        assertThat(asB.body()).contains("6011");

        // 甲建批（正例），拿到明文
        OpenApiTestClient.Response asA = client.post("/open/v1/codes/batches",
                "{\"requestId\":\"cc-f-a-1\",\"templateId\":" + tplA
                        + ",\"count\":2,\"codeType\":1}",
                APP_A, secretA, "cc-f-a-tok");
        assertThat(asA.status()).as(asA.body()).isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode data =
                objectMapper.readTree(asA.body()).at("/data");
        List<String> codes = new ArrayList<>();
        data.at("/codes").forEach(n -> codes.add(n.asText()));
        assertThat(codes).hasSize(2);

        // 乙兑甲的码：按"不存在"处理——回 6011 等于承认这个码存在，防枚举优先
        long userOfB = USER.incrementAndGet();
        OpenApiTestClient.Response redeemAsB = client.post("/open/v1/codes/redeem",
                "{\"requestId\":\"cc-f-b-r1\",\"code\":\"" + codes.get(0)
                        + "\",\"userId\":" + userOfB + "}",
                APP_B, secretB, "cc-f-b-r-tok");
        assertThat(redeemAsB.status()).as(redeemAsB.body()).isEqualTo(200);
        assertThat(redeemAsB.body()).contains("3007");
        assertThat(redeemAsB.body()).as("不得回 6011 暴露码存在").doesNotContain("6011");

        // 甲自己兑换（HTTP 正例）：券发出
        long userOfA = USER.incrementAndGet();
        OpenApiTestClient.Response redeemAsA = client.post("/open/v1/codes/redeem",
                "{\"requestId\":\"cc-f-a-r1\",\"code\":\"" + codes.get(1)
                        + "\",\"userId\":" + userOfA + "}",
                APP_A, secretA, "cc-f-a-r-tok");
        assertThat(redeemAsA.status()).as(redeemAsA.body()).isEqualTo(200);
        assertThat(objectMapper.readTree(redeemAsA.body()).at("/code").asInt()).isZero();
        assertThat(objectMapper.readTree(redeemAsA.body())
                .at("/data/couponId").asLong()).isPositive();
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
}
