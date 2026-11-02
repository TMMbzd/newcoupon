package com.newcoupon.infrastructure.redis;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 异步发放的 Redis 准入门（ADR-0019）。
 *
 * <p>它只做“能不能受理”的快速判断和预扣，**不是库存账本**：
 * 最终是否发券仍由 worker 在 C1 的 {@code coupon_stock_shard} 条件更新裁决。
 * 因此 Redis 丢失只会影响受理率/需要重建 gate，不会造成超发。
 *
 * <p>所有 key 都带 {@code {templateId}} hash tag：Lua 一次操作多个 key，
 * Redis Cluster 要求它们落在同一个 slot。键形：
 * <pre>
 * c:issue:idem:{tpl}:{app}:{requestId}  幂等标记 PENDING/ACCEPTED
 * c:issue:ul:{tpl}:{user}               每人总限计数
 * c:issue:uld:{tpl}:{user}:{day}        每人日限计数
 * c:issue:dly:{tpl}:{day}               模板日限计数
 * c:issue:gate:{tpl}                    库存 gate 余额（从 C1 分片重建）
 * </pre>
 */
@Component
public class RedisIssueGate {

    public static final int OK = 0;
    public static final int TEMPLATE_DAILY_LIMIT = -1;
    public static final int USER_TOTAL_LIMIT = -2;
    public static final int USER_DAILY_LIMIT = -6;
    public static final int STOCK_GATE_EMPTY = -7;
    public static final int IDEMPOTENT_HIT = -8;
    public static final int GATE_MISSING = -9;
    /** gate 值不是数字/类型错误；可删除重建。 */
    public static final int GATE_CORRUPT = -10;
    /** 限领计数不是数字/类型错误；可按 DB 重建。 */
    public static final int COUNTER_CORRUPT = -11;
    /** 限领计数 key 缺失；不能当 0 静默 over-admission，按 DB 重建。 */
    public static final int COUNTER_MISSING = -12;

    /** seq 键独立于 ul 计数，TTL 与幂等窗口一致；修复计数时不影响序号单调。 */
    private static final long SEQ_TTL_SECONDS = 604800;
    /** 每人总限计数没有配置项，沿用幂等窗口的 7 天。 */
    private static final long UL_TTL_SECONDS = 604800;

    /** 返回码 + Redis 预分配的用户序号（仅 OK 时有意义；worker 仍以 DB MAX(seq)+1 终审）。 */
    public record GateResult(int code, long seq) {
        public boolean success() { return code == OK; }
    }

    /** 一次受理的准入参数。day 必须由调用方在受理时算一次，不能留给 worker。 */
    public record GateCommand(long templateId, String appId, long userId, String requestId,
                              int maxPerUser, int perUserDaily, long dailyLimit,
                              LocalDate day, long idemTtlSeconds, long dayTtlSeconds) {
    }

