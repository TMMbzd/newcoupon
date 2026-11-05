package com.newcoupon.infrastructure.risk;

/**
 * 风控透传属性的 ThreadLocal 持有者（V6 4A）——X-Device-Id 与客户端 IP 从
 * Filter 层透传到应用服务层的唯一通道。
 *
 * <p><b>为什么不塞进 RequestContext</b>：内部面（/api/**）根本没有真实上下文
 * （RequestContext.current() 返回 EMPTY 单例，所有 writer 是 no-op）——往它加
 * deviceId 字段在内部面永远写不进去；而 OpenApiAuthFilter 还会在验签后 new 一个
 * 新上下文，先写的字段会被覆盖。独立 ThreadLocal 对两个调用面行为一致，
 * 也避免为一个透传维度去动全站上下文的生命周期。
 *
 * <p><b>使用纪律</b>：只有 {@link RiskContextFilter} 一个写入者（try/finally 清理），
 * 业务代码只读；线程池复用线程，漏清会把上一个请求的设备号带给下一个请求
 * （与 RequestContext/TraceIdFilter 同一个经典事故，同一份防御）。
 */
public final class RiskRequestAttributes {

    private static final ThreadLocal<Attributes> HOLDER = new ThreadLocal<>();

    /** 一次请求的风控透传值；任一字段可为 null（=调用方没给，规则侧按因子缺失处理） */
    public record Attributes(String deviceId, String clientIp) {
    }

    private RiskRequestAttributes() {
    }

    /**
     * 写入。**全工程只允许 RiskContextFilter 一个调用者**（Java 没有 friend，
     * 用可见性挡不住跨包——靠注释与调用方纪律守住；误用会造成设备号串号）。
     * finally 里必须 clear。
     */
    public static void set(Attributes attributes) {
        HOLDER.set(attributes);
    }

    /** 同上：仅 RiskContextFilter 在 finally 调用 */
    public static void clear() {
        HOLDER.remove();
    }

    /** 当前请求的透传值；Filter 未覆盖的路径（定时任务/非 HTTP 调用）返回 null */
    public static Attributes current() {
        return HOLDER.get();
    }

    /** 当前设备号（可能 null——缺失时设备维度规则跳过并标 degraded，不挡请求） */
    public static String deviceId() {
        Attributes a = HOLDER.get();
        return a == null ? null : a.deviceId();
    }

    /** 当前客户端 IP（容器解析；与 RiskControlFilter.clientIp 同一提取规则） */
    public static String clientIp() {
        Attributes a = HOLDER.get();
        return a == null ? null : a.clientIp();
    }
}
