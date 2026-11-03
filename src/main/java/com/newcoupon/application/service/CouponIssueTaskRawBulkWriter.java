package com.newcoupon.application.service;

import com.newcoupon.application.service.CouponIssueTaskBatchTxService.BatchItem;
import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.converter.CouponEventPOConverter;
import com.newcoupon.infrastructure.persistence.converter.UserCouponPOConverter;
import com.newcoupon.infrastructure.persistence.po.CouponEventPO;
import com.newcoupon.infrastructure.persistence.po.CouponExpireTaskPO;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskSuccessItem;
import com.newcoupon.infrastructure.persistence.po.CouponOutboxPO;
import com.newcoupon.infrastructure.persistence.po.UserCouponPO;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 异步 worker 的物理表批量写（裸 JDBC，不走 ShardingSphere 解析）。
 *
 * <p><b>为什么存在</b>：2026-10-23 本机对比——同样写 8000 行（2000 券+事件+outbox+过期任务），
 * 裸 MySQL 一条连接约 0.76 s；经 ShardingSphere 的批量 INSERT 约 4–5 s。worker 在领取任务后
 * 已经知道 `user_id % 64` 的物理表和库存分片，SQL 是确定的；中间件逐行解析/改写成为瓶颈。
 *
 * <p><b>边界</b>：只服务“同模板、无日限、批内用户不重复”的 worker 批量快路径。单任务路径、
 * 日限路径、任何批量失败后的重跑仍走 {@link CouponIssueTaskTxService} 的 ShardingSphere 事务；
 * 因此它不是绕过治理的唯一写入通道，而是一条可回退的加速通道。表名由 0..63/0..15 的整数
 * 拼出，不接受外部字符串。
 *
 * <p><b>正确性</b>：整批在一条裸连接的一个本地事务里：库存按分片条件扣减、券/事件/outbox/
 * 过期任务多行插入、任务状态逐条条件更新；任一步失败 rollback，调用方退回单任务路径重新裁决。
 */