    /**
     * 原子准入：幂等 → gate 解析/余额 → 限领计数 → 写回（计数 INCR → gate DECR → seq → 幂等 SET）。
     *
     * <p>Redis 值可能被人工写坏/类型错/部分丢失；这里用 pcall+tonumber 区分
     * missing(-12)/corrupt(-11)/gate corrupt(-10)，交给编排层按 DB 重建后重试。
     * seq 独立于 ul：ul 可被 DB 修复/回退，seq 只增不减，避免修复计数导致序号倒退。
     */
    private static final String ACQUIRE_SCRIPT = """
            -- KEYS: 1 idem 2 ul 3 uld 4 dly 5 gate 6 seq
            -- ARGV: 1 maxPerUser 2 perUserDaily 3 dailyLimit 4 idemTtl 5 dayTtl 6 seqTtl 7 ulTtl
            if redis.call('EXISTS', KEYS[1]) == 1 then return {-8, 0} end
            local okGate, gateRaw = pcall(redis.call, 'GET', KEYS[5])
            if not okGate then return {-10, 0} end
            if gateRaw == false then return {-9, 0} end
            local gate = tonumber(gateRaw)
            if gate == nil then return {-10, 0} end
            if gate <= 0 then return {-7, 0} end

            local dl = tonumber(ARGV[3])
            if dl > 0 then
              local okDly, dlyRaw = pcall(redis.call, 'GET', KEYS[4])
              if not okDly then return {-11, 0} end
              if dlyRaw == false then return {-12, 0} end
              local dly = tonumber(dlyRaw)
              if dly == nil then return {-11, 0} end
              if dly >= dl then return {-1, 0} end
            end

            local okUl, ulRaw = pcall(redis.call, 'GET', KEYS[2])
            if not okUl then return {-11, 0} end
            if ulRaw == false then return {-12, 0} end
            local ul = tonumber(ulRaw)
            if ul == nil then return {-11, 0} end
            if ul >= tonumber(ARGV[1]) then return {-2, 0} end

            local pud = tonumber(ARGV[2])
            if pud > 0 then
              local okUld, uldRaw = pcall(redis.call, 'GET', KEYS[3])
              if not okUld then return {-11, 0} end
              if uldRaw == false then return {-12, 0} end
              local uld = tonumber(uldRaw)
              if uld == nil then return {-11, 0} end
              if uld >= pud then return {-6, 0} end
            end

            -- 写序（讨论 02 核对结论 P3）：先加计数、后扣库存、最后登记幂等。
            -- Redis Lua 不回滚已执行命令；若某条写命令中途失败，这个顺序让残余**更可能**
            -- 落在"计数偏高"方向——偏高会触发限领拒绝码，被 repairLimitIfAllowed 用 DB
            -- 目标复核后修低（有信号、可自愈）；反过来先扣库存则残余是"gate 偏低"，
            -- 无错误码可发现，只能等 TTL/对账。注意这是方向性缓解不是保证：
            -- noeviction 下内存满通常第一条写命令就失败（残余为零），真正能越过
            -- 部分写命令的失败模式极少；残余无论落向哪边都被 DB 终审兜住，不会超发。
            redis.call('INCR', KEYS[2])
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[7]))
            if pud > 0 then
              redis.call('INCR', KEYS[3])
              redis.call('EXPIRE', KEYS[3], tonumber(ARGV[5]))
            end
            if dl > 0 then
              redis.call('INCR', KEYS[4])
              redis.call('EXPIRE', KEYS[4], tonumber(ARGV[5]))
            end
            redis.call('DECR', KEYS[5])
            local okSeq, seq = pcall(redis.call, 'INCR', KEYS[6])
            if not okSeq then return {-10, 0} end
            redis.call('EXPIRE', KEYS[6], tonumber(ARGV[6]))
            redis.call('SET', KEYS[1], 'PENDING', 'EX', tonumber(ARGV[4]))
            return {0, seq}
            """;

    /**
     * 任务插入成功后把标记从 PENDING 改成 ACCEPTED；补偿脚本只回退 PENDING，
     * 避免“任务已落库、补偿却把计数退掉”的误伤。KEEPTTL 保留幂等 TTL。
     */
    private static final String ACCEPTED_SCRIPT = """
            -- KEYS: 1 idem
            if redis.call('GET', KEYS[1]) == 'PENDING' then
              redis.call('SET', KEYS[1], 'ACCEPTED', 'KEEPTTL')
            end
            return 1
            """;

    /** 成功发券：idem 置 ISSUED，之后 release 永远 no-op。 */
    private static final String ISSUED_SCRIPT = """
            -- KEYS: 1 idem
            local s = redis.call('GET', KEYS[1])
            if not s then return 0 end
            if s == 'RELEASED' then return 0 end
            redis.call('SET', KEYS[1], 'ISSUED', 'KEEPTTL')
            return 1
            """;

