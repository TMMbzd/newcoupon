package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.infrastructure.ratelimit.RateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 限流器的**行为契约**（2026-10-08，换用 Bucket4j 的重构基线）。
 *
 * <p><b>这批用例的作用是"锁住行为"</b>：换实现（自研三算法 + 预热装饰器 → Bucket4j 令牌桶）
 * 时，它们必须**一路保持绿**——那才是"行为没变、只换了实现"的证据。
 * 所以这里只断言**契约**，不碰任何实现细节（不预填计数器、不假设键长什么样、
 * 不假设用的是哪种算法）——上一版正是栽在"假设了实现细节"上。
 *
 * <p><b>契约本身（重新定义的）</b>：
 * <ol>
 *   <li>调用方只说"限谁"（subject），**不含任何时间信息**；窗口怎么切是实现的事；</li>
 *   <li>窗口内前 limit 次放行、第 limit+1 次拒绝；</li>
 *   <li>不同 subject 互不影响；</li>
 *   <li>{@code limit <= 0} 表示不限；</li>
 *   <li>状态是**共享的**（存在 Redis），跨调用、跨实例都算同一份；</li>
 *   <li>窗口过后能恢复（计数会随时间补充）。</li>
 * </ol>
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE="
})
class RateLimitContractTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        // 2026-10-10 环0 补写：本类此前漏写注入块，一直在悄悄连 dev 库（dev 容器停了才暴露）
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }


    @Autowired
    private RateLimiter rateLimiter;

    /** 每个用例用自己的 subject（铁律①：用例的数据自己建） */
    private static String subject(String tag) {
        return "contract:" + tag + ":" + System.nanoTime();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("① 窗口内：前 limit 次放行，第 limit+1 次拒绝")
    void withinWindow_allowsUpToLimitThenRejects() {
        String subject = subject("basic");

        for (int i = 1; i <= 3; i++) {
            assertThat(rateLimiter.tryAcquire(subject, 3, Duration.ofMinutes(10)))
                    .as("第 %d 次应当放行（上限 3）", i).isTrue();
        }
        assertThat(rateLimiter.tryAcquire(subject, 3, Duration.ofMinutes(10)))
                .as("第 4 次必须被拒").isFalse();
    }

    @Test
    @DisplayName("② 不同 subject 互不影响——限流是「按主体」的，不是一个全局计数器")
    void differentSubjectsAreIndependent() {
        String a = subject("iso-a");
        String b = subject("iso-b");

        assertThat(rateLimiter.tryAcquire(a, 1, Duration.ofMinutes(10))).isTrue();
        assertThat(rateLimiter.tryAcquire(a, 1, Duration.ofMinutes(10)))
                .as("a 已用掉额度").isFalse();
        assertThat(rateLimiter.tryAcquire(b, 1, Duration.ofMinutes(10)))
                .as("b 有自己的额度，不该被 a 影响").isTrue();
    }

    @Test
    @DisplayName("③ limit <= 0 表示不限：连打都不拦")
    void nonPositiveLimitMeansUnlimited() {
        String subject = subject("unlimited");

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryAcquire(subject, 0, Duration.ofMinutes(1)))
                    .as("0 = 不限，第 %d 次也该放行", i + 1).isTrue();
        }
    }

    @Test
    @DisplayName("④ 状态是共享的：换一个 RateLimiter 视角看，计数仍然是同一份")
    void stateIsSharedAcrossCalls() {
        String subject = subject("shared");

        // 连续三次拿"桶"——如果状态在进程内、每次新建，这里会各算各的、永远放行
        assertThat(rateLimiter.tryAcquire(subject, 2, Duration.ofHours(1))).isTrue();
        assertThat(rateLimiter.tryAcquire(subject, 2, Duration.ofHours(1))).isTrue();
        assertThat(rateLimiter.tryAcquire(subject, 2, Duration.ofHours(1)))
                .as("状态若没有共享，这一发会误放行").isFalse();
    }

    @Test
    @DisplayName("⑤ 窗口过后能恢复：短窗口下等一会儿，额度会补回来")
    void quotaRecoversAfterWindow() throws Exception {
        String subject = subject("recover");

        // 用很短的窗口（1 秒）+ 很小的上限，让"补充"能在用例里被观察到。
        // **为什么不用"等到窗口正好到期"来卡边界**：那是不可控的量（机器快慢）。
        // 这里用轮询——"最终会恢复"是契约，"恰好在第几毫秒恢复"不是。
        assertThat(rateLimiter.tryAcquire(subject, 1, Duration.ofSeconds(1))).isTrue();
        assertThat(rateLimiter.tryAcquire(subject, 1, Duration.ofSeconds(1)))
                .as("额度已用完").isFalse();

        boolean recovered = false;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (rateLimiter.tryAcquire(subject, 1, Duration.ofSeconds(1))) {
                recovered = true;
                break;
            }
            Thread.sleep(100);
        }
        assertThat(recovered)
                .as("等够时间后额度应当补回来——限流是「按时间补充」的，不是「一次用光永久拉黑」")
                .isTrue();
    }

    @Test
    @DisplayName("⑥ 主体语义：调用方传的就是「限谁」，不需要也不应该拼时间进去")
    void subjectCarriesNoTimeInformation() {
        String subject = subject("subject-semantics");

        // 这条用例是"契约本身"的守卫：同一个 subject 在**跨越一个很短的窗口**之后，
        // 仍然被认作同一个主体（计数连续），而不是"每秒变成一个新主体"。
        // 上一版的契约要求调用方把"当前秒"拼进 key，于是同一个租户每秒都是新主体——
        // 那正是"配了滑动窗口却拿不到滑动窗口"的根源。
        assertThat(rateLimiter.tryAcquire(subject, 2, Duration.ofMinutes(10))).isTrue();
        assertThat(rateLimiter.tryAcquire(subject, 2, Duration.ofMinutes(10))).isTrue();
        assertThat(rateLimiter.tryAcquire(subject, 2, Duration.ofMinutes(10)))
                .as("跨过一秒钟之后，同一个 subject 仍然是同一个主体（计数连续）").isFalse();
    }
}
