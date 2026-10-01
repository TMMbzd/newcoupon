package com.newcoupon.infrastructure.redis;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 库存 Redis 网关（P2b v2 桶化+世代化形态，2026-09-18；前身 ADR-0009 守恒式单键形态）。
 *
 * <p>key 设计（hash tag 一律 {tplId}——Lua 多 key 同 slot，Cluster 前提；方案 v2 §6.3.5 取舍记录在案）：
 * <pre>
 * 【分代桶键——每 (桶,世代) 一套四状态分区，E1：stk+pend+dbi+void ≡ bucket_total】
 * c:stk:{tpl}:{b}:{g}   可领          c:pend:{tpl}:{b}:{g}  在途（ZSET，score=Redis TIME）
 * c:dbi:{tpl}:{b}:{g}   已发放        c:void:{tpl}:{b}:{g}  已撤销（旧代/终态退休）
 * 【模板级注册与镜像】
 * c:act:{tpl}     活跃桶注册表（HASH：field=桶号 → value=当代世代）——Lua 寻址/耗尽遍历的依据
 * c:alloc:{tpl}   DB allocated 的 Redis 镜像（开桶 Lua 原子同步——E2 全局等式纯 Redis 化）
 * c:so:{tpl}      停流短路（售罄/下线）      c:fz:{tpl}   冻结牌（DEDUCT 首行短路，v2 §12 改进2）
 * 【限领/幂等键——不入守恒式，键形不变】
 * c:idem:{tpl}:{uid}:{req}   c:ul/uld/dly
 * </pre>
 *
 * <p><b>等式族（v2 §6.1）</b>：E1 分代桶内（如上）；E2 全局 Σ(活跃代四键和)+c:alloc ≡ total（一条 Lua 原子读）；
 * E3 镜像（方向判据）：c:alloc↔DB allocated ｜ 分代 dbi↔stock_bucket.used ｜ Σdbi↔SUM(used)。
 *
 * <p><b>世代隔离（v2 §4）</b>：桶耗尽重开=同桶号新世代（新键新账）。令牌固化 (b,g)：
 * confirm 按令牌世代记账；cancel(transient) 仅当「未停流 且 该桶活跃世代==令牌世代」才回加 stk——
 * 迟到/跨代取消落旧代 void（治 CONC-4/CHAO-2：绝不污染新代可卖库存）。
 */
@Component
public class RedisStockGateway {

    /** 冻结/停流/幂等/限领前置 + 活跃桶遍历扣减。0成功；-1批次日限；-2用户总限；-3售罄(含so)；
     * -6用户日限；-7无活跃桶(未初始化→ensure路径)；-8幂等命中；-10全桶耗尽(→开桶协议)；-11冻结 */
    public static final String DEDUCT_SCRIPT = """
            -- KEYS: 1 ul  2 uld  3 dly  4 idem  5 so  6 fz  7 act(hash)
            -- ARGV: 1 maxPerUser  2 pud  3 dl  4 dayTtl  5 idemTtl  6 pendMember  7 tpl  8 preferredBucket
            if redis.call('EXISTS', KEYS[6]) == 1 then return {-11, 0, 0, 0} end
            if redis.call('EXISTS', KEYS[5]) == 1 then
              redis.call('EXPIRE', KEYS[5], 10)   -- 2e-④ 售罄牌自续期：清除消息丢失时 10s 自动放行
              return {-3, 0, 0, 0}
            end
            if redis.call('EXISTS', KEYS[4]) == 1 then return {-8, 0, 0, 0} end
            local dl = tonumber(ARGV[3])
            if dl > 0 and tonumber(redis.call('GET', KEYS[3]) or '0') >= dl then return {-1, 0, 0, 0} end
            local ul0 = tonumber(redis.call('GET', KEYS[1]) or '0')
            if ul0 >= tonumber(ARGV[1]) then return {-2, 0, 0, 0} end
            local pud = tonumber(ARGV[2])
            if pud > 0 and tonumber(redis.call('GET', KEYS[2]) or '0') >= pud then return {-6, 0, 0, 0} end
            local tpl = ARGV[7]
            local flat = redis.call('HGETALL', KEYS[7])
            if #flat == 0 then return {-7, 0, 0, 0} end
            local gens = {}
            for i = 1, #flat, 2 do gens[#gens + 1] = {tonumber(flat[i]), tonumber(flat[i + 1])} end
            table.sort(gens, function(a, b) return a[1] < b[1] end)
            local pref = tonumber(ARGV[8])
            local order = {}
            for _, p in ipairs(gens) do if p[1] == pref then order[#order + 1] = p end end
            for _, p in ipairs(gens) do if p[1] ~= pref then order[#order + 1] = p end end
            for _, p in ipairs(order) do
              local base = 'c:stk:{' .. tpl .. '}:' .. p[1] .. ':' .. p[2]
              local stk = redis.call('GET', base)
              if stk and tonumber(stk) > 0 then
                redis.call('INCR', KEYS[1])
                redis.call('EXPIRE', KEYS[1], 604800)
                if pud > 0 then redis.call('INCR', KEYS[2]); redis.call('EXPIRE', KEYS[2], tonumber(ARGV[4])) end
                if dl > 0 then redis.call('INCR', KEYS[3]); redis.call('EXPIRE', KEYS[3], tonumber(ARGV[4])) end
                redis.call('DECR', base)                                              -- E1：stk−1
                local t = redis.call('TIME')
                local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
                redis.call('ZADD', 'c:pend:{' .. tpl .. '}:' .. p[1] .. ':' .. p[2], nowms, ARGV[6])
                redis.call('SET', KEYS[4], '1', 'EX', tonumber(ARGV[5]))
                return {0, ul0 + 1, p[1], p[2]}                                       -- 命中桶固化进令牌
              end
            end
            return {-10, 0, 0, 0}                                                      -- 全桶耗尽→开桶协议
            """;

