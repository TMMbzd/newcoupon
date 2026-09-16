package com.newcoupon.infrastructure.redis;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 库存 Redis 网关（ADR-0009 守恒式形态，2026-09-16 迁移；前身 ADR-0008 恒等式）。
 *
 * <p>key 设计（全部挂 {tplId} hash tag——Lua 多 key 必须同 slot，Cluster 前提）：
 * <pre>
 * 【状态键——四状态分区，入守恒式 stk+pend+dbi+void≡total】
 * c:stk:{tplId}     可领（available）      c:pend:{tplId}  预占（reserved，ZSET，score=Redis TIME）
 * c:dbi:{tplId}     已发放（confirmed）    c:void:{tplId}  已撤销（withdrawn：售罄/下线退役）
 * 【策略/凭证键——不入守恒式】
 * c:so:{tplId}      停流短路标志（售罄与下线退役均立）
 * c:idem:{tpl}:{uid}:{req} 请求去重缓存   c:ul/uld/dly   限领计数（崩溃残余走 L3 自愈）
 * </pre>
 *
 * <p><b>守恒式（唯一第一层不变量，仅对已发布模板强制）</b>：
 * {@code stk + ZCARD(pend,清扫后) + dbi + void ≡ total}——读作业务话术：
 * 剩余+在途+已发放+已撤销=总投放量。取代 issued−dbi−pend≡0（内部记账自洽）——
 * 旧式三盲区：stk 不在式内（改低无检测）、三键整体平移仍平、全清库 0=0 空真。
 *
 * <p><b>五段 Lua</b>（六段→五段，净简化）：
 * DEDUCT（无 issued 计账）；CANCEL（transient 含 so 检测/terminal 两变体）；
 * CONFIRM（不变）；CONSERVATION（清扫+读五键）；REBUILD NX|FORCE（合并懒重建与修复，
 * FORCE 含 ZCARD 门+双键 CAS 栅栏+业务状态分支推导+总开关影子）。
 */
@Component
public class RedisStockGateway {

    /** 0=成功；-1 批次日限；-2 用户总限；-3 售罄(含so短路)；-6 用户日限；-7 库存未初始化；-8 幂等命中 */
    public static final String DEDUCT_SCRIPT = """
            -- KEYS: 1 stk  2 ul  3 uld  4 dly  5 idem  6 pend(zset)  7 so      【ADR-0009：issued 键族删除】
            -- ARGV: 1 maxPerUser  2 perUserDaily  3 dailyLimit  4 dayTtl  5 idemTtl  6 pendMember
            if redis.call('EXISTS', KEYS[7]) == 1 then return {-3, 0} end
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
            redis.call('DECR', KEYS[1])                                            -- 守恒：stk−1
            local t = redis.call('TIME')                                           -- Redis 服务端时钟（多实例漂移归零）
            local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZADD', KEYS[6], nowms, ARGV[6])                            -- 守恒：预占入 pend（+1）
            redis.call('SET', KEYS[5], '1', 'EX', tonumber(ARGV[5]))
            return {0, ul0 + 1}
            """;

    /**
     * 取消记账（ADR-0009 三态）。mode：transient=瞬时（EXISTS 门回加，so 存在时内部改走
     * soldout 终态——防风暴期 INCR 重开闸门）；sold_out=售罄终态（void+1 只退休这张令牌，
     * **不动 stk**——被污染的 stk 留给 FORCE 精确还原）；offline=下线终态（void 吸收存量
     * 并钉零+立 so）。令牌门控 ZREM==0 → no-op 全程幂等。守恒效应：三态均和不变。
     */
    public static final String CANCEL_SCRIPT = """
            -- KEYS: 1 stk  2 ul  3 uld  4 dly  5 idem  6 pend  7 so  8 void
            -- ARGV: 1 pud  2 dl  3 mode(transient/sold_out/offline)  4 pendMember【契约：与 Java CancelCause.name().toLowerCase() 严格一致——2026-09-16 又抓到一次反向失配，测试 void 断言当场暴露】
            if redis.call('ZREM', KEYS[6], ARGV[4]) == 0 then return 0 end
            local pud = tonumber(ARGV[1])
            local dl = tonumber(ARGV[2])
            if redis.call('DECR', KEYS[2]) < 0 then redis.call('SET', KEYS[2], 0) end
            if pud > 0 and redis.call('EXISTS', KEYS[3]) == 1 then
              if redis.call('DECR', KEYS[3]) < 0 then redis.call('SET', KEYS[3], 0) end
            end
            if dl > 0 and redis.call('EXISTS', KEYS[4]) == 1 then
              if redis.call('DECR', KEYS[4]) < 0 then redis.call('SET', KEYS[4], 0) end
            end
            local mode = ARGV[3]
            if mode == 'transient' and redis.call('EXISTS', KEYS[7]) == 1 then
              mode = 'sold_out'         -- v2：风暴期 so 已立→瞬态回加会重开闸门，改走终态
            end
            if mode == 'sold_out' then
              redis.call('INCR', KEYS[8])                                           -- 守恒：void+1（只退休令牌）
              redis.call('SET', KEYS[7], 1)                                         -- B4：停流短路
            elseif mode == 'offline' then
              local stkV = redis.call('GET', KEYS[1])
              local absorbed = 0
              if stkV then absorbed = tonumber(stkV); redis.call('SET', KEYS[1], 0) end
              redis.call('INCRBY', KEYS[8], absorbed + 1)                           -- 守恒：void 吸收存量+这张令牌
              redis.call('SET', KEYS[7], 1)                                         -- 退役标志（防 transient INCR 重开）
            else
              if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]) end  -- 存在门：缺失不造键（交 REBUILD）
            end
            redis.call('DEL', KEYS[5])
            return 1
            """;

