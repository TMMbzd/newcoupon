package com.newcoupon.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.common.context.RequestContext;
import java.sql.Statement;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SQL 侧裁剪的单测（S2 第 9 步）。
 *
 * <p>对抗性审查指出这个类**零覆盖**——"删掉整个类也没人发现"。它是本阶段唯一能把超时
 * 真正传到数据库的地方（Redis 那边做不到单次超时），必须有人守着。
 *
 * <p>三个最容易写反的点钉在这里：**无预算必须跳过**（否则内部面与定时任务的每条 SQL
 * 都会被设成 1 秒超时）、**剩余不足 1 秒必须钳到 1**（JDBC 里 0 表示"无超时"，写反比不写更糟）、
 * **拦截器只设超时不抛异常**（抛了会被 MyBatis 包装成 MyBatisSystemException，业务码就丢了）。
 */
class DeadlineStatementInterceptorTest {

    private final DeadlineStatementInterceptor interceptor = new DeadlineStatementInterceptor();

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private Invocation invocationWith(Statement statement) throws Exception {
        Invocation invocation = mock(Invocation.class);
        when(invocation.getArgs()).thenReturn(new Object[]{statement, null});
        when(invocation.proceed()).thenReturn("ok");
        return invocation;
    }

    @Test
    @DisplayName("无预算（内部面 / 定时任务）→ **一次都不设超时**，直接放行")
    void noDeadline_doesNotTouchStatement() throws Throwable {
        Statement statement = mock(Statement.class);

        Object result = interceptor.intercept(invocationWith(statement));

        assertThat(result).isEqualTo("ok");
        verify(statement, never()).setQueryTimeout(anyInt());
    }

    @Test
    @DisplayName("预算充足 → 按秒**向上取整**（1000ms → 1 秒、1001ms → 2 秒）")
    void ampleBudget_roundsUp() throws Throwable {
        Statement s1 = mock(Statement.class);
        RequestContext.set(new RequestContext("app", "t", System.currentTimeMillis() + 1_000, true));
        interceptor.intercept(invocationWith(s1));
        verify(s1).setQueryTimeout(1);

        Statement s2 = mock(Statement.class);
        RequestContext.set(new RequestContext("app", "t", System.currentTimeMillis() + 1_500, true));
        interceptor.intercept(invocationWith(s2));
        verify(s2).setQueryTimeout(2);
    }

    @Test
    @DisplayName("剩余不足 1 秒 → **钳到 1**，绝不传 0（JDBC 里 0 = 无超时，写反就是放开）")
    void tinyBudget_clampsToOne() throws Throwable {
        Statement statement = mock(Statement.class);
        RequestContext.set(new RequestContext("app", "t", System.currentTimeMillis() + 50, true));

        interceptor.intercept(invocationWith(statement));

        verify(statement).setQueryTimeout(1);
    }

    @Test
    @DisplayName("已过期（负剩余）也钳到 1，且**不抛异常**——要不要开始由准入门决定，这里只设超时")
    void expiredBudget_stillOnlySetsTimeout() throws Throwable {
        Statement statement = mock(Statement.class);
        RequestContext.set(new RequestContext("app", "t", System.currentTimeMillis() - 60_000, true));

        Object result = interceptor.intercept(invocationWith(statement));

        assertThat(result).as("放行而不是抛异常（抛了会被 MyBatis 包装，业务码就丢了）").isEqualTo("ok");
        verify(statement).setQueryTimeout(1);
    }
}
