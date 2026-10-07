package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.property.DynamicSentinelProperty;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * **JVM 级**的规则属性槽（2026-10-08，第 6 步全量测试暴露的问题）。
 *
 * <p><b>为什么需要它——一个只有"多上下文"才会暴露的坑</b>：
 * {@code FlowRuleManager} 的规则槽位是**JVM 级静态状态**：{@code register2Property}
 * 只能挂<b>一个</b>{@code SentinelProperty}，再注册一个会把**前一个的监听器摘掉**
 * （官方源码如此，我们也早在 {@link SentinelRuleConfig} 的注释里写过"多次注册互相顶掉"）。
 *
 * <p>生产里只有一个 Spring 上下文，所以这条规则一直没被触发。但**测试**（共享容器之后）
 * 同一个 JVM 里会有十几个上下文，每个上下文都构造一个 {@link TenantFlowRuleDataSource}
 * 并在启动时注册一次 → 除了最后一个，**其余数据源全部"脱挂"**：
 * 它们 {@code refreshNow()} 推的规则进了自己那个已经没人听的 property，
 * 而 {@code FlowRuleManager} 里是别的上下文最后一次推的内容。
 *
 * <p>症状实测（全量跑）：`SentinelRuleSourceTest` 报
 * "数据源读到 69 个租户配额，其中含本租户=true；当前规则总数=0"——
 * **数据源读得到、规则却是空的**，因为读的那个源根本不是挂着的那个。
 * 更阴的是它**取决于上下文的创建顺序**：单跑绿、全量红。
 *
 * <p><b>修法</b>：既然槽位是 JVM 级的，属性也做成 JVM 级的——全进程一个
 * {@link SharedRuleProperty}，{@link FlowRuleManager} 只注册它一次；
 * 每个数据源（每个上下文一个）都往它里面推（见 {@code TenantFlowRuleDataSource#getProperty}）。
 * 于是"谁推都算数"，与"谁先启动"无关。
 *
 * <p><b>为什么继承 {@code DynamicSentinelProperty} 而不是自己实现接口</b>：官方那个实现已经把
 * 监听器簿记做对了，其中最关键的一点是 {@code addListener} 会**立刻用当前值回调一次 configLoad**
 * ——{@code FlowRuleManager.register2Property} 正是靠它拿到初始规则的。自己写一遍很容易漏掉这半句，
 * 表现就是"注册之后规则一直是空的"。
 *
 * <p>顺带解决一个语义问题：{@code AutoRefreshDataSource} 的定时任务走的是
 * {@code getProperty()}（字节码核实：{@code invokevirtual getProperty → updateValue}），
 * 所以重写它之后，**周期刷新与主动推送两条路都进同一个槽位**。
 */
public final class SharedRuleProperty extends DynamicSentinelProperty<List<FlowRule>> {

    private static final Logger log = LoggerFactory.getLogger(SharedRuleProperty.class);

    /** 全进程唯一 */
    private static final SharedRuleProperty INSTANCE = new SharedRuleProperty();

    /** 只注册一次——第二次注册会把第一次的监听摘掉，那正是我们要避免的事 */
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private SharedRuleProperty() {
    }

    public static SharedRuleProperty instance() {
        return INSTANCE;
    }

    /**
     * 把本槽位挂到 {@code FlowRuleManager} 上——**只生效一次**（幂等）。
     *
     * <p>后到的调用直接返回：它想挂的是同一个对象，挂第二次只会白白摘一次监听。
     */
    public void registerOnce() {
        if (REGISTERED.compareAndSet(false, true)) {
            FlowRuleManager.register2Property(this);
            log.info("租户配额规则槽位已注册（JVM 级，只此一个）");
        }
    }
}
