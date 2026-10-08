package com.newcoupon.infrastructure.web;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.CachedOpenAppReader;
import com.newcoupon.application.service.CachedOpenAppReader.AppAuth;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import com.newcoupon.infrastructure.ratelimit.BlockRejection;
import com.newcoupon.infrastructure.ratelimit.IpExactSlot;
import com.newcoupon.infrastructure.ratelimit.TenantFlowRuleMapper;
import com.newcoupon.common.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 接入方配额过滤器（S1 第 7 步；S3 限流迁移后成为 **Sentinel 的入口**）。
 *
 * <p><b>它现在做什么</b>：只拦 {@code /open/**}，按"**平台级 → 租户级**"的顺序判三个维度：
 *
 * <pre>
 *   IP      open_api_ip 资源 → 自建 IpExactSlot（Redis 精确计数，官方规则模型表达不了）
 *   接口    {路径模式} 资源 → Sentinel FlowRule(limitApp=default)（第 8 步从 Bucket4j 迁过来）
 *   租户    open_api   资源 → Sentinel FlowRule(limitApp=appId) + 自建 DailyQuotaSlot
 * </pre>
 *
 * <p><b>为什么平台级排在租户级前面</b>："自我保护优先于商业约定"。而且这不只是优先级问题——
 * 被 IP 维度拒掉的请求**不应该算进租户用量**：日配额是要对账的商业数字，
 * 把平台根本没服务的请求计进去，等于让攻击者替别人消耗配额。
 * （实现上靠"先 enter IP 专属资源"，见 {@link IpExactSlot} 的类注释。）
 *
 * <p><b>为什么是 appId 维度而不是 IP 维度</b>：IP 频控（{@code RiskControlFilter}）防的是
 * "没人管的脚本刷券"；appId 配额管的是"**已接入的合作方**用了多少"——两者的对象、依据与
 * 处置都不同（前者可以直接拒，后者要能对账、能提额、能通知对方）。同一个入口挂两道闸，
 * 各管各的。
 *
 * <p><b>fail-open</b>（与 {@code IpRateGate} 同款裁决）：Redis 故障时放行。理由也一样——
 * 配额是**可用性/公平性**防线，不是资损防线；Redis 挂时误杀全部接入方，代价大于短暂失控。
 * 对照：nonce 检查是安全防线，所以它 fail-closed。<b>同一个问题，两种结论，取决于它是哪一类防线。</b>
 *
 * <p><b>迁移留下的两条接线纪律</b>（都是踩出来的，见各方法的注释）：
 * ① Sentinel 的上下文（origin）**必须由调用链的最外一层的我们自己建立**——
 * 适配器是在 Interceptor 里建的，而本过滤器比它更早；
 * ② 手写 {@code SphU.entry} 的异常**到不了** {@code @RestControllerAdvice}——
 * Filter 的异常交给 Servlet 容器，所以拒绝响应必须在 Filter 内自己写（{@link BlockRejection}）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)   // 认证(+5) → 配额(+6) → 幂等(+7)
public class OpenApiQuotaFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiQuotaFilter.class);

    private static final String OPEN_PREFIX = "/open/";

    /** 这个调用链的名字（Sentinel 的 context name，与资源名不是一回事） */
    private static final String CONTEXT_NAME = "open-api";

    /** 当前请求的租户维度 Entry（ThreadLocal：Entry/exit 必须同线程配对） */
    private static final ThreadLocal<Entry> tenantDimensionEntry = new ThreadLocal<>();

    private final CachedOpenAppReader appReader;
    private final ObjectMapper objectMapper;
    private final GovernanceMetrics governanceMetrics;

    /** 集群模式（第 9 步）：决定租户维度的资源名怎么算（每租户一个资源名） */
    private final boolean clusterEnabled;

    public OpenApiQuotaFilter(CachedOpenAppReader appReader,
                              ObjectMapper objectMapper,
                              GovernanceMetrics governanceMetrics,
                              @Value("${coupon.sentinel.cluster.enabled:false}") boolean clusterEnabled) {
        this.appReader = appReader;
        this.objectMapper = objectMapper;
        this.governanceMetrics = governanceMetrics;
        this.clusterEnabled = clusterEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(OPEN_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String appId = RequestContext.current().appId();
        if (appId == null) {
            // 认证过滤器已拦下无身份请求；走到这里说明接线异常，放行（不静默吞请求）
            chain.doFilter(request, response);
            return;
        }
        Optional<AppAuth> found = appReader.find(appId);   // 命中本地缓存（认证时刚查过）
        if (found.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        // **一整个请求 = 一条 Sentinel 调用链**，所以上下文在这里建、且只建一次。
        //
        // origin（调用方身份）由 `ContextUtil.enter(name, origin)` 建立，而适配器是在
        // **Interceptor** 里做的——本过滤器比它更早（Filter 先于 Interceptor），
        // 那时上下文还没建，于是 Slot 看到的 origin 是空的。
        // 症状极具迷惑性：Slot 被调用了、资源名也对、checker 也在，**只有 origin 是空的**
        // （第 5 步就是这么红的：日配额计数键里 appId 是空串）。
        //
        // 在这里 enter 还有个好处：`ContextUtil.enter` 只在"调用链入口第一次"生效，
        // 所以随后适配器在 Interceptor 里再 enter 不会覆盖——**整条链共用同一个 origin**。
        ContextUtil.enter(CONTEXT_NAME, appId);
        try {
            // 平台级维度先检查（自我保护优先于商业约定），任一超限即拒
            if (!enterIpDimension(request, response)) {
                return;
            }
            // 接口维度已经交给 Sentinel 的规则（第 8 步迁走）——它现在和租户维度一样，
            // 是规则表里的一条 FlowRule，由适配器的埋点判，这里不需要再管。
            // 租户维度（QPS + 日配额）：**一次 entry 同时承载两者**
            //   · QPS 由 Sentinel 的 FlowRule(resource=open_api, limitApp=appId) 判
            //   · 日配额由处理链上的 DailyQuotaSlot 判
            // 为什么用聚合资源名而不是接口路径：租户配额的语义是"**该租户跨所有接口的总量**"，
            // 按接口各自限会让总量随接口数放大（详见 TenantFlowRuleMapper 的注释）。
            if (!enterTenantDimension(appId, response)) {
                return;
            }
            chain.doFilter(request, response);
        } finally {
            // **必须 exit**：Entry 与 exit 要同线程 LIFO 配对，漏了统计会错乱，
            // 而且 Tomcat 复用线程时会把上下文串给下一个请求（与 RequestContext 同一个道理）。
            exitDimensions(appId);
        }
    }

    /**
     * IP 维度：进入专属资源 {@link IpExactSlot#IP_GUARD_RESOURCE}，
     * 由自建 Slot 判、超限抛 {@code IpExactBlockException}。
     *
     * <p><b>为什么判据在 Slot 里而不在这里</b>：让"所有限流判定"都长在 Sentinel 的处理链上
     * （官方 SPI），拒绝也就自然汇入同一条异常出口——本方法只需负责"建立一次 Entry"
     * 与"把拒绝写成契约"。
     *
     * <p><b>为什么 entry 后立刻 exit</b>：这里要的是"**检查一次**"，不是"占着这个资源跑完请求"。
     * Sentinel 仍然会为它记通过的 QPS 与 RT（检查耗时），这正是我们要的观测口径。
     *
     * <p><b>IP 怎么传</b>：Sentinel 官方的参数通道——{@code args} 会原样传给
     * {@code ProcessorSlot.entry(..., Object... args)}（{@code ParamFlowSlot} 走的就是它）。
     * 位置契约见 {@link IpExactSlot#CLIENT_IP_ARG_INDEX}。
     *
     * <p><b>即使 IP 维度是关的，也会走这一次 entry</b>（开关只在 checker 里判）。
     * 代价是一次链查找（纳秒级）与一条统计；换来的是**"是否启用"只有一个真相**——
     * 在这里再判一次 {@code coupon.ratelimit.ip.enabled} 会让开关出现两个副本，
     * 而两个副本迟早会不一致（那种 bug 的表现是"配置明明关了却还在限"）。
     */
    private boolean enterIpDimension(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String ip = RiskControlFilter.clientIp(request);
        Entry entry = null;
        try {
            entry = SphU.entry(IpExactSlot.IP_GUARD_RESOURCE, EntryType.IN, 1, ip);
            return true;
        } catch (BlockException e) {
            // **必须在这里自己写响应**：这是 **Filter** 层，而 `@RestControllerAdvice`
            // 只处理 **Controller** 层抛出的异常。第一版把 BlockException 转成 BizException
            // 往外抛，结果响应是 **500**——Filter 的异常交给的是 Servlet 容器。
            writeRejection(response, e, IpExactSlot.IP_GUARD_RESOURCE);
            return false;
        } finally {
            // 被拒时 CtSph 已经替我们 exit 过了（源码：catch BlockException → e.exit(...) → rethrow），
            // 所以这里只能 exit 自己**成功拿到**的那个 Entry，否则是重复 exit（统计会错乱）。
            if (entry != null) {
                entry.exit();
            }
        }
    }

    /**
     * 进入租户维度的聚合资源（QPS 规则 + 日配额 Slot 都在这一次 entry 里判）。
     *
     * <p>上下文（origin）不在这里建——已经在 {@code doFilterInternal} 里建好了，
     * 见那里的注释（origin 为空是这次迁移最隐蔽的一个坑）。
     *
     * @return true = 放行；false = 已被拒（响应已写出）
     */
    private boolean enterTenantDimension(String appId, HttpServletResponse response) throws IOException {
        try {
            // 签名是 entry(name, EntryType, count, args...)——**没有** resourceType 参数的那个变体
            // 集群模式下租户在**资源名**里（集群不匹配 origin，见 resourceFor 的注释）；
            // 单机模式下仍是聚合资源 + limitApp=appId
            tenantDimensionEntry.set(SphU.entry(
                    TenantFlowRuleMapper.resourceFor(appId, clusterEnabled), EntryType.IN, 1, appId));
            return true;
        } catch (BlockException e) {
            // 被拒时 CtSph 内部已经 exit 过，ThreadLocal 不会被赋值 → finally 里不会重复 exit
            writeRejection(response, e, TenantFlowRuleMapper.resourceFor(appId, clusterEnabled));
            return false;
        }
    }

    /**
     * 把拒绝写成项目契约（429 + JSON + Retry-After + 维度）。
     *
     * <p>与 {@code OpenApiBlockExceptionHandler}（适配器埋点那条路）共用 {@link BlockRejection}
     * ——**两份实现迟早会漂移出两种 429**，所以映射规则只留一份。
     */
    private void writeRejection(HttpServletResponse response, BlockException e, String resourceName)
            throws IOException {
        BlockRejection.of(e, resourceName).writeTo(response, objectMapper, governanceMetrics, resourceName);
    }

    /** 与 {@code ContextUtil.enter} 配对：退出 Entry 与上下文（幂等，未建立时是空操作） */
    private void exitDimensions(String appId) {
        Entry entry = tenantDimensionEntry.get();
        if (entry != null) {
            entry.exit(1, appId);
            tenantDimensionEntry.remove();
        }
        // 不清会把 Context 留在 Tomcat 线程池的线程上，下一个请求会串味
        // ——与 RequestContext 必须 finally clear 是同一个道理。
        ContextUtil.exit();
    }
}
