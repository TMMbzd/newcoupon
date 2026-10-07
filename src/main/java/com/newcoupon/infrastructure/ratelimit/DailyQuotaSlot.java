package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * **日配额**检查（迁移第 5 步，2026-10-08）——**官方规则模型表达不了，所以自建**。
 *
 * <p><b>为什么官方做不到</b>（源码级，见设计文档 §3.1）：
 * {@code FlowRule} 的 grade 只有 QPS/线程数，QPS 的统计窗口是**秒级滑动**，没有任何日窗口表达能力；
 * 而 {@code TrafficShapingController} **不可插拔**（{@code FlowRule.setRater} 包私有、
 * {@code FlowRuleUtil.generateRater} 是 {@code private static}），所以也无法用自定义 Controller 扩展。
 * {@code ParamFlowRule.durationInSec} 虽然能配 86400，但它是"窗口内固定预算 + 到点补满、
 * **锚点漂移、非墙钟对齐**"——给出的是"从第一次请求起 24 小时"，**不是本项目的"自然日重置"**，
 * 也对不上 {@code Retry-After = 到明天的秒数}。
 *
 * <p><b>怎么挂上去</b>：实现官方 {@code ProcessorSlot} SPI，由
 * {@link OpenApiSlotChainBuilder} 追加到标准链的末尾，
 * 再通过 {@code META-INF/services} 注册（见 {@code resources/META-INF/services/}）。
 *
 * <p><b>SPI 带来的一个麻烦</b>：SPI 加载的类**不受 Spring 管理**，拿不到容器里的 bean
 * （我们需要 {@code StringRedisTemplate} 与租户配置）。解法是用一个静态持有者
 * （{@link #install}）由 Spring 启动时注入——见 {@link SentinelRuleConfig}。
 *
 * <p><b>为什么只对聚合资源生效</b>：日配额是"**按租户**"的（不是按接口），
 * 所以它挂在租户维度的聚合资源 {@link TenantFlowRuleMapper#OPEN_API_RESOURCE} 上。
 * 其余资源直接放行给下一个 slot——**这是本类第一件要做的事**
 * （Slot 是全局的，对每个资源都会执行）。
 */
public class DailyQuotaSlot extends AbstractLinkedProcessorSlot<Object> {

    private static final Logger log = LoggerFactory.getLogger(DailyQuotaSlot.class);

    /** 由 Spring 启动时注入的检查器（SPI 类不受容器管理，只能用静态桥接） */
    private static volatile DailyQuotaChecker checker;

    /** "检查器没装上"只报一次——它是接线错误，不是每请求都要刷的错误 */
    private static final java.util.concurrent.atomic.AtomicBoolean MISSING_CHECKER_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 安装实现——只应由 Spring 在启动时调用一次 */
    public static void install(DailyQuotaChecker impl) {
        checker = impl;
    }

    @Override
    public void entry(Context context, ResourceWrapper resource, Object param, int count,
                      boolean prioritized, Object... args) throws Throwable {
        // ① 只对聚合资源生效（Slot 是全链共享的，别的资源必须原样放行）
        if (TenantFlowRuleMapper.OPEN_API_RESOURCE.equals(resource.getName())) {
            DailyQuotaChecker impl = checker;
            if (impl == null && MISSING_CHECKER_LOGGED.compareAndSet(false, true)) {
                // 接线错误：SPI 加载的 Slot 拿到了 null 检查器 → 日配额会**静默失效**
                log.error("日配额检查器未安装（SentinelRuleConfig 的静态桥接没生效），日配额将静默失效");
            }
            if (impl != null) {
                String appId = context.getOrigin();   // 租户身份来自适配器的 originParser
                if (appId != null && !appId.isBlank()) {
                    // 直接抛（checker 内部抛的是 BlockException 的子类）。
                    // **不能改成"返回 boolean 再自己抛业务异常"**：那样会被 CtSph 的
                    // catch(Throwable) 当成 slot 内部错误吞掉并放行——见 DailyQuotaBlockException。
                    //
                    // （2026-10-08 第 6 步修：这里原先有一行 `log.warn("日配额拒绝：appId={}")`
                    //  写在 check() **之前**——那是第 5 步调试时留下的诊断日志，结果是
                    //  **每一个放行的请求都会打一条"拒绝"的 WARN**。按请求速率刷 WARN 日志
                    //  会把真正的告警淹掉；现在拒绝与故障的日志都在 checker 里、
                    //  只在真的发生时打。）
                    impl.check(appId);
                }
            }
        }
        // ② **必须继续往下走**，否则整条链断在这里（官方 SPI 的硬要求）
        fireEntry(context, resource, param, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resource, int count, Object... args) {
        fireExit(context, resource, count, args);
    }

    /**
     * 日配额检查的实现——由 Spring 提供（它需要 Redis 与租户配置）。
     *
     * <p>抽成接口是为了让本 Slot 只依赖"一个能检查的东西"，
     * 而不直接依赖 {@code StringRedisTemplate} 与配置服务——这样 Slot 本身可以单测。
     */
    public interface DailyQuotaChecker {

        /**
         * @throws DailyQuotaBlockException 超限时抛出。
         *         <b>必须是 {@code BlockException} 的子类</b>——抛业务异常会被 Sentinel 静默吞掉并放行。
         */
        void check(String appId) throws DailyQuotaBlockException;

        /** 该异常对应的错误码（供拒绝响应用） */
        ErrorCode errorCode();
    }
}
