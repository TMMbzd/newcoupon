package com.newcoupon.infrastructure.redis;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 库存 Redis 网关：Lua 与 key 细节关在本类（端口-适配器的 Redis 侧）。
 *
 * <p>key 设计（全部挂 {tplId} hash tag——Lua 多 key 必须同 slot，Cluster 前提）：
 * <pre>
 * c:stk:{tplId}     剩余额度            c:issued:{tplId}   Redis侧已发放计数（复式记账借方）
 * c:dbi:{tplId}      DB侧已确认计数（贷方，confirm 原子+1）  c:pend:{tplId}  在途令牌 ZSET（score=进入时刻ms）
 * c:ul:{tplId}:{uid} 用户累计            c:so:{tplId}       售罄标志（B4，显式清除协议）
 * c:uld:{d}:{tplId}:{uid} / c:dly:{d}:{tplId}  日限计数      c:idem:{tplId}:{uid}:{reqId}  请求去重缓存（纯去重，不再承担记账）
 * </pre>
 *
 * <p><b>恒等式（ADR-0008，采纳外部方案修复版范式）</b>：任意时刻
 * {@code issued − dbi − pending ≡ 0}。记账规则（每次状态转移都是原子 Lua）：
 * <ul>
 *   <li>deduct 成功：issued+1 且 令牌入 pend（借方与在途同增）</li>
 *   <li>DB 提交成功 → confirm：dbi+1 且 令牌出 pend（在途转贷方，原子配对）</li>
 *   <li>DB 失败 → cancel：issued−1 且 令牌出 pend（借方与在途同减，原子配对）</li>
 *   <li>崩溃：令牌由对账按时间清理（ZREMRANGEBYSCORE 过期）→ 差值暴露 → 告警/修复</li>
 * </ul>
 * 令牌（pend 成员）是<b>单次使用</b>的记账凭证：confirm/cancel 都以 ZREM 结果为门——
 * 第二次 confirm/cancel、迟到的 cancel 全部自然 no-op（比幂等键门控更根本的幂等）。
 *
 * <p>stock 修复语义（A1'）：售罄/下线拒绝时钉零（SET 0）而非 INCR 回加，
 * 消灭 N1 过补偿自持环；售罄同时立 soldout 标志短路后续流量（B4）。
 */
@Component
public class RedisStockGateway {

    /** 0=成功；-1 批次日限；-2 用户总限；-3 售罄(含soldout标志短路)；-6 用户日限；-7 库存未初始化；-8 幂等命中 */
    public static final String DEDUCT_SCRIPT = """
            -- KEYS: 1 stk  2 ul  3 uld  4 dly  5 idem  6 issued  7 pend(zset)  8 so(售罄标志)
            -- ARGV: 1 maxPerUser  2 perUserDaily  3 dailyLimit  4 dayTtl  5 idemTtl  6 pendMember  7 nowMs
            if redis.call('EXISTS', KEYS[8]) == 1 then return {-3, 0} end
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
            -- ===== 检查全过，一次性提交（check-all-then-commit）=====
            redis.call('INCR', KEYS[2])
            redis.call('EXPIRE', KEYS[2], 604800)
            if pud > 0 then redis.call('INCR', KEYS[3]); redis.call('EXPIRE', KEYS[3], tonumber(ARGV[4])) end
            if dl > 0 then redis.call('INCR', KEYS[4]); redis.call('EXPIRE', KEYS[4], tonumber(ARGV[4])) end
            redis.call('DECR', KEYS[1])
            redis.call('INCR', KEYS[6])                                             -- 复式记账：借方
            local t = redis.call('TIME')                                            -- 服务端时钟：多实例时钟漂移归零（ADR-0009）
            local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZADD', KEYS[7], nowms, ARGV[6])                             -- 在途令牌（score=Redis服务端时刻）
            redis.call('SET', KEYS[5], '1', 'EX', tonumber(ARGV[5]))
            return {0, ul0 + 1}
            """;

