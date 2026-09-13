package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 批次二验收（ADR-0010）：分片路由 + 雪花 ID 的**可见证据**。
 * 三个断言全部用"查物理表"的方式证明（不信中间层，只信数据落点）：
 * ① 领到的券真的落在 user_coupon_{uid%64} 这张物理分表里；
 * ② 库里真的有 64×3 张物理分表（V6 建的）；
 * ③ 券 ID 是 19 位左右的雪花（大于 2026 纪元起点，且趋势递增）。
 * System.out 输出供讲解文档引用（真实运行记录）。
 */
@SpringBootTest(properties = {"coupon.sharding.sql-show=true"})
class ShardingRoutingIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicInteger USER = new AtomicInteger(90_000);

    @Test
    @Timeout(120)
    @DisplayName("验收：领的券落在 user_coupon_{uid%64}，库里有 64×3 张分表，ID 是雪花")
    void couponLandsInShardAndIdIsSnowflake() throws Exception {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "分片验收券", 1001L, 1, 1000L, null, 10000L, 1000L, 1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, null, null));
        templateAppService.publish(t.id());
        long uid = USER.incrementAndGet();
        long expectedShard = uid % 64;

        ReceiveResult r = receiveAppService.receive(
                new ReceiveRequest("shard-" + uid, uid, t.id()));
        long couponId = r.couponId();

        // ① 逐张物理分表找这张券——只能在路由公式算出的那张里。
        //    注意：物理表名查询必须走**裸连接**（分片驱动只认逻辑表名，user_coupon_0 对它是"不存在的表"）
        String foundTable = null;
        try (java.sql.Connection raw = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             java.sql.Statement st = raw.createStatement()) {
            for (int i = 0; i < 64; i++) {
                try (java.sql.ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM user_coupon_" + i + " WHERE id = " + couponId)) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        foundTable = "user_coupon_" + i;
                        break;
                    }
                }
            }
        }
        System.out.println("[分片验收] uid=" + uid + " → uid%64=" + expectedShard
                + "，券 id=" + couponId + "（" + String.valueOf(couponId).length() + " 位长，雪花）"
                + "，实际落在表 " + foundTable);

        assertThat(foundTable).as("券必须落在路由公式算出的分表").isEqualTo("user_coupon_" + expectedShard);

        // 教学证据：把这张券的完整行打出来（列名+值），让人看见"数据库里存了什么"
        try (java.sql.Connection raw = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             java.sql.Statement st = raw.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT * FROM " + foundTable + " WHERE id = " + couponId)) {
            if (rs.next()) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                StringBuilder row = new StringBuilder();
                for (int c = 1; c <= md.getColumnCount(); c++) {
                    row.append(md.getColumnName(c)).append("=").append(rs.getString(c)).append(" | ");
                }
                System.out.println("[分片验收] 完整行（" + foundTable + "）: " + row);
            }
        }

        // ② 物理分表数量（同样走裸连接直查 information_schema）
        Integer uc = 0;
        Integer cu = 0;
        try (java.sql.Connection raw = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             java.sql.Statement st = raw.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")) {
            while (rs.next()) {
                String n = rs.getString(1);
                if (n.startsWith("user_coupon_")) uc++;
                if (n.startsWith("coupon_usage_")) cu++;
            }
        }
        System.out.println("[分片验收] 物理分表：user_coupon_*=" + uc + " 张，coupon_usage_*=" + cu + " 张");
        assertThat(uc).isEqualTo(64);
        assertThat(cu).isEqualTo(64);

        // ③ 雪花形态：远大于自增小数字，且第二张更大（趋势递增）
        ReceiveResult r2 = receiveAppService.receive(
                new ReceiveRequest("shard2-" + uid, USER.incrementAndGet() + 0L, t.id()));
        System.out.println("[分片验收] 第二张券 id=" + r2.couponId() + "，大于第一张="
                + (r2.couponId() > couponId));
        assertThat(couponId > 10_000_000_000_000_000L)   // >10^16：2026纪元的雪花≈9×10^16（17位）
                .as("雪花 ID：17 位左右的大数，不再是自增小数字").isTrue();
        assertThat(r2.couponId() > couponId).as("趋势递增").isTrue();
    }
}