@Service
public class CouponIssueTaskRawBulkWriter {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueTaskRawBulkWriter.class);
    private static final int USER_TABLE_COUNT = 64;
    private static final int EXPIRE_SCAN_SHARD_COUNT = 16;

    private static final String STOCK_SQL =
            "UPDATE coupon_stock_shard SET used = used + ?, version = version + 1 "
                    + "WHERE template_id = ? AND layout_version = ? AND shard_no = ? "
                    + "AND status = 0 AND used + ? <= quota";
    private final DataSource rawBulkDataSource;
    private final SnowflakeIdGenerator idGenerator;

    public CouponIssueTaskRawBulkWriter(
            @Lazy @Qualifier("rawBulkDataSource") DataSource rawBulkDataSource,
            SnowflakeIdGenerator idGenerator) {
        this.rawBulkDataSource = rawBulkDataSource;
        this.idGenerator = idGenerator;
    }

    public void write(List<BatchItem> items, String owner) {
        if (items.isEmpty()) {
            return;
        }
        CouponTemplate template = items.get(0).template();
        LocalDateTime now = LocalDateTime.now();
        try (Connection conn = rawBulkDataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long t0 = System.nanoTime();
                deductStock(conn, template.getId(), items);
                long t1 = System.nanoTime();
                Artifacts artifacts = build(items, template, now);
                long t2 = System.nanoTime();
                insertCoupons(conn, artifacts.couponsByUserShard);
                long t3 = System.nanoTime();
                insertExpireTasks(conn, artifacts.expiresByUserShard);
                long t4 = System.nanoTime();
                insertEvents(conn, artifacts.events);
                insertOutboxes(conn, artifacts.outboxes);
                long t5 = System.nanoTime();
                markSuccess(conn, artifacts.successItems, owner);
                long t6 = System.nanoTime();
                conn.commit();
                long t7 = System.nanoTime();
                log.debug("raw bulk issue committed: templateId={}, tasks={}, "
                                + "stock={}ms, build={}ms, coupons={}ms, expire={}ms, "
                                + "event=outbox={}ms, task={}ms, commit={}ms",
                        template.getId(), items.size(),
                        (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000,
                        (t3 - t2) / 1_000_000, (t4 - t3) / 1_000_000,
                        (t5 - t4) / 1_000_000, (t6 - t5) / 1_000_000, (t7 - t6) / 1_000_000);
            } catch (SQLException e) {
                rollback(conn, e);
                throw new IllegalStateException("raw bulk issue failed", e);
            } catch (RuntimeException e) {
                rollback(conn, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("raw bulk connection failed", e);
        }
    }

    private void deductStock(Connection conn, long templateId, List<BatchItem> items) throws SQLException {
        // 按 (layout_version, shard_no) 分组：正常批次同版本；若 reshard 恰好发生在
        // processor 读模板与裸写之间，旧版本行已被退役（status=1, quota=used），
        // 这里的条件更新会返回 0 行并触发回退到单任务路径（重读模板后按新版本执行）。
        Map<StockKey, Integer> counts = new TreeMap<>((a, b) -> a.layoutVersion() != b.layoutVersion()
                ? Integer.compare(a.layoutVersion(), b.layoutVersion())
                : Integer.compare(a.shardNo(), b.shardNo()));
        for (BatchItem item : items) {
            counts.merge(new StockKey(item.layoutVersion(), item.shardNo()), 1, (a, b) -> a + b);
        }
        try (PreparedStatement ps = conn.prepareStatement(STOCK_SQL)) {
            for (Map.Entry<StockKey, Integer> entry : counts.entrySet()) {
                ps.setInt(1, entry.getValue());
                ps.setLong(2, templateId);
                ps.setInt(3, entry.getKey().layoutVersion());
                ps.setInt(4, entry.getKey().shardNo());
                ps.setInt(5, entry.getValue());
                if (ps.executeUpdate() != 1) {
                    throw new StockShardDeductRejectedException(templateId);
                }
            }
        }
    }

    private record StockKey(int layoutVersion, int shardNo) {
    }

    private Artifacts build(List<BatchItem> items, CouponTemplate template, LocalDateTime now) {
        Map<Integer, List<UserCouponPO>> couponsByUserShard = new TreeMap<>();
        Map<Integer, List<CouponExpireTaskPO>> expiresByUserShard = new TreeMap<>();
        List<CouponEventPO> events = new ArrayList<>(items.size());
        List<CouponOutboxPO> outboxes = new ArrayList<>(items.size());
        List<CouponIssueTaskSuccessItem> successItems = new ArrayList<>(items.size());

        for (BatchItem item : items) {
            UserCoupon coupon = UserCoupon.issue(template, item.task().getUserId(),
                    item.task().getRequestId(), item.seq(), now).withId(idGenerator.next());
            int userShard = (int) Math.floorMod(item.task().getUserId(), USER_TABLE_COUNT);

            UserCouponPO couponPO = UserCouponPOConverter.toPO(coupon, coupon.getReceiveTime());
            couponsByUserShard.computeIfAbsent(userShard, k -> new ArrayList<>()).add(couponPO);

            // V43 事件归因：裸写批量路径同样标 ASYNC——与单任务路径（CouponIssueTaskTxService）
            // 口径一致，不能因为走了批量快车道就丢归因；appId/activityId 从模板行取快照。
            CouponEvent event = CouponEvent.issued(coupon, now, new CouponEvent.BizContext(
                    template.getAppId(), template.getActivityId(),
                    CouponEvent.BizContext.SOURCE_ASYNC, null, null));
            CouponEventPO eventPO = CouponEventPOConverter.toPO(event);
            eventPO.setId(idGenerator.next());
            events.add(eventPO);
            outboxes.add(CouponIssueArtifacts.issuedOutbox(event, coupon, now));

            CouponExpireTaskPO expire = new CouponExpireTaskPO();
            expire.setId(idGenerator.next());
            expire.setCouponId(coupon.getId());
            expire.setUserId(coupon.getUserId());
            expire.setShard((int) Math.floorMod(coupon.getId(), EXPIRE_SCAN_SHARD_COUNT));
            expire.setNextScanTime(coupon.getValidEnd());
            expire.setStatus(0);
            expire.setRetryCount(0);
            expiresByUserShard.computeIfAbsent(userShard, k -> new ArrayList<>()).add(expire);

            successItems.add(new CouponIssueTaskSuccessItem(item.task().getId(), coupon.getId()));
        }
        return new Artifacts(couponsByUserShard, expiresByUserShard, events, outboxes, successItems);
    }

    private void insertCoupons(Connection conn, Map<Integer, List<UserCouponPO>> groups) throws SQLException {
        String columns = "(id, user_id, template_id, template_code, face_value, threshold, discount_cap, "
                + "valid_end, status, receive_time, idempotent_key, seq, app_id)";
        for (Map.Entry<Integer, List<UserCouponPO>> entry : groups.entrySet()) {
            List<UserCouponPO> rows = entry.getValue();
            String sql = "INSERT INTO user_coupon_" + entry.getKey() + " " + columns
                    + " VALUES " + placeholders(rows.size(), 13);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int p = 1;
                for (UserCouponPO row : rows) {
                    p = setLong(ps, p, row.getId());
                    p = setLong(ps, p, row.getUserId());
                    p = setLong(ps, p, row.getTemplateId());
                    ps.setString(p++, row.getTemplateCode());
                    p = setLong(ps, p, row.getFaceValue());
                    p = setLong(ps, p, row.getThreshold());
                    if (row.getDiscountCap() == null) {
                        ps.setNull(p++, Types.BIGINT);
                    } else {
                        ps.setLong(p++, row.getDiscountCap());
                    }
                    ps.setTimestamp(p++, Timestamp.valueOf(row.getValidEnd()));
                    ps.setInt(p++, row.getStatus());
                    ps.setTimestamp(p++, Timestamp.valueOf(row.getReceiveTime()));
                    ps.setString(p++, row.getIdempotentKey());
                    ps.setInt(p++, row.getSeq());
                    ps.setString(p++, row.getAppId());
                }
                if (ps.executeUpdate() != rows.size()) {
                    throw new IllegalStateException("user_coupon batch insert mismatch: " + rows.size());
                }
            }
        }
    }

    private void insertExpireTasks(Connection conn, Map<Integer, List<CouponExpireTaskPO>> groups)
            throws SQLException {
        String columns = "(id, coupon_id, user_id, shard, next_scan_time, status, retry_count)";
        for (Map.Entry<Integer, List<CouponExpireTaskPO>> entry : groups.entrySet()) {
            List<CouponExpireTaskPO> rows = entry.getValue();
            String sql = "INSERT INTO coupon_expire_task_" + entry.getKey() + " " + columns
                    + " VALUES " + placeholders(rows.size(), 7);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int p = 1;
                for (CouponExpireTaskPO row : rows) {
                    p = setLong(ps, p, row.getId());
                    p = setLong(ps, p, row.getCouponId());
                    p = setLong(ps, p, row.getUserId());
                    ps.setInt(p++, row.getShard());
                    ps.setTimestamp(p++, Timestamp.valueOf(row.getNextScanTime()));
                    ps.setInt(p++, row.getStatus());
                    ps.setInt(p++, row.getRetryCount());
                }
                if (ps.executeUpdate() != rows.size()) {
                    throw new IllegalStateException("coupon_expire_task batch insert mismatch: " + rows.size());
                }
            }
        }
    }

    private void insertEvents(Connection conn, List<CouponEventPO> rows) throws SQLException {
        // V43：业务上下文五列进显式列清单（裸 SQL 不认 PO 注解，列必须手写全）。
        // 可空列按 setStringOrNull/setLongOrNull 落 NULL，与单条 insert 行为一致。
        String sql = "INSERT INTO coupon_event "
                + "(id, event_no, event_type, template_id, user_coupon_id, user_id, order_no, "
                + "amount, occur_time, app_id, activity_id, source, biz_type, biz_id) "
                + "VALUES " + placeholders(rows.size(), 14);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int p = 1;
            for (CouponEventPO row : rows) {
                p = setLong(ps, p, row.getId());
                ps.setString(p++, row.getEventNo());
                ps.setInt(p++, row.getEventType());
                p = setLong(ps, p, row.getTemplateId());
                p = setLong(ps, p, row.getUserCouponId());
                p = setLong(ps, p, row.getUserId());
                if (row.getOrderNo() == null) {
                    ps.setNull(p++, Types.VARCHAR);
                } else {
                    ps.setString(p++, row.getOrderNo());
                }
                if (row.getAmount() == null) {
                    ps.setNull(p++, Types.BIGINT);
                } else {
                    ps.setLong(p++, row.getAmount());
                }
                ps.setTimestamp(p++, Timestamp.valueOf(row.getOccurTime()));
                p = setStringOrNull(ps, p, row.getAppId());
                p = setLong(ps, p, row.getActivityId());
                p = setStringOrNull(ps, p, row.getSource());
                p = setStringOrNull(ps, p, row.getBizType());
                p = setStringOrNull(ps, p, row.getBizId());
            }
            if (ps.executeUpdate() != rows.size()) {
                throw new IllegalStateException("coupon_event batch insert mismatch: " + rows.size());
            }
        }
    }

    private void insertOutboxes(Connection conn, List<CouponOutboxPO> rows) throws SQLException {
        String sql = "INSERT INTO coupon_outbox "
                + "(event_no, event_type, payload, status, retry_count, next_scan_time) "
                + "VALUES " + placeholders(rows.size(), 6);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int p = 1;
            for (CouponOutboxPO row : rows) {
                ps.setString(p++, row.getEventNo());
                ps.setInt(p++, row.getEventType());
                ps.setString(p++, row.getPayload());
                ps.setInt(p++, row.getStatus());
                ps.setInt(p++, row.getRetryCount());
                ps.setTimestamp(p++, Timestamp.valueOf(row.getNextScanTime()));
            }
            if (ps.executeUpdate() != rows.size()) {
                throw new IllegalStateException("coupon_outbox batch insert mismatch: " + rows.size());
            }
        }
    }

    private void markSuccess(Connection conn, List<CouponIssueTaskSuccessItem> items, String owner)
            throws SQLException {
        // 一条 CASE 更新整组，而不是 2000 条 UPDATE 走 JDBC batch：实测 batch 约 1.5 s，
        // CASE 只发一条语句，MySQL 按主键 IN 更新，通常百毫秒级。
        StringBuilder sql = new StringBuilder(items.size() * 34 + 256)
                .append("UPDATE coupon_issue_task SET status = 2, coupon_id = CASE id ");
        for (int i = 0; i < items.size(); i++) {
            sql.append("WHEN ? THEN ? ");
        }
        sql.append("END, error_code = NULL, error_msg = NULL, lease_owner = NULL, lease_until = NULL, ")
                // 与单任务 markSuccess 一致：成功即"gate 已签发"，补偿任务永不释放（讨论 02 核对结论 P2）
                .append("gate_release_state = 1, gate_next_retry_time = NULL, ")
                .append("update_time = NOW(3) WHERE id IN (");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(") AND lease_owner = ? AND status = 1");

        int marked;
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int p = 1;
            for (CouponIssueTaskSuccessItem item : items) {
                p = setLong(ps, p, item.getId());
                p = setLong(ps, p, item.getCouponId());
            }
            for (CouponIssueTaskSuccessItem item : items) {
                p = setLong(ps, p, item.getId());
            }
            ps.setString(p, owner);
            marked = ps.executeUpdate();
        }
        if (marked != items.size()) {
            throw new IssueTaskLeaseLostException(items.get(0).getId(), owner);
        }
    }

    private static String placeholders(int rows, int columns) {
        StringBuilder sb = new StringBuilder(rows * (columns * 3 + 4));
        for (int r = 0; r < rows; r++) {
            if (r > 0) {
                sb.append(',');
            }
            sb.append('(');
            for (int c = 0; c < columns; c++) {
                if (c > 0) {
                    sb.append(',');
                }
                sb.append('?');
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private static void psetLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static int setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        psetLong(ps, index, value);
        return index + 1;
    }

    /** V43 归因列用：可空字符串落 NULL（psetLong 已有同款空安全，字符串这里补上）。 */
    private static int setStringOrNull(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
        return index + 1;
    }

    private static void rollback(Connection conn, Exception cause) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    private record Artifacts(
            Map<Integer, List<UserCouponPO>> couponsByUserShard,
            Map<Integer, List<CouponExpireTaskPO>> expiresByUserShard,
            List<CouponEventPO> events,
            List<CouponOutboxPO> outboxes,
            List<CouponIssueTaskSuccessItem> successItems) {
    }
}
