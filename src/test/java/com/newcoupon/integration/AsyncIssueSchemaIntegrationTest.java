package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponDailyCounterMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponUserDailyCounterMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

/**
 * V27/V28 的真实 DB 契约（ADR-0019 / L73）：
 *
 * <ul>
 *   <li>V27 三张单表能被 ShardingSphere 正常路由，任务唯一键跨租户不冲突；</li>
 *   <li>V28 把 64 张 {@code user_coupon_*} 的 uk_idem 改成
 *       {@code (user_id, app_id, idempotent_key)}，同一 user+requestId 在不同租户下可共存；</li>
 *   <li>计数表“ensureRow（ODKU 持排他锁）-> 条件 UPDATE”两步法能正确达限，不会把首行误判为达限。</li>
 * </ul>
 */
class AsyncIssueSchemaIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    /** 64 的倍数：确定落在 user_coupon_0，便于直接对物理表做约束测试。 */
    private static final long USER_ID = 64_000L;
    private static final long TASK_TEMPLATE_ID = 990_001L;
    private static final long COUNTER_TEMPLATE_ID = 990_002L;
    private static final String APP_A = "schema-test-a";
    private static final String APP_B = "schema-test-b";
    private static final String REQUEST_ID = "schema-request-1";

    @Autowired
    private CouponIssueTaskMapper taskMapper;
    @Autowired
    private CouponDailyCounterMapper dailyCounterMapper;
    @Autowired
    private CouponUserDailyCounterMapper userDailyCounterMapper;
    @Autowired
    private UserCouponRepository userCouponRepository;

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection c = rawConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM coupon_issue_task WHERE user_id = " + USER_ID);
            s.executeUpdate("DELETE FROM coupon_daily_counter WHERE template_id = " + COUNTER_TEMPLATE_ID);
            s.executeUpdate("DELETE FROM coupon_user_daily_counter WHERE user_id IN (" + USER_ID
                    + "," + (USER_ID + 1) + ")");
            s.executeUpdate("DELETE FROM user_coupon_0 WHERE user_id = " + USER_ID);
        }
    }

    @Test
    @DisplayName("V27：任务唯一键 (app_id,user_id,request_id) 跨租户不冲突")
    void taskUniqueKeyIsTenantScoped() {
        CouponIssueTaskPO a = task(APP_A, "task-a");
        assertThat(taskMapper.insert(a)).isEqualTo(1);
        assertThat(a.getId()).isNotNull();

        CouponIssueTaskPO found = taskMapper.selectByUniqueKey(APP_A, USER_ID, "task-a");
        assertThat(found).isNotNull();
        assertThat(found.getAppId()).isEqualTo(APP_A);
        assertThat(found.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_PENDING);

        assertThatThrownBy(() -> taskMapper.insert(task(APP_A, "task-a")))
                .isInstanceOf(DuplicateKeyException.class);

        // 同 user + 同 requestId，不同租户：必须允许各有一行
        assertThat(taskMapper.insert(task(APP_B, "task-a"))).isEqualTo(1);
        assertThat(taskMapper.countByStatus(CouponIssueTaskPO.STATUS_PENDING)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("V28：user_coupon 的 uk_idem 含 app_id，同用户同 requestId 可跨租户共存")
    void idempotencyKeyIncludesTenant() throws SQLException {
        // 模板 id 全局唯一：同 user+requestId 跨租户时，template_id 也必须不同，
        // 否则会先撞 uk_user_seq(user_id, template_id, seq) —— 那是另一个约束。
        insertCouponRow(9_000_000_000_000_001L, APP_A, TASK_TEMPLATE_ID);
        insertCouponRow(9_000_000_000_000_002L, APP_B, TASK_TEMPLATE_ID + 1);

        Optional<UserCoupon> a = userCouponRepository.findByIdempotentKey(USER_ID, APP_A, REQUEST_ID);
        Optional<UserCoupon> b = userCouponRepository.findByIdempotentKey(USER_ID, APP_B, REQUEST_ID);
        assertThat(a).isPresent();
        assertThat(b).isPresent();
        assertThat(a.orElseThrow().getAppId()).isEqualTo(APP_A);
        assertThat(b.orElseThrow().getAppId()).isEqualTo(APP_B);

        assertThatThrownBy(() -> insertCouponRow(9_000_000_000_000_003L, APP_A, TASK_TEMPLATE_ID))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("V27/V28：唯一键列顺序与 ADR 一致")
    void indexColumnsAreAsDesigned() throws SQLException {
        assertThat(indexColumns("coupon_issue_task", "uk_app_user_request"))
                .containsExactly("app_id", "user_id", "request_id");
        assertThat(indexColumns("user_coupon_0", "uk_idem"))
                .containsExactly("user_id", "app_id", "idempotent_key");
    }

    @Test
    @DisplayName("硬日限：ensureRow 后条件 UPDATE；达限返回 0 且 used 不超限")
    void dailyCountersAreHardLimits() {
        LocalDate day = LocalDate.now().plusDays(1);   // 避开其它用例的当天计数

        dailyCounterMapper.ensureRow(COUNTER_TEMPLATE_ID, day);
        dailyCounterMapper.ensureRow(COUNTER_TEMPLATE_ID, day);   // 重复键也要成功并锁住行
        assertThat(dailyCounterMapper.tryIncrement(COUNTER_TEMPLATE_ID, day, 2)).isEqualTo(1);
        assertThat(dailyCounterMapper.tryIncrement(COUNTER_TEMPLATE_ID, day, 2)).isEqualTo(1);
        assertThat(dailyCounterMapper.tryIncrement(COUNTER_TEMPLATE_ID, day, 2)).isZero();
        assertThat(dailyCounterMapper.selectUsed(COUNTER_TEMPLATE_ID, day)).isEqualTo(2);

        userDailyCounterMapper.ensureRow(USER_ID + 1, COUNTER_TEMPLATE_ID, day);
        assertThat(userDailyCounterMapper.tryIncrement(USER_ID + 1, COUNTER_TEMPLATE_ID, day, 1)).isEqualTo(1);
        assertThat(userDailyCounterMapper.tryIncrement(USER_ID + 1, COUNTER_TEMPLATE_ID, day, 1)).isZero();
        assertThat(userDailyCounterMapper.selectUsed(USER_ID + 1, COUNTER_TEMPLATE_ID, day)).isEqualTo(1);
    }

    private CouponIssueTaskPO task(String appId, String requestId) {
        CouponIssueTaskPO po = new CouponIssueTaskPO();
        po.setAppId(appId);
        po.setUserId(USER_ID);
        po.setTemplateId(TASK_TEMPLATE_ID);
        po.setRequestId(requestId);
        po.setStatDay(LocalDate.now());
        po.setSeq(1);
        po.setStatus(CouponIssueTaskPO.STATUS_PENDING);
        po.setNextScanTime(LocalDateTime.now());
        return po;
    }

    private void insertCouponRow(long id, String appId, long templateId) throws SQLException {
        try (Connection c = rawConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO user_coupon_0 "
                             + "(id,user_id,template_id,template_code,face_value,threshold,discount_cap,"
                             + "valid_end,status,receive_time,idempotent_key,seq,app_id) "
                             + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, id);
            ps.setLong(2, USER_ID);
            ps.setLong(3, templateId);
            ps.setString(4, "SCHEMA-TEST");
            ps.setLong(5, 100L);
            ps.setLong(6, 0L);
            ps.setObject(7, null);
            ps.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now().plusDays(30)));
            ps.setInt(9, 0);
            ps.setTimestamp(10, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(11, REQUEST_ID);
            ps.setInt(12, 1);
            ps.setString(13, appId);
            ps.executeUpdate();
        }
    }

    private List<String> indexColumns(String table, String index) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Connection c = rawConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COLUMN_NAME FROM information_schema.statistics "
                             + "WHERE table_schema = ? AND table_name = ? AND index_name = ? "
                             + "ORDER BY SEQ_IN_INDEX")) {
            ps.setString(1, MYSQL.getDatabaseName());
            ps.setString(2, table);
            ps.setString(3, index);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
        }
        return cols;
    }

    private Connection rawConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
