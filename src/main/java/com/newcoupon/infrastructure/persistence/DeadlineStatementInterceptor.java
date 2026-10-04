package com.newcoupon.infrastructure.persistence;

import com.newcoupon.common.context.RequestContext;
import java.sql.Statement;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL 侧的超时裁剪（S2 第 9 步）——**这是本阶段唯一能"真的把超时传下去"的地方**。
 *
 * <p><b>为什么只有 SQL 能做到</b>（逐条核实过）：MyBatis 的 {@code StatementHandler} 可以被拦截，
 * 而 {@code Statement.setQueryTimeout} 是真实存在的 per-statement 能力；
 * ShardingSphere-JDBC 的 {@code AbstractStatementAdapter.setQueryTimeout} 会把值 replay 到
 * **每一条物理语句**上；MySQL 驱动用 {@code KILL QUERY} 实现。
 * 相比之下 Redis 的单次调用超时在 Spring Data / Lettuce 上**根本不存在**（超时是连接工厂级的全局值），
 * 所以那边只能做"开工前拒绝"。
 *
 * <p><b>三个必须处理的坑</b>：
 * <ol>
 *   <li><b>JDBC 里 {@code setQueryTimeout(0)} 表示"无超时"</b>——剩余不足 1 秒时必须钳到 1，
 *       否则本意是收紧、实际是放开（写反了比不写更糟）；</li>
 *   <li>粒度是**整秒**：向上取整，宁可多给一点也不要少给；</li>
 *   <li>{@link RequestContext#hasDeadline()} 为假的场景（内部面、定时任务）**直接跳过**——
 *       对账、过期扫描、开桶这些任务要完整跑完，不能被裁剪掐断。</li>
 * </ol>
 *
 * <p><b>本拦截器只"设超时"，绝不抛异常</b>（这是实施期纠正的一个设计错误）：
 * 走到 SQL 执行说明操作**已经开始**，按本阶段的原则"只决定要不要开始、不打断已经开始的操作"，
 * 这里不该抛。况且拦截器抛出的异常会被 MyBatis 包装成 {@code MyBatisSystemException}——
 * 调用方拿到的就不是我们精心定义的业务码了（实测踩到）。
 * 预算的"要不要开始"由 {@code ReceiveAppService} 的准入门与写地板负责。
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query",
                args = {Statement.class, org.apache.ibatis.session.ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class})
})
@org.springframework.stereotype.Component
public class DeadlineStatementInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(DeadlineStatementInterceptor.class);

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        RequestContext ctx = RequestContext.current();
        if (!ctx.hasDeadline()) {
            return invocation.proceed();   // 内部面/定时任务：不裁
        }
        long remaining = ctx.remainingMs();
        // 向上取整并**钳到至少 1 秒**：JDBC 里 setQueryTimeout(0) 表示"无超时"，
        // 剩余不足 1 秒时若原样传下去，本意是收紧、实际是放开（写反比不写更糟）
        int seconds = (int) Math.max(1, (remaining + 999) / 1000);
        for (Object arg : invocation.getArgs()) {
            if (arg instanceof Statement statement) {
                try {
                    statement.setQueryTimeout(seconds);
                } catch (Exception e) {
                    // 某些驱动/包装器不支持：不因为"设不上超时"就让业务失败，但要有痕迹
                    log.debug("setQueryTimeout 不受支持，跳过裁剪: {}", e.toString());
                }
            }
        }
        return invocation.proceed();
    }
}