    /**
     * 记账确认（不变）：dbi+1 与令牌出 pend 原子配对。ZREM==0 → no-op。
     */
    public static final String CONFIRM_SCRIPT = """
            -- KEYS: 1 dbi  2 pend(zset)      ARGV: 1 pendMember
            if redis.call('ZREM', KEYS[2], ARGV[1]) == 0 then return 0 end
            redis.call('INCR', KEYS[1])
            return 1
            """;

    /**
     * 守恒读数（原 IDENTITY，ADR-0009 §3 第一层）：清扫过期在途令牌后原子读五键。
     * 崩溃残留的合法外衣在清扫后被撕掉（故意破缺）。时间权威=Redis TIME。
     */
    public static final String CONSERVATION_SCRIPT = """
            -- KEYS: 1 stk  2 pend(zset)  3 dbi  4 void  5 so    ARGV: 1 inflightTtlMs
            local t = redis.call('TIME')
            local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', nowms - tonumber(ARGV[1]))
            return {tonumber(redis.call('GET', KEYS[1]) or '0'),
                    redis.call('ZCARD', KEYS[2]),
                    tonumber(redis.call('GET', KEYS[3]) or '0'),
                    tonumber(redis.call('GET', KEYS[4]) or '0'),
                    redis.call('EXISTS', KEYS[5])}
            """;

    /**
     * 重建（NX|FORCE 合并原 LAZY_REBUILD+REPAIR；ADR-0009 §2/§4）。
     * ARGV: 1 mode('nx'|'force'|'shadow')  2 total  3 ic  4 inflightTtlMs  5 dbiObs  6 voidObs
     * NX（-7 懒重建，领券可用性路径——总开关不拦）：清扫；stk 存在→放弃（清库风暴首个生效）；
     *   否则 dbi 缺失→SET dbi:=ic；SET stk=max(0,total−dbi−ZCARD−void)。选择性丢失精确、全清库近似。
     * FORCE（守卫修复）：ZCARD 门（在途非空→-1 推迟）；双键栅栏（GET dbi≠dbiObs 或 GET void≠voidObs
     *   →-2 中止——封"读数与执行之间混入 confirm/terminal"；纯 confirm-lost 形态 dbiObs&lt;ic 放行）；
     *   分支推导：退役态（so 存在或 ic≥total）→ stk=0,dbi=ic,void=total−ic（污染洗白归零）；
     *   活跃态 → stk=max(0,total−ic),dbi=ic,void=0，stk&gt;0 则 DEL so（B4 清除协议）。
     * SHADOW（影子模式，v3 修订8）：推导同 FORCE 但不执行 SET——返回推导值供落表对比。
     * 返回：{ret, stk, dbi, void}  ret: 1=已重建/推导, 0=NX放弃, -1=在途推迟, -2=栅栏中止
     */
    public static final String REBUILD_SCRIPT = """
            -- KEYS: 1 stk  2 pend(zset)  3 dbi  4 void  5 so
            local mode = ARGV[1]
            local total = tonumber(ARGV[2])
            local ic = tonumber(ARGV[3])
            local t = redis.call('TIME')
            local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', nowms - tonumber(ARGV[4]))
            local pend = redis.call('ZCARD', KEYS[2])
            if mode == 'nx' then
              if redis.call('EXISTS', KEYS[1]) == 1 then return {0, 0, 0, 0} end
              local dbiV = tonumber(redis.call('GET', KEYS[3]) or '0')
              if redis.call('EXISTS', KEYS[3]) == 0 then
                dbiV = ic
                redis.call('SET', KEYS[3], ic)
              end
              local voidV = tonumber(redis.call('GET', KEYS[4]) or '0')
              local stkV = math.max(0, total - dbiV - pend - voidV)
              redis.call('SET', KEYS[1], stkV)
              return {1, stkV, dbiV, voidV}
            end
            -- force / shadow
            if pend > 0 then return {-1, 0, 0, 0} end
            local dbiNow = tonumber(redis.call('GET', KEYS[3]) or '0')
            local voidNow = tonumber(redis.call('GET', KEYS[4]) or '0')
            if dbiNow ~= tonumber(ARGV[5]) or voidNow ~= tonumber(ARGV[6]) then
              return {-2, 0, 0, 0}
            end
            local retired = redis.call('EXISTS', KEYS[5]) == 1 or ic >= total
            local stkV, dbiV, voidV
            if retired then
              stkV = 0; dbiV = ic; voidV = total - ic
            else
              stkV = math.max(0, total - ic); dbiV = ic; voidV = 0
            end
            if mode == 'force' then
              redis.call('SET', KEYS[1], stkV)
              redis.call('SET', KEYS[3], dbiV)
              redis.call('SET', KEYS[4], voidV)
              if not retired and stkV > 0 then redis.call('DEL', KEYS[5]) end
            end
            return {1, stkV, dbiV, voidV}
            """;

