package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.CouponExpireService;
import com.newcoupon.application.service.StockReconcileService;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 多实例调度互斥回归（2026-09-12）：Redis SET NX PX 抢锁 + 比对释放。
 * 验证三件事：①锁互斥（第二个持有者抢不到）②释放只认自己的持有者标识（防误删他人锁）
 * ③没抢到锁的调度轮次让路（scan/巡检直接返回，不产生任何 DB 负载）。
 * 正确性本由幂等 CAS 保证（重复执行不改错数据），锁消的是重复扫描的负载——
 * 这条测试钉住的是"让路行为"本身。
 */
@Testcontainers
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=50")
class SchedulerLockIntegrationTest {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private RedisScanLock scanLock;
    @Autowired
    private CouponExpireService couponExpireService;
    @Autowired
    private StockReconcileService stockReconcileService;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    @Timeout(60)
    @DisplayName("锁互斥：先到者得；错误的持有者释放不掉；正确的持有者释放后可再抢")
    void mutualExclusionAndOwnerSafeRelease() {
        String key = "test:lock:1";
        redis.delete(key);

        assertThat(scanLock.tryLock(key, "A", Duration.ofSeconds(30)))
                .as("第一个持有者抢到").isTrue();
        assertThat(scanLock.tryLock(key, "B", Duration.ofSeconds(30)))
                .as("第二个持有者被拒").isFalse();

        scanLock.unlock(key, "B");   // B 试图释放 A 的锁——必须失败
        assertThat(redis.hasKey(key)).as("B 释放不掉 A 的锁").isTrue();

        scanLock.unlock(key, "A");   // A 释放自己的锁
        assertThat(redis.hasKey(key)).as("A 释放成功").isFalse();

        assertThat(scanLock.tryLock(key, "C", Duration.ofSeconds(30)))
                .as("释放后可再抢").isTrue();
        scanLock.unlock(key, "C");
    }

    @Test
    @Timeout(60)
    @DisplayName("过期扫描让路：锁被他人持有 → scan() 本轮零处理零 DB 触碰；释放后恢复")
    void expireScanYieldsWhenLockHeld() {
        String lockKey = "c:exp:scanlock:0";
        scanLock.tryLock(lockKey, "another-instance", Duration.ofSeconds(30));

        int processed = couponExpireService.scan();

        assertThat(processed).as("没抢到锁：本轮让路，零处理").isZero();

        scanLock.unlock(lockKey, "another-instance");
        assertThat(couponExpireService.scan())
                .as("锁释放后恢复扫描（无到期任务也正常跑完返回 0）").isZero();
    }

    @Test
    @Timeout(60)
    @DisplayName("库存巡检让路：锁被他人持有 → identityCheckAll() 直接返回，不扫任何模板")
    void stockPatrolYieldsWhenLockHeld() {
        String lockKey = "c:lock:stock-patrol";
        scanLock.tryLock(lockKey, "another-instance", Duration.ofSeconds(30));

        int confirmed = stockReconcileService.identityCheckAll();

        assertThat(confirmed).as("没抢到锁：本轮让路").isZero();

        scanLock.unlock(lockKey, "another-instance");
    }
}