    /** 记账确认（分代寻址，脚本不变）：dbi+1 与令牌出 pend 原子配对。ZREM==0 → no-op（幂等） */
    public static final String CONFIRM_SCRIPT = """
            -- KEYS: 1 dbi  2 pend(zset)      ARGV: 1 pendMember
            if redis.call('ZREM', KEYS[2], ARGV[1]) == 0 then return 0 end
            redis.call('INCR', KEYS[1])
            return 1
            """;

    /**
     * 取消记账（分代三态）。transient 的世代判据（v2 §4 核心）：未停流 且 HGET(act,b)==g 才 INCR stk；
     * 否则 INCR void（旧代封账/停流退休——跨代回加会凭空制造新代可卖库存=超发）。
     * offline 的"void 吸收全部存量"退役语义移交 REBUILD·FORCE 按桶 retired 分支（v2 §6）。
     */
    public static final String CANCEL_SCRIPT = """
            -- KEYS: 1 ul  2 uld  3 dly  4 idem  5 pend  6 void  7 stk  8 so  9 act(hash)
            -- ARGV: 1 pud  2 dl  3 mode(transient/sold_out/offline)  4 member  5 tpl  6 bucket  7 gen
            -- 【契约：mode 与 Java CancelCause.name().toLowerCase() 严格一致——2026-09-16 反向失配前科】
            if redis.call('ZREM', KEYS[5], ARGV[4]) == 0 then return 0 end
            local pud = tonumber(ARGV[1])
            local dl = tonumber(ARGV[2])
            if redis.call('DECR', KEYS[1]) < 0 then redis.call('SET', KEYS[1], 0) end
            if pud > 0 and redis.call('EXISTS', KEYS[2]) == 1 then
              if redis.call('DECR', KEYS[2]) < 0 then redis.call('SET', KEYS[2], 0) end
            end
            if dl > 0 and redis.call('EXISTS', KEYS[3]) == 1 then
              if redis.call('DECR', KEYS[3]) < 0 then redis.call('SET', KEYS[3], 0) end
            end
            local mode = ARGV[3]
            if mode == 'transient' then
              local soUp = redis.call('EXISTS', KEYS[8]) == 1
              local stillActive = redis.call('HGET', KEYS[9], ARGV[6]) == ARGV[7]
              if (not soUp) and stillActive then
                redis.call('INCR', KEYS[7])     -- 当代且未停流：回加可再卖
              else
                redis.call('INCR', KEYS[6])     -- 旧代/停流：退休（世代隔离判据）
              end
            else
              redis.call('INCR', KEYS[6])       -- sold_out / offline：void 只退休这张令牌
              redis.call('SET', KEYS[8], 1, 'EX', 10)   -- 2e-④：10s 自续期（DEDUCT 命中时续）
            end
            redis.call('DEL', KEYS[4])
            return 1
            """;

    /**
     * E1+E2 原子读：清扫各代 pend 后逐代读四键，返回 {alloc, Σ四键, so存在性, b1,g1,stk1,pend1,dbi1,void1, ...}
     * 遍历 c:gens 全世代集合（含已退役未 GC 的代）——槽位复用重开后旧代账不丢（E2 假破缺修复，
     * 2026-09-19 第8步故障用例推演发现：只数 act 活跃代，重开槽位的旧代 dbi 会凭空消失）。
     */
    public static final String CONSERVATION_SCRIPT = """
            -- KEYS: 1 act(hash)  2 alloc  3 so  4 gens(set)   ARGV: 1 tpl  2 inflightTtlMs
            local t = redis.call('TIME')
            local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local tpl = ARGV[1]
            local members = redis.call('SMEMBERS', KEYS[4])
            local out = {}
            local sum = 0
            for _, bg in ipairs(members) do
              local colon = string.find(bg, ':', 1, true)
              local b = string.sub(bg, 1, colon - 1)
              local g = string.sub(bg, colon + 1)
              local base = '{' .. tpl .. '}:' .. b .. ':' .. g
              redis.call('ZREMRANGEBYSCORE', 'c:pend:' .. base, '-inf', nowms - tonumber(ARGV[2]))
              local stk = tonumber(redis.call('GET', 'c:stk:' .. base) or '0')
              local pend = redis.call('ZCARD', 'c:pend:' .. base)
              local dbi = tonumber(redis.call('GET', 'c:dbi:' .. base) or '0')
              local void = tonumber(redis.call('GET', 'c:void:' .. base) or '0')
              out[#out + 1] = tonumber(b); out[#out + 1] = tonumber(g)
              out[#out + 1] = stk; out[#out + 1] = pend; out[#out + 1] = dbi; out[#out + 1] = void
              sum = sum + stk + pend + dbi + void
            end
            return {tonumber(redis.call('GET', KEYS[2]) or '0'), sum,
                    redis.call('EXISTS', KEYS[3]), unpack(out)}
            """;

