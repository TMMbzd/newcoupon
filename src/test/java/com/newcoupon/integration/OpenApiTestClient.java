package com.newcoupon.integration;

import com.newcoupon.common.crypto.OpenApiSignature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 开放面测试客户端（S1）：把"签名 + 发送 + 解包"收在一处，避免每个用例类各写一遍。
 *
 * <p><b>为什么不用 {@code TestRestTemplate}</b>：实测它在 <b>429 + {@code Retry-After}</b> 下
 * 会阻塞读并自动重试（重试还带着同一个 nonce，于是拿到的是 6005 而不是 429）——
 * 详见 {@code docs/S1-开放接入层-需求与设计-2026-10-01.md} §9-7。
 * JDK 的 {@link HttpClient} 没有这个问题，所以开放面用例统一用它。
 *
 * <p>它是**参考实现**：接入方拿任何语言写客户端时，这份"怎么拼待签串、怎么发"就是范例。
 */
final class OpenApiTestClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;

    OpenApiTestClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    record Response(int status, String body, Map<String, String> headers) {

        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    Response get(String path, String query, String appId, String secret) throws Exception {
        return send("GET", path, query, null, appId, secret, null);
    }

    /** 带额外请求头（如 {@code X-Forwarded-For}——用它模拟不同来源 IP） */
    Response get(String path, String query, String appId, String secret, String... extraHeaders) throws Exception {
        return send("GET", path, query, null, appId, secret, null, extraHeaders);
    }

    Response post(String path, String body, String appId, String secret, String clientToken) throws Exception {
        return send("POST", path, null, body, appId, secret, clientToken);
    }

    private Response send(String method, String path, String query, String body,
                          String appId, String secret, String clientToken, String... extraHeaders) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String bodySha = OpenApiSignature.bodySha256(
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        String canonical = OpenApiSignature.canonicalString(method, path,
                OpenApiSignature.canonicalQuery(query), bodySha, appId, Long.parseLong(timestamp), nonce);

        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(baseUrl + path + (query == null || query.isEmpty() ? "" : "?" + query)))
                .header("Authorization", OpenApiSignature.SCHEME + " Credential=" + appId
                        + ", Timestamp=" + timestamp
                        + ", Nonce=" + nonce
                        + ", Signature=" + OpenApiSignature.sign(secret, canonical))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));
        if (clientToken != null) {
            builder.header("X-Client-Token", clientToken);
        }
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            builder.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, String> headers = new java.util.HashMap<>();
        response.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(), v.isEmpty() ? null : v.get(0)));
        return new Response(response.statusCode(), response.body(), headers);
    }
}
