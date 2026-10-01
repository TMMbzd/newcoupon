package com.newcoupon.infrastructure.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.util.StreamUtils;

/**
 * 可重放的请求包装器（S1）：把 body 一次性读进内存，之后可以读任意多次。
 *
 * <p><b>为什么必须自己写一个</b>：Servlet 的 {@code InputStream} 是<b>一次性的</b>——
 * 验签要读 body 算哈希，读完就没了吗？那 Controller 的 {@code @RequestBody} 只能拿到空 body。
 * Spring 自带的 {@code ContentCachingRequestWrapper} <b>解决不了这个</b>：它只是把读过的内容
 * 顺手缓存一份，<b>并不会把流重置</b>——Filter 读完，Controller 再读就是空
 * （这是它最容易被误用的地方）。
 *
 * <p>所以这里一次性读完、之后每次 {@link #getInputStream()} 都返回一个新的
 * {@link ByteArrayInputStream}：<b>读多少次都一样</b>。
 *
 * <p>代价：body 常驻内存。所以它**只包 /open/** 的请求**（见 {@code OpenApiAuthFilter}），
 * 且开放面必须有请求体大小上限（Nginx/容器层限制，登记在接入文档）。
 */
class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.body = StreamUtils.copyToByteArray(request.getInputStream());
    }

    /** 供验签计算 body 哈希（原始字节，不做任何解码） */
    byte[] cachedBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // 同步读取，不需要异步回调
            }

            @Override
            public int read() {
                return in.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        Charset charset = getCharacterEncoding() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(getCharacterEncoding());
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
}