    private final StringRedisTemplate redis;

    private final DefaultRedisScript<List> deductScript = new DefaultRedisScript<>(DEDUCT_SCRIPT, List.class);
    private final DefaultRedisScript<Long> confirmScript = new DefaultRedisScript<>(CONFIRM_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> cancelScript = new DefaultRedisScript<>(CANCEL_SCRIPT, Long.class);
    private final DefaultRedisScript<List> conservationScript = new DefaultRedisScript<>(CONSERVATION_SCRIPT, List.class);
    private final DefaultRedisScript<List> rebuildScript = new DefaultRedisScript<>(REBUILD_SCRIPT, List.class);

    public RedisStockGateway(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 取消原因（决定三态语义） */
    public enum CancelCause { TRANSIENT, SOLD_OUT, OFFLINE }

    /**
     * 扣减参数（纯数据）。day=编排层算一次传入的日限键日期（P0 修复③：跨天失败取消扣错日键）。
     */
    public record DeductCommand(long templateId, long userId, String requestId,
                                int maxPerUser, int perUserDaily, long dailyLimit,
                                String day, int dayTtlSec, int idemTtlSec) {
    }

    /** 扣减结果：code 见脚本头；seq=成功时该用户第几张 */
    public record DeductResult(int code, long seq) {
        public boolean success() { return code == 0; }
    }

    /** 守恒五键读数（清扫后） */
    public record ConservationReading(long stk, long pend, long dbi, long voidRetired, boolean soldOut) {
        public long sum() { return stk + pend + dbi + voidRetired; }
    }

    /** 重建结果 */
    public record RebuildResult(int ret, long stk, long dbi, long voidRetired) {
        public boolean rebuilt() { return ret == 1; }
    }

    public DeductResult deduct(DeductCommand cmd) {
        String day = cmd.day();
        String member = pendMember(cmd.userId(), cmd.requestId());
        List<?> r = redis.execute(deductScript,
                List.of(stockKey(cmd.templateId()), ulKey(cmd.templateId(), cmd.userId()),
                        uldKey(day, cmd.templateId(), cmd.userId()), dlyKey(day, cmd.templateId()),
                        idemKey(cmd.templateId(), cmd.userId(), cmd.requestId()),
                        pendKey(cmd.templateId()), soldoutKey(cmd.templateId())),
                String.valueOf(cmd.maxPerUser()), String.valueOf(cmd.perUserDaily()),
                String.valueOf(cmd.dailyLimit()), String.valueOf(cmd.dayTtlSec()),
                String.valueOf(cmd.idemTtlSec()), member);
        return new DeductResult(((Number) r.get(0)).intValue(), ((Number) r.get(1)).longValue());
    }

    /** DB 提交成功后的记账确认：dbi+1 与令牌出清原子配对。失败不阻断响应——对账层修复 */
    public boolean confirm(DeductCommand cmd) {
        Long r = redis.execute(confirmScript,
                List.of(dbiKey(cmd.templateId()), pendKey(cmd.templateId())),
                pendMember(cmd.userId(), cmd.requestId()));
        return r != null && r == 1;
    }

    /** DB 失败后的记账取消（三态）。令牌已消费则 no-op；日键用 cmd.day() */
    public boolean cancel(DeductCommand cmd, CancelCause cause) {
        String day = cmd.day();
        Long r = redis.execute(cancelScript,
                List.of(stockKey(cmd.templateId()), ulKey(cmd.templateId(), cmd.userId()),
                        uldKey(day, cmd.templateId(), cmd.userId()), dlyKey(day, cmd.templateId()),
                        idemKey(cmd.templateId(), cmd.userId(), cmd.requestId()),
                        pendKey(cmd.templateId()), soldoutKey(cmd.templateId()), voidKey(cmd.templateId())),
                String.valueOf(cmd.perUserDaily()), String.valueOf(cmd.dailyLimit()),
                cause.name().toLowerCase(), pendMember(cmd.userId(), cmd.requestId()));
        return r != null && r == 1;
    }

    /** 守恒五键读数（含过期在途清理副作用） */
    public ConservationReading conservation(long templateId, long inflightTtlMs) {
        List<?> r = redis.execute(conservationScript,
                List.of(stockKey(templateId), pendKey(templateId), dbiKey(templateId),
                        voidKey(templateId), soldoutKey(templateId)),
                String.valueOf(inflightTtlMs));
        return new ConservationReading(((Number) r.get(0)).longValue(), ((Number) r.get(1)).longValue(),
                ((Number) r.get(2)).longValue(), ((Number) r.get(3)).longValue(),
                ((Number) r.get(4)).intValue() == 1);
    }

    /**
     * 重建：NX=懒重建（领券 -7 路径，总开关不拦）；FORCE=守卫修复（含栅栏与分支推导）；
     * SHADOW=影子模式（推导不执行）。
     */
    public RebuildResult rebuild(long templateId, String mode, long total, long ic,
                                 long inflightTtlMs, long dbiObs, long voidObs) {
        List<?> r = redis.execute(rebuildScript,
                List.of(stockKey(templateId), pendKey(templateId), dbiKey(templateId),
                        voidKey(templateId), soldoutKey(templateId)),
                mode, String.valueOf(total), String.valueOf(ic), String.valueOf(inflightTtlMs),
                String.valueOf(dbiObs), String.valueOf(voidObs));
        return new RebuildResult(((Number) r.get(0)).intValue(), ((Number) r.get(1)).longValue(),
                ((Number) r.get(2)).longValue(), ((Number) r.get(3)).longValue());
    }

    /** 发布/重新上线的整本重置协议（ADR-0009 §2）：stk=total−ic、dbi:=ic、DEL pend、void:=0、DEL so */
    public void resetAll(long templateId, long total, long ic) {
        redis.opsForValue().set(stockKey(templateId), String.valueOf(Math.max(0, total - ic)));
        redis.opsForValue().set(dbiKey(templateId), String.valueOf(ic));
        redis.delete(pendKey(templateId));
        redis.opsForValue().set(voidKey(templateId), "0");
        redis.delete(soldoutKey(templateId));
    }

    /** 兼容旧名（发布路径） */
    public void initStock(long templateId, long amount) {
        redis.opsForValue().set(stockKey(templateId), String.valueOf(amount));
    }

    /** 兼容旧调用方（-7 懒重建走 rebuild NX） */
    public boolean lazyRebuildStock(long templateId, long amount) {
        // ADR-0009：懒重建改走 REBUILD·NX（含清扫+守恒推导），amount 参数仅作日志参考不再直接 SET
        return rebuild(templateId, "nx", amount, 0, 0, 0, 0).ret == 1;
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

    /** DB 侧已确认计数（守恒式第三态读数） */
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

    /** L3 计数自愈：无条件把用户累计计数重置为 DB 权威行数（TTL 对齐 deduct 的 7 天节奏） */
    public void setUserCount(long templateId, long userId, long count) {
        redis.opsForValue().set(ulKey(templateId, userId), String.valueOf(count),
                java.time.Duration.ofSeconds(604800));
    }

    /** 限领计数（ul/uld/dly 的 key 全部保持不变——不入守恒体系） */
    private String stockKey(long t) { return "c:stk:{" + t + "}"; }
    private String ulKey(long t, long u) { return "c:ul:{" + t + "}:" + u; }
    private String uldKey(String d, long t, long u) { return "c:uld:" + d + ":{" + t + "}:" + u; }
    private String dlyKey(String d, long t) { return "c:dly:" + d + ":{" + t + "}"; }
    private String dbiKey(long t) { return "c:dbi:{" + t + "}"; }
    private String voidKey(long t) { return "c:void:{" + t + "}"; }
    private String pendKey(long t) { return "c:pend:{" + t + "}"; }
    private String soldoutKey(long t) { return "c:so:{" + t + "}"; }

    private String idemKey(long t, long u, String r) { return "c:idem:{" + t + "}:" + u + ":" + r; }
    private String pendMember(long u, String r) { return u + ":" + r; }
}
