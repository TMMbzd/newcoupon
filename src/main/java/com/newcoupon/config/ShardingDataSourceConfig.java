package com.newcoupon.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * ShardingSphere 数据源装配（ADR-0010 批次二，2026-09-13）。
 *
 * <p><b>为什么驱动模式 + 动态生成 YAML</b>：ShardingSphere 的 YAML 里要写真实 JDBC 连接串，
 * 而集成测试的 MySQL 容器端口随机——YAML 做成代码内模板，连接串从 {@code coupon.sharding.*}
 * 属性注入（dev=本机、test=容器、prod=环境变量），一处模板三处适用。
 *
 * <p><b>为什么外层 DriverManagerDataSource</b>：ShardingSphere 驱动是"虚拟数据源"——
 * 真实连接由 YAML 里 ds0（Hikari）池化，外层只是给 Spring/MyBatis 的门面，不重复池化。
 *
 * <p><b>Flyway 走裸库</b>：V6 建 192 张物理分表是 DDL，必须绕过分片路由直连真实库
 * （application.yml 的 spring.flyway.url 单独指向同一 MySQL）。业务 SQL 才走分片。
 *
 * <p>分片规则与 V6 迁移**两处同改**（表数是不可逆决策）：三张用户维度表按
 * {@code user_id % 64} 路由到 64 张物理分表；绑定表同路由键 join 不跨片。
 */
@Configuration
public class ShardingDataSourceConfig {

    private static final String YAML_TEMPLATE = """
            databaseName: __SCHEMA__
            dataSources:
              ds0:
                dataSourceClassName: com.zaxxer.hikari.HikariDataSource
                driverClassName: com.mysql.cj.jdbc.Driver
                url: __DB_URL__
                username: __DB_USER__
                password: __DB_PASS__
                maximumPoolSize: __POOL_MAX__
                minimumIdle: __POOL_MIN__
                connectionTimeout: __POOL_TIMEOUT__
            rules:
            - !SHARDING
              tables:
                user_coupon:
                  actualDataNodes: ds0.user_coupon_$->{0..63}
                  tableStrategy:
                    standard:
                      shardingColumn: user_id
                      shardingAlgorithmName: uc-mod
                coupon_usage:
                  actualDataNodes: ds0.coupon_usage_$->{0..63}
                  tableStrategy:
                    standard:
                      shardingColumn: user_id
                      shardingAlgorithmName: cu-mod
                coupon_expire_task:
                  actualDataNodes: ds0.coupon_expire_task_$->{0..63}
                  tableStrategy:
                    standard:
                      shardingColumn: user_id
                      shardingAlgorithmName: et-mod
                coupon_release_task:
                  actualDataNodes: ds0.coupon_release_task_$->{0..63}
                  tableStrategy:
                    standard:
                      shardingColumn: user_id
                      shardingAlgorithmName: rt-mod
              bindingTables:
                - user_coupon,coupon_usage,coupon_expire_task,coupon_release_task
              shardingAlgorithms:
                uc-mod:
                  type: INLINE
                  props:
                    algorithm-expression: user_coupon_${user_id % 64}
                cu-mod:
                  type: INLINE
                  props:
                    algorithm-expression: coupon_usage_${user_id % 64}
                et-mod:
                  type: INLINE
                  props:
                    algorithm-expression: coupon_expire_task_${user_id % 64}
                rt-mod:
                  type: INLINE
                  props:
                    algorithm-expression: coupon_release_task_${user_id % 64}
            - !SINGLE
              tables:
                - "*.*"
            props:
              sql-show: __SQL_SHOW__
            """;

    @Value("${coupon.sharding.url}")
    private String url;

    @Value("${coupon.sharding.username}")
    private String username;

    @Value("${coupon.sharding.password}")
    private String password;

    @Value("${coupon.sharding.sql-show:false}")
    private boolean sqlShow;

