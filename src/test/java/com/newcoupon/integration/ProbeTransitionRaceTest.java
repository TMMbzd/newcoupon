package com.newcoupon.integration;

import com.newcoupon.application.dto.SettlementHoldDtos.HoldPlaceRequest;
import com.newcoupon.application.dto.SettlementHoldDtos.HoldView;
import com.newcoupon.application.service.SettlementHoldService;
import com.newcoupon.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探针（审查用，跑完即删）：transition() 0 行分支用的是迁移前旧快照还是当前行？
 *
 * <p>强制交错（真实 MySQL 行锁，确定性）：
 * 赢家连接开事务做条件 UPDATE 但不提交（行锁在手，等价于"赢家的 UPDATE 已执行未提交"）；
 * 输家线程此时调 release()——SELECT 是一致性读，读到提交前的 ACTIVE；UPDATE 撞行锁阻塞；
 * 赢家提交后输家的 UPDATE 才求值 → 0 行。这正是并发同目标 release 的真实时序。
 */
@SpringBootTest(properties = {
        "coupon.issue.async-enabled=false",
        "coupon.issue.worker.enabled=false",
        "coupon.issue.gate-compensation.enabled=false"
})
class ProbeTransitionRaceTest extends ShardedIntegrationTestSupport {

    @DynamicPropertySource
    static void shardingProperties(DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private SettlementHoldService holdService;
    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("PROBE: 并发同目标 release，输家 0 行分支按旧快照 ACTIVE 分类还是重读当前行")
    void concurrentSameTargetRelease() throws Exception {
        String holdNo = "HOLD-PROBE-" + UUID.randomUUID().toString().substring(0, 8);
        HoldView placed = holdService.place(
                new HoldPlaceRequest(holdNo, "1", "M_PROBE", null, 100L, "并发探针", null));
        assertThat(placed.status()).isEqualTo("ACTIVE");

        // 赢家：事务内条件 UPDATE 改中 1 行，先不提交（行锁在手）
        Connection winner = dataSource.getConnection();
        winner.setAutoCommit(false);
        Statement st = winner.createStatement();
        int aRows = st.executeUpdate("UPDATE coupon_settlement_hold "
                + "SET status='RELEASED', released_by='WINNER', released_at=NOW() "
                + "WHERE hold_no='" + holdNo + "' AND status='ACTIVE'");
        assertThat(aRows).as("赢家条件 UPDATE 应改中 1 行（未提交，行锁在手）").isEqualTo(1);

        // 输家：并发同目标重试——SELECT 读 ACTIVE，UPDATE 阻塞在赢家行锁上
        AtomicReference<Throwable> loserEx = new AtomicReference<>();
        AtomicReference<HoldView> loserView = new AtomicReference<>();
        Thread loser = new Thread(() -> {
            try {
                loserView.set(holdService.release(holdNo));
            } catch (Throwable t) {
                loserEx.set(t);
            }
        }, "probe-loser");
        loser.start();
        Thread.sleep(800);   // 让输家完成 SELECT（读到 ACTIVE）并阻塞在 UPDATE
        winner.commit();     // 赢家提交——输家的 UPDATE 此刻才求值 → 0 行
        winner.close();
        loser.join(10_000);
        assertThat(loser.getState()).as("输家线程应已结束").isEqualTo(Thread.State.TERMINATED);

        String dbStatus = new JdbcTemplate(dataSource).queryForObject(
                "SELECT status FROM coupon_settlement_hold WHERE hold_no=?", String.class, holdNo);
        assertThat(dbStatus).as("库里最终 RELEASED（条件 UPDATE 的并发裁决本身正确）")
                .isEqualTo("RELEASED");

        System.out.println("=== PROBE RESULT ===");
        System.out.println("loserView=" + loserView.get());
        System.out.println("loserEx=" + loserEx.get());
        if (loserEx.get() instanceof BizException be) {
            System.out.println("PROBE BizException code=" + be.getErrorCode().getCode()
                    + " message=" + be.getMessage());
        }

        // 对照组：尘埃落定后的顺序重放（SELECT 读到 RELEASED → 走幂等成功）
        HoldView replay = holdService.release(holdNo);
        System.out.println("PROBE sequential replay -> status=" + replay.status()
                + " duplicated=" + replay.duplicated());
        assertThat(replay.status()).isEqualTo("RELEASED");
    }
}
