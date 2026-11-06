package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponExpireTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponReleaseTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponUsageMapper;
import com.newcoupon.infrastructure.persistence.mapper.UserCouponMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * P0-6：分片路由的物理 SQL 断言。
 *
 * <p>用 ShardingSphere 的 {@code sql-show} 日志做证据：同一条逻辑 SQL 经分片后实际执行了几条、
 * 命中哪张物理表。这里只断言已经带 {@code user_id} 的查询命中 1 张表；没有 user_id 的扫描
 * 和旧 CAS 会广播，测试把真实数量打印出来，作为后续优化清单，不把当前广播行为写成永久契约。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.sharding.sql-show=true",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class ShardingRouteAssertionTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final long USER_ID = 320_063L;

    @Autowired
    private UserCouponRepository userCouponRepository;
    @Autowired
    private CouponUsageMapper usageMapper;
    @Autowired
    private UserCouponMapper userCouponMapper;
    @Autowired
    private CouponExpireTaskMapper expireTaskMapper;
    @Autowired
    private CouponReleaseTaskMapper releaseTaskMapper;

    private ListAppender<ILoggingEvent> appender;
    private Logger sqlLogger;

    @BeforeEach
    void attachSqlAppender() {
        sqlLogger = (Logger) LoggerFactory.getLogger("ShardingSphere-SQL");
        appender = new ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);
    }

    @AfterEach
    void detachSqlAppender() {
        sqlLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("user_coupon 按 id+userId 查询只命中 1 张物理表")
    void userCouponByIdAndUserHitsOneTable() {
        assertSingleTable("user_coupon_", USER_ID,
                () -> userCouponRepository.findByIdForUser(1L, USER_ID));
    }

    @Test
    @DisplayName("coupon_usage 按 usageNo+userId 查询/锁只命中 1 张物理表")
    void usageByUsageNoAndUserHitsOneTable() {
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.selectByUsageNoForUpdate("route-usage-1", USER_ID));
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.selectByUsageNoForUser("route-usage-1", USER_ID));
    }

    @Test
    @DisplayName("coupon_usage 按 userId+orderNo 查询/订单行锁只命中 1 张物理表")
    void usageByOrderNoAndUserHitsOneTable() {
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.selectByOrderNo(USER_ID, "route-order-1"));
        // L75：核对销事务内按 id 升序锁订单全部占用单——同样只命中 1 张物理表
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.selectByOrderNoForUpdate(USER_ID, "route-order-1"));
    }

    @Test
    @DisplayName("过期任务 finish/backoff 带 userId 只命中 1 张物理表")
    void expireTaskFinishAndBackoffHitOneTable() {
        assertSingleTable("coupon_expire_task_", USER_ID,
                () -> expireTaskMapper.finish(1L, USER_ID));
        assertSingleTable("coupon_expire_task_", USER_ID,
                () -> expireTaskMapper.backoff(1L, USER_ID));
    }

    @Test
    @DisplayName("释放任务 finish 带 userId 只命中 1 张物理表")
    void releaseTaskFinishHitsOneTable() {
        assertSingleTable("coupon_release_task_", USER_ID,
                () -> releaseTaskMapper.finish("route-usage-1", USER_ID));
    }

    @Test
    @DisplayName("coupon_usage 的 8 个 CAS 带 userId 后都只命中 1 张物理表")
    void usageCasWithUserHitsOneTable() {
        LocalDateTime now = LocalDateTime.now();
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.casConfirm("route-usage-1", USER_ID, 100L, now));
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.casRelease("route-usage-1", USER_ID, now));
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.casRefundState("route-usage-1", USER_ID, 5));
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.casConfirmException("route-usage-1", USER_ID));
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.casConfirmFromException("route-usage-1", USER_ID, 100L, now));
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.casReleaseFromException("route-usage-1", USER_ID, now));
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.casExpireByUsageNo("route-usage-1", USER_ID));
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.casExpireByCouponId(7L, USER_ID));
        assertSingleTable("coupon_usage_", USER_ID,
                () -> usageMapper.casTimeoutByUsageNo("route-usage-1", USER_ID));
    }

    @Test
    @DisplayName("user_coupon 的 7 个 CAS 带 userId 后都只命中 1 张物理表")
    void userCouponCasWithUserHitsOneTable() {
        assertSingleTable("user_coupon_", USER_ID,
                () -> userCouponMapper.casLock(1L, USER_ID));
        assertSingleTable("user_coupon_", USER_ID,
                () -> userCouponMapper.casConfirm(1L, USER_ID));
        assertSingleTable("user_coupon_", USER_ID,
                () -> userCouponMapper.casReturnOnRefund(1L, USER_ID));
        assertSingleTable("user_coupon_", USER_ID,
                () -> userCouponMapper.casExpireUsable(1L, USER_ID));
        assertSingleTable("user_coupon_", USER_ID,
                () -> userCouponMapper.casExpireOnLock(1L, USER_ID));
        assertSingleTable("user_coupon_", USER_ID,
                () -> userCouponMapper.casRelease(1L, USER_ID));
        assertSingleTable("user_coupon_", USER_ID,
                () -> userCouponMapper.casTimeoutClose(1L, USER_ID));
    }

    @Test
    @DisplayName("记录后台扫描的当前广播数量（不作为永久契约）")
    void recordBroadcastQueries() {
        appender.list.clear();
        expireTaskMapper.scanDueShard(0, 1, 10);
        List<String> scanSqls = actualSql("coupon_expire_task_");
        System.out.println("[P0-6] coupon_expire_task scanDueShard 实际物理 SQL 数="
                + scanSqls.size());
        assertThat(scanSqls).as("按 user_id 分表的扫描广播 64 张，已作为接受成本登记").hasSize(64);

        appender.list.clear();
        releaseTaskMapper.scanDueShard(0, 1, 10);
        List<String> releaseScanSqls = actualSql("coupon_release_task_");
        System.out.println("[P0-6] coupon_release_task scanDueShard 实际物理 SQL 数="
                + releaseScanSqls.size());
        assertThat(releaseScanSqls).as("释放任务扫描同样广播 64 张").hasSize(64);
    }

    private void assertSingleTable(String tablePrefix, long userId, Runnable query) {
        appender.list.clear();
        query.run();
        List<String> sqls = actualSql(tablePrefix);
        assertThat(sqls)
                .as("%s 带 userId 的查询应只实际执行 1 条 SQL", tablePrefix)
                .hasSize(1);
        int shard = (int) (userId % 64);
        // 精确匹配表名后缀，不能用 contains：coupon_usage_1 会误匹配 coupon_usage_10..19。
        assertThat(java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(tablePrefix + shard) + "(?![0-9])")
                .matcher(sqls.get(0)).find())
                .as("应精确命中 %s%d，而不是前缀相同的其它分表", tablePrefix, shard)
                .isTrue();
    }

    private List<String> actualSql(String tablePrefix) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Actual SQL:"))
                .filter(message -> message.contains(tablePrefix))
                .toList();
    }
}