    /**
     * 终态释放：只有本请求的 idem 仍是 PENDING/ACCEPTED 才执行一次。
     * key 丢失 = no-op（任务表/重建兜底），绝不依赖缺失的 key 做盲 DECR。
     */
    private static final String RELEASE_SCRIPT = """
            -- KEYS: 1 idem 2 ul 3 uld 4 dly 5 gate
            -- ARGV: 1 releaseStock 2 releaseCounters 3 perUserDaily 4 dailyLimit
            local function decIfPositive(key)
              local ok, raw = pcall(redis.call, 'GET', key)
              if not ok then return end
              local n = tonumber(raw or '0')
              if n ~= nil and n > 0 then redis.call('DECR', key) end
            end
            local s = redis.call('GET', KEYS[1])
            if not s then return 0 end
            if s == 'RELEASED' or s == 'ISSUED' then return 0 end
            if ARGV[1] == '1' and redis.call('EXISTS', KEYS[5]) == 1 then
              pcall(redis.call, 'INCR', KEYS[5])
            end
            if ARGV[2] == '1' then
              decIfPositive(KEYS[2])
              if tonumber(ARGV[3]) > 0 then decIfPositive(KEYS[3]) end
              if tonumber(ARGV[4]) > 0 then decIfPositive(KEYS[4]) end
            end
            redis.call('SET', KEYS[1], 'RELEASED', 'KEEPTTL')
            return 1
            """;

    /**
     * 任务插入失败时回退预扣。只有本请求的幂等标记仍是 PENDING 才执行；
     * 二次调用返回 0，不会重复减计数。gate 不存在时不重建，交 ensureGate/对账处理。
     */
    private static final String COMPENSATE_SCRIPT = """
            -- KEYS: 1 idem  2 ul  3 uld  4 dly  5 gate
            -- ARGV: 1 perUserDaily  2 dailyLimit
            local function decIfPositive(key)
              local ok, raw = pcall(redis.call, 'GET', key)
              if not ok then return end
              local n = tonumber(raw or '0')
              if n ~= nil and n > 0 then redis.call('DECR', key) end
            end
            if redis.call('GET', KEYS[1]) ~= 'PENDING' then return 0 end
            redis.call('DEL', KEYS[1])
            decIfPositive(KEYS[2])
            if tonumber(ARGV[1]) > 0 then decIfPositive(KEYS[3]) end
            if tonumber(ARGV[2]) > 0 then decIfPositive(KEYS[4]) end
            if redis.call('EXISTS', KEYS[5]) == 1 then
              pcall(redis.call, 'INCR', KEYS[5])
            end
            return 1
            """;

    /** 计数修复：保留原 TTL；key 缺失时按传入 TTL 建。允许短暂 over-admission，DB 终审。 */
    private static final String REPAIR_COUNTER_SCRIPT = """
            -- KEYS: 1 counter  ARGV: 1 target 2 ttl
            redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))
            return 1
            """;

