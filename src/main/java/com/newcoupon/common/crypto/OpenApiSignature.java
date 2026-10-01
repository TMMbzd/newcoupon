package com.newcoupon.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 开放接口签名（S1 的核心协议，2026-10-01）。
 *
 * <p><b>待签串（canonical string）</b>——7 行，用 {@code \n} 连接，最后一行后<b>不加</b>换行：
 * <pre>
 *   {HTTP_METHOD}      大写，如 POST
 *   {CanonicalPath}    requestURI 原始值（不含 query，不做 URL 解码）
 *   {CanonicalQuery}   query 片段按字典序排序后用 &amp; 连接；无 query 则空行
 *   {BodySha256Hex}    请求体 SHA-256 小写十六进制；无 body = 空串的 SHA-256
 *   {appId}
 *   {timestamp}
 *   {nonce}
 * </pre>
 *
 * <p><b>四个"为什么"</b>：
 * <ol>
 *   <li><b>为什么签 body 的哈希而不是 body 本身</b>：① body 可能很大，HMAC 直接吃大输入浪费；
 *       ② body 里若含换行会与分隔符歧义。哈希把任意长度压成 64 字符定长，同时保住完整性语义
 *       （改一个字节哈希就变）。</li>
 *   <li><b>为什么 query 要排序</b>：两端拼接顺序必须<b>确定性</b>一致——Map 遍历顺序在语言间、
 *       版本间都不保证，排序是最简单的确定性方案。</li>
 *   <li><b>为什么要有 timestamp</b>：没有它，抓到一个合法请求就能<b>永久重放</b>；
 *       时间窗把重放的可用期压到 ±5 分钟。</li>
 *   <li><b>为什么有了 timestamp 还要 nonce</b>：时间窗内仍可重放（5 分钟能打一万次）。
 *       nonce 是"窗口内一次性的凭证"。两者<b>互补，不是二选一</b>。</li>
 * </ol>
 *
 * <p><b>为什么构造待签串时要拒绝含换行的字段</b>：否则攻击者可以在 appId/nonce 里塞 {@code \n}
 * 伪造出行结构（"签名串注入"，与 HTTP 头注入同族）。字段格式在入口已用白名单校验，
 * 这里是<b>第二道防线</b>——安全校验不做"上游已经查过了"的假设。
 */
public final class OpenApiSignature {

    /** 签名头前缀（Authorization 的开头） */
    public static final String SCHEME = "NC-HMAC-SHA256";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private OpenApiSignature() {
    }

    /**
     * 拼装待签串。
     *
     * @param method        大写 HTTP 方法
     * @param path          requestURI（不含 query）
     * @param canonicalQuery {@link #canonicalQuery} 的结果；无 query 传空串
     * @param bodySha256Hex {@link #bodySha256} 的结果
     * @throws IllegalArgumentException 任一字段含 CR/LF（签名串注入）
     */
    public static String canonicalString(String method, String path, String canonicalQuery,
                                         String bodySha256Hex, String appId,
                                         long timestamp, String nonce) {
        requireNoControlChars(method, "method");
        requireNoControlChars(path, "path");
        requireNoControlChars(canonicalQuery, "query");
        requireNoControlChars(appId, "appId");
        requireNoControlChars(nonce, "nonce");

        return method + "\n"
                + path + "\n"
                + canonicalQuery + "\n"
                + bodySha256Hex + "\n"
                + appId + "\n"
                + timestamp + "\n"
                + nonce;
    }

    /** 请求体的 SHA-256（小写十六进制）；{@code null} 或空体按空数组处理 */
    public static String bodySha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // JDK 必备算法，不可达
        }
    }

    /**
     * query 规范化：按 {@code &} 切分 → 片段字典序排序 → 重新连接。
     *
     * <p>为什么按<b>整个片段</b>排序而不是"先按 key 再按 value"：URL 里 {@code =} 的字典序小于
     * 任何字母/数字，所以 {@code k1=v} 与 {@code k2=v} 的大小关系由 key 决定；key 相同时自然落到
     * 比 value。两种表述在合法 query 上等价，而"整个片段排序"实现更短、更不容易写错。
     * <b>不做 URL 解码</b>——接入方按原始串参与签名，服务端也必须按原始串算。
     */
    public static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String p : rawQuery.split("&")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        parts.sort(Comparator.naturalOrder());
        return String.join("&", parts);
    }

    /** HMAC-SHA256(secret, canonical)，小写十六进制 */
    public static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * 验签：<b>恒定时间比较</b>。用 {@code String.equals} 会在第一个不同字符处短路返回，
     * 攻击者可以靠响应时间逐字节猜签名（时序侧信道）——与 AdminTokenFilter 同款处理。
     */
    public static boolean verify(String secret, String canonical, String providedSignature) {
        if (providedSignature == null) {
            return false;
        }
        byte[] expected = sign(secret, canonical).getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedSignature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    private static void requireNoControlChars(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("签名字段 " + field + " 不能为 null");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r') {
                throw new IllegalArgumentException("签名字段 " + field + " 含换行符（签名串注入）");
            }
        }
    }
}
