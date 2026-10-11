package com.newcoupon.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>哈希链原理：每条记录的 record_hash = SHA-256(全部字段拼接 + prev_hash)。
 * 篡改任何字段 → 重算的 hash 与存储值不匹配；删除任何记录 → 后续记录的 prev_hash 断链。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 首条记录的前驱哈希（创世值）——所有链的起点 */
    public static final String GENESIS_HASH = sha256("AUDIT_GENESIS");

    /** JSON 序列化器（静态——审计序列化不需要 Spring 配置的定制 mapper） */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 审计写入专用单线程执行器（守护线程——不阻止 JVM 关闭） */
    private final ExecutorService auditExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audit-writer");
        t.setDaemon(true);
        return t;
    });

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
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
            String ip, Integer resultCode) {
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
     * 同步写入（synchronized 保证哈希链连续性）。
     * "读最后一条 hash → 算新 hash → 插入"三步必须原子。
     */
    private synchronized void writeRecord(AuditContext ctx) {
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
        po.setCreatedAt(LocalDateTime.now());
        po.setPrevHash(getLastHash());
        po.setRecordHash(computeHash(po));

        auditLogMapper.insert(po);
        log.debug("AUDIT WRITTEN: id={} action={} hash={}",
                po.getId(), po.getAction(), po.getRecordHash().substring(0, 12) + "…");
    }

    /** 读最后一条记录的 record_hash（无记录时返回创世哈希） */
    private String getLastHash() {
        AuditLogPO last = auditLogMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditLogPO>()
                        .orderByDesc(AuditLogPO::getId)
                        .last("LIMIT 1"));
        return last != null ? last.getRecordHash() : GENESIS_HASH;
    }

    /**
     * 计算一条审计记录的哈希（静态——测试可直接调用来验证篡改）。
     * 拼接规则：全部业务字段 + prev_hash，用 "|" 分隔，null 转空串，SHA-256 输出十六进制。
     */
    public static String computeHash(AuditLogPO po) {
        String canonical = String.join("|",
                ns(po.getAppId()),
                ns(po.getUserId()),
                ns(po.getTraceId()),
                ns(po.getAction()),
                ns(po.getResourceType()),
                ns(po.getResourceId()),
                ns(po.getBeforeSnapshot()),
                ns(po.getAfterSnapshot()),
                ns(po.getIp()),
                ns(po.getResultCode()),
                ns(po.getCreatedAt()),
                ns(po.getPrevHash()));
        return sha256(canonical);
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
