package com.newcoupon.infrastructure.redis;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 售罄提示键 `c:so:{模板id}` 的唯一属主（方案 A+B，2026-10-30）。
 *
 * <p>语义：**最近一次数据库核实该模板确实售罄**。写入者两处——异步侧 {@code GateRebuildService.rebuild}
 * 算出 remaining==0 时、同步侧冷路径确认可卖片为 0 时；清除者三处运营路径（库存调整/生命周期/reshard）
 * 加上 rebuild 算出 remaining&gt;0 时的防御性清除。读者两处——同步 {@code C1ReceiveAppService} 与
 * 异步 {@code ReceiveAsyncAppService} 的受理入口，命中即快速失败，洪峰停留在 Redis。
 *
 * <p>TTL 10 秒：误报上界（补货后提示删除失败也最多挡 10 秒）。提示只影响流量不影响正确性——
 * 任何读取失败按"不存在"处理（fail-open），售罄的真裁决在数据库条件更新。
 */
@Component
public class SoldOutHint {

    private static final Logger log = LoggerFactory.getLogger(SoldOutHint.class);
    private static final Duration TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;

    public SoldOutHint(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 标记"数据库已核实售罄"，10 秒后自动失效。 */
    public void mark(long templateId) {
        try {
            redis.opsForValue().set(key(templateId), "1", TTL);
        } catch (RuntimeException e) {
            log.debug("sold-out hint mark failed, ignore: templateId={}", templateId, e);
        }
    }

    /** 提示是否存在；Redis 故障按不存在处理（fail-open，走完整路径）。 */
    public boolean isSet(long templateId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(templateId)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 清除提示（补货/重上线/reshard 后调用；rebuild 算出有余额时也防御性调用）。 */
    public void clear(long templateId) {
        try {
            redis.delete(key(templateId));
        } catch (RuntimeException e) {
            log.debug("sold-out hint clear failed, TTL will expire: templateId={}", templateId, e);
        }
    }

    static String key(long templateId) {
        return "c:so:{" + templateId + "}";
    }
}
