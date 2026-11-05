package com.newcoupon.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 临时探针（审查验证用，跑完即删）：MySQL SUM(amount) 超 Long 范围后
 * Connector/J 的 getLong 行为——抛异常还是静默截断。
 */
class ProbeSumOverflowTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("probe").withUsername("root").withPassword("root");

    private static Connection conn;

    @BeforeAll
    static void start() throws Exception {
        MYSQL.start();
        conn = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INT PRIMARY KEY, amount BIGINT NOT NULL)");
            st.execute("INSERT INTO t VALUES (1, 9223372036854775807)");   // 单笔 Long.MAX
            st.execute("INSERT INTO t VALUES (2, 100)");                   // 同组普通模板级 hold
            st.execute("INSERT INTO t VALUES (3, 9223372036854775807)");   // 第二笔 Long.MAX（同组）
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (conn != null) conn.close();
        MYSQL.stop();
    }

    /** 单笔 Long.MAX 的 SUM：DECIMAL 值恰在 Long 范围内，正常映射应当成功 */
    @Test
    void sumSingleMaxValue() throws Exception {
        probe("SELECT SUM(amount) FROM t WHERE id IN (1)");
    }

    /** 组内合计 Long.MAX + 100：Java long 运算回绕演示 */
    @Test
    void javaOverflowArithmetic() {
        long hold = Long.MAX_VALUE + 100L;   // == 9223372036854775807 + 100
        System.out.println("[PROBE] java Long.MAX+100 = " + hold
                + " (negative? " + (hold < 0) + ")");
        long discount = -5000L;              // 退款净额日：负 discount_total
        System.out.println("[PROBE] payable = discount - hold = " + (discount - hold)
                + " (positive? " + (discount - hold > 0) + ")");
    }

    /** 两笔 Long.MAX 同组：SQL SUM=18446744073709551614 超 Long——看驱动反应 */
    @Test
    void sumTwoMaxValues() throws Exception {
        probe("SELECT SUM(amount) FROM t WHERE id IN (1,3)");
    }

    private void probe(String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            System.out.println("[PROBE] " + sql
                    + " -> columnType=" + rs.getMetaData().getColumnType(1)
                    + " typeName=" + rs.getMetaData().getColumnTypeName(1)
                    + " string=" + rs.getString(1));
            try {
                long v = rs.getLong(1);
                System.out.println("[PROBE] getLong OK, value=" + v);
            } catch (SQLException | RuntimeException e) {
                System.out.println("[PROBE] getLong THROWS " + e.getClass().getName()
                        + ": " + e.getMessage());
            }
        }
    }
}