    /**
     * 记账确认（DB 提交成功后调用）：dbi+1 与令牌出 pend 原子配对。
     * ZREM==0（令牌已被消费/过期清理）→ no-op（重复 confirm 安全）。
     * 返回 1=确认入账，0=令牌不在（重复/迟到）。
     */
    public static final String CONFIRM_SCRIPT = """
            -- KEYS: 1 dbi  2 pend(zset)      ARGV: 1 pendMember
            if redis.call('ZREM', KEYS[2], ARGV[1]) == 0 then return 0 end
            redis.call('INCR', KEYS[1])
            return 1
            """;

    /**
     * 记账取消（DB 失败后按分类调用）。mode：transient=瞬时异常(INCR stock)；
     * soldout=售罄(钉零+立标志)；offline=下线(钉零)。
     * 令牌门控：ZREM==0 → 完全 no-op（重复 cancel / 迟到 cancel 天然幂等）。
     */
    public static final String CANCEL_SCRIPT = """
            -- KEYS: 1 stk  2 ul  3 uld  4 dly  5 idem  6 issued  7 pend  8 so
            -- ARGV: 1 pud  2 dl  3 mode(transient/soldout/offline)  4 pendMember
            if redis.call('ZREM', KEYS[7], ARGV[4]) == 0 then return 0 end
            local pud = tonumber(ARGV[1])
            local dl = tonumber(ARGV[2])
            if redis.call('DECR', KEYS[2]) < 0 then redis.call('SET', KEYS[2], 0) end
            if pud > 0 and redis.call('EXISTS', KEYS[3]) == 1 then
              if redis.call('DECR', KEYS[3]) < 0 then redis.call('SET', KEYS[3], 0) end
            end
            if dl > 0 and redis.call('EXISTS', KEYS[4]) == 1 then
              if redis.call('DECR', KEYS[4]) < 0 then redis.call('SET', KEYS[4], 0) end
            end
            redis.call('DECR', KEYS[6])                                              -- 复式记账：借方回滚
            local mode = ARGV[3]
            if mode == 'sold_out' then
              redis.call('SET', KEYS[1], 0)                                          -- A1'：钉零（非INCR，断N1自持环）
              redis.call('SET', KEYS[8], 1)                                           -- B4：售罄标志短路后续流量
            elseif mode == 'offline' then
              redis.call('SET', KEYS[1], 0)
            else
              redis.call('INCR', KEYS[1])
            end
            redis.call('DEL', KEYS[5])                                               -- 允许同请求重试
            return 1
            """;

    /**
     * 恒等式读数（对账用）：先按时间清理过期在途令牌（崩溃残留在此暴露为差值），
     * 原子返回三本账。调用方计算 issued − dbi − pending，期望恒 0。
     */
    public static final String IDENTITY_SCRIPT = """
            -- KEYS: 1 issued  2 dbi  3 pend(zset)   ARGV: 1 inflightTtlMs（阈值；时刻取 Redis 服务端 TIME）
            local t = redis.call('TIME')
            local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', nowms - tonumber(ARGV[1]))
            return {tonumber(redis.call('GET', KEYS[1]) or '0'),
                    tonumber(redis.call('GET', KEYS[2]) or '0'),
                    redis.call('ZCARD', KEYS[3])}
            """;