    /** seq 只增不减；丢失/损坏时从 DB max(seq) 补种，降低与已占用序号碰撞。 */
    private static final String ENSURE_SEQ_SCRIPT = """
            -- KEYS: 1 seq  ARGV: 1 floor 2 ttl
            local ok, raw = pcall(redis.call, 'GET', KEYS[1])
            local cur = 0
            local corrupt = false
            if not ok then
              corrupt = true
            elseif raw ~= false then
              cur = tonumber(raw)
              if cur == nil then corrupt = true end
            end
            local floor = tonumber(ARGV[1])
            if corrupt or cur < floor then
              redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))
            end
            return 1
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> acquireScript =
            new DefaultRedisScript<>(ACQUIRE_SCRIPT, List.class);
    private final DefaultRedisScript<Long> acceptedScript =
            new DefaultRedisScript<>(ACCEPTED_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> issuedScript =
            new DefaultRedisScript<>(ISSUED_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> releaseScript =
            new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> compensateScript =
            new DefaultRedisScript<>(COMPENSATE_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> repairCounterScript =
            new DefaultRedisScript<>(REPAIR_COUNTER_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> ensureSeqScript =
            new DefaultRedisScript<>(ENSURE_SEQ_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> compareAndDeleteScript =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                            + "return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);

    public RedisIssueGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public GateResult tryAcquire(GateCommand cmd) {
        List<?> raw = redis.execute(acquireScript, keys(cmd),
                String.valueOf(cmd.maxPerUser()),
                String.valueOf(cmd.perUserDaily()),
                String.valueOf(cmd.dailyLimit()),
                String.valueOf(cmd.idemTtlSeconds()),
                String.valueOf(jitter(cmd.dayTtlSeconds())),
                String.valueOf(jitter(SEQ_TTL_SECONDS)),
                String.valueOf(jitter(UL_TTL_SECONDS)));
        if (raw == null || raw.size() < 2) {
            throw new IllegalStateException("issue gate returned malformed result");
        }
        return new GateResult(((Number) raw.get(0)).intValue(), ((Number) raw.get(1)).longValue());
    }

    /** 任务已持久化：把幂等标记改成 ACCEPTED，使后续补偿不会误退。 */
    public void markAccepted(GateCommand cmd) {
        redis.execute(acceptedScript, List.of(idemKey(cmd)));
    }

    /** 发券成功：idem 置 ISSUED；之后终态释放永远不会再加回库存/计数。 */
    public void markIssued(GateCommand cmd) {
        redis.execute(issuedScript, List.of(idemKey(cmd)));
    }

    /**
     * 终态释放（拒绝/死信）。
     *
     * @param releaseStock   是否回加库存 gate；STOCK_OUT 必须传 false（DB 确实无货）
     * @param releaseCounters 是否回退 ul/uld/dly；按受理时快照判断
     * @return true=本次确实释放；false=key 已丢失/已签发/已释放（no-op）
     */
    public boolean releaseTerminal(GateCommand cmd, boolean releaseStock, boolean releaseCounters) {
        Long r = redis.execute(releaseScript, keys(cmd),
                releaseStock ? "1" : "0",
                releaseCounters ? "1" : "0",
                String.valueOf(cmd.perUserDaily()),
                String.valueOf(cmd.dailyLimit()));
        return r != null && r == 1L;
    }

    /** 任务插入失败/重复跨模板时回退；返回 true 表示本次确实回退了。 */
    public boolean compensate(GateCommand cmd) {
        Long r = redis.execute(compensateScript, keys(cmd),
                String.valueOf(cmd.perUserDaily()), String.valueOf(cmd.dailyLimit()));
        return r != null && r == 1L;
    }

    /** 按 DB 目标修复每人总限计数（ul）。 */
    public void repairUserTotal(GateCommand cmd, long target) {
        repairCounter(userLimitKey(cmd), target, cmd.idemTtlSeconds());
    }

    /** 按 DB 目标修复每人日限计数（uld）。 */
    public void repairUserDaily(GateCommand cmd, long target) {
        repairCounter(userDailyKey(cmd), target, cmd.dayTtlSeconds());
    }

    /** 按 DB 目标修复模板日限计数（dly）。 */
    public void repairTemplateDaily(GateCommand cmd, long target) {
        repairCounter(templateDailyKey(cmd), target, cmd.dayTtlSeconds());
    }

    /** seq 丢失/损坏时补种到至少 floor；只增不减。 */
    public void ensureSeqAtLeast(GateCommand cmd, long floor) {
        redis.execute(ensureSeqScript, List.of(seqKey(cmd)),
                String.valueOf(Math.max(0, floor)), String.valueOf(jitter(SEQ_TTL_SECONDS)));
    }

    private void repairCounter(String key, long target, long ttlSeconds) {
        redis.execute(repairCounterScript, List.of(key),
                String.valueOf(Math.max(0, target)), String.valueOf(jitter(ttlSeconds)));
    }

    /** TTL 抖动：避免同一批 key 在同一秒集中过期，触发 Redis 主动过期扫描的延迟尖峰。 */
    private static long jitter(long seconds) {
        if (seconds <= 2) {
            return Math.max(1, seconds);
        }
        long delta = Math.max(1, seconds / 10);
        return Math.max(1, seconds + ThreadLocalRandom.current().nextLong(-delta, delta + 1));
    }

    /**
     * 从 C1 分片重建 gate：剩余额度 = Σquota - Σused。只在 key 不存在时写入，
     * 避免并发重建覆盖已经被预扣的余额。返回 true 表示 gate 当前存在。
     */
    public boolean ensureGate(long templateId, long remaining, Duration ttl) {
        String key = gateKey(templateId);
        Boolean created = redis.opsForValue()
                .setIfAbsent(key, String.valueOf(Math.max(0, remaining)), ttl);
        return Boolean.TRUE.equals(created) || Boolean.TRUE.equals(redis.hasKey(key));
    }

    /**
     * 强制用 DB 重建值覆盖 gate（假售罄兜底）。
     * 只在持 rebuild permit 时调用；DB 是权威，覆盖竞态最多造成短暂多放/少放，不会超发。
     */
    public void resetGate(long templateId, long remaining, Duration ttl) {
        redis.opsForValue().set(gateKey(templateId),
                String.valueOf(Math.max(0, remaining)), ttl);
    }

    /**
     * 修复单飞：同一个修复作用域（模板或模板+用户）在 TTL 内只允许一个调用者做 DB 重建。
     * 值是 owner，释放时用 compare-and-delete，避免 TTL 过期后误删新持有者的许可。
     */
    public boolean tryAcquireRepairPermit(String key, String owner, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, ttl));
    }

    /** 修复成功或无需修复时释放；修复失败时故意不释放，让 TTL 充当短冷却。 */
    public void releaseRepairPermit(String key, String owner) {
        redis.execute(compareAndDeleteScript, List.of(key), owner);
    }

    /** 库存调整/发布后让 gate 失效，下次受理从 C1 分片重建。 */
    public void invalidate(long templateId) {
        redis.delete(gateKey(templateId));
    }

    public Long currentGate(long templateId) {
        String v = redis.opsForValue().get(gateKey(templateId));
        return v == null ? null : Long.valueOf(v);
    }

    private List<String> keys(GateCommand cmd) {
        return List.of(idemKey(cmd), userLimitKey(cmd), userDailyKey(cmd),
                templateDailyKey(cmd), gateKey(cmd.templateId()), seqKey(cmd));
    }

    private String idemKey(GateCommand cmd) {
        return "c:issue:idem:{" + cmd.templateId() + "}:" + cmd.appId() + ":" + cmd.requestId();
    }

    private String userLimitKey(GateCommand cmd) {
        return userLimitKey(cmd.templateId(), cmd.userId());
    }

    private String userDailyKey(GateCommand cmd) {
        return userDailyKey(cmd.templateId(), cmd.userId(), cmd.day());
    }

    private String templateDailyKey(GateCommand cmd) {
        return templateDailyKey(cmd.templateId(), cmd.day());
    }

    private String seqKey(GateCommand cmd) {
        return "c:issue:seq:{" + cmd.templateId() + "}:" + cmd.userId();
    }

    private String gateKey(long templateId) {
        return "c:issue:gate:{" + templateId + "}";
    }

    // ------------------------------------------------------------------
    // 裸 id 版本：对账任务（GateCounterReconcileJob）按 id 枚举/修复键，
    // 与 cmd 版本共用同一格式，避免两处漂移。
    // ------------------------------------------------------------------

    public static String userLimitKey(long templateId, long userId) {
        return "c:issue:ul:{" + templateId + "}:" + userId;
    }

    public static String userDailyKey(long templateId, long userId, java.time.LocalDate day) {
        return "c:issue:uld:{" + templateId + "}:" + userId + ":" + day;
    }

    public static String templateDailyKey(long templateId, java.time.LocalDate day) {
        return "c:issue:dly:{" + templateId + "}:" + day;
    }

    /** 按 DB 目标修复每人总限计数（ul）——裸 id 版本，对账任务用。 */
    public void repairUserTotal(long templateId, long userId, long target, long ttlSeconds) {
        repairCounter(userLimitKey(templateId, userId), target, ttlSeconds);
    }

    /** 按 DB 目标修复每人日限计数（uld）——裸 id 版本，对账任务用。 */
    public void repairUserDaily(long templateId, long userId, java.time.LocalDate day,
                                long target, long ttlSeconds) {
        repairCounter(userDailyKey(templateId, userId, day), target, ttlSeconds);
    }

    /** 按 DB 目标修复模板日限计数（dly）——裸 id 版本，对账任务用。 */
    public void repairTemplateDaily(long templateId, java.time.LocalDate day,
                                     long target, long ttlSeconds) {
        repairCounter(templateDailyKey(templateId, day), target, ttlSeconds);
    }
}
