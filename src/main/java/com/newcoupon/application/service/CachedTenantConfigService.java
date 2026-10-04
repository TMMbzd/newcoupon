package com.newcoupon.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.cache.CacheTtl;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppConfigLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppConfigMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppConfigLogPO;
import com.newcoupon.infrastructure.persistence.po.OpenAppConfigPO;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 租户策略配置的读取实现（S2 第 1 步）——带缓存，缓存策略与接入方认证缓存同款
 * （防击穿 / 负缓存 / TTL 抖动 + 主动失效）。
 *
 * <p><b>为什么缓存"整个租户的配置映射"而不是单条</b>：一次回源把该租户的全部配置取回来，
 * 避免"读一项查一次库"。上层的读取频率远高于配置变更频率，一次回源摊薄到很多次读。
 *
 * <p><b>负缓存为什么不用 Optional</b>：这里"没有配置"不是一个异常状态，而是一个正常状态
 * （绝大多数租户都用默认值）。用一个空 Map 表达即可，读取方不需要额外处理包装类型。
 *
 * <p><b>三条不可让步的规则</b>（与接口注释呼应）：
 * ① 未配置 → 默认值；② 解析失败 → 默认值 + WARN，绝不抛异常；
 * ③ 无默认值的键 → {@link #get} 返回 null、{@link #getInt} 返回 0（"不设限"是安全的默认方向——
 * 配置读不到时把用户全拒了，比放开更糟）。
 */
@Service
public class CachedTenantConfigService implements TenantConfigService {

    private static final Logger log = LoggerFactory.getLogger(CachedTenantConfigService.class);

    private static final long MAX_ENTRIES = 10_000;

    private final OpenAppConfigMapper mapper;
    private final OpenAppConfigLogMapper logMapper;
    private final OpenAppRepository openAppRepository;
    private final long ttlMillis;
    private final long refreshMillis;

    /** 值：该租户的 配置键 → 配置值；没有配置 = 空 Map（负缓存） */
    private final LoadingCache<String, Map<String, String>> cache;

    public CachedTenantConfigService(OpenAppConfigMapper mapper,
                                     OpenAppConfigLogMapper logMapper,
                                     OpenAppRepository openAppRepository,
                                     @Value("${coupon.tenant-config.ttl-ms:60000}") long ttlMillis,
                                     @Value("${coupon.tenant-config.refresh-ms:30000}") long refreshMillis) {
        this.mapper = mapper;
        this.logMapper = logMapper;
        this.openAppRepository = openAppRepository;
        this.ttlMillis = ttlMillis;
        this.refreshMillis = refreshMillis;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .refreshAfterWrite(refreshMillis, TimeUnit.MILLISECONDS)   // 防击穿：越线后单人回源，余者取旧值
                .recordStats()
                .expireAfter(new Expiry<String, Map<String, String>>() {
                    @Override
                    public long expireAfterCreate(String key, Map<String, String> value, long now) {
                        // 返回的是**持续时长**（带 ±20% 防雪崩抖动），不是绝对时刻——
                        // 这里曾写成 now + 时长，导致条目实际存活 = JVM 已运行时长 + TTL（见 CacheTtl 注释）
                        return CacheTtl.jitteredNanos(ttlMillis);
                    }

                    @Override
                    public long expireAfterUpdate(String key, Map<String, String> value,
                                                  long now, long previousDuration) {
                        return CacheTtl.jitteredNanos(ttlMillis);
                    }

                    @Override
                    public long expireAfterRead(String key, Map<String, String> value,
                                                long now, long currentDuration) {
                        return currentDuration;   // 读不续命
                    }
                })
                .build(this::load);
    }

    @Override
    public String get(String appId, TenantConfigKey key) {
        if (appId == null || appId.isBlank()) {
            return key.defaultValue();
        }
        Map<String, String> configs = cache.get(appId);
        String value = configs == null ? null : configs.get(key.key());
        if (value == null || value.isBlank()) {
            return key.defaultValue();
        }
        return value;
    }

    @Override
    public int getInt(String appId, TenantConfigKey key) {
        String raw = get(appId, key);
        if (raw == null) {
            // 该键没有内置默认值（如"每秒上限"要回落登记表）：返回 0。
            // 选 0 而不是抛异常，是因为在限流语义里 0 = 不设限——配置读不到时"放开"比"全拒"安全
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("租户配置解析失败，回落默认值：appId={} key={} value={}", appId, key.key(), raw);
            return key.defaultValue() == null ? 0 : Integer.parseInt(key.defaultValue());
        }
    }

    @Override
    public boolean getBool(String appId, TenantConfigKey key) {
        String raw = get(appId, key);
        if (raw == null) {
            return false;
        }
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        log.warn("租户配置不是布尔值，回落默认：appId={} key={} value={}", appId, key.key(), raw);
        return "true".equalsIgnoreCase(key.defaultValue());
    }

    @Override
    public Map<String, String> allOf(String appId) {
        Map<String, String> configs = cache.get(appId);
        return configs == null ? Map.of() : Map.copyOf(configs);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void put(String appId, Map<String, String> values, String operator) {
        requireApp(appId);
        if (values == null || values.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "配置内容不能为空");
        }
        // ① 先全部校验：批量写入要么全成、要么全不成（不做部分写入）
        for (Map.Entry<String, String> e : values.entrySet()) {
            ConfigKeySpec key = resolveKey(e.getKey());
            if (key == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "未登记的配置键：" + e.getKey());
            }
            String reason = key.validate(e.getValue());
            if (reason != null) {
                throw new BizException(ErrorCode.PARAM_ERROR, e.getKey() + "：" + reason);
            }
        }
        // ② 再逐键 upsert + 记审计
        for (Map.Entry<String, String> e : values.entrySet()) {
            String old = currentValue(appId, e.getKey());
            upsert(appId, e.getKey(), e.getValue());
            writeLog(appId, e.getKey(), old, e.getValue(), operator);
        }
        invalidate(appId);   // ③ 改完立刻失效缓存（本实例马上生效，不等 30 秒刷新）
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void remove(String appId, String key, String operator) {
        requireApp(appId);
        if (resolveKey(key) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未登记的配置键：" + key);
        }
        String old = currentValue(appId, key);
        mapper.delete(new QueryWrapper<OpenAppConfigPO>().eq("app_id", appId).eq("config_key", key));
        writeLog(appId, key, old, null, operator);
        invalidate(appId);
    }

    @Override
    public void invalidate(String appId) {
        cache.invalidate(appId);
        log.info("租户配置缓存已失效：appId={}", appId);
    }

    /** 直读一项（绕过缓存）：写入前取旧值，用于审计 */
    private String currentValue(String appId, String key) {
        OpenAppConfigPO po = mapper.selectOne(new QueryWrapper<OpenAppConfigPO>()
                .eq("app_id", appId).eq("config_key", key));
        return po == null ? null : po.getConfigValue();
    }

    /** 有则更新、无则插入（**upsert**——直接 insert 会在改第二遍时撞唯一索引） */
    private void upsert(String appId, String key, String value) {
        OpenAppConfigPO existing = mapper.selectOne(new QueryWrapper<OpenAppConfigPO>()
                .eq("app_id", appId).eq("config_key", key));
        if (existing == null) {
            OpenAppConfigPO po = new OpenAppConfigPO();
            po.setAppId(appId);
            po.setConfigKey(key);
            po.setConfigValue(value);
            mapper.insert(po);
        } else {
            existing.setConfigValue(value);
            mapper.updateById(existing);
        }
    }

    /** 变更审计：**谁在什么时候把哪一项从什么改成了什么**（配置改错了就是一次线上事故，必须可追溯） */
    private void writeLog(String appId, String key, String oldValue, String newValue, String operator) {
        OpenAppConfigLogPO log = new OpenAppConfigLogPO();
        log.setAppId(appId);
        log.setConfigKey(key);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        log.setOperator(operator == null || operator.isBlank() ? "unknown" : operator);
        logMapper.insert(log);
    }

    /** 配置必须挂在一个真实存在的租户上，否则迟早变成没人认领的孤儿行 */
    private void requireApp(String appId) {
        if (appId == null || appId.isBlank() || openAppRepository.findApp(appId).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "应用不存在：" + appId);
        }
    }

    private Map<String, String> load(String appId) {
        Map<String, String> result = new HashMap<>();
        try {
            for (OpenAppConfigPO po : mapper.selectList(
                    new QueryWrapper<OpenAppConfigPO>().eq("app_id", appId))) {
                result.put(po.getConfigKey(), po.getConfigValue());
            }
        } catch (RuntimeException e) {
            // 回源失败 → 当作「该租户没有任何配置」（全走默认值），**不让 DB 抖动穿到调用方**。
            //
            // 这是本接口三条不可让步规则的第 1 条（读不到就用默认值）在故障场景下的兑现：
            // 没有兜底时，DB 抖动 + 缓存硬过期会让每一次读取都抛异常——而开关判断
            // 挂在**全路径拦截器**上，等于把所有接口一起打挂。**保命组件不能自己变成故障放大器。**
            //
            // 注意这里是"返回空"而不是"保留旧值"：Caffeine 的 refreshAfterWrite 在回源失败时
            // 本来就会保留旧值（异步刷新不覆盖），只有**硬过期后**的同步装载才会走到这里——
            // 那一刻旧值已被丢弃，能给的只有默认值。
            log.warn("租户配置回源失败，本次按「无配置」处理（全走默认值）：appId={} err={}",
                    appId, e.toString());
            return Map.of();
        }
        log.debug("租户配置回源：appId={} 项数={}", appId, result.size());
        return result;
    }

    /**
     * 组合解析配置键：先查租户策略词表（{@link TenantConfigKey}），再查开关清单（{@link SwitchKey}）。
     *
     * <p>（S2 第 10 步·词表对齐）两套词表各自独立存在，但<b>写入校验只有这一个入口</b>——
     * 于是"开关能不能按租户配"变成一行解析，而不是把 8 个开关复制进 {@code TenantConfigKey}
     * （复制必然漂移：漏一次，租户覆盖就静默失效；拼错一个键，语义永远读不到）。
     *
     * <p>两个 {@code fromXxx} 都是<b>精确匹配</b>——不做前缀匹配，
     * 否则任意垃圾键都能写进配置表。
     */
    private static ConfigKeySpec resolveKey(String configKey) {
        TenantConfigKey tenantKey = TenantConfigKey.fromKey(configKey);
        if (tenantKey != null) {
            return tenantKey;
        }
        return SwitchKey.fromConfigKey(configKey);
    }
}
