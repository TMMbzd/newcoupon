package com.newcoupon.common.crypto;

import com.newcoupon.domain.open.OpenApp;
import java.util.regex.Pattern;

/**
 * 签名头解析（S1）：{@code Authorization: NC-HMAC-SHA256 Credential=..., Timestamp=..., Nonce=..., Signature=...}
 *
 * <p>单独成类而不是塞进 Filter 的理由：这是**协议层**的活（格式与白名单校验），
 * 与"怎么查应用、怎么连 Redis"无关——独立出来就能用单测把边界喂饱，
 * 不必为了一条格式校验去起一个容器。
 *
 * <p><b>每个字段都要过白名单</b>，不是"格式检查洁癖"：
 * <ul>
 *   <li>{@code appId} 会拼进 Redis key 与日志 → 放任任意字符就是 key 注入与日志伪造；</li>
 *   <li>{@code nonce} 同上（它直接是 Redis key 的一部分）；</li>
 *   <li>{@code signature} 限定 64 位十六进制 → 免得把一兆字节的"签名"拿去比对；</li>
 *   <li>{@code timestamp} 必须是数字，否则时间窗校验形同虚设。</li>
 * </ul>
 * 任何一项不过 → 整体判为"签名头格式错误"（6001），<b>不细说哪一项不对</b>：
 * 对攻击者少给信息，对合法接入方（他会照文档自查）也无损失。
 */
public record OpenApiAuthHeader(String appId, long timestamp, String nonce, String signature) {

    private static final Pattern CREDENTIAL = Pattern.compile(OpenApp.APP_ID_PATTERN);
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-f]{64}");

    /** @return 解析成功返回对象；格式不合法返回 {@code null}（调用方统一按 6001 处理） */
    public static OpenApiAuthHeader parse(String header) {
        if (header == null || !header.startsWith(OpenApiSignature.SCHEME + " ")) {
            return null;
        }
        String[] parts = header.substring(OpenApiSignature.SCHEME.length() + 1).split(",");

        String appId = null;
        String nonce = null;
        String signature = null;
        Long timestamp = null;
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            switch (key) {
                case "Credential" -> appId = value;
                case "Nonce" -> nonce = value;
                case "Signature" -> signature = value;
                case "Timestamp" -> {
                    try {
                        timestamp = Long.valueOf(value);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                default -> {
                    // 未知字段忽略：向前兼容（将来加 SignedHeaders 之类的字段时，老服务端不该拒新客户端）
                }
            }
        }
        if (appId == null || nonce == null || signature == null || timestamp == null) {
            return null;
        }
        if (!CREDENTIAL.matcher(appId).matches()
                || !NONCE.matcher(nonce).matches()
                || !SIGNATURE.matcher(signature).matches()) {
            return null;
        }
        return new OpenApiAuthHeader(appId, timestamp, nonce, signature);
    }
}
