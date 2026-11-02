package com.newcoupon.application.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 兑换码编解码器（V6 阶段 1B）：生成、规范化、形态校验、哈希——
 * 码的字符规则只在这一处定义，生成端与兑换端不会各自长出一套。
 *
 * <p><b>Crockford Base32 字母表</b>（0-9 A-Z 去 I/L/O/U，32 字符）：手工抄录最易混的
 * 四个字符被拿掉；输入侧再把 O→0、I/L→1 映射回来——用户看错/写错形状相近字符时
 * 规范化后仍是同一个码，而不是"码无效"。
 *
 * <p><b>明文与哈希</b>：DB 只存 {@code HMAC-SHA256(pepper, 规范化码)} 的 hex。
 * 用 HMAC 而不是裸 SHA-256：pepper 在服务端配置里（不随库泄漏），拖库后攻击者
 * 没有 pepper 就无法离线预算"生成一张码表撞库"。
 */
final class CouponCodeCodec {

    /** Crockford Base32：32 个字符（10 数字 + 22 字母，去 I/L/O/U）。 */
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** 手工码总长：15 随机字符 + 1 校验符。 */
    static final int MANUAL_LENGTH = 16;

    /** 二维码总长：26 随机字符（扫码无手误，不需要校验符；更长 alphabet 空间防穷举）。 */
    static final int QR_LENGTH = 26;

    private CouponCodeCodec() {
    }

    /**
     * 生成一个码明文。
     *
     * @param random    SecureRandom（系统熵源；禁 Random——可预测序列等于码全泄）
     * @param codeType  1=手工 16 字符（含校验符） 2=二维码 26 字符
     */
    static String generate(SecureRandom random, int codeType) {
        int length = codeType == 1 ? MANUAL_LENGTH : QR_LENGTH;
        // 末位是否留作校验符：手工码留（抄录手误在输入侧被校验符拦下），
        // 二维码不留（扫码不存在"抄错一位"）
        int bodyLength = codeType == 1 ? length - 1 : length;
        StringBuilder sb = new StringBuilder(length);
        int checkSum = 0;
        for (int i = 0; i < bodyLength; i++) {
            int index = random.nextInt(ALPHABET.length());
            sb.append(ALPHABET.charAt(index));
            // 位置加权（i+1 倍）：无权和是加法交换的——换位不改变和、校验符检不出，
            // 而"相邻两位抄反"恰是手工抄录的高频错。加权后换位必变和（除非两位同字符）
            checkSum += index * (i + 1);
        }
        if (codeType == 1) {
            // 校验符 = 位置加权和 mod 32：替换单字符与换位都会改变它——输错的码
            // 在校验符这关就被拦下，省一次 DB 查询
            sb.append(ALPHABET.charAt(Math.floorMod(checkSum, ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 规范化输入码：去首尾空白 → 大写 → O→0、I/L→1（Crockford 解码映射）。
     * 只做无损映射；映射后仍不在字母表内的字符留给 {@link #wellFormed} 判非法。
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(switch (c) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> c;
            });
        }
        return sb.toString();
    }

    /**
     * 形态校验：长度是 16（手工码，校验符必须吻合）或 26（二维码），
     * 且全部字符在 Crockford 字母表内。不合法=直接统一失败文案，不打 DB。
     */
    static boolean wellFormed(String normalized) {
        if (normalized.length() != MANUAL_LENGTH && normalized.length() != QR_LENGTH) {
            return false;
        }
        int checkSum = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int index = ALPHABET.indexOf(normalized.charAt(i));
            if (index < 0) {
                return false;
            }
            if (normalized.length() == MANUAL_LENGTH && i < MANUAL_LENGTH - 1) {
                checkSum += index * (i + 1);   // 与 generate 同款位置加权（换位敏感）
            }
        }
        if (normalized.length() == MANUAL_LENGTH) {
            // 校验符位（第 16 位）必须等于前 15 位位置加权和 mod 32
            return normalized.charAt(MANUAL_LENGTH - 1)
                    == ALPHABET.charAt(Math.floorMod(checkSum, ALPHABET.length()));
        }
        return true;
    }

    /**
     * 码 → 存储哈希：HMAC-SHA256(pepper, 规范化码) 的 hex（64 字符）。
     * 每次调用新建 Mac：Mac 非线程安全，而本方法会被 web 线程并发调用，
     * 一次 new 的开销远小于一把全局锁（兑换路径 QPS 低，不值得池化）。
     */
    static String hash(String normalizedCode, String pepper) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(normalizedCode.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 是 JDK 必配算法，init 不会失败——走到这里属环境损坏，直接失败
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
