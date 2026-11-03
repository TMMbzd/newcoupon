package com.newcoupon.security;

import com.newcoupon.integration.ShardedIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 2A 会话时长验收：空闲超时与绝对超时（设计要求 30m/8h，这里配短等比例验证机制）。
 *
 * <p>配置：idle-timeout=2s（登录时写进会话的 maxInactiveInterval）、absolute-timeout=4s
 * （AdminAuthorityRefreshFilter 按登录时刻核对）。两类超时在**同一个上下文**里分两段验证：
 * <ol>
 *   <li><b>空闲</b>：登录后放着不动 2.5s → 会话死（401）。此时会话**没有**活跃请求，
 *       绝对上限（4s）还没到——死因只能是空闲。</li>
 *   <li><b>绝对</b>：重新登录后每 0.7s 摸一次 /me（每次请求刷新 lastAccessedTime，
 *       空闲永不满足）→ 到 4s 上限照样 401——一直活跃的会话也会被绝对上限收割。</li>
 * </ol>
 *
 * <p>单独成类（不与 SecurityRbacIntegrationTest 共上下文）：短超时配置若进了主类，
 * 其余用例的会话会在断言间隙里暴毙。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "coupon.security.session.idle-timeout=2s",
        "coupon.security.login.ip-limit=1000",   // 共享 Redis 的 IP 节流键被前一个类烧过，同 JVM 同 IP
        "coupon.security.session.absolute-timeout=4s"
})
class SecurityRbacSessionTimeoutIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    private String loginSession() {
        ResponseEntity<String> csrfResp = rest.getForEntity("/console/api/auth/me", String.class);
        String csrf = csrfResp.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().map(c -> c.split(";")[0].substring("XSRF-TOKEN=".length()))
                .orElseThrow();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CSRF-TOKEN", csrf);
        headers.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf);
        ResponseEntity<String> resp = rest.exchange("/console/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"dev-seed-admin-123\"}", headers),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return resp.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("SESSION="))
                .findFirst().map(c -> c.split(";")[0].substring("SESSION=".length()))
                .orElseThrow();
    }

    private int meStatus(String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "SESSION=" + session);
        return rest.exchange("/console/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode().value();
    }

    @Test
    @DisplayName("空闲超时：登录后静置 2.5s（> idle 2s，< absolute 4s）→ 会话 401")
    void idleTimeout_expiresUntouchedSession() throws InterruptedException {
        String session = loginSession();
        assertThat(meStatus(session)).isEqualTo(200);   // 建立即刻可用

        Thread.sleep(2_500);                            // 只过了空闲上限，未到绝对上限

        assertThat(meStatus(session))
                .as("静置超过空闲上限：会话应已死（401），且此刻绝对上限未到，死因只能是空闲")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("绝对超时：持续活跃（每 0.7s 一请求，空闲永不满足）也会在 4s 上限被收割")
    void absoluteTimeout_capsContinuouslyActiveSession() throws InterruptedException {
        String session = loginSession();
        long loginAt = System.currentTimeMillis();
        boolean stayedAliveBeyondIdleWindow = false;
        int lastStatus = 200;

        // 摸到 401 或 8s 防漏网为止
        while (lastStatus == 200 && System.currentTimeMillis() - loginAt < 8_000) {
            Thread.sleep(700);
            lastStatus = meStatus(session);
            if (lastStatus == 200 && System.currentTimeMillis() - loginAt > 2_500) {
                // 会话活过了空闲窗口仍 200：说明"活跃会刷新空闲计时"在工作
                stayedAliveBeyondIdleWindow = true;
            }
        }

        assertThat(stayedAliveBeyondIdleWindow)
                .as("会话应一直靠活跃续命到绝对上限（若中途空闲死掉，这条红）");
        assertThat(lastStatus)
                .as("绝对上限 4s 后，活跃会话也应 401（防会话长生）")
                .isEqualTo(401);
    }
}