    /**
     * 开桶（Redis 侧，v2 §5 ④）：注册新代 + 建四键 + c:alloc INCRBY N。
     * 幂等门（治 CONC-3 崩溃夹缝重试双计）：该桶当代==目标代 且 stk 键已存在 → return 0 不再加 alloc。
     */
    public static final String OPEN_BUCKET_SCRIPT = """
            -- KEYS: 1 act(hash)  2 alloc  3 gens(set)   ARGV: 1 tpl  2 bucket  3 gen  4 quota
            local cur = redis.call('HGET', KEYS[1], ARGV[2])
            if cur and tonumber(cur) and tonumber(cur) > tonumber(ARGV[3]) then
              return -1                                        -- 名册上已是更新的世代：拒绝回退
            end                                                -- （被暂停的开桶者恢复执行时不得把 act 指回旧代）
            if cur == ARGV[3] and redis.call('EXISTS', 'c:stk:{' .. ARGV[1] .. '}:' .. ARGV[2] .. ':' .. ARGV[3]) == 1 then
              return 0
            end
            redis.call('HSET', KEYS[1], ARGV[2], ARGV[3])
            redis.call('SADD', KEYS[3], ARGV[2] .. ':' .. ARGV[3])
            local base = 'c:stk:{' .. ARGV[1] .. '}:' .. ARGV[2] .. ':' .. ARGV[3]
            redis.call('SET', base, tonumber(ARGV[4]))
            redis.call('SET', 'c:dbi:{' .. ARGV[1] .. '}:' .. ARGV[2] .. ':' .. ARGV[3], 0)
            redis.call('SET', 'c:void:{' .. ARGV[1] .. '}:' .. ARGV[2] .. ':' .. ARGV[3], 0)
            redis.call('DEL', 'c:pend:{' .. ARGV[1] .. '}:' .. ARGV[2] .. ':' .. ARGV[3])
            redis.call('INCRBY', KEYS[2], tonumber(ARGV[4]))
            return 1
            """;

    /**
     * Redis 侧灾难恢复（CHAO-4：Redis 重启/AOF 丢失后 act 注册表没了）：仅当 act 为空时，
     * 按 DB 权威行一次性重建注册表+各代四键+alloc=Σquota（SET 非累加——崩溃残余不叠加）。
     */
    public static final String ENSURE_BUCKETS_SCRIPT = """
            -- KEYS: 1 act(hash)  2 alloc  3 gens(set)
            -- ARGV: 1 force(0/1)  2 tpl  3 b1 4 g1 5 q1 6 u1 7 r1  8 b2 ...（五元组 b,g,quota,used,retired(0/1)）
            -- 2026-09-28 评审批 2：输入改"全世代行（活跃+封账真代，排除 V10 历史行）"——
            -- 只重建活跃行会让封账真代的 dbi/void 键缺失 → E2b/镜像方向检查永久破缺、修复熔断。
            -- force=1（2026-09-30 L47）：跳过"名册非空就不动"的门——用于"名册还在、某些代的键丢了"的
            -- 修复（缺的键按行补回；已存在的键一律不覆盖，见下面的 EXISTS 判定）
            local force = ARGV[1] == '1'
            if (not force) and redis.call('HLEN', KEYS[1]) > 0 then return 0 end
            local total = 0
            local created = 0    -- 实际补了几代（0 = 一个都不缺）
            for i = 3, #ARGV, 5 do
              local b, g = ARGV[i], ARGV[i + 1]
              local q, u = tonumber(ARGV[i + 2]), tonumber(ARGV[i + 3])
              local retired = ARGV[i + 4] == '1'
              if not retired then redis.call('HSET', KEYS[1], b, g) end   -- act 只登记活跃代（槽→当代）
              redis.call('SADD', KEYS[3], b .. ':' .. g)                  -- 全世代名册：活跃+封账真代
              local base = 'c:stk:{' .. ARGV[2] .. '}:' .. b .. ':' .. g
              if redis.call('EXISTS', base) == 0 then
                created = created + 1
                if retired then
                  -- 封账真代：按退役推导（stk=0、dbi=used、void=quota−used——与 REBUILD retired 分支同式）
                  redis.call('SET', base, 0)
                  redis.call('SET', 'c:dbi:{' .. ARGV[2] .. '}:' .. b .. ':' .. g, u)
                  redis.call('SET', 'c:void:{' .. ARGV[2] .. '}:' .. b .. ':' .. g, math.max(0, q - u))
                else
                  -- 活跃代：按 quota-used 推导铸 stk（审查 P2：全额铸=凭空回补已卖量）；dbi 同步落 used
                  redis.call('SET', base, math.max(0, q - u))
                  redis.call('SET', 'c:dbi:{' .. ARGV[2] .. '}:' .. b .. ':' .. g, u)
                  redis.call('SET', 'c:void:{' .. ARGV[2] .. '}:' .. b .. ':' .. g, 0)
                end
              end
              total = total + q
            end
            redis.call('SET', KEYS[2], total)
            return created     -- 补了几代（0 = 全部已存在；调用方据此免日志）
            """;

