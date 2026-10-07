package com.newcoupon.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 分片环境集成测试基座（ADR-0010 批次二）：MySQL 容器**不走 @ServiceConnection**——
 * 业务数据源是 ShardingSphere 驱动（连接串在动态生成的 YAML 里）。各子类自带
 * @DynamicPropertySource 把容器地址注入 {@code coupon.sharding.*}
 * （本 Spring 版本的 @DynamicPropertySource 不从父类发现，6.2 起才支持继承——踩坑实录）。
 */
@SpringBootTest
public abstract class ShardedIntegrationTestSupport {

    @ServiceConnection
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            // 与 dev compose 对齐：不设 TZ 时容器 UTC，NOW() 比 JVM(+08) 慢 8 小时——
            // "到点即扫"类断言（如 L5 的 next_scan_time<=NOW()）要等 8 小时才成立
            .withEnv("TZ", "Asia/Shanghai");

    /**
     * **整个测试 JVM 只启动一对容器**（2026-10-08 测试基础设施优化）。
     *
     * <p><b>为什么去掉 {@code @Testcontainers} + {@code @Container}</b>：那两个注解的语义是
     * "**每个测试类**启动并在类结束后停止容器"。后果是 63 个测试类各拿一个**不同的**
     * {@code jdbcUrl}——而 36 个子类的 {@code @DynamicPropertySource} 正是把它注进
     * {@code coupon.sharding.url}。**属性值每类都不同 → Spring 的上下文缓存永远命中不了**
     * → 每个类都要重走一遍"起容器 → 13 个 Flyway 迁移 → 起 Spring 上下文"。
     *
     * <p>实测代价（优化前的全量，63 类 / 374 例 / 50:48）：
     * <pre>
     *   测试类内耗时合计：3035 秒（几乎就是全部）
     *   容器启动：MySQL 34 次 + Redis 34 次
     *   平均每类 48 秒，而多数类只有 3~8 个用例
     * </pre>
     *
     * <p>改成静态单例之后：容器只起一次、Flyway 只迁移一次、上下文按"配置组合数"复用
     * （而不是按类数）。
     *
     * <p><b>换来的代价——数据不再按类隔离</b>：所有测试类共用同一个库与同一个 Redis。
     * 这要求用例遵守两条既有纪律（本来就有，只是现在更硬）：
     * ① 用例的数据自己建、用独有的标识（appId / subject / userId）；
     * ② 有状态组件要复位（熔断器、Sentinel 规则——后者是 JVM 级静态状态）。
     *
     * <p>{@code @ServiceConnection} 保留在 Redis 上：它由 Spring Boot 的
     * {@code ServiceConnectionContextCustomizer} 处理，扫描的是"标注该注解的字段"，
     * **不依赖 {@code @Container}**——但要求容器已启动，所以下面用静态块先启。
     */
    static {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (MYSQL.isRunning()) {
                MYSQL.stop();
            }
            if (REDIS.isRunning()) {
                REDIS.stop();
            }
        }, "testcontainers-shutdown"));
    }

    /** 熔断器（可能为 null：不涉及治理组件的测试类不装配它） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers circuitBreakers;

    /**
     * 每个用例之后复位熔断器。
     *
     * <p><b>为什么必须在基类做</b>：熔断器的状态是**进程级**的（一个应用进程一份），
     * 而测试类共享同一个 Spring 上下文 —— 于是"某个用例的压力把连接池打满、熔断打开"
     * 会**波及同批跑的其它用例**（实测：领券用例集里千并发那条一跑，后面几条全报"系统繁忙"）。
     *
     * <p>这不是熔断器的问题（连接池满确实该熔断），而是**测试之间共享了一个有状态组件**。
     * 处理方式与"用例数据不共享"同一个原则：**让每个用例从同一个起点开始**。
     */
    @org.junit.jupiter.api.AfterEach
    void resetCircuitBreakers() {
        if (circuitBreakers != null) {
            circuitBreakers.redis().reset();
            circuitBreakers.mysql().reset();
        }
    }
}
