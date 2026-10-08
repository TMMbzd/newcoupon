package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 系统自适应保护（迁移第 8 步，2026-10-08）——**实例自保的最后一层**，默认关。
 *
 * <p><b>它保护什么</b>：前面几层（租户配额 / 接口维度 / IP 维度）回答的都是"**谁**打太多"，
 * 这一层回答的是"**这台机器自己**是不是扛不住了"——判据读的是全局入口节点
 * （{@code Constants.ENTRY_NODE}）的实时 QPS、在途线程数、平均 RT。
 * 触发时走 {@link BlockRejection} 的 system 分支：业务码 5000 + **HTTP 503**
 * （不是 429——对客户端来说"服务不可用"和"你打太快"是两件事）。
 *
 * <p><b>哪些阈值该配、哪些不该配</b>（第 8 步调研的结论）：
 * <ul>
 *   <li><b>maxThread</b>：唯一"口径与应用自身瓶颈直接对应、不依赖宿主环境"的维度——
 *       Tomcat 线程池被打满就是真的过载。建议 ≈ 线程数 × 2 × 0.9（本项目一个请求会产生
 *       2 个 entry：接口维度 + 租户维度）。</li>
 *   <li><b>avgRt</b>：平均响应时间（1 秒窗口内**成功**请求的均值）。注意它被那些极快的
 *       自建 entry（如 IP 维度的检查）**稀释**，所以真实请求的 RT 大约是阈值的 1.5 倍。</li>
 *   <li><b>不配 qps</b>：它与租户/接口维度的规则重复，而且 ENTRY_NODE 的口径是"所有 entry"，
 *       没有归因——真出问题时看不出是谁打的。只在测试里用它做确定性断言。</li>
 *   <li><b>不配 highestSystemLoad / highestCpuUsage</b>：容器/共享宿主下这俩读到的可能是
 *       **邻居的负载**（{@code load1} 还是 Linux-only；取不到值时 Sentinel 会静默永不触发，
 *       只写它自己的日志）。要开的话必须先确认部署形态。</li>
 * </ul>
 *
 * <p><b>为什么默认关</b>：系统级阈值配错会在高峰**拒掉所有人**，而且没有归因、没有公平性——
 * 与项目里其它"会改变所有接入方可见行为"的开关同一个规矩（如
 * {@code coupon.ratelimit.resource.enabled}）。
 *
 * <p><b>一个必须避开的坑</b>（实测）：**绝不要构造一条"全部字段都是默认值 -1"的 SystemRule**——
 * 它过不了 {@code isValid}，而 {@code SystemRuleManager} 会把整个系统检查**静默关掉**
 * （不是只丢这一条）。所以这里的做法是"先攒出至少一项真实阈值，攒不出来就报错"。
 */
@Component
public class SentinelSystemRules {

    private static final Logger log = LoggerFactory.getLogger(SentinelSystemRules.class);

    public SentinelSystemRules(
            @Value("${coupon.sentinel.system.enabled:false}") boolean enabled,
            @Value("${coupon.sentinel.system.max-thread:360}") long maxThread,
            @Value("${coupon.sentinel.system.avg-rt-ms:1000}") long avgRtMs,
            @Value("${coupon.sentinel.system.qps:-1}") double qps) {
        if (!enabled) {
            log.info("系统自适应规则未启用（默认关）：它会在高峰拒掉所有人且没有归因，"
                    + "开启前请先按 docs/S3 §10.7 标定阈值");
            return;
        }

        SystemRule rule = new SystemRule();
        boolean configured = false;
        if (maxThread > 0) {
            rule.setMaxThread(maxThread);
            configured = true;
        }
        if (avgRtMs > 0) {
            rule.setAvgRt(avgRtMs);
            configured = true;
        }
        if (qps >= 0) {
            // qps 的合法下限就是 0（0 = "总 QPS 超过 0 就拒"，测试里用它做确定性断言）
            rule.setQps(qps);
            configured = true;
        }
        if (!configured) {
            // 一条"什么都没配"的规则会被 SystemRuleManager 当成非法并**静默关掉整套系统检查**，
            // 比不加载它更糟（那会让"本来该拦的"也不拦）。所以宁可启动失败。
            throw new IllegalStateException(
                    "coupon.sentinel.system.enabled=true 但一个阈值都没配"
                            + "（max-thread / avg-rt-ms / qps 全是关的）："
                            + "加载一条空规则会把整套系统检查静默关掉，这是比不加载更糟的状态");
        }

        SystemRuleManager.loadRules(List.of(rule));
        log.warn("系统自适应规则已加载：maxThread={} avgRtMs={} qps={}——"
                        + "触发时对外是 503 + 业务码 5000（系统繁忙），请确保面板上有这条告警",
                maxThread, avgRtMs, qps);
    }
}
