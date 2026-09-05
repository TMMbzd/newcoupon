package com.newcoupon.infrastructure.redis;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 库存 Redis 网关：领域/应用层通过它读写库存闸门，Lua 与 key 细节关在本类（端口-适配器的 Redis 版）。
 *
 * <p>key 设计（{} 为 hash tag：同批次 key 落同一 slot，保证 Lua 多 key 原子——为 Redis Cluster 预留）：
 * <pre>
 * c:stk:{tplId}                    剩余可扣额度（发布时灌入 total-issued）
 * c:ul:{tplId}:{uid}               用户累计领取数（值即"第几张"→ DB 的 seq）
 * c:uld:{yyyyMMdd}:{tplId}:{uid}   用户当日领取数（日期入 key 自然跨天滚动，TTL 兜底清理）
 * c:dly:{yyyyMMdd}:{tplId}         批次当日发放数
 * c:idem:{uid}:{reqId}             请求级幂等标记（短 TTL，DB uk_idem 仍是终审）
 * </pre>
 * 计数用独立小 key 而非批次大 Hash——防大 Key（study/01a 索引讲过同款思想：每个键职责单一）。
 */
@Component
public class RedisStockGateway {

    /** 0=成功；-1 批次日限；-2 用户总限；-3 售罄；-6 用户日限；-7 库存未初始化；-8 幂等命中 */
    public static final String DEDUCT_SCRIPT = """
            -- KEYS: 1 c:stk:{t}  2 c:ul:{t}:{u}  3 c:uld:{d}:{t}:{u}  4 c:dly:{d}:{t}  5 c:idem:{u}:{r}
            -- ARGV: 1 maxPerUser  2 perUserDaily(0不限)  3 dailyLimit(0不限)  4 dayTtlSec  5 idemTtlSec
            if redis.call('EXISTS', KEYS[5]) == 1 then return {-8, 0} end
            local stock = redis.call('GET', KEYS[1])
            if not stock then return {-7, 0} end
            if tonumber(stock) <= 0 then return {-3, 0} end
            local dl = tonumber(ARGV[3])
            if dl > 0 and tonumber(redis.call('GET', KEYS[4]) or '0') >= dl then return {-1, 0} end
            local ul0 = tonumber(redis.call('GET', KEYS[2]) or '0')
            if ul0 >= tonumber(ARGV[1]) then return {-2, 0} end
            local pud = tonumber(ARGV[2])
            if pud > 0 and tonumber(redis.call('GET', KEYS[3]) or '0') >= pud then return {-6, 0} end
            -- ===== 检查全过，一次性提交全部写入（check-all-then-commit，脚本内无需回滚分支）=====
            redis.call('INCR', KEYS[2])
            redis.call('EXPIRE', KEYS[2], 604800)
            if pud > 0 then redis.call('INCR', KEYS[3]); redis.call('EXPIRE', KEYS[3], tonumber(ARGV[4])) end
            if dl > 0 then redis.call('INCR', KEYS[4]); redis.call('EXPIRE', KEYS[4], tonumber(ARGV[4])) end
            redis.call('DECR', KEYS[1])
            redis.call('SET', KEYS[5], '1', 'EX', tonumber(ARGV[5]))
            return {0, ul0 + 1}
            """;

    /** 补偿：DB 落库失败时反向撤销 deduct 的全部写入（幂等键 DEL 让重试可再进） */
    public static final String COMPENSATE_SCRIPT = """
            if redis.call('DECR', KEYS[2]) < 0 then redis.call('SET', KEYS[2], 0) end
            if tonumber(ARGV[2]) > 0 and redis.call('EXISTS', KEYS[3]) == 1 then redis.call('DECR', KEYS[3]) end
            if tonumber(ARGV[3]) > 0 and redis.call('EXISTS', KEYS[4]) == 1 then redis.call('DECR', KEYS[4]) end
            redis.call('INCR', KEYS[1])
            redis.call('DEL', KEYS[5])
            return 1
            """;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;

    private final DefaultRedisScript<List> deductScript =
            new DefaultRedisScript<>(DEDUCT_SCRIPT, List.class);
    private final DefaultRedisScript<Long> compensateScript =
            new DefaultRedisScript<>(COMPENSATE_SCRIPT, Long.class);

    public RedisStockGateway(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 扣减参数（纯数据，无行为） */
    public record DeductCommand(long templateId, long userId, String requestId,
                                 int maxPerUser, int perUserDaily, long dailyLimit,
                                 int dayTtlSec, int idemTtlSec) {
    }

    /** 扣减结果：code 见脚本头注释；seq=成功时该用户的第几张（写入 DB 的序号） */
    public record DeductResult(int code, long seq) {
        public boolean success() {
            return code == 0;
        }
    }

    public DeductResult deduct(DeductCommand cmd) {
        String day = LocalDate.now().format(DAY);
        List<?> r = redis.execute(deductScript,
                List.of(stockKey(cmd.templateId()), ulKey(cmd.templateId(), cmd.userId()),
                        uldKey(day, cmd.templateId(), cmd.userId()), dlyKey(day, cmd.templateId()),
                        idemKey(cmd.templateId(), cmd.userId(), cmd.requestId())),
                String.valueOf(cmd.maxPerUser()), String.valueOf(cmd.perUserDaily()),
                String.valueOf(cmd.dailyLimit()), String.valueOf(cmd.dayTtlSec()),
                String.valueOf(cmd.idemTtlSec()));
        return new DeductResult(((Number) r.get(0)).intValue(), ((Number) r.get(1)).longValue());
    }

    /** DB 落库失败后的补偿（撤销 deduct 的写入） */
    public void compensate(DeductCommand cmd) {
        String day = LocalDate.now().format(DAY);
        redis.execute(compensateScript,
                List.of(stockKey(cmd.templateId()), ulKey(cmd.templateId(), cmd.userId()),
                        uldKey(day, cmd.templateId(), cmd.userId()), dlyKey(day, cmd.templateId()),
                        idemKey(cmd.templateId(), cmd.userId(), cmd.requestId())),
                "0", String.valueOf(cmd.perUserDaily()), String.valueOf(cmd.dailyLimit()));
    }

    /** 发布时初始化可扣额度（= 审批总量 - 已发放），幂等（重复发布会重置为正确值） */
    public void initStock(long templateId, long amount) {
        redis.opsForValue().set(stockKey(templateId), String.valueOf(amount));
    }

    /** 对账用：当前 Redis 余量；key 不存在返回 null */
    public Long remaining(long templateId) {
        String v = redis.opsForValue().get(stockKey(templateId));
        return v == null ? null : Long.parseLong(v);
    }

    /** 清除某用户的累计计数（测试与运维用：模拟 Redis 计数丢失） */
    public void resetUserCount(long templateId, long userId) {
        redis.delete(ulKey(templateId, userId));
    }

    private String stockKey(long t) { return "c:stk:{" + t + "}"; }
    private String ulKey(long t, long u) { return "c:ul:{" + t + "}:" + u; }
    private String uldKey(String d, long t, long u) { return "c:uld:" + d + ":{" + t + "}:" + u; }
    private String dlyKey(String d, long t) { return "c:dly:" + d + ":{" + t + "}"; }

    /** 幂等 key 同样挂 {tplId} tag——Lua 全部 KEYS 必须同 slot（Cluster 前提），语义=每次"领取意图" */
    private String idemKey(long t, long u, String r) { return "c:idem:{" + t + "}:" + u + ":" + r; }
}