    /**
     * 分代重建（NX|FORCE|SHADOW）。推导锚=该代 DB 真值 used（CONS-3 正解：ic_i 不存在，
     * stock_bucket.used 就是分代权威）。闭式（E1 守恒）：活跃代 stk=quota−used、dbi=used、void=0；
     * 退役代（DB status≠0 或模板 so）stk=0、void=quota−used。护栏：pend 门 + dbi/void 观察栅栏。
     */
    public static final String REBUILD_SCRIPT = """
            -- KEYS: 1 stk  2 pend  3 dbi  4 void  5 so(模板级)  6 act(hash)
            -- ARGV: 1 mode('nx'|'force'|'shadow')  2 tpl  3 bucket  4 gen  5 quota  6 used  7 retired(0/1)  8 ttl  9 dbiObs  10 voidObs
            local mode = ARGV[1]
            local base = '{' .. ARGV[2] .. '}:' .. ARGV[3] .. ':' .. ARGV[4]
            local t = redis.call('TIME')
            local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', 'c:pend:' .. base, '-inf', nowms - tonumber(ARGV[8]))
            local pend = redis.call('ZCARD', 'c:pend:' .. base)
            if mode == 'nx' then
              if redis.call('EXISTS', 'c:stk:' .. base) == 1 then return {0, 0, 0, 0} end
              if redis.call('HGET', KEYS[6], ARGV[3]) ~= ARGV[4] then return {0, 0, 0, 0} end
              local used = tonumber(ARGV[6])
              local stkV = math.max(0, tonumber(ARGV[5]) - used - pend)
              redis.call('SET', 'c:stk:' .. base, stkV)
              if redis.call('EXISTS', 'c:dbi:' .. base) == 0 then redis.call('SET', 'c:dbi:' .. base, used) end
              if redis.call('EXISTS', 'c:void:' .. base) == 0 then redis.call('SET', 'c:void:' .. base, 0) end
              return {1, stkV, used, 0}
            end
            if pend > 0 then return {-1, 0, 0, 0} end
            local dbiNow = tonumber(redis.call('GET', 'c:dbi:' .. base) or '0')
            local voidNow = tonumber(redis.call('GET', 'c:void:' .. base) or '0')
            if dbiNow ~= tonumber(ARGV[9]) or voidNow ~= tonumber(ARGV[10]) then
              return {-2, 0, 0, 0}
            end
            -- 未注册世代护栏（2026-09-28 评审批 2）：名册里没有该桶、或登记的世代比本代更旧——
            -- 说明本代还没走注册协议（行已提交、注册未执行/Redis 刚丢）；此时按 retired 推导
            -- 会把新鲜额度写成 void（不可卖）→ 拒绝执行，交还调用方走注册/ENSURE 路径。
            local cur = redis.call('HGET', KEYS[6], ARGV[3])
            local curNum = cur and tonumber(cur) or nil
            local dbRetired = tonumber(ARGV[7]) == 1
            -- 未注册世代护栏（2026-09-28 评审批 2；2026-09-30 L46 收窄）：
            -- 只拦"数据库还活跃、但名册没有此桶/世代更旧"的行——那种情况按 retired 推导会把
            -- 新鲜额度写成 void（不可卖）。**封账真代必须放行**：它本来就该按退役形态补键，
            -- 而清库重建后 ENSURE 只把活跃代写进名册，封账真代所在桶在名册里没有字段——
            -- 旧写法会让这类模板的 deep 修复整体放弃（-3 直接跳出循环），永远不能自愈。
            if not dbRetired and (curNum == nil or curNum < tonumber(ARGV[4])) then
              return {-3, 0, 0, 0}
            end
            local used = tonumber(ARGV[6])
            -- 当代门（审查 P2）：该桶注册当代≠本代=旧代——按 retired 处理（防 void 复活/误删售罄牌）
            local retired = dbRetired or redis.call('EXISTS', KEYS[5]) == 1
                            or (curNum ~= nil and curNum > tonumber(ARGV[4]))
            local stkV, dbiV, voidV
            if retired then
              stkV = 0; dbiV = used; voidV = tonumber(ARGV[5]) - used
            else
              stkV = math.max(0, tonumber(ARGV[5]) - used); dbiV = used; voidV = 0
            end
            if mode == 'force' then
              redis.call('SET', 'c:stk:' .. base, stkV)
              redis.call('SET', 'c:dbi:' .. base, dbiV)
              redis.call('SET', 'c:void:' .. base, voidV)
              if not retired and stkV > 0 then redis.call('DEL', KEYS[5]) end
            end
            return {1, stkV, dbiV, voidV}
            """;

    private final StringRedisTemplate redis;

