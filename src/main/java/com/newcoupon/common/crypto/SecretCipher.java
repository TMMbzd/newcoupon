package com.newcoupon.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 接入方密钥（SK）的加解密（S1，2026-10-01）。
 *
 * <p><b>为什么不能像密码一样只存哈希</b>：哈希是"验证"语义——服务端只需要判断"你输入的和原来的一致"；
 * 而 HMAC 验签是"<b>使用</b>"语义——服务端必须拿到<b>原密钥</b>去重算签名。需要用它，就必须能还原它，
 * 所以只能加密存储。<b>"能不能哈希"取决于语义，不取决于数据敏不敏感</b>——这是本类存在的全部理由。
 *
 * <p><b>算法</b>：AES-256-GCM。选 GCM 而不是 CBC 的理由：GCM 是<b>认证加密</b>（AEAD）——
 * 密文被篡改一个 bit，解密就抛异常，不需要额外挂一个 HMAC 去保完整性（CBC 需要，且挂错顺序
 * 会出 padding oracle 漏洞）。代价是 GCM 的 IV 绝不允许重复——所以每次加密都随机生成 12 字节 IV。
 *
 * <p><b>密文格式</b>：{@code Base64( IV(12) || ciphertext || tag(16) )}——IV 随密文一起存，
 * 它不是秘密（秘密是主密钥）；GCM 的 tag 已含在 {@code doFinal} 的输出尾部。
 *
 * <p><b>主密钥从哪来</b>：配置 {@code coupon.open.secret-master-key}（Base64 的 32 字节），
 * 生产用环境变量注入。**未配置直接启动失败**（fail-fast）——静默用一个内置默认密钥，
 * 等于"所有人都能解开你库里的 SK"，比启动失败危险得多。本地实现的边界写在文档里：
 * 生产应接 KMS（托管主密钥 + 轮换 + 审计），本项目不引入外部依赖。
 */
@Component
public class SecretCipher {

    /** 32 字节 = AES-256 */
    private static final int KEY_BYTES = 32;
    /** GCM 推荐 IV 长度（96 bit） */
    private static final int IV_BYTES = 12;
    /** 认证标签长度（bit） */
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(@Value("${coupon.open.secret-master-key:}") String masterKeyBase64) {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            throw new IllegalStateException(
                    "coupon.open.secret-master-key 未配置：接入方密钥无法加密存储。"
                            + "生产环境请通过环境变量 OPEN_SECRET_MASTER_KEY 注入（Base64 的 32 字节随机值），"
                            + "本地开发用 application-dev.yml 的默认值。");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(masterKeyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("coupon.open.secret-master-key 不是合法的 Base64 字符串", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "coupon.open.secret-master-key 长度必须为 " + KEY_BYTES + " 字节（Base64 解码后），实际 " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 加密：每次随机 IV，输出 Base64(IV||密文||tag) */
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            // 到不了这里：算法/密钥长度都是 JDK 必备能力，配置错误在构造期就拦了
            throw new IllegalStateException("SK 加密失败", e);
        }
    }

    /**
     * 解密。密文被篡改（GCM tag 校验失败）会抛 {@link IllegalStateException}——
     * 这正是认证加密的价值：**不会**返回一段"看起来正常但其实是垃圾"的明文。
     */
    public String decrypt(String cipherText) {
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("密文长度异常：无法容纳 IV");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("SK 解密失败（密文损坏、被篡改，或主密钥与加密时不一致）", e);
        }
    }

    /**
     * 生成一个新的 SK：32 字节密码学随机 → Base64 URL 安全编码（无填充）= 43 字符。
     *
     * <p>为什么不用 UUID：UUID v4 只有 122 位随机且格式固定，熵更低；密钥要的就是熵。
     * 为什么用 URL 安全编码：SK 会出现在脚本、配置文件、签名示例里，带 {@code +/} 的 Base64
     * 在 URL/命令行场景容易被转义或截断。
     */
    public static String newSecret() {
        byte[] raw = new byte[KEY_BYTES];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
