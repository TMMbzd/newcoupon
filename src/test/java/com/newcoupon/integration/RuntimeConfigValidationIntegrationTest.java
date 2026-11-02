package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.config.RuntimeConfigValidator;
import com.newcoupon.config.RuntimeConfigValidator.Report;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 运行时配置校验对真实 MySQL（Testcontainers）的探测回归：
 *
 * <p>单元测试覆盖的是纯判断逻辑；这里证明 {@code SmartInitializingSingleton} 真的在
 * Spring 启动期执行、真的能通过 ShardingSphere 主数据源拿到 session 时区偏移与
 * {@code max_connections}。探测 SQL 若被分片解析器拒绝，本用例会红——这是设计里
 * 唯一无法靠单测证明的部分。
 *
 * <p>测试 profile 不是 prod，因此时区/预算问题只会告警；断言 errors 为空即可，
 * 不把"容器/JVM 时区恰好一致"变成脆弱的通过条件。
 */
class RuntimeConfigValidationIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private RuntimeConfigValidator validator;

    @Test
    @Timeout(60)
    @DisplayName("启动期校验真实执行：时区与连接数预算均已完成探测")
    void startupValidationRunsAgainstRealDatabase() {
        Report report = validator.lastReport();

        assertThat(report).as("SmartInitializingSingleton 必须已经执行").isNotNull();
        assertThat(report.errors())
                .as("测试 profile 必须宽松；容器时区差异最多只告警")
                .isEmpty();
        assertThat(report.checked())
                .as("DB 探测成功时才登记 timezone / mysql-pool-budget")
                .contains("mysql-probe", "timezone", "mysql-pool-budget");
        assertThat(report.checked())
                .as("xxl.job.enabled=false 时不应检查 XXL")
                .doesNotContain("xxl-job");
    }
}