    /** A2'：懒重建条件 SET（key 已存在则放弃）——清库风暴中首个 SET 生效，其余 no-op，其后 DECR 天然保留 */
    public static final String LAZY_REBUILD_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('SET', KEYS[1], ARGV[1])
            return 1
            """;

    /**
     * 修复重建（对账确认漂移后）：三本账+库存一次重置为 DB 口径。
     * P0 修复④（2026-09-12）：在途门下沉进 Lua——Java 侧"检查 pending==0"与"执行 SET"之间隔着
     * 一次 MySQL COUNT（秒级窗口），期间新进的预占会被 SET 抹掉记账（TOCTOU）。门放进脚本后
     * 检查与执行原子，窗口关闭。返回 -1=在途非空（调用方按下轮推迟处理），1=已修复。
     */
    public static final String REPAIR_SCRIPT = """
            -- KEYS: 1 stk  2 issued  3 dbi  4 pend(zset)
            if redis.call('ZCARD', KEYS[4]) > 0 then return -1 end
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('SET', KEYS[2], ARGV[2])
            redis.call('SET', KEYS[3], ARGV[3])
            return 1
            """;

    private final StringRedisTemplate redis;

    private final DefaultRedisScript<List> deductScript = new DefaultRedisScript<>(DEDUCT_SCRIPT, List.class);
    private final DefaultRedisScript<Long> confirmScript = new DefaultRedisScript<>(CONFIRM_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> cancelScript = new DefaultRedisScript<>(CANCEL_SCRIPT, Long.class);
    private final DefaultRedisScript<List> identityScript = new DefaultRedisScript<>(IDENTITY_SCRIPT, List.class);
    private final DefaultRedisScript<Long> lazyRebuildScript = new DefaultRedisScript<>(LAZY_REBUILD_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> repairScript = new DefaultRedisScript<>(REPAIR_SCRIPT, Long.class);

    public RedisStockGateway(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 取消原因（决定 stock 的修复语义） */
    public enum CancelCause { TRANSIENT, SOLD_OUT, OFFLINE }

    /**
     * 扣减参数（纯数据）。day=编排层算一次传入的日限键日期（yyyyMMdd）——
     * P0 修复③（2026-09-12）：deduct/cancel 各自 LocalDate.now() 会在跨天失败场景扣错日键
     * （23:59:59.900 预占、00:00:05 取消→扣的是新一天的键），日限无 DB 防线，属超发方向。
     * 日期随命令固化后，cancel 扣的一定是 deduct 加的那个键。
     */
    public record DeductCommand(long templateId, long userId, String requestId,
                                int maxPerUser, int perUserDaily, long dailyLimit,
                                String day, int dayTtlSec, int idemTtlSec) {
    }

    /** 扣减结果：code 见脚本头；seq=成功时该用户第几张 */
    public record DeductResult(int code, long seq) {
        public boolean success() { return code == 0; }
    }

    /** 恒等式三本账读数 */
    public record IdentityReading(long issued, long confirmed, long pending) {
        public long delta() { return issued - confirmed - pending; }
    }

    public DeductResult deduct(DeductCommand cmd) {
        String day = cmd.day();                                   // 日期随命令固化（P0 修复③）
        String member = pendMember(cmd.userId(), cmd.requestId());
        List<?> r = redis.execute(deductScript,
                List.of(stockKey(cmd.templateId()), ulKey(cmd.templateId(), cmd.userId()),
                        uldKey(day, cmd.templateId(), cmd.userId()), dlyKey(day, cmd.templateId()),
                        idemKey(cmd.templateId(), cmd.userId(), cmd.requestId()),
                        issuedKey(cmd.templateId()), pendKey(cmd.templateId()), soldoutKey(cmd.templateId())),
                String.valueOf(cmd.maxPerUser()), String.valueOf(cmd.perUserDaily()),
                String.valueOf(cmd.dailyLimit()), String.valueOf(cmd.dayTtlSec()),
                String.valueOf(cmd.idemTtlSec()), member, String.valueOf(System.currentTimeMillis()));
        return new DeductResult(((Number) r.get(0)).intValue(), ((Number) r.get(1)).longValue());
    }

    /** DB 提交成功后的记账确认：dbi+1 与令牌出清原子配对。失败（超时等）不阻断响应——对账层按 confirm-lost 模式修复 */
    public boolean confirm(DeductCommand cmd) {
        Long r = redis.execute(confirmScript,
                List.of(dbiKey(cmd.templateId()), pendKey(cmd.templateId())),
                pendMember(cmd.userId(), cmd.requestId()));
        return r != null && r == 1;
    }

    /** DB 失败后的记账取消（按分类决定 stock 修复语义）。令牌已消费则 no-op（幂等）；日键用 cmd.day()（P0 修复③） */
    public boolean cancel(DeductCommand cmd, CancelCause cause) {
        String day = cmd.day();
        Long r = redis.execute(cancelScript,
                List.of(stockKey(cmd.templateId()), ulKey(cmd.templateId(), cmd.userId()),
                        uldKey(day, cmd.templateId(), cmd.userId()), dlyKey(day, cmd.templateId()),
                        idemKey(cmd.templateId(), cmd.userId(), cmd.requestId()),
                        issuedKey(cmd.templateId()), pendKey(cmd.templateId()), soldoutKey(cmd.templateId())),
                String.valueOf(cmd.perUserDaily()), String.valueOf(cmd.dailyLimit()),
                cause.name().toLowerCase(), pendMember(cmd.userId(), cmd.requestId()));
        return r != null && r == 1;
    }

    /** 恒等式读数（含过期在途清理的副作用） */
    public IdentityReading identity(long templateId, long inflightTtlMs) {
        List<?> r = redis.execute(identityScript,
                List.of(issuedKey(templateId), dbiKey(templateId), pendKey(templateId)),
                String.valueOf(inflightTtlMs));
        return new IdentityReading(((Number) r.get(0)).longValue(),
                ((Number) r.get(1)).longValue(), ((Number) r.get(2)).longValue());
    }

    /** 发布时初始化额度（无条件——发布是受控写者） */
    public void initStock(long templateId, long amount) {
        redis.opsForValue().set(stockKey(templateId), String.valueOf(amount));
    }

    /** A2'：懒重建（条件 SET，风暴中首个生效）；true=本次重建，false=key 已在 */
    public boolean lazyRebuildStock(long templateId, long amount) {
        Long r = redis.execute(lazyRebuildScript, List.of(stockKey(templateId)), String.valueOf(amount));
        return r != null && r == 1;
    }

    /**
     * 修复重建：stock/issued/dbi 一次重置为 DB 口径。Lua 内在途门原子拦截（P0 修复④）。
     *
     * @return true=已修复；false=执行瞬间在途非空（Lua 门拦截），调用方按下轮推迟处理
     */
    public boolean repair(long templateId, long stock, long issued, long confirmed) {
        Long r = redis.execute(repairScript,
                List.of(stockKey(templateId), issuedKey(templateId), dbiKey(templateId),
                        pendKey(templateId)),
                String.valueOf(stock), String.valueOf(issued), String.valueOf(confirmed));
        return r != null && r == 1;
    }

    /** B4 清除协议：合法回补/修复使库存>0 时必须清标志，否则模板永久假死 */
    public void clearSoldout(long templateId) {
        redis.delete(soldoutKey(templateId));
    }

    public boolean isSoldout(long templateId) {
        return Boolean.TRUE.equals(redis.hasKey(soldoutKey(templateId)));
    }

    /** 对账余量读数；key 不存在返回 null */
    public Long remaining(long templateId) {
        String v = redis.opsForValue().get(stockKey(templateId));
        return v == null ? null : Long.parseLong(v);
    }

    /** Redis 侧已发放计数（恒等式借方读数，测试/运维用） */
    public Long issuedCount(long templateId) {
        String v = redis.opsForValue().get(issuedKey(templateId));
        return v == null ? null : Long.parseLong(v);
    }

    /** DB 侧已确认计数（恒等式贷方读数） */
    public Long confirmedCount(long templateId) {
        String v = redis.opsForValue().get(dbiKey(templateId));
        return v == null ? null : Long.parseLong(v);
    }

    /** 在途令牌数（不经清理的原始 ZCARD，测试用） */
    public long pendingCount(long templateId) {
        Long c = redis.opsForZSet().zCard(pendKey(templateId));
        return c == null ? 0 : c;
    }

    /** 测试与运维用：清除用户累计计数（模拟计数丢失） */
    public void resetUserCount(long templateId, long userId) {
        redis.delete(ulKey(templateId, userId));
    }

    public Long userCount(long templateId, long userId) {
        String v = redis.opsForValue().get(ulKey(templateId, userId));
        return v == null ? null : Long.parseLong(v);
    }

    private String stockKey(long t) { return "c:stk:{" + t + "}"; }
    private String ulKey(long t, long u) { return "c:ul:{" + t + "}:" + u; }
    private String uldKey(String d, long t, long u) { return "c:uld:" + d + ":{" + t + "}:" + u; }
    private String dlyKey(String d, long t) { return "c:dly:" + d + ":{" + t + "}"; }
    private String issuedKey(long t) { return "c:issued:{" + t + "}"; }
    private String dbiKey(long t) { return "c:dbi:{" + t + "}"; }
    private String pendKey(long t) { return "c:pend:{" + t + "}"; }
    private String soldoutKey(long t) { return "c:so:{" + t + "}"; }

    private String idemKey(long t, long u, String r) { return "c:idem:{" + t + "}:" + u + ":" + r; }
    private String pendMember(long u, String r) { return u + ":" + r; }
}
