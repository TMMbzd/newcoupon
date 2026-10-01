package com.newcoupon.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 有界幂等键（2026-09-30，L39 修复）。
 *
 * <p><b>问题</b>：业务键（事件号 {@code event_no}、占用单号 {@code usage_no}）由"前缀 + 业务字段"
 * 拼成，而业务字段本身允许较长（{@code requestId} ≤64、{@code orderNo} ≤64）——拼出来会超过列的
 * {@code VARCHAR(64)}。合法输入让 INSERT 报 {@code Data too long for column 'event_no'}，整个事务
 * 失败：用户被错误拒绝，而且抛的是未处理异常（不是业务码）。
 *
 * <p><b>做法</b>：短的**原样保留**，长的**折叠**成 {@code 前缀 + "H:" + 96 位摘要}。折叠必须是
 * **确定性**的（同样的输入永远得到同样的键）——否则"重放/补偿产生的重复事件靠唯一索引撞掉"这条
 * 幂等前提就失效了。摘要取 SHA-256 前 24 个十六进制字符（96 位）：十亿量级下碰撞概率约 1e-11。
 *
 * <p><b>为什么不扩列</b>：{@code usage_no} 分布在 192+ 张分表里，扩列是几百条 DDL；"键有界"是零
 * DDL 的修法，且对存量数据零影响（旧值本来就没超）。
 * <b>为什么不直接截断</b>：截断会让两个不同的长输入映射到同一个键——那是撞车，不是折叠。
 */
public final class BoundedKey {

    /** 列宽上限（{@code event_no} / {@code usage_no} 均为 VARCHAR(64)） */
    public static final int MAX_LEN = 64;

    /** 摘要取 24 个十六进制字符（96 位） */
    private static final int DIGEST_CHARS = 24;

    private BoundedKey() {
    }

    /**
     * 前缀 + 主体，超长则折叠。
     *
     * @param prefix 形如 {@code "RCV:"}——不参与折叠，折叠后它仍可读（便于排障时按类型筛选）
     * @param body   业务字段拼成的部分（可能很长）
     */
    public static String of(String prefix, String body) {
        String raw = prefix + body;
        if (raw.length() <= MAX_LEN) {
            return raw;
        }
        return prefix + "H:" + digest(body);
    }

    /**
     * 占用单号（占用单/核销三段的幂等锚点）：正常形态 {@code orderNo + ":" + couponId}
     * （订单号在前，便于人读）；超长时把**订单号部分**折叠成摘要、保留券号——
     * 券号是雪花 ID，天然唯一，保留它也让"这条占用单属于哪张券"一眼可见。
     */
    public static String usageNo(String orderNo, long couponId) {
        String raw = orderNo + ":" + couponId;
        if (raw.length() <= MAX_LEN) {
            return raw;
        }
        return "H:" + digest(orderNo) + ":" + couponId;
    }

    /** 确定性摘要：SHA-256 的前 24 个十六进制字符 */
    static String digest(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(DIGEST_CHARS);
            for (int i = 0; sb.length() < DIGEST_CHARS; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // JDK 必备算法，不可达
        }
    }
}
