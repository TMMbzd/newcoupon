package com.newcoupon.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.infrastructure.persistence.mapper.AuditChainHeadMapper;
import com.newcoupon.infrastructure.persistence.mapper.AuditLogMapper;
import com.newcoupon.infrastructure.persistence.po.AuditLogPO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 操作审计服务（第 1 环 1-1 项——"动钱先审计"）。
 *
 * <p>职责：接收切面提交的审计上下文 → 计算哈希链 → 异步写入审计表。
 *
 * <p>三个设计决定：
 * <ul>
 *   <li><b>异步写入</b>：审计通过专用单线程执行器写入，不阻塞业务方法。
 *       写入失败只记 ERROR 日志（审计是可观测性设施，不是业务正确性的一部分——
 *       丢了审计记录可以通过哈希链的缺口发现，但不该让业务操作失败）。</li>
 *   <li><b>哈希链同步计算</b>："读最后一条的 hash → 算新 hash → 插入"这三步
 *       用 synchronized 保证原子性（单实例足够；多实例需换分布式锁或号段方案）。
 *       链断了 = 有记录被删除或跳号。</li>
 *   <li><b>computeHash 是静态方法</b>：测试可以直接调用来验证篡改（重算哈希与存储值比对）。</li>
 * </ul>
 *
 * <p>哈希链原理：每条记录的 record_hash = SHA-256(按行 hashVersion 选口径的字段拼接 + prev_hash)。
 * 篡改任何字段 → 重算的 hash 与存储值不匹配；删除任何记录 → 后续记录的 prev_hash 断链。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 首条记录的前驱哈希（创世值）——所有链的起点 */
    public static final String GENESIS_HASH = sha256("AUDIT_GENESIS");

    /**
     * 当前哈希算法版本（V6 2C）：新写入一律 v2——actor_type/actor_id/session_id 三列
     * 参与 record_hash 拼接（改 actor 即篡改，可被 verify 检出）。存量行是 v1
     * （列默认 1），v1 口径不含 actor 列且**永不重算**——重算历史哈希等于平台亲手
     * 毁掉"链上历史不可改"这个性质本身。
     */
    public static final int HASH_VERSION_CURRENT = 2;

    /** JSON 序列化器（静态——审计序列化不需要 Spring 配置的定制 mapper） */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 审计写入专用单线程执行器（守护线程——不阻止 JVM 关闭） */
    private final ExecutorService auditExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audit-writer");
        t.setDaemon(true);
        return t;
    });

    private final AuditLogMapper auditLogMapper;
    private final AuditChainHeadMapper auditChainHeadMapper;
    private final TransactionTemplate transactionTemplate;

    public AuditService(AuditLogMapper auditLogMapper,
                        AuditChainHeadMapper auditChainHeadMapper,
                        PlatformTransactionManager transactionManager) {
        this.auditLogMapper = auditLogMapper;
        this.auditChainHeadMapper = auditChainHeadMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 审计上下文：切面组装好六要素后传给本方法。
     *
     * @param appId         谁调用（签名认证得出的 appId）
     * @param userId        终端用户 ID（可为空——内部调用无用户）
     * @param traceId       链路追踪 ID
     * @param action        操作类型（如 COUPON_CALCULATE）
     * @param resourceType  资源类型（如 COUPON）
     * @param resourceId    资源标识（可为空）
     * @param beforeSnapshot 前值快照（JSON——操作前的状态或入参）
     * @param afterSnapshot  后值快照（JSON——操作后的状态或出参）
     * @param ip            来源 IP（可为空——非 HTTP 调用时无 IP）
     * @param resultCode    结果码（0=成功，非零=业务码或 500）
     */
    public record AuditContext(
            String appId, Long userId, String traceId,
            String action, String resourceType, String resourceId,
            String beforeSnapshot, String afterSnapshot,
            String ip, Integer resultCode,
            String actorType, String actorId, String sessionId, Integer hashVersion) {

        /**
         * 调用方入口（唯一受支持的构造方式）：hashVersion 由服务端钉死为当前版本——
         * 版本号的意义是"链上每行的算法可辨识"（校验按行选口径），调用方传错
         * （比如写 1，新行就不含 actor 三列地进哈希）比不传更糟，所以不给选择。
         */
        public static AuditContext of(String appId, Long userId, String traceId,
                                      String action, String resourceType, String resourceId,
                                      String beforeSnapshot, String afterSnapshot,
                                      String ip, Integer resultCode,
                                      String actorType, String actorId, String sessionId) {
            return new AuditContext(appId, userId, traceId, action, resourceType, resourceId,
                    beforeSnapshot, afterSnapshot, ip, resultCode,
                    actorType, actorId, sessionId, HASH_VERSION_CURRENT);
        }
    }

    /**
     * 记录一条审计（异步——提交后立即返回，不阻塞调用方）。
     * 写入失败只记 ERROR，不抛异常（审计不影响业务）。
     */
    public void record(AuditContext ctx) {
        auditExecutor.submit(() -> {
            try {
                writeRecord(ctx);
            } catch (Exception e) {
                log.error("AUDIT WRITE FAILED: action={} traceId={}（审计写入失败不影响业务，"
                        + "但哈希链可能出现缺口——需排查）", ctx.action(), ctx.traceId(), e);
            }
        });
    }

    /**
     * 写入审计：链头单行 FOR UPDATE 把“读最后 hash → 算新 hash → 插记录 → 更新链头”
     * 串行化到数据库，跨实例也连续；单实例的 synchronized 已移除。
     */
    private void writeRecord(AuditContext ctx) {
        transactionTemplate.executeWithoutResult(status -> {
            AuditLogPO po = new AuditLogPO();
            po.setAppId(ctx.appId() != null ? ctx.appId() : "SYSTEM");
            po.setUserId(ctx.userId());
            po.setTraceId(ctx.traceId() != null ? ctx.traceId() : "NO_TRACE");
            po.setAction(ctx.action());
            po.setResourceType(ctx.resourceType());
            po.setResourceId(ctx.resourceId());
            po.setBeforeSnapshot(ctx.beforeSnapshot());
            po.setAfterSnapshot(ctx.afterSnapshot());
            po.setIp(ctx.ip());
            po.setResultCode(ctx.resultCode() != null ? ctx.resultCode() : 0);
            // actor 三列与算法版本：版本只信服务端——canonical 构造器直接传 null 时
            // 也按当前版本落库（写 1 会让新行绕开 actor 列参与哈希的口径）
            po.setActorType(ctx.actorType());
            po.setActorId(ctx.actorId());
            po.setSessionId(ctx.sessionId());
            po.setHashVersion(ctx.hashVersion() != null ? ctx.hashVersion() : HASH_VERSION_CURRENT);
            // createdAt 先截到秒再进哈希：列是 DATETIME（秒精度），JDBC 落库会四舍五入掉
            // 纳秒——写时用带纳秒的值算哈希、验证时从库里读回秒值重算，永远对不上。
            // 截断让"写时算的"与"读回复算的"是同一个值（V6 2C 全链 verify 逼出的存量缺陷：
            // v1 时代无人做过全链重算，这个不可复现性一直潜伏）。
            po.setCreatedAt(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));

            String prev = auditChainHeadMapper.selectLastHashForUpdate();
            po.setPrevHash(prev == null ? GENESIS_HASH : prev);
            po.setRecordHash(computeHash(po));

            auditLogMapper.insert(po);
            auditChainHeadMapper.updateLastHash(po.getRecordHash());
            log.debug("AUDIT WRITTEN: id={} action={} actor={}/{} hash={}",
                    po.getId(), po.getAction(), po.getActorType(), po.getActorId(),
                    po.getRecordHash().substring(0, 12) + "…");
        });
    }

    /**
     * 计算一条审计记录的哈希（静态——测试可直接调用来验证篡改）。
     * 拼接规则：业务字段 + prev_hash，用 "|" 分隔，null 转空串，SHA-256 输出十六进制。
     * 版本口径按行的 hashVersion 选：null 或 1 = v1（不含 actor 三列——存量行的哈希
     * 就是按这个口径算的，换口径等于全部误报篡改）；2 = v2（actor 三列插在
     * createdAt 与 prev_hash 之间）。
     */
    public static String computeHash(AuditLogPO po) {
        boolean v2 = po.getHashVersion() != null && po.getHashVersion() >= 2;
        String[] fields = v2
                ? new String[]{
                        ns(po.getAppId()), ns(po.getUserId()), ns(po.getTraceId()),
                        ns(po.getAction()), ns(po.getResourceType()), ns(po.getResourceId()),
                        ns(po.getBeforeSnapshot()), ns(po.getAfterSnapshot()),
                        ns(po.getIp()), ns(po.getResultCode()), ns(po.getCreatedAt()),
                        ns(po.getActorType()), ns(po.getActorId()), ns(po.getSessionId()),
                        ns(po.getPrevHash())}
                : new String[]{
                        ns(po.getAppId()), ns(po.getUserId()), ns(po.getTraceId()),
                        ns(po.getAction()), ns(po.getResourceType()), ns(po.getResourceId()),
                        ns(po.getBeforeSnapshot()), ns(po.getAfterSnapshot()),
                        ns(po.getIp()), ns(po.getResultCode()), ns(po.getCreatedAt()),
                        ns(po.getPrevHash())};
        return sha256(String.join("|", fields));
    }

    /** 把任意对象序列化为 JSON 字符串（失败时退化为 toString——审计不能因序列化失败而丢） */
    public static String serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private static String ns(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
