package com.newcoupon.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 分片环境集成测试基座（ADR-0010 批次二）：MySQL 容器**不走 @ServiceConnection**——
 * 业务数据源是 ShardingSphere 驱动（连接串在动态生成的 YAML 里）。各子类自带
 * @DynamicPropertySource 把容器地址注入 {@code coupon.sharding.*}
 * （本 Spring 版本的 @DynamicPropertySource 不从父类发现，6.2 起才支持继承——踩坑实录）。
 */
@Testcontainers
@SpringBootTest
public abstract class ShardedIntegrationTestSupport {

    @Container
    @ServiceConnection
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            // 与 dev compose 对齐：不设 TZ 时容器 UTC，NOW() 比 JVM(+08) 慢 8 小时——
            // "到点即扫"类断言（如 L5 的 next_scan_time<=NOW()）要等 8 小时才成立
            .withEnv("TZ", "Asia/Shanghai");

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
