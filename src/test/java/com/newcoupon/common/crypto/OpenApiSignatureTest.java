package com.newcoupon.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 签名协议单测（S1 第 3 步）。
 *
 * <p>第一条用例是<b>黄金用例</b>：它的期望值不是"跑一遍实现然后抄下来"，而是
 * 2026-10-01 用 {@code printf} + {@code sha256sum} + {@code openssl dgst -hmac} 独立算出来的，
 * 并写进了设计文档 §4.3。**算不出同样的值 = 实现错了，不是文档错了**——
 * 这份向量是"文档与代码不许漂移"的锚。
 */
class OpenApiSignatureTest {

    private static final String GOLDEN_BODY = "{\"requestId\":\"req-1\",\"userId\":1001,\"templateId\":2001}";
    private static final String GOLDEN_BODY_SHA =
            "8c4a865ff382d9a73dca622cd8151648183d79839b3ae132f9347caf5f618d38";
    private static final String GOLDEN_CANONICAL =
            "POST\n/open/v1/coupons/receive\n\n" + GOLDEN_BODY_SHA + "\ndemo-app\n1759280400000\nabc123";
    private static final String GOLDEN_SIGNATURE =
            "786b25f54b296c7d79cd97b757e92ecaddc299a0576c1260b4315f9f5c4aec67";

    @Test
    @DisplayName("黄金用例：body 哈希 / 待签串 / 签名三项与设计文档 §4.3 的独立实算值逐字节一致")
    void goldenVector_matchesDesignDoc() {
        String bodySha = OpenApiSignature.bodySha256(GOLDEN_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(bodySha).as("body SHA-256").isEqualTo(GOLDEN_BODY_SHA);

        String canonical = OpenApiSignature.canonicalString(
                "POST", "/open/v1/coupons/receive", "", bodySha, "demo-app", 1759280400000L, "abc123");
        assertThat(canonical).as("待签串（7 行，末尾无换行）").isEqualTo(GOLDEN_CANONICAL);

        assertThat(OpenApiSignature.sign("demo-secret-key", canonical))
                .as("HMAC-SHA256 签名").isEqualTo(GOLDEN_SIGNATURE);
    }

    @Test
    @DisplayName("无 body = 空串的 SHA-256（规则统一，不出现'空就是没有'的第二种约定）")
    void emptyBody_hashesEmptyString() {
        assertThat(OpenApiSignature.bodySha256(null))
                .isEqualTo(OpenApiSignature.bodySha256(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("query 规范化：按片段字典序排序，与书写顺序无关")
    void canonicalQuery_sortsDeterministically() {
        assertThat(OpenApiSignature.canonicalQuery("b=2&a=1&c=3")).isEqualTo("a=1&b=2&c=3");
        assertThat(OpenApiSignature.canonicalQuery("a=1&b=2&c=3")).isEqualTo("a=1&b=2&c=3");
        assertThat(OpenApiSignature.canonicalQuery("a=2&a=1")).as("同 key 按 value 排序").isEqualTo("a=1&a=2");
        assertThat(OpenApiSignature.canonicalQuery(null)).isEmpty();
        assertThat(OpenApiSignature.canonicalQuery("")).isEmpty();
        assertThat(OpenApiSignature.canonicalQuery("&&a=1&&")).as("忽略空片段").isEqualTo("a=1");
    }

    @Test
    @DisplayName("验签：正确签名通过；改一个字节、换密钥、换路径都失败")
    void verify_rejectsAnyTampering() {
        String canonical = GOLDEN_CANONICAL;

        assertThat(OpenApiSignature.verify("demo-secret-key", canonical, GOLDEN_SIGNATURE)).isTrue();
        assertThat(OpenApiSignature.verify("demo-secret-key", canonical, GOLDEN_SIGNATURE.toUpperCase()))
                .as("十六进制大小写不匹配也应当拒绝（约定是小写）")
                .isFalse();

        // 篡改：签名末位改一个字符
        String tampered = GOLDEN_SIGNATURE.substring(0, GOLDEN_SIGNATURE.length() - 1) + "0";
        assertThat(OpenApiSignature.verify("demo-secret-key", canonical, tampered)).isFalse();
        // 换密钥
        assertThat(OpenApiSignature.verify("other-secret", canonical, GOLDEN_SIGNATURE)).isFalse();
        // 换待签串（相当于改了请求内容）
        String otherCanonical = canonical.replace("/open/v1/coupons/receive", "/open/v1/coupons/lock");
        assertThat(OpenApiSignature.verify("demo-secret-key", otherCanonical, GOLDEN_SIGNATURE)).isFalse();
        // 空签名
        assertThat(OpenApiSignature.verify("demo-secret-key", canonical, null)).isFalse();
    }

    @Test
    @DisplayName("签名串注入防护：字段里塞换行直接拒绝")
    void canonicalString_rejectsNewlineInjection() {
        String sha = OpenApiSignature.bodySha256(new byte[0]);

        assertThatThrownBy(() -> OpenApiSignature.canonicalString(
                "POST", "/open/v1/x", "", sha, "evil\napp", 1L, "n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("签名串注入");
        assertThatThrownBy(() -> OpenApiSignature.canonicalString(
                "POST", "/open/v1/x", "", sha, "app", 1L, "nonce\r\nInjected: 1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
