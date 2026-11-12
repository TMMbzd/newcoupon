package com.newcoupon.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控制台浏览器 E2E（V6 生产化模拟刀 C1）：把 runbook《控制台最小页.md》§6 的<b>人工五步
 * 冒烟</b>自动化——此前 §5 裁剪登记里"浏览器交互逻辑未经真实浏览器验证"的残余风险由本类关闭。
 *
 * <p><b>架构（为什么不是在 JVM 里嵌 Playwright 客户端库）</b>：
 * <ol>
 *   <li>本类用 {@code RANDOM_PORT} 起真实应用（Testcontainers MySQL/Redis，复用共享基座），
 *       造好种子管理员的登录凭据与一张<b>预置模板</b>；</li>
 *   <li>再用 {@link ProcessBuilder} 拉起 console/ 下的 Playwright（Node 独立进程，
 *       chromium 真实浏览器），被测地址与凭据经环境变量注入；</li>
 *   <li>断言 Playwright 进程<b>退出码 0</b>（五个用例全绿）——用例本体在
 *       {@code console/e2e/console.spec.ts}，与服务端语义的对照表见该文件头注释。</li>
 * </ol>
 * 浏览器进程与 JVM 隔离：Node 侧崩溃/超时不拖垮测试 JVM，退出码与 stdout/stderr
 * 原样带回断言消息。
 *
 * <p><b>运行纪律</b>：@Tag("e2e") + surefire excludedGroups 默认排除（E2E 重：上下文
 * + Testcontainers + chromium，全量与 CI 都不含它）。手动触发与前置（装 chromium）见
 * docs/runbook/Tracing与E2E.md §E2E。
 */
@Tag("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 五步里多次登录全来自 127.0.0.1（错密码一次 + 正确登录四次），阈值放宽留给登录节流专测
        "coupon.security.login.ip-limit=1000"
})
class ConsoleE2eIntegrationTest extends ShardedIntegrationTestSupport {

    private static final String SEED_ADMIN_PASSWORD = "dev-seed-admin-123";   // application-dev.yml dev 默认
    private static final ObjectMapper JSON = new ObjectMapper();

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Value("${local.server.port:0}")
    private int serverPort;

    @Autowired
    private TestRestTemplate rest;

    /** 走真实链路建一张预置模板（旧令牌 dev-admin-token，dev profile 便利值），返回模板名。 */
    private String createTemplateViaApi() throws Exception {
        String name = "E2E预置模板-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", "dev-admin-token");
        String body = "{\"name\":\"" + name + "\",\"activityId\":1001,\"couponType\":1,"
                + "\"faceValue\":100,\"threshold\":1000,\"totalCount\":10,\"perUserLimit\":1,"
                + "\"validType\":2,\"validDays\":7}";
        org.springframework.http.ResponseEntity<String> resp = rest.exchange(
                "/api/templates", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().value())
                .as("建预置模板应成功。body=" + resp.getBody()).isEqualTo(200);
        JsonNode data = JSON.readTree(resp.getBody()).path("data");
        assertThat(data.path("id").asLong()).isGreaterThan(0);
        return name;
    }

    @Test
    @DisplayName("五步冒烟自动化：Playwright 真浏览器跑通 runbook §6（退出码 0）")
    void manualFiveSteps_playwrightGreen() throws Exception {
        // ---- 服务端前置：预置模板（列表断言的目标行；id 倒序必在第一页）----
        String templateName = createTemplateViaApi();

        // ---- 拉起 Playwright（console/ 工程独立进程），地址/凭据全走环境变量 ----
        // surefire 的工作目录是模块根（= 仓库根，单模块工程），console/ 就在这里
        Path consoleDir = Path.of("console").toAbsolutePath().normalize();
        assertThat(consoleDir.resolve("e2e").resolve("console.spec.ts"))
                .as("Playwright 工程应在 console/ 内（console/e2e/console.spec.ts）").exists();

        Path shot = Path.of("target", "e2e", "console-list.png").toAbsolutePath();
        shot.getParent().toFile().mkdirs();

        ProcessBuilder pb = new ProcessBuilder("npx", "playwright", "test", "--reporter=list")
                .directory(consoleDir.toFile())
                .redirectErrorStream(true);
        pb.environment().putAll(Map.of(
                "CONSOLE_BASE_URL", "http://127.0.0.1:" + serverPort,
                "CONSOLE_ADMIN_USER", "admin",
                "CONSOLE_ADMIN_PASSWORD", SEED_ADMIN_PASSWORD,
                "CONSOLE_TEMPLATE_NAME", templateName,
                "CONSOLE_E2E_SHOT", shot.toString()));
        Process playwright = pb.start();
        assertThat(serverPort).as("RANDOM_PORT 应注入 local.server.port").isPositive();

        // 输出先在后台线程累积（进程挂住时 waitFor 的超时才能生效，不被管道读阻塞）
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (var in = playwright.getInputStream()) {
                output.append(new String(in.readAllBytes()));
            } catch (Exception ignored) {
                // 进程被强杀时流关闭，读到多少算多少
            }
        }, "playwright-output");
        reader.setDaemon(true);
        reader.start();

        if (!playwright.waitFor(5, TimeUnit.MINUTES)) {
            playwright.destroyForcibly();
            reader.join(5_000);
            throw new AssertionError("Playwright 进程 5 分钟未结束（已强杀）。输出：\n" + output);
        }
        reader.join(5_000);

        assertThat(playwright.exitValue())
                .as("Playwright 应以退出码 0 结束（五步用例全绿）。输出：\n%s", output)
                .isZero();
        assertThat(output.toString()).as("五个用例应各跑一次").contains("5 passed");

        // 绿的时候也把 Playwright 输出打出来：surefire 日志/取证归档需要它（不只失败时）
        System.out.println("[E2E] Playwright 全绿，输出如下：\n" + output);
    }
}