    private final DefaultRedisScript<List> deductScript = new DefaultRedisScript<>(DEDUCT_SCRIPT, List.class);
    private final DefaultRedisScript<Long> confirmScript = new DefaultRedisScript<>(CONFIRM_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> cancelScript = new DefaultRedisScript<>(CANCEL_SCRIPT, Long.class);
    private final DefaultRedisScript<List> conservationScript = new DefaultRedisScript<>(CONSERVATION_SCRIPT, List.class);
    private final DefaultRedisScript<Long> openBucketScript = new DefaultRedisScript<>(OPEN_BUCKET_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> ensureBucketsScript = new DefaultRedisScript<>(ENSURE_BUCKETS_SCRIPT, Long.class);
    private final DefaultRedisScript<List> rebuildScript = new DefaultRedisScript<>(REBUILD_SCRIPT, List.class);

    public RedisStockGateway(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 取消原因（决定三态语义） */
    public enum CancelCause { TRANSIENT, SOLD_OUT, OFFLINE }

    /**
     * 扣减参数（纯数据）。day=编排层算一次传入（P0 修复③）。
     * preferredBucket=route(userId,bucketCount) 的路由亲和位（同用户同桶；空则脚本内顺位切换）。
     */
    public record DeductCommand(long templateId, long userId, String requestId,
                                int maxPerUser, int perUserDaily, long dailyLimit,
                                String day, int dayTtlSec, int idemTtlSec, int preferredBucket) {
        /** 兼容旧调用/测试：无路由位（-1=按注册表升序取第一个有余桶） */
        public DeductCommand(long templateId, long userId, String requestId,
                             int maxPerUser, int perUserDaily, long dailyLimit,
                             String day, int dayTtlSec, int idemTtlSec) {
            this(templateId, userId, requestId, maxPerUser, perUserDaily, dailyLimit,
                    day, dayTtlSec, idemTtlSec, -1);
        }
    }

    /** 扣减结果：code 见脚本头；seq=成功时该用户第几张；bucket/gen=命中桶（令牌固化，confirm/cancel 寻址） */
    public record DeductResult(int code, long seq, int bucket, long gen) {
        public boolean success() { return code == 0; }
        /** 兼容旧测试桩：无桶位（-1）——仅测试构造用，生产结果一律来自 deduct() 实测 */
        public DeductResult(int code, long seq) { this(code, seq, -1, -1); }
    }

    /** 单代守恒读数（E1） */
    public record BucketReading(int bucket, long gen, long stk, long pend, long dbi, long voidRetired) {
        public long sum() { return stk + pend + dbi + voidRetired; }
    }

    /** 全局守恒读数（E2）：Σ(活跃代四键和)+alloc 应 ≡ total（total 由调用方从 DB 带入） */
    public record GlobalConservation(long alloc, long redisSum, boolean soldOut, List<BucketReading> buckets) {
        /** 兼容旧消费者的聚合视图（数学上与单键形态同构——巡检迁移期桥接，step6 换 E1/E2/E3） */
        public ConservationReading aggregate() {
            long stk = 0, pend = 0, dbi = 0, voidR = 0;
            for (BucketReading b : buckets) { stk += b.stk(); pend += b.pend(); dbi += b.dbi(); voidR += b.voidRetired(); }
            return new ConservationReading(stk, pend, dbi, voidR, soldOut);
        }
    }

    /** 守恒五键读数（聚合形态，清扫后）——旧签名桥接 */
    public record ConservationReading(long stk, long pend, long dbi, long voidRetired, boolean soldOut) {
        public long sum() { return stk + pend + dbi + voidRetired; }
    }

    /** 分代重建结果 */
    public record RebuildResult(int ret, long stk, long dbi, long voidRetired) {
        public boolean rebuilt() { return ret == 1; }
    }

    public DeductResult deduct(DeductCommand cmd) {
        com.newcoupon.infrastructure.testing.FaultHooks.fire(
                com.newcoupon.infrastructure.testing.FaultHooks.REDIS_CMD_DELAY, "deduct", cmd.templateId());
        String day = cmd.day();
        String member = pendMember(cmd.userId(), cmd.requestId());
        List<?> r = redis.execute(deductScript,
                List.of(ulKey(cmd.templateId(), cmd.userId()),
                        uldKey(day, cmd.templateId(), cmd.userId()), dlyKey(day, cmd.templateId()),
                        idemKey(cmd.templateId(), cmd.userId(), cmd.requestId()),
                        soldoutKey(cmd.templateId()), freezeKey(cmd.templateId()), actKey(cmd.templateId())),
                String.valueOf(cmd.maxPerUser()), String.valueOf(cmd.perUserDaily()),
                String.valueOf(cmd.dailyLimit()), String.valueOf(cmd.dayTtlSec()),
                String.valueOf(cmd.idemTtlSec()), member,
                String.valueOf(cmd.templateId()), String.valueOf(cmd.preferredBucket()));
        return new DeductResult(((Number) r.get(0)).intValue(), ((Number) r.get(1)).longValue(),
                ((Number) r.get(2)).intValue(), ((Number) r.get(3)).longValue());
    }

    /** DB 提交成功后的记账确认（分代寻址）。失败不阻断响应——对账层修复 */
    public boolean confirm(DeductCommand cmd, int bucket, long gen) {
        com.newcoupon.infrastructure.testing.FaultHooks.fire(
                com.newcoupon.infrastructure.testing.FaultHooks.REDIS_CMD_DELAY, "confirm", cmd.templateId());
        Long r = redis.execute(confirmScript,
                List.of(dbiKey(cmd.templateId(), bucket, gen), pendKey(cmd.templateId(), bucket, gen)),
                pendMember(cmd.userId(), cmd.requestId()));
        return r != null && r == 1;
    }

    /** DB 失败后的记账取消（分代三态）。令牌已消费则 no-op；日键用 cmd.day() */
    public boolean cancel(DeductCommand cmd, int bucket, long gen, CancelCause cause) {
        com.newcoupon.infrastructure.testing.FaultHooks.fire(
                com.newcoupon.infrastructure.testing.FaultHooks.REDIS_CMD_DELAY, "cancel", cmd.templateId());
        String day = cmd.day();
        Long r = redis.execute(cancelScript,
                List.of(ulKey(cmd.templateId(), cmd.userId()),
                        uldKey(day, cmd.templateId(), cmd.userId()), dlyKey(day, cmd.templateId()),
                        idemKey(cmd.templateId(), cmd.userId(), cmd.requestId()),
                        pendKey(cmd.templateId(), bucket, gen), voidKey(cmd.templateId(), bucket, gen),
                        stkKey(cmd.templateId(), bucket, gen), soldoutKey(cmd.templateId()), actKey(cmd.templateId())),
                String.valueOf(cmd.perUserDaily()), String.valueOf(cmd.dailyLimit()),
                cause.name().toLowerCase(), pendMember(cmd.userId(), cmd.requestId()),
                String.valueOf(cmd.templateId()), String.valueOf(bucket), String.valueOf(gen));
        return r != null && r == 1;
    }

    /** E1+E2 全量守恒读数（含各代 pend 清扫副作用） */
    public GlobalConservation conservationAll(long templateId, long inflightTtlMs) {
        List<?> r = redis.execute(conservationScript,
                List.of(actKey(templateId), allocKey(templateId), soldoutKey(templateId), gensKey(templateId)),
                String.valueOf(templateId), String.valueOf(inflightTtlMs));
        long alloc = ((Number) r.get(0)).longValue();
        long sum = ((Number) r.get(1)).longValue();
        boolean so = ((Number) r.get(2)).intValue() == 1;
        List<BucketReading> buckets = new ArrayList<>();
        for (int i = 3; i + 5 < r.size() + 1 && i + 5 <= r.size(); i += 6) {
            buckets.add(new BucketReading(((Number) r.get(i)).intValue(), ((Number) r.get(i + 1)).longValue(),
                    ((Number) r.get(i + 2)).longValue(), ((Number) r.get(i + 3)).longValue(),
                    ((Number) r.get(i + 4)).longValue(), ((Number) r.get(i + 5)).longValue()));
        }
        return new GlobalConservation(alloc, sum, so, buckets);
    }

    /** 旧签名桥接（巡检/发布迁移期）：聚合视图。step6 换 E1/E2/E3 形态 */
    public ConservationReading conservation(long templateId, long inflightTtlMs) {
        return conservationAll(templateId, inflightTtlMs).aggregate();
    }

    /** 开桶 Redis 侧（v2 §5 ④）。返回 1=本次执行；0=幂等命中（该代已开，alloc 未双计）；
     * -1=名册上已是更新的世代（拒绝回退——被暂停的开桶者恢复执行时的护栏，什么都不写） */
    public int openBucket(long templateId, int bucket, long gen, long quota) {
        Long r = redis.execute(openBucketScript,
                List.of(actKey(templateId), allocKey(templateId), gensKey(templateId)),
                String.valueOf(templateId), String.valueOf(bucket), String.valueOf(gen), String.valueOf(quota));
        return r == null ? 0 : r.intValue();
    }

    /** 开桶锁是否被持有（判定"真售罄"前排除"有人正在开桶"） */
    public boolean openLockHeld(long templateId) {
        return Boolean.TRUE.equals(redis.hasKey(openingKey(templateId)));
    }

    /**
     * 该代是否"确已终结"（可安全封账）：在途令牌为空 且 余量归零（一个 Lua 原子判）。
     * 2026-09-28 评审批 3（P2-3）：封账判定必须看在途——stk==0 但有令牌在 pend 里时封账，
     * 会把还没提交的合法领取打成"DB status 守卫拒绝"（自伤）；判据原子化后，通过检查的瞬间
     * 起既不会有新令牌（新令牌要求 stk&gt;0）也不会有取消回加（回加要求有令牌）——状态冻结，可封。
     */
    /**
     * 分配镜像重置（2026-09-30，教学追问检出 L44）：把 c:alloc 覆盖写为"数据库口径的额度合计"。
     * 只在"deep 已把全部已注册世代重建成功"之后调用——那时 Redis 的 Σ四键 与 Σquota（镜像口径）
     * 已经逐一相等，覆盖写让 E2a/E2b 同时恢复一致。
     * 补的是什么洞：写 c:alloc 的原本只有开桶脚本（累加）与 ENSURE（覆盖写，但门是"名册为空"）——
     * "名册非空 + 镜像偏大"这个组合此前无任何自动纠偏路径（冻结后请求在闸门首行被 -11 挡回，
     * -7→ENSURE 不可达），只能人工。
     */
    /**
     * 是否存在"名册里有、但余量键缺失"的世代（2026-09-30 L47 的精确判据）。
     * 与"数据库还有可卖行"的区别：后者在**正常惊群**里也成立（尾量正被别人消费），据它降级会把
     * 正常售罄风暴变成 5001 风暴（批 1 评审实锤 73 例误降级）。这里查的是"Redis 真的缺数据"。
     */
    private static final String ANY_STK_MISSING_SCRIPT = """
            -- KEYS: 1 gens(set)   ARGV: 1 tpl
            for _, bg in ipairs(redis.call('SMEMBERS', KEYS[1])) do
              if redis.call('EXISTS', 'c:stk:{' .. ARGV[1] .. '}:' .. bg) == 0 then
                return 1
              end
            end
            return 0
            """;
    private final DefaultRedisScript<Long> anyStkMissingScript =
            new DefaultRedisScript<>(ANY_STK_MISSING_SCRIPT, Long.class);

    /** 名册里是否有世代的余量键缺失（L47：据它决定要不要按数据库行强制补键） */
    public boolean anyStkKeyMissing(long templateId) {
        Long r = redis.execute(anyStkMissingScript, List.of(gensKey(templateId)), String.valueOf(templateId));
        return r != null && r == 1;
    }

    private static final String RESET_ALLOC_SCRIPT = """
            -- KEYS: 1 alloc   ARGV: 1 目标值（= SumQuota 镜像口径，由调用方按数据库行算好）
            redis.call('SET', KEYS[1], tonumber(ARGV[1]))
            return 1
            """;
    private final DefaultRedisScript<Long> resetAllocScript = new DefaultRedisScript<>(RESET_ALLOC_SCRIPT, Long.class);

    /** 分配镜像覆盖写（仅 deep 全世代重建成功后调用——见脚本注释的适用前提） */
    public void resetAlloc(long templateId, long alloc) {
        redis.execute(resetAllocScript, List.of(allocKey(templateId)), String.valueOf(alloc));
    }

    private static final String SETTLED_SCRIPT = """
            -- KEYS: 1 pend(zset)  2 stk
            if redis.call('ZCARD', KEYS[1]) > 0 then return 0 end
            if tonumber(redis.call('GET', KEYS[2]) or '0') > 0 then return 0 end
            return 1
            """;
    private final DefaultRedisScript<Long> settledScript = new DefaultRedisScript<>(SETTLED_SCRIPT, Long.class);

    public boolean generationSettled(long templateId, int bucket, long gen) {
        Long r = redis.execute(settledScript,
                List.of(pendKey(templateId, bucket, gen), stkKey(templateId, bucket, gen)));
        return r != null && r == 1;
    }

    /** Redis 侧灾难恢复：act 为空时按 DB 行重建（五元组 b,g,quota,used,retired 平铺传入） */
    public boolean ensureBuckets(long templateId, List<long[]> bucketGenQuota) {
        return ensureBuckets(templateId, bucketGenQuota, false);
    }

    /** L47（2026-09-30）：强制重建——跳过"名册非空就不动"的门，用于"名册还在、某些代的键丢了"的修复。
     *  幂等由脚本内的"该代 stk 键已存在就不动"保证：只补缺的，绝不覆盖已有的。 */
    public boolean ensureBucketsForce(long templateId, List<long[]> bucketGenQuota) {
        return ensureBuckets(templateId, bucketGenQuota, true);
    }

    private boolean ensureBuckets(long templateId, List<long[]> bucketGenQuota, boolean force) {
        List<String> keys = List.of(actKey(templateId), allocKey(templateId), gensKey(templateId));
        List<String> argv = new ArrayList<>();
        argv.add(force ? "1" : "0");
        argv.add(String.valueOf(templateId));
        for (long[] bgqur : bucketGenQuota) {
            argv.add(String.valueOf(bgqur[0]));   // 桶号
            argv.add(String.valueOf(bgqur[1]));   // 世代
            argv.add(String.valueOf(bgqur[2]));   // quota
            argv.add(String.valueOf(bgqur[3]));   // used
            argv.add(String.valueOf(bgqur[4]));   // retired（0/1）——批 2 起五元组，别漏（漏=ARGV 错位、脚本 u 为 nil）
        }
        Long r = redis.execute(ensureBucketsScript, keys, argv.toArray(new String[0]));
        // 返回值 = 实际补建的代数（0 = 一个都不缺，例如名册已被并发请求抢先补好）——
        // 调用方据此免掉重复日志（清库风暴下 50 个并发各调一次，逐条打日志会刷屏）
        return r != null && r > 0;
    }

    /** 指定（桶,世代）的剩余量读数（null=键不存在）——批量重开的封账判定用 */
    public Long bucketStock(long templateId, int bucket, long gen) {
        String v = redis.opsForValue().get(stkKey(templateId, bucket, gen));
        return v == null ? null : Long.parseLong(v);
    }

    /** 活跃注册表只读（槽->当代世代，字符串形态）——孤儿桶重放/巡检辅助 */
    public java.util.Map<String, String> actEntries(long templateId) {
        return redis.<String, String>opsForHash().entries(actKey(templateId));
    }

    /** 开桶 single-flight 锚（v2 §5 D 簇）：SET NX PX 5000 */
    private static final String RELEASE_LOCK_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """;
    private final DefaultRedisScript<Long> releaseLockScript =
            new DefaultRedisScript<>(RELEASE_LOCK_SCRIPT, Long.class);

    /** 抢开桶锁（返回属主令牌；null=别人持有）。值=UUID——释放时比对，防超时后误删他人锁 */
    public String grabOpenLock(long templateId) {
        String token = java.util.UUID.randomUUID().toString();
        boolean ok = Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(openingKey(templateId), token, java.time.Duration.ofSeconds(5)));
        return ok ? token : null;
    }

    public void releaseOpenLock(long templateId, String token) {
        redis.execute(releaseLockScript, List.of(openingKey(templateId)), token);
    }

    /**
     * 分代重建：NX=Redis 灾后按 DB 行补键；FORCE=守卫修复（推导锚=该代 used）；
     * SHADOW=影子（推导不执行）。
     */
    public RebuildResult rebuildBucket(long templateId, int bucket, long gen, String mode,
                                       long quota, long used, boolean retired,
                                       long inflightTtlMs, long dbiObs, long voidObs) {
        List<?> r = redis.execute(rebuildScript,
                List.of(stkKey(templateId, bucket, gen), pendKey(templateId, bucket, gen),
                        dbiKey(templateId, bucket, gen), voidKey(templateId, bucket, gen),
                        soldoutKey(templateId), actKey(templateId)),
                mode, String.valueOf(templateId), String.valueOf(bucket), String.valueOf(gen),
                String.valueOf(quota), String.valueOf(used), retired ? "1" : "0",
                String.valueOf(inflightTtlMs), String.valueOf(dbiObs), String.valueOf(voidObs));
        return new RebuildResult(((Number) r.get(0)).intValue(), ((Number) r.get(1)).longValue(),
                ((Number) r.get(2)).longValue(), ((Number) r.get(3)).longValue());
    }

    /** B4 清除协议：合法回补/修复使库存>0 时必须清标志，否则模板永久假死 */
    public void clearSoldout(long templateId) {
        redis.delete(soldoutKey(templateId));
    }

    public boolean isSoldout(long templateId) {
        return Boolean.TRUE.equals(redis.hasKey(soldoutKey(templateId)));
    }

    /** 立售罄牌带 TTL（巡检重立用——10s 自续期防永久假死） */
    public void setSoldoutWithTtl(long templateId, int ttlSeconds) {
        redis.opsForValue().set(soldoutKey(templateId), "1", java.time.Duration.ofSeconds(ttlSeconds));
    }

    /** 冻结牌取值标记：自动来源（巡检判危险方向）——只有这种来源允许"干净轮自动解冻" */
    public static final String FREEZE_SOURCE_AUTO = "auto";
    /** 冻结牌取值标记：人工来源（运维介入）——绝不自动解 */
    public static final String FREEZE_SOURCE_MANUAL = "manual";

    public void freeze(long templateId, String source) {
        redis.opsForValue().set(freezeKey(templateId), source);
    }

    public void unfreeze(long templateId) {
        redis.delete(freezeKey(templateId));
    }

    public boolean isFrozen(long templateId) {
        return Boolean.TRUE.equals(redis.hasKey(freezeKey(templateId)));
    }

    /** 是否"自动来源"冻结——巡检只自动解自己置的牌，人工冻结一律不动 */
    public boolean isAutoFrozen(long templateId) {
        return FREEZE_SOURCE_AUTO.equals(redis.opsForValue().get(freezeKey(templateId)));
    }

    /** 对账余量读数（聚合各活跃代；非原子——观测/测试用，巡检走 conservationAll）。
     * ttl 取 MAX_VALUE：观测方法不得有清扫副作用（ttl=0 会把全部在途令牌扫掉——2026-09-19
     * repairGated 红灯实测抓到：remaining() 一调，随后的在途拦截断言就失真） */
    public Long remaining(long templateId) {
        long sum = 0;
        for (BucketReading b : conservationAll(templateId, Long.MAX_VALUE).buckets()) {
            sum += b.stk();
        }
        return sum;
    }

    /** DB 侧已确认计数（聚合；E3c 正式形态=Σdbi ↔ SUM(stock_bucket.used)，step6 接） */
    public Long confirmedCount(long templateId) {
        long sum = 0;
        for (BucketReading b : conservationAll(templateId, Long.MAX_VALUE).buckets()) {
            sum += b.dbi();
        }
        return sum;
    }

    /** 在途令牌数（聚合，不经清理的原始读数，测试用） */
    public long pendingCount(long templateId) {
        long sum = 0;
        for (BucketReading b : conservationAll(templateId, Long.MAX_VALUE).buckets()) {
            sum += b.pend();
        }
        return sum;
    }

    /** 测试与运维用：清除用户累计计数（模拟计数丢失） */
    public void resetUserCount(long templateId, long userId) {
        redis.delete(ulKey(templateId, userId));
    }

    public Long userCount(long templateId, long userId) {
        String v = redis.opsForValue().get(ulKey(templateId, userId));
        return v == null ? null : Long.parseLong(v);
    }

    /** L3 计数自愈：无条件把用户累计计数重置为 DB 权威行数（TTL 对齐 deduct 的 7 天节奏） */
    public void setUserCount(long templateId, long userId, long count) {
        redis.opsForValue().set(ulKey(templateId, userId), String.valueOf(count),
                java.time.Duration.ofSeconds(604800));
    }

    /** L42①（2026-09-30）：无条件把用户"当日"计数重置为 DB 权威值（TTL 对齐 deduct 的 48h 节奏） */
    public void setUserDailyCount(long templateId, long userId, String day, long count) {
        redis.opsForValue().set(uldKey(day, templateId, userId), String.valueOf(count),
                java.time.Duration.ofSeconds(172800));
    }

    // ---------- key helpers（hash tag 一律 {tpl}——见类注释 §6.3.5） ----------

    private String stkKey(long t, int b, long g) { return "c:stk:{" + t + "}:" + b + ":" + g; }
    private String pendKey(long t, int b, long g) { return "c:pend:{" + t + "}:" + b + ":" + g; }
    private String dbiKey(long t, int b, long g) { return "c:dbi:{" + t + "}:" + b + ":" + g; }
    private String voidKey(long t, int b, long g) { return "c:void:{" + t + "}:" + b + ":" + g; }
    private String actKey(long t) { return "c:act:{" + t + "}"; }
    private String allocKey(long t) { return "c:alloc:{" + t + "}"; }
    private String openingKey(long t) { return "c:opening:{" + t + "}"; }
    private String gensKey(long t) { return "c:gens:{" + t + "}"; }
    private String freezeKey(long t) { return "c:fz:{" + t + "}"; }
    private String soldoutKey(long t) { return "c:so:{" + t + "}"; }
    private String ulKey(long t, long u) { return "c:ul:{" + t + "}:" + u; }
    private String uldKey(String d, long t, long u) { return "c:uld:" + d + ":{" + t + "}:" + u; }
    private String dlyKey(String d, long t) { return "c:dly:" + d + ":{" + t + "}"; }
    private String idemKey(long t, long u, String r) { return "c:idem:{" + t + "}:" + u + ":" + r; }
    private String pendMember(long u, String r) { return u + ":" + r; }
}