    /** L31（2026-09-30）：连接池参数外部化——池子大小就是并发上限（Little 定律：并发 ≈ QPS × RT），
     *  必须能按环境/压测配平，不能硬编码在代码内模板里。默认值维持原状（20/2/3000ms）。 */
    @Value("${coupon.sharding.pool.max-size:20}")
    private int poolMaxSize;

    @Value("${coupon.sharding.pool.min-idle:2}")
    private int poolMinIdle;

    /** 拿不到连接等待上限：宁可快速失败，也不让请求线程堆积（配合降级码给用户"稍后重试"） */
    @Value("${coupon.sharding.pool.connection-timeout-ms:3000}")
    private long poolConnectionTimeoutMs;

    /** 异步 worker 物理表批量写专用连接池；默认小池，避免与业务池争连接。 */
    @Value("${coupon.issue.worker.raw-bulk-pool-size:4}")
    private int rawBulkPoolSize;

    @Bean
    @Primary
    public DataSource shardingDataSource() throws IOException {
        // ① 先跑 Flyway（裸库直连）：DDL 建分表必须绕过分片路由；且必须在 ShardingSphere
        //    构造前完成——它构造时会加载表元数据，空 schema = TableNotFound（实测踩坑）。
        //    不用 Boot 自动装配：自定义 DataSource 与其存在循环依赖（实测踩坑二连）。
        org.flywaydb.core.Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // ② 再建分片数据源。databaseName 必须等于 MySQL 里的真实库名（从连接串解析——
        //    SS 5.5 用它做单表元数据定位，写死逻辑名会拿到空 schema=任何表都"不存在"，实测踩坑）
        String schema = url.replaceAll("^.*/([^?]+).*$", "$1");
        String yaml = YAML_TEMPLATE
                .replace("__DB_URL__", url)
                .replace("__DB_USER__", username)
                .replace("__DB_PASS__", password)
                .replace("__SQL_SHOW__", String.valueOf(sqlShow))
                .replace("__POOL_MAX__", String.valueOf(poolMaxSize))
                .replace("__POOL_MIN__", String.valueOf(poolMinIdle))
                .replace("__POOL_TIMEOUT__", String.valueOf(poolConnectionTimeoutMs))
                .replace("__SCHEMA__", schema);
        Path tmp = Files.createTempFile("newcoupon-sharding-", ".yaml");
        Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
        tmp.toFile().deleteOnExit();

        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:shardingsphere:absolutepath:" + tmp.toAbsolutePath());
        ds.setDriverClassName("org.apache.shardingsphere.driver.ShardingSphereDriver");
        return ds;
    }

    /**
     * 裸库连接池：只给异步 worker 的“物理表批量写”使用。
     *
     * <p>为什么不走 ShardingSphere：同一份 8000 行写入，裸 MySQL 约 0.76 s，经 ShardingSphere
     * 解析/改写后约 4–5 s（2026-10-23 本机实测）。worker 已经算好 user_id%64 与库存分片，
     * 物理表名是确定的，没必要让中间件再逐行解析；单任务路径仍走 ShardingSphere，批量路径
     * 失败也回退到它，所以它不是唯一写入通道。
     *
     * <p>独立小池 + @Lazy：worker 关闭或未走批量写时不建立连接。这里必须用无参构造再 set，
     * 不能用 {@code new HikariDataSource(config)}：后者会在构造函数里立刻启动连接池，
     * 即使 @Lazy 也挡不住 actuator 把 {@code Map<String, DataSource>} 注入健康检查。
     * 无参对象的池在第一次 {@code getConnection()} 时才创建。
     */
    @Bean(name = "rawBulkDataSource", destroyMethod = "close")
    @Lazy
    public HikariDataSource rawBulkDataSource() {
        // 无参构造不会启动池；set 完后由第一次 getConnection() 初始化 HikariPool。
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(Math.max(1, rawBulkPoolSize));
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(Math.max(1000, poolConnectionTimeoutMs));
        ds.setPoolName("raw-bulk");
        return ds;
    }
}
