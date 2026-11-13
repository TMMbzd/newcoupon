package com.newcoupon.infrastructure.tracing;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.opentelemetry.context.Context;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 自建线程池的上下文传播包装（V6 5B 生产化模拟刀 C2）。
 *
 * <p><b>解决什么问题</b>：trace 上下文存在 ThreadLocal 里。业务线程（HTTP 请求线程、
 * XXL/@Scheduled 调度线程）把任务提交给自建线程池（audit-writer / issue-task-worker）后，
 * 工作线程上"当前 span"是空的——不包装的话，异步段落的 span 会另起一条新 trace
 * （2026-11-12 实测：不包装时子 span traceId 与父不同，TracingAsyncPropagationIntegrationTest
 * 的红——正是这条契约的先红证据），Jaeger 上"这次领券的审计写完没有"就断了线。
 *
 * <p><b>为什么是两层包装</b>（实测逼出的结论，缺一不可）：
 * <ol>
 *   <li><b>OTel Context.wrap</b>（里层）：micrometer-tracing 的 OTel bridge 里
 *       {@code Tracer.nextSpan()} 从 OTel 自己的 ThreadLocal（{@code Context.current()}）
 *       找父 span。Boot 3.3 默认**没有**把 OTel Context 注册进 micrometer 的
 *       ThreadLocalAccessor——只靠 micrometer 快照，OTel 侧上下文不过线程，
 *       nextSpan 拿不到父（上面的红就是它）。OTel 的 {@code Context.current().wrap(task)}
 *       把 Context 随任务带到工作线程（进入时 makeCurrent、退出时还原），这是 OTel 官方
 *       的跨线程标准接法。</li>
 *   <li><b>Micrometer ContextSnapshot</b>（外层）：恢复当前 Observation（指标/观测上下文）
 *       与日志 MDC 桥接——@Observed/@Async 这类走 Observation 的代码在工作线程上才能挂回
 *       父观测。这一层是 Spring 6.1 {@code ContextPropagatingTaskDecorator} 的同款机制。</li>
 * </ol>
 *
 * <p><b>开销</b>：每次 submit 多两次快照（若干 ThreadLocal 读）；tracing 关闭时快照里
 * 没有 span 上下文，近零。与 Spring 官方"不适合海量极小任务"的提醒一致——本项目只包
 * 三个低频池（审计单线程/导出单线程/批量 worker 池），不碰热路径。
 *
 * <p><b>@Async/@Scheduled 不经这里</b>：Boot 自动配置的任务执行器挂同源传播；
 * {@code Executors.newXxx} 的自建池才是本类的服务对象。{@link ExecutorService} 的
 * 委托实现按需覆盖提交族方法（execute/submit/invokeAll/invokeAny）——每个提交点
 * 在<b>调用线程上</b>完成包装（这正是"捕获"必须在提交时做的原因），其余生命周期方法直通。
 */
public final class ContextPropagatingExecutors {

    private static final ContextSnapshotFactory SNAPSHOT_FACTORY =
            ContextSnapshotFactory.builder().build();

    private ContextPropagatingExecutors() {
    }

    /** 包装一个自建 ExecutorService：经它提交的每个任务都携带提交线程的观测/追踪上下文。 */
    public static ExecutorService wrap(ExecutorService delegate) {
        return new PropagatingExecutorService(delegate);
    }

    /** 在调用线程上捕获两层上下文并包进任务（提交时调用——捕获点错了整个机制就错了）。 */
    static Runnable wrapTask(Runnable task) {
        ContextSnapshot snapshot = SNAPSHOT_FACTORY.captureAll();
        return Context.current().wrap(snapshot.wrap(task));
    }

    static <V> Callable<V> wrapTask(Callable<V> task) {
        ContextSnapshot snapshot = SNAPSHOT_FACTORY.captureAll();
        return Context.current().wrap(snapshot.wrap(task));
    }

    /** 只重写提交族方法的委托 ExecutorService（生命周期方法直通被包装池）。 */
    private static final class PropagatingExecutorService implements ExecutorService {

        private final ExecutorService delegate;

        PropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(wrapTask(command));
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return delegate.submit(wrapTask(task));
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return delegate.submit(wrapTask(task), result);
        }

        @Override
        public Future<?> submit(Runnable task) {
            return delegate.submit(wrapTask(task));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            return delegate.invokeAll(wrapEach(tasks));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                             long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.invokeAll(wrapEach(tasks), timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws ExecutionException, InterruptedException {
            return delegate.invokeAny(wrapEach(tasks));
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                               long timeout, TimeUnit unit)
                throws ExecutionException, InterruptedException, TimeoutException {
            return delegate.invokeAny(wrapEach(tasks), timeout, unit);
        }

        private static <T> Collection<Callable<T>> wrapEach(Collection<? extends Callable<T>> tasks) {
            return tasks.stream().<Callable<T>>map(ContextPropagatingExecutors::wrapTask).toList();
        }

        // ---- 生命周期方法直通（包装语义与关闭/终止无关） ----

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
