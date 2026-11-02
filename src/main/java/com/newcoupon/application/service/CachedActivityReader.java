package com.newcoupon.application.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.newcoupon.infrastructure.persistence.mapper.CouponActivityMapper;
import com.newcoupon.infrastructure.persistence.po.CouponActivityPO;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 活动预算闸门的本地缓存读（V6 阶段 1C）——只服务**发放热路径**的"预算开关是否打开"判断。
 *
 * <p>为什么必须缓存：预算闸门的契约是"活动没开预算 = 一次内存判断就跳过"——
 * 如果每次领券都先 SELECT coupon_activity，等于给所有存量流量（预算关着）平白
 * 加一次 DB 往返，违背"budget_enabled=0 现行为零变化"的回滚承诺。
 *
 * <p>为什么敢缓存（TTL 语义）：开关是低频运营动作。开启后最多 ttl（默认 30s）
 * 才生效、关闭后最多 ttl 仍在拦截——对预算闸门都是安全方向（晚开=少拦一点，
 * 晚关=多拦一点，都不会超发钱）。模板读走 {@link CachedTemplateReader} 的同款
 * 防击穿/防穿透手法；这里 TTL 更短是因为它只挡"要不要走慢路径"。
 *
 * <p>负缓存（活动不存在）：存量模板的 activityId 多为 1001 这类字面量、库里并没有
 * 对应活动行——没有负缓存的话这些模板每次领取都会穿透到 DB。
 */
@Component
public class CachedActivityReader {

    private static final long MAX_ENTRIES = 10_000;

    private final LoadingCache<Long, Optional<CouponActivityPO>> cache;

    public CachedActivityReader(CouponActivityMapper activityMapper,
                                @Value("${coupon.activity.cache-ttl-ms:30000}") long ttlMillis) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(Duration.ofMillis(ttlMillis).toNanos(), TimeUnit.NANOSECONDS)
                .build(key -> Optional.ofNullable(activityMapper.selectById(key)));
    }

    /** 读活动行：不存在返回 empty（= 该模板没有活动归属，预算闸门直接放行）。 */
    public Optional<CouponActivityPO> get(long activityId) {
        Optional<CouponActivityPO> v = cache.get(activityId);
        return v == null ? Optional.empty() : v;
    }

    /** 主动失效（活动创建/改配置时调用，让新开关立刻可见，不必等 TTL）。 */
    public void invalidate(long activityId) {
        cache.invalidate(activityId);
    }
}
