package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.common.crypto.OpenApiSignature;
import com.newcoupon.infrastructure.web.OpenApiAuthFilter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * S1 第 5 步：资源命名与请求上下文（RQ-4/RQ-9）——"治理插槽真的接上了"的可见证据。
 *
 * <p>与 {@link OpenApiAuthIntegrationTest} 用**完全相同的 Spring 配置**，
 * 因此共享同一个上下文与容器（不是每个测试类都重起一遍容器——WSL 资源纪律）。
 *
 * <p>两条断言链：
 * <ol>
 *   <li><b>响应头</b>：{@code X-Request-App} / {@code X-Request-Resource}——
 *       资源名要能看出**应用服务补的维度**（{@code coupons/receive:template:999999}），
 *       那才是限流/热点探测真正要用的 key；</li>
 *   <li><b>访问日志</b>：一行一次调用，含 appId / resource / deadline 余量——
 *       deadline 是 S2 超时预算的挂载点，S1 先让它可见。</li>
 * </ol>
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class OpenApiContextIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String APP_ID = "t-context";

    @Autowired
    private OpenAppService appService;

    @Autowired
    private TestRestTemplate rest;

    private static String secret;
    private static final java.util.concurrent.atomic.AtomicBoolean PROVISIONED =
            new java.util.concurrent.atomic.AtomicBoolean();

    @BeforeEach
    void provisionOnce() {
        if (PROVISIONED.compareAndSet(false, true)) {
            secret = appService.create(new CreateAppRequest(APP_ID, "上下文测试接入方", null, null, null)).secret();
        }
    }

    @Test
    @DisplayName("领券请求：资源名带上应用服务补的 template 维度（热点参数限流的挂载点）")
    void receiveRequest_carriesTemplateDimension() {
        // 模板不存在没关系——维度在 receive() 的第一行就已补上，之后才做业务
        String body = "{\"requestId\":\"ctx-1\",\"userId\":123,\"templateId\":999999}";
        String line = captureAccessLog(() ->
                call(HttpMethod.POST, "/open/v1/coupons/receive", null, body, 500L));

        assertThat(line).contains("resource=coupons/receive:template:999999");
    }

    @Test
    @DisplayName("查询请求：资源名来自 @OpenResource 注解（不是路径推导）")
    void queryRequest_usesAnnotationResource() {
        String line = captureAccessLog(() ->
                call(HttpMethod.GET, "/open/v1/coupons", "userId=123", null, null));

        assertThat(line)
                .as("注解写的是 coupons/query，路径推导会给 coupons——注解优先，说明插槽接对了")
                .contains("resource=coupons/query");
    }

    @Test
    @DisplayName("访问日志：一行一次调用，含 appId / resource / deadline 余量")
    void accessLog_carriesContext() {
        String line = captureAccessLog(() ->
                call(HttpMethod.GET, "/open/v1/coupons", "userId=123", null, 800L));

        assertThat(line).contains("appId=" + APP_ID);
        assertThat(line).as("deadline 余量必须可见（S2 超时预算的挂载点）").contains("remainingMs=");
        assertThat(line).as("请求头给了 800ms 预算，余量应当小于默认的 3000ms")
                .doesNotContain("remainingMs=3000");
    }

    // ------------------------------------------------------------------

    /**
     * 捕获访问日志。
     *
     * <p>为什么断言日志而不是响应头：曾试图在过滤器里回写 {@code X-Request-Resource} 响应头，
     * 实测**拿不到**——Tomcat 在消息转换器写完响应体时就 flush 了 buffer，filter chain 返回时
     * 响应已提交，提交后设置响应头按规范无效。日志是此时唯一可靠的可见出口。
     */
    private String captureAccessLog(Runnable action) {
        Logger filterLogger = (Logger) LoggerFactory.getLogger(OpenApiAuthFilter.class);
        SyncListAppender appender = new SyncListAppender();
        appender.start();
        filterLogger.addAppender(appender);
        try {
            action.run();
            // **"请求返回"不等于"服务端日志已写"**：响应在过滤器链内部就被 flush 给客户端
            // （见设计文档 §9-4），而访问日志写在之后的 finally 里——两者之间是一个真实的竞态窗口。
            // 断言服务端日志必须轮询等待（detach 之前等，否则日志会写到已摘除的 appender 上）。
            long deadline = System.currentTimeMillis() + 3_000;
            while (appender.messages.stream().noneMatch(m -> m.startsWith("open-api:"))
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            filterLogger.detachAppender(appender);
        }
        return appender.messages.stream()
                .filter(m -> m.startsWith("open-api:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到开放面访问日志"));
    }

    /**
     * 线程安全的日志收集器。
     *
     * <p><b>为什么不用 logback 自带的 {@code ListAppender}</b>：它内部是普通 {@code ArrayList}——
     * 而这里**写日志的是 http-nio 线程、读日志的是测试主线程**，跨线程读一个非同步集合
     * 没有 happens-before 保证，读到空是合法的（实测：同一个类里两条用例稳定通过、
     * 一条稳定读到空，日志在服务端明明打出来了）。<b>测试基建自己也要讲并发正确性。</b>
     */
    private static final class SyncListAppender extends AppenderBase<ILoggingEvent> {
        private final List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            messages.add(event.getFormattedMessage());
        }
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String query, String body, Long timeoutMs) {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String bodySha = OpenApiSignature.bodySha256(
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        String canonical = OpenApiSignature.canonicalString(
                method.name(), path, OpenApiSignature.canonicalQuery(query), bodySha, APP_ID,
                Long.parseLong(ts), nonce);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", OpenApiSignature.SCHEME + " Credential=" + APP_ID
                + ", Timestamp=" + ts
                + ", Nonce=" + nonce
                + ", Signature=" + OpenApiSignature.sign(secret, canonical));
        if (timeoutMs != null) {
            headers.set("X-Timeout-Ms", String.valueOf(timeoutMs));
        }
        if (method == HttpMethod.POST) {
            // S1 第 6 步起，写接口必须带 clientToken（幂等过滤器强制）——
            // 本类写于第 5 步，第 6 步之后没跑它，这个回归潜伏了两步才被回归发现
            headers.set("X-Client-Token", "ctx-token-" + nonce);
        }

        String url = query == null || query.isEmpty() ? path : path + "?" + query;
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return rest.exchange(url, method, entity, String.class);
    }
}
