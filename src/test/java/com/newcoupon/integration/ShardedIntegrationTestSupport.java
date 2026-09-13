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
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

}
