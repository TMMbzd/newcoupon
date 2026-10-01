package com.newcoupon.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 请求上下文单测（S1 第 5 步，RQ-4/RQ-9）。
 *
 * <p>三条最要紧的语义：<b>无上下文时是 EMPTY 而不是 null</b>（调用方少写判空）、
 * <b>EMPTY 上的写入必须 no-op</b>（它是共享单例，被写脏就是全局污染）、
 * <b>clear 必须真的清掉</b>（线程池复用线程，不清就把上个请求的身份带给下个请求）。
 */
class RequestContextTest {

    @AfterEach
    void tearDown() {
        RequestContext.clear();   // 单测之间不互相污染
    }

    @Test
    @DisplayName("未设置上下文：current() 返回 EMPTY（不是 null），各字段为空")
    void withoutSet_returnsEmpty() {
        RequestContext ctx = RequestContext.current();

        assertThat(ctx).isNotNull();
        assertThat(ctx.appId()).isNull();
        assertThat(ctx.userId()).isNull();
        assertThat(ctx.resourceName()).isNull();
        assertThat(ctx.remainingMs()).as("无预算 = 永不到期").isEqualTo(Long.MAX_VALUE);
        assertThat(ctx.expired()).isFalse();
    }

    @Test
    @DisplayName("EMPTY 上的写入是 no-op——它是共享单例，写脏就是全局污染")
    void emptyContext_writesAreNoOp() {
        RequestContext empty = RequestContext.current();
        empty.user(123L);
        empty.resource("evil");
        empty.dimension("template", 1);

        assertThat(empty.userId()).isNull();
        assertThat(empty.resourceName()).isNull();
        assertThat(RequestContext.current().resourceName()).as("共享单例未被写脏").isNull();
    }

    @Test
    @DisplayName("资源名拼接：接口[:维度[:值]]，维度按加入顺序（保证同一资源每次拼出来一样）")
    void resourceName_joinsDimensionsInOrder() {
        RequestContext ctx = new RequestContext("demo-app", "trace-1", System.currentTimeMillis() + 1000, false);

        ctx.resource("coupons/receive");
        assertThat(ctx.resourceName()).isEqualTo("coupons/receive");

        ctx.dimension("template", 2001);
        assertThat(ctx.resourceName()).isEqualTo("coupons/receive:template:2001");

        ctx.dimension("user", 1001L);
        assertThat(ctx.resourceName())
                .as("后加的维度在后——限流/监控/开关共用这个 key，顺序必须确定")
                .isEqualTo("coupons/receive:template:2001:user:1001");
    }

    @Test
    @DisplayName("资源名兜底：注解没标时用路径推导的名字（resource 为 null 时不拼维度）")
    void resourceName_nullResource_staysNull() {
        RequestContext ctx = new RequestContext("demo-app", "trace-1", Long.MAX_VALUE, false);
        ctx.dimension("template", 2001);

        assertThat(ctx.resourceName()).isNull();
    }

    @Test
    @DisplayName("clear 必须真的清掉——线程池复用线程，不清就串味")
    void clear_removesContext() {
        RequestContext ctx = new RequestContext("demo-app", "trace-1", Long.MAX_VALUE, false);
        ctx.resource("coupons/receive");
        RequestContext.set(ctx);

        assertThat(RequestContext.current().appId()).isEqualTo("demo-app");

        RequestContext.clear();

        RequestContext after = RequestContext.current();
        assertThat(after.appId()).as("清完之后读到的必须是 EMPTY，不是上一个请求的身份").isNull();
        assertThat(after.resourceName()).isNull();
    }

    @Test
    @DisplayName("超时预算：remainingMs 单调减少，到点为 expired")
    void deadline_countsDown() {
        RequestContext ctx = new RequestContext("demo-app", "t", System.currentTimeMillis() + 200, true);

        assertThat(ctx.deadlineExplicit()).isTrue();
        assertThat(ctx.remainingMs()).isBetween(1L, 200L);
        assertThat(ctx.expired()).isFalse();

        RequestContext alreadyExpired = new RequestContext("demo-app", "t", System.currentTimeMillis() - 1, true);
        assertThat(alreadyExpired.expired()).isTrue();
        assertThat(alreadyExpired.remainingMs()).isNegative();
    }
}
