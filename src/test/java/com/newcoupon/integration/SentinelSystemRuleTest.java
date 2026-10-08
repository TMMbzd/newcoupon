package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 第 8 步：**系统自适应保护**（Sentinel {@code SystemRule}）——新增能力，契约是 503。
 *
 * <p><b>为什么这里用 qps=0 当判据</b>：它是这一层里**唯一确定性**的阈值
 * （{@code passQps + 1 > 0} 恒真，一发就拦）。maxThread / avgRt 都依赖"先有流量"，
 * 拿它们断言会变成"不可控的量"。生产上不该用 qps（与租户/接口维度重复且无归因），
 * 但**测试里它是最锋利的那把刀**——这一点写在 {@code SentinelSystemRules} 的注释里。
 *
 * <p><b>契约是 503 而不是 429</b>（第 8 步改）：429 是"你打太快"，503 是"服务现在不可用"。
 * 接入方对两者的处置完全不同（前者退避重试同一实例，后者认为整体不可用）。
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.risk.ip-rate.enabled=false",
                "coupon.sentinel.system.enabled=true",
                "coupon.sentinel.system.qps=0"
        })
class SentinelSystemRuleTest extends ShardedIntegrationTestSupport {

    private static final String RESOURCE = "/api/coupons/receive";

    @Autowired
    private TestRestTemplate rest;

    /**
     * 用完**必须清掉**（不是"恢复快照"）。
     *
     * <p>为什么快照/恢复在这里是错的：那条 `qps=0` 的阻塞规则是**本类的 Spring 上下文在启动时
     * 加载的**——快照拍到的就是它自己。于是"恢复"等于把它留着，而系统规则是 **JVM 级**状态、
     * 拦截器又挂在 `/**` 上 → **同一 JVM 里后面所有测试类的每一次请求都会 503**。
     * （第一版就是这么写的，全量一跑 16 条红、全是 `expected: 200 but was: 503`。）
     *
     * <p>实测教训：**自己打开的 JVM 级开关，必须自己关**；"恢复快照"只适用于
     * "我临时改了一个本来就有值的东西"，不适用于"我打开了一个默认关着的东西"。
     */
    @org.junit.jupiter.api.AfterAll
    static void clearSystemRules() {
        com.alibaba.csp.sentinel.slots.system.SystemRuleManager.loadRules(List.of());
    }

    @Test
    @DisplayName("系统过载：503 + 业务码 5000 + Retry-After + 说明是系统自适应（不是「你打太快」）")
    void systemOverload_returns503NotNon429() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var resp = rest.postForEntity(RESOURCE,
                new HttpEntity<>("{\"requestId\":\"sys-" + System.nanoTime()
                        + "\",\"userId\":1,\"templateId\":999999}", h), String.class);

        assertThat(resp.getStatusCode().value())
                .as("系统自适应触发时必须与限流区分开：429 会让接入方以为「退避一下就能重试成功」，"
                        + "而实际上这台实例已经扛不住了。实际响应=" + resp.getBody())
                .isEqualTo(503);
        assertThat(resp.getBody())
                .as("业务码 5000 = 系统繁忙（与 6006 请求超限区分开）").contains("5000");
        assertThat(resp.getBody())
                .as("要说清是哪个维度被限，否则接入方与运维只能猜").contains("系统自适应");
        assertThat(resp.getHeaders().getFirst("Retry-After"))
                .as("503 同样要告诉对方多久后重试").isNotNull();
    }
}
