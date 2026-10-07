package com.newcoupon.application.service;

/**
 * 租户配置键词表（S2）——**显式登记**，与开关清单同一个理由：
 * 键值对的存储形态让"加一项配置"变成"插一行"，但**没有词表就没人知道有哪些配置项可用**。
 *
 * <p>每个键带三样：**键名**、**默认值**、**人话描述**。默认值是"读不到时用的那个值"，
 * 也是"这个配置项该填什么"的示例。默认值为 {@code null} 表示"这一项没有内置默认，
 * 由调用方决定回落策略"（例如每秒上限要回落登记表里的值）。
 *
 * <p><b>命名规范</b>：{@code 域.对象[.子项]}——与资源命名、开关命名共用同一套词汇，
 * 让"看到一个键就知道它管什么"。
 */
public enum TenantConfigKey implements ConfigKeySpec {

    /** 每秒上限；不配则回落到登记表 open_app.qps_limit（保持 S1 的既有入口） */
    RATE_LIMIT_QPS("rate_limit.qps", null, Type.INT, "每秒请求上限（0=不限；不配则用登记表的值）"),

    /** 每日上限；不配则回落到登记表 open_app.daily_quota */
    RATE_LIMIT_DAILY_QUOTA("rate_limit.daily_quota", null, Type.INT, "每日请求上限（0=不限；不配则用登记表的值）"),

    /** 冷启动预热时长（秒）：0 = 不预热 */
    RATE_LIMIT_WARMUP_SECONDS("rate_limit.warmup_seconds", "0", Type.INT, "冷启动预热时长（秒，0=不预热）"),

    /**
     * 未带 X-Timeout-Ms 时的默认时间预算（毫秒）——**下限取重试地板**。
     *
     * <p>下限不是随手定的：低于 {@code DeadlineGuard.RETRY_FLOOR_MS}（1200）的预算会让该租户
     * 每个请求都在"扣减成功 → 写地板补偿 → 拒绝"之间空转——成功率归零，还要白占一次 Redis 与取消。
     * 上限与请求头的上限（30000）一致，避免"走头"与"走配置"两条路径行为不一致。
     */
    TIMEOUT_DEFAULT_MS("timeout.default_ms", "3000", Type.INT, "默认时间预算（毫秒，1200~30000）", 1200, 30_000),

    /** 熔断的异常比例阈值（百分比） */
    CIRCUIT_BREAKER_FAILURE_RATE("circuit_breaker.failure_rate", "50", Type.INT, "熔断：异常比例阈值（%）"),

    /** 熔断的慢调用界线（毫秒） */
    CIRCUIT_BREAKER_SLOW_CALL_MS("circuit_breaker.slow_call_ms", "1000", Type.INT, "熔断：慢调用界线（毫秒）"),

    /** 熔断打开后保持多久才允许试探（秒） */
    CIRCUIT_BREAKER_OPEN_SECONDS("circuit_breaker.open_seconds", "30", Type.INT, "熔断：打开后保持时长（秒）");

    /** 配置值的类型——写入时据此校验（**写入严格、读取宽容**） */
    public enum Type { STRING, INT, BOOL }

    private static final int MAX_VALUE_LENGTH = 256;   // = 列宽

    private final String key;
    private final String defaultValue;
    private final Type type;
    private final String description;
    private final java.util.Set<String> allowedValues;
    private final Integer minValue;
    private final Integer maxValue;

    /** 枚举式取值（allowedValues 非空即校验取值必须在其中） */
    TenantConfigKey(String key, String defaultValue, Type type, String description, String... allowedValues) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.type = type;
        this.description = description;
        this.allowedValues = java.util.Set.of(allowedValues);
        this.minValue = null;
        this.maxValue = null;
    }

    /**
     * 数值型取值范围。
     *
     * <p><b>为什么要范围</b>：只校验"能不能解析成整数"是不够的——配成 0 或负数会让该租户每个请求
     * 立即过期（整片 5001），配成 10^9 又等于没有约束。**越界的值比写错格式更危险**，
     * 因为它看起来是合法的。
     */
    TenantConfigKey(String key, String defaultValue, Type type, String description, int minValue, int maxValue) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.type = type;
        this.description = description;
        this.allowedValues = java.util.Set.of();
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public Type type() {
        return type;
    }

    /** 按配置键名反查（写入时校验"这个键是否登记过"）；未登记返回 null */
    public static TenantConfigKey fromKey(String key) {
        for (TenantConfigKey k : values()) {
            if (k.key.equals(key)) {
                return k;
            }
        }
        return null;
    }

    /**
     * 校验一个配置值是否可接受，返回 null 表示通过，否则返回原因。
     *
     * <p><b>为什么写入要严格、读取要宽容</b>：写入时严格 → 运维当场知道自己写错了；
     * 读取时宽容 → 即使库里有坏数据（历史遗留、有人手工改库），服务也不受影响。
     * 反过来（写入宽容、读取严格）会让"你以为配生效了其实没有"成为常态。
     */
    public String validate(String value) {
        if (value == null) {
            return "配置值不能为 null（要删除请用删除接口）";
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            return "配置值最长 " + MAX_VALUE_LENGTH + " 字符";
        }
        switch (type) {
            case INT -> {
                int parsed;
                try {
                    parsed = Integer.parseInt(value.trim());
                } catch (NumberFormatException e) {
                    return "该配置项需要整数，收到：" + value;
                }
                if (minValue != null && (parsed < minValue || parsed > maxValue)) {
                    return "取值必须在 [" + minValue + ", " + maxValue + "] 之间，收到：" + value;
                }
            }
            case BOOL -> {
                if (!"true".equalsIgnoreCase(value.trim()) && !"false".equalsIgnoreCase(value.trim())) {
                    return "该配置项需要 true/false，收到：" + value;
                }
            }
            default -> { }
        }
        if (!allowedValues.isEmpty() && !allowedValues.contains(value.trim())) {
            return "取值必须是 " + allowedValues + " 之一，收到：" + value;
        }
        return null;
    }

    public String key() {
        return key;
    }

    /**
     * 存储键 = {@link #key()}（租户配置项的键名本来就是完整的，不像开关要加 {@code switch.} 前缀）。
     *
     * <p>这个方法的存在是为了与 {@link SwitchKey} 共用 {@link ConfigKeySpec} 契约——
     * 让写入校验能做**组合解析**，而不是维护两套并行的词表。
     */
    @Override
    public String configKey() {
        return key;
    }

    /** 读不到配置时用的值；{@code null} 表示"没有内置默认，由调用方决定" */
    public String defaultValue() {
        return defaultValue;
    }

    public String description() {
        return description;
    }
}
