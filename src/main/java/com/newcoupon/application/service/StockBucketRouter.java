package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.StockBucketMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import com.newcoupon.infrastructure.persistence.po.StockBucketPO;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 桶路由器（P2b v2 §3/§5/§11）：路由亲和、开桶协议（single-flight+尾量+夹缝钩子）、Redis 灾后重建。
 *
 * <p>本组件直接注入 Mapper（而非领域仓储）——它本身是"Redis 世界与 DB 分桶账本之间的搬运工"，
 * 操作的就是持久化形态的数据（桶行/allocated），过领域层纯属绕路。
 */
@Component
public class StockBucketRouter {

    private static final Logger log = LoggerFactory.getLogger(StockBucketRouter.class);

    private final RedisStockGateway gateway;
    private final TemplateMapper templateMapper;
    private final StockBucketMapper bucketMapper;

    /** 每桶额度（v2 §3：10000——DB 每万笔领券被打扰一次） */
    @Value("${coupon.sharding.bucket-size:10000}")
    private long bucketSize;

    /** 槽位数（v2 §3：16——双重职责：Redis 热 key 分散 + DB 桶行分散 312 TPS/行） */
    @Value("${coupon.sharding.bucket-count:16}")
    private int bucketCount;

    public StockBucketRouter(RedisStockGateway gateway,
                             TemplateMapper templateMapper,
                             StockBucketMapper bucketMapper) {
        this.gateway = gateway;
        this.templateMapper = templateMapper;
        this.bucketMapper = bucketMapper;
    }

    /** 启动校验（审查 P2：bucket-size=0 笔误 → casAllocate(0) 恒成功 → 零额度开桶行无界堆积） */
    @jakarta.annotation.PostConstruct
    void validateConfig() {
        if (bucketSize < 1 || bucketCount < 1) {
            throw new IllegalStateException("coupon.sharding.bucket-size/bucket-count 必须 ≥1，当前 size="
                    + bucketSize + ", count=" + bucketCount);
        }
    }

    /**
     * 用户 → 桶号（公开纯函数，USAB-10：测试可预计算桶号直连桶键断言）。
     * Fibonacci 散列——裸 {@code userId % n} 在连续 userId（发号器产物）下会连续落同桶。
     *
     * @throws IllegalArgumentException bucketCount&lt;1（总监盲区⑦：模零除法全站 500 防护）
     */
    public static int route(long userId, int bucketCount) {
        if (bucketCount < 1) {
            throw new IllegalArgumentException("bucketCount must be >= 1 (防模零：coupon.sharding.bucket-count)");
        }
        long h = userId * 0x9E3779B97F4A7C15L;   // 2^64/φ 黄金比例散列
        h ^= (h >>> 32);                          // 高低位混合，放大小差异
        return (int) Long.remainderUnsigned(h, bucketCount);
    }

    /** 路由亲和位（编排层构造 DeductCommand 用） */
    public int preferred(long userId) {
        return route(userId, bucketCount);
    }

    /**
     * 发布初始化：循环开桶直到额度分完或槽位占满（v2 §2/§7）。
     * 开满策略：Σquota=total（全部额度入桶）——-10 开桶协议实际服务于 appendStock/灾后补建/未来部分分配策略。
     */
    public void openInitialBuckets(long templateId) {
        CouponTemplatePO t = templateMapper.selectById(templateId);
        if (t == null) {
            return;
        }
        // 槽位上限（2026-09-19 二次修正：额度驱动"开到分完"会在发布时提前复用槽位——
        // 把还有存货的旧代封账=困库存。部分分配（total>count×size）是合法态：余量等
        // 桶耗尽的 -10 重开路径补（那时复用封账才安全——旧代已空）。E2b 同步改锚 Σquota。
        int opened = 0;
        while (opened < bucketCount) {
            if (!tryOpenBucket(templateId)) {
                break;   // allocated==total（分完）或 CAS 失败——前者是正常出口
            }
            opened++;
        }
        log.info("initial buckets opened: templateId={}, buckets={}, allocated={}",
                templateId, opened, templateMapper.selectById(templateId).getAllocated());
    }

    /**
     * 开桶协议（v2 §5，D 簇）。返回 true=新桶已开（调用方重试 deduct）。
     * <pre>
     * ① c:opening NX 单飞锚：抢不到=别人在开，返回 false（调用方短退避后重试 deduct）
     * ② DB：casAllocate(n=min(bucketSize, total-allocated))——尾量语义；0 行=真售罄
     * ③ 【openbucket.seam 故障注入缝】：DB 提交后、Redis EVAL 前——崩溃夹缝钩子（红灯基建）
     * ④ 选空闲槽位 + 世代=该桶 maxGen+1 → 写"额度段上界"（L37：用暂停之前读到的 Σquota 算，
     *    同一段额度只允许建一行；撞 uk_tpl_allocated_hi = 并发者已建，放弃并返回 true）
     * ⑤ 复用槽位时封旧代（L37 调整：建行成功之后才封——被撞键挡下时不得先封别人的行）
     * ⑥ Redis 开桶 Lua（幂等门防 c:alloc 双计）+ 释放锚
     * 顺序铁律：先 DB 后 Redis——夹缝偏差永远落在 c:alloc&lt;allocated 安全方向（E3a 补建）
     * </pre>
     */
    public boolean tryOpenBucket(long templateId) {
        String lockToken = gateway.grabOpenLock(templateId);
        if (lockToken == null) {
            return false;
        }
        try {
            CouponTemplatePO t = templateMapper.selectById(templateId);
            if (t == null || t.getAllocated() == null) {
                return false;
            }
            long total = t.getTotalCount();
            long allocated = t.getAllocated();
            // 夹缝差额恢复（CHAO-1 自愈路径，2026-09-19 故障用例逼出）：上次开桶中断在
            // 「DB CAS 已提交、行/Redis 未建」——allocated 已含那笔 n 但 Σ(行quota) 没有。
            // 补建差额，不重复 CAS（否则差额永远卡成"已分完"的假售罄）。
            // 孤儿桶重放（审查 P1：insert 已提交但 Redis EVAL 未执行的窗口——行在、注册无）：
            // 幂等门保证重放安全（已注册则 no-op）；重放后本请求的 deduct 重试可达
            boolean replayed = replayOrphanBuckets(templateId);
            if (replayed) {
                return true;   // 有桶被补登记——调用方直接重扣
            }
            long rowsQuota = bucketMapper.sumQuota(templateId);
            long n;
            if (rowsQuota < allocated) {
                n = Math.min(bucketSize, allocated - rowsQuota);
                log.warn("open-bucket gap recovery: templateId={}, allocated={}, Σquota={} → 补建 {}（不重复 CAS）",
                        templateId, allocated, rowsQuota, n);
            } else if (allocated >= total) {
                return false;   // 真售罄：额度已分完（allocated 单调不减，v2 §9）
            } else {
                n = Math.min(bucketSize, total - allocated);
                if (templateMapper.casAllocate(templateId, n) == 0) {
                    return false;
                }
            }
            // 故障注入缝：DB 已提交、Redis 未执行——CONC-2/CHAO-1 的崩溃夹缝窗口（测试专用，生产零开销）
            com.newcoupon.infrastructure.testing.FaultHooks.fire(
                    com.newcoupon.infrastructure.testing.FaultHooks.OPEN_BUCKET_SEAM, templateId);
            int slot = pickFreeSlot(templateId);
            int gen = bucketMapper.maxGen(templateId, slot) + 1;
            StockBucketPO row = new StockBucketPO();
            row.setTemplateId(templateId);
            row.setBucketNo(slot);
            row.setGeneration(gen);
            row.setQuota(n);
            row.setUsed(0L);
            row.setStatus(0);
            // L37：本行覆盖的额度段 = [rowsQuota, rowsQuota + n]，这个上界必须用进入本方法时读到的
            // rowsQuota 来算——绝不能在插行时重新读一次。重新读会把"被暂停期间别人补建的那一段"
            // 错算成本行的新段，唯一键就撞不上，重复建行照样发生。
            row.setAllocatedHi(rowsQuota + n);
            try {
                bucketMapper.insert(row);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // L37：这一段额度已经被并发的开桶者建过行（锁过期后恢复执行的重复写入）——
                // 放弃本行、直接返回 true：对方那行的入账已经成立，调用方重扣即可命中它。
                // 封账改到插行之后执行（见下），所以走到这里时还没有封掉任何行。
                log.warn("重复建行已拦截（并发开桶者已为这一段额度建好行）: templateId={}, segment=[{}..{}], "
                        + "slot={}, gen={}", templateId, rowsQuota, rowsQuota + n, slot, gen);
                return true;
            }
            if (gen > 1) {
                // 复用槽位=旧代存在——封账（status=1：ENSURE 取 maxGen、镜像臂转活跃口径的前提）
                // L37 实施期调整：本行建成功之后才封——建行被唯一键挡下时不得先封掉别人的行
                sealOldGenerations(templateId, slot, gen);
            }
            int rc = gateway.openBucket(templateId, slot, gen, n);
            if (rc == -1) {
                // L38 修复（评审 P1-1）：名册上已是更新的世代——本代（暂停前算出的）注册被拒绝，
                // 什么都没写。放弃本次注册（绝不把 act 指回旧代）；调用方重扣即可命中新代的桶。
                log.warn("stale generation registration rejected (newer gen active): templateId={}, slot={}, gen={}",
                        templateId, slot, gen);
                return true;
            }
            gateway.clearSoldout(templateId);   // L38：新桶带可卖库存——清停流牌（B4 清除协议）
            log.info("bucket opened: templateId={}, slot={}, gen={}, quota={}", templateId, slot, gen, n);
            return true;
        } finally {
            gateway.releaseOpenLock(templateId, lockToken);
        }
    }

    /**
     * -7 灾后重建（v2 §6.1 ENSURE；CHAO-4：Redis 重启/AOF 丢 act）：
     * act 注册表空 → 按 DB 权威行一次性重建（SET 非累加，不叠崩溃残余）。
     * 2026-09-28 评审批 2：输入改为**全世代行**（活跃 + 封账真代，排除 V10 历史行）——
     * 只重建活跃行会让封账真代的 dbi/void 键缺失 → E2b/镜像方向检查永久破缺、自动修复熔断。
     * 封账真代按退役推导（stk=0、dbi=used、void=quota−used），只进全世代名册不进活跃名册。
     * 返回 true=执行了重建（调用方重试 deduct）。
     */
    public boolean ensureRegisteredBuckets(long templateId) {
        var rows = bucketMapper.selectRegistered(templateId);
        if (rows.isEmpty()) {
            return false;
        }
        boolean rebuilt = gateway.ensureBuckets(templateId,
                rows.stream().map(r -> new long[]{r.getBucketNo(), r.getGeneration(),
                        r.getQuota(), r.getUsed(),
                        r.getStatus() != null && r.getStatus() != 0 ? 1L : 0L})
                        .toList());   // 五元组：ENSURE 按行状态推导铸键（活跃 stk=q−u / 封账 stk=0、void=q−u）
        if (rebuilt) {
            log.warn("buckets ENSURED from DB rows (redis lost registry?): templateId={}, rows={}",
                    templateId, rows.size());
            boolean anyStock = rows.stream().anyMatch(r ->
                    (r.getStatus() == null || r.getStatus() == 0) && r.getQuota() > r.getUsed());
            if (anyStock) {
                gateway.clearSoldout(templateId);   // L38：重建带回可卖库存——清停流牌（B4 清除协议）
            }
        }
        return true;
    }

    /**
     * L47（2026-09-30）：按数据库行**强制**补建缺失的 Redis 键（"名册还在、某些代的键丢了"的形态）。
     * 与 {@link #ensureRegisteredBuckets} 的唯一区别：不跳过"名册非空"的门。幂等：只补缺的键。
     * 补回可卖库存时清停流牌（B4 清除协议）。
     */
    public boolean rebuildMissingKeys(long templateId) {
        // 与开桶互斥（2026-09-30 实施期实锤）：重建会把 alloc 覆盖写成 Σquota——若与"在途开桶"并发，
        // 开桶脚本随后的 INCRBY 会把同一份额度加第二次（镜像偏大 → 巡检按危险方向冻结）。
        // 拿同一把单飞锁让两者串行；抢不到锁就本轮不动（下一个请求再来）。
        String lockToken = gateway.grabOpenLock(templateId);
        if (lockToken == null) {
            return false;
        }
        try {
            // 存在"已划出、未建行"的差额 = 有开桶动作没做完——那种场景该走差额恢复（单桶路径），
            // 不能在这里按行重建（行还没建，Σquota 也不是终态）。
            if (hasAllocationGap(templateId)) {
                return false;
            }
            var rows = bucketMapper.selectRegistered(templateId);
            if (rows.isEmpty()) {
                return false;
            }
            boolean rebuilt = gateway.ensureBucketsForce(templateId,
                    rows.stream().map(r -> new long[]{r.getBucketNo(), r.getGeneration(),
                            r.getQuota(), r.getUsed(),
                            r.getStatus() != null && r.getStatus() != 0 ? 1L : 0L})
                            .toList());
            if (rebuilt) {
                boolean anyStock = rows.stream().anyMatch(r ->
                        (r.getStatus() == null || r.getStatus() == 0) && r.getQuota() > r.getUsed());
                if (anyStock) {
                    gateway.clearSoldout(templateId);
                }
                log.warn("missing redis keys rebuilt from DB rows (registry alive): templateId={}, rows={}",
                        templateId, rows.size());
            }
            return rebuilt;
        } finally {
            gateway.releaseOpenLock(templateId, lockToken);
        }
    }

    /** L38（评审 P2-1 的三条件之一）：是否存在"已划出、未建行"的差额——含被暂停的开桶者
     * 留在途中的划款（判"真售罄"前必须排除：这笔钱马上就要变成桶行）。 */
    public boolean hasAllocationGap(long templateId) {
        CouponTemplatePO t = templateMapper.selectById(templateId);
        if (t == null || t.getAllocated() == null) {
            return false;
        }
        return bucketMapper.sumQuota(templateId) < t.getAllocated();
    }

    /** L38（评审 P2-2）：令牌所在桶行是否"确实用满"——真售罄的正当归因；
     * 行被封账/瞬时拒绝不算（不该因此立模板级停流牌）。 */
    public boolean bucketRowFull(long templateId, int bucket, long gen) {
        if (bucket < 0 || gen < 0) {
            return false;   // 兼容旧测试桩的无桶位结果
        }
        StockBucketPO row = bucketMapper.findByTplBucketGen(templateId, bucket, (int) gen);
        return row != null && row.getUsed() != null && row.getQuota() != null
                && row.getUsed() >= row.getQuota();
    }

    /** L38（评审 P2-2）：DB 侧是否已经"没有任何可卖的行"（used<quota 的活跃行）——钉售罄牌前与
     * bucketRowFull 配套的权威判据。刻意不用 Redis 余量：污染场景（Redis 被写入多余 stk）下
     * Redis 会谎报有货——拿谎报者当证人会让"数据库已售罄"被翻案，破坏"钉零 + 交付 FORCE 清污染"语义。 */
    public boolean noSellableRowInDb(long templateId) {
        return bucketMapper.countSellableRows(templateId) == 0;
    }

    /** 孤儿桶重放：DB 活跃行未注册进 Redis act 的，重放开桶 Lua（幂等）。true=有补登记 */
    private boolean replayOrphanBuckets(long templateId) {
        var registered = gateway.actEntries(templateId);   // 槽 -> 当代世代
        boolean replayed = false;
        for (StockBucketPO row : bucketMapper.selectActive(templateId)) {
            String cur = registered.get(String.valueOf(row.getBucketNo()));
            if (cur == null || Long.parseLong(cur) < row.getGeneration()) {
                int rc = gateway.openBucket(templateId, row.getBucketNo(), row.getGeneration(), row.getQuota());
                if (rc == -1) {
                    continue;   // 名册在上面的读取之后已被更新的世代占据——本次不重放（脚本护栏兜底）
                }
                log.warn("orphan bucket replayed (row committed but EVAL never ran): "
                        + "templateId={}, slot={}, gen={}, quota={}",
                        templateId, row.getBucketNo(), row.getGeneration(), row.getQuota());
                replayed = true;
            }
        }
        return replayed;
    }

    /** 封账旧代（status=1）——世代账本闭合的 DB 侧写方。
     * L38 修复（评审 P1-9）：改定值 CAS（只置 status），不再整行回写——
     * 整行回写会把 selectActive 与 updateById 之间并发提交的 used+1 抹回旧值（账目永久少记）。 */
    private void sealOldGenerations(long templateId, int slot, int newGen) {
        for (StockBucketPO row : bucketMapper.selectActive(templateId)) {
            if (row.getBucketNo() == slot && row.getGeneration() < newGen
                    && gateway.generationSettled(templateId, slot, row.getGeneration())) {
                // 批量 3 P2-3：在途令牌未清的旧代本轮不封（DB 的 used<quota 仍兜底）；
                // 出清后由 refill ① 的封账循环接管（它遍历活跃行）
                bucketMapper.seal(row.getId());
            }
        }
    }

    /**
     * 批量重开（2026-09-21 修"大盘模板全桶耗尽后单桶补=单行热点"，用户实锤）：-10 触发时
     * 一波完成"封所有空代 → 循环开桶直到额度分完或槽满"——单飞锁保护的是"一批只有一人执行"，
     * 不是"一次只开一只"。封账判定看 Redis 余量（stk==0 的活跃代=卖完的真代；留有余量的不动）。
     * 崩溃夹缝/孤儿桶仍由 tryOpenBucket 兜底（本方法 false 时调用方回退它）。
     */
    public boolean refillExhaustedBuckets(long templateId) {
        String lockToken = gateway.grabOpenLock(templateId);
        if (lockToken == null) {
            return false;
        }
        try {
            CouponTemplatePO t = templateMapper.selectById(templateId);
            if (t == null || t.getAllocated() == null || t.getAllocated() >= t.getTotalCount()) {
                return false;   // 真售罄/模板没了
            }
            long total = t.getTotalCount();
            long allocated = t.getAllocated();
            // ① 封账所有"余量为 0"的活跃代——腾出槽位（有余量的不动：-10 理论上不该发生，防御）。
            // L38 修复（评审 P1-2）：区分"键不存在"与"余量 0"——键不存在 = 从未注册（行已提交、
            // 注册未执行的中断残留），先补注册（重放），绝不按空代封账（封了孤儿重放就永远绕不回来）。
            for (StockBucketPO row : bucketMapper.selectActive(templateId)) {
                Long stk = gateway.bucketStock(templateId, row.getBucketNo(), row.getGeneration());
                if (stk == null) {
                    int rc = gateway.openBucket(templateId, row.getBucketNo(), row.getGeneration(), row.getQuota());
                    if (rc >= 0) {
                        log.warn("unregistered active row replayed by refill (was treated as empty before L38): "
                                + "templateId={}, slot={}, gen={}, quota={}",
                                templateId, row.getBucketNo(), row.getGeneration(), row.getQuota());
                        if (row.getQuota() > row.getUsed()) {
                            gateway.clearSoldout(templateId);   // 补注册带回可卖库存——顺手清停流牌
                        }
                    }
                    continue;
                }
                if (gateway.generationSettled(templateId, row.getBucketNo(), row.getGeneration())) {
                    bucketMapper.seal(row.getId());   // L38：定值 CAS 封账（不再整行回写）
                    log.warn("settled gen sealed for batch refill: templateId={}, slot={}, gen={}, used={}",
                            templateId, row.getBucketNo(), row.getGeneration(), row.getUsed());
                }
                // 余量 0 但在途令牌未清（或有回加余量）→ 本轮不封：等令牌出清后由下一轮封账接管
                // （批 3 P2-3：封了会把在途提交打成 status 守卫拒绝=自伤）
            }
            // ② 循环开桶：额度分完或槽满为止（一波补满 16 槽——并行度恢复）
            boolean opened = false;
            while (allocated < total && activeSlotCount(templateId) < bucketCount) {
                long n = Math.min(bucketSize, total - allocated);
                // L37：本段额度的起点（建行前已建行额度合计）——与 tryOpenBucket 同一规矩：
                // 必须在进入暂停窗口之前读，插行时不得重读（重读会撞不上唯一键）
                long segBase = bucketMapper.sumQuota(templateId);
                // L38 批 2（评审 P1-8：L37 段唯一键残洞）：存在"已划出、未建行"的差额时不得再
                // 划款建行——唯一键去重的前提是"两个建行者算出的段上界相同"；有差额时 refill 的
                // segBase 与暂停者的 rowsQuota 不再相等（唯一键撞不上，Σquota 可超 allocated=超发方向）。
                // 交给单桶路径的差额恢复分支（语义精确、不重复 CAS）。
                CouponTemplatePO fresh = templateMapper.selectById(templateId);
                long allocatedNow = fresh != null && fresh.getAllocated() != null
                        ? fresh.getAllocated() : allocated;
                if (segBase < allocatedNow) {
                    return false;
                }
                if (templateMapper.casAllocate(templateId, n) == 0) {
                    break;
                }
                // 故障缝：CAS 后建键前（与 tryOpenBucket 同款窗口——批量重开同样可能在这里
                // 被暂停或中断，兜底靠下一轮 tryOpenBucket 的差额恢复）
                com.newcoupon.infrastructure.testing.FaultHooks.fire(
                        com.newcoupon.infrastructure.testing.FaultHooks.OPEN_BUCKET_SEAM, templateId);
                int slot = pickFreeSlot(templateId);
                int gen = bucketMapper.maxGen(templateId, slot) + 1;
                StockBucketPO row = new StockBucketPO();
                row.setTemplateId(templateId);
                row.setBucketNo(slot);
                row.setGeneration(gen);
                row.setQuota(n);
                row.setUsed(0L);
                row.setStatus(0);
                row.setAllocatedHi(segBase + n);   // L37：本行覆盖的额度段上界（段唯一键的键值）
                try {
                    bucketMapper.insert(row);
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // L37：这一段已被并发的开桶者建好行——停止本轮并返回 true 让调用方重扣；
                    // 尚未建完的段由后续请求的差额恢复补齐（安全方向，不会超发）
                    log.warn("重复建行已拦截（批量重开）: templateId={}, segment=[{}..{}]",
                            templateId, segBase, segBase + n);
                    return true;
                }
                int rc = gateway.openBucket(templateId, slot, gen, n);
                if (rc == -1) {
                    log.warn("stale generation registration rejected (batch refill): templateId={}, slot={}, gen={}",
                            templateId, slot, gen);
                    return true;   // 本代注册被拒——停止本轮，调用方重扣
                }
                gateway.clearSoldout(templateId);   // L38：新桶带可卖库存——清停流牌
                allocated += n;
                opened = true;
                log.info("bucket opened (batch refill): templateId={}, slot={}, gen={}, quota={}",
                        templateId, slot, gen, n);
            }
            return opened;
        } finally {
            gateway.releaseOpenLock(templateId, lockToken);
        }
    }

    /** 活跃槽位数（不同 bucket_no 的 status=0 行数） */
    private int activeSlotCount(long templateId) {
        return (int) bucketMapper.selectActive(templateId).stream()
                .map(StockBucketPO::getBucketNo).distinct().count();
    }

    /** DB 侧售罄判定（v2 §6.3 换锚：ic 冻结后，售罄=allocated>=total——分类路径用） */
    public boolean soldOutInDb(long templateId) {
        CouponTemplatePO t = templateMapper.selectById(templateId);
        return t == null || t.getAllocated() == null || t.getAllocated() >= t.getTotalCount();
    }

    /**
     * 选桶策略：优先空闲槽位（0..bucketCount-1 无活跃行的最低位）；全忙则**复用最低活跃槽**
     * （重开新世代——世代隔离保证旧代账不丢：迟到操作按令牌世代寻址，E2 遍历 c:gens 全世代）。
     * 2026-09-19 修复：旧版全忙时抛 IllegalStateException——槽位数小于活跃桶数时 -10 开桶直接炸。
     */
    private int pickFreeSlot(long templateId) {
        Set<Integer> active = new HashSet<>();
        for (StockBucketPO r : bucketMapper.selectActive(templateId)) {
            active.add(r.getBucketNo());
        }
        for (int slot = 0; slot < bucketCount; slot++) {
            if (!active.contains(slot)) {
                return slot;
            }
        }
        int lowest = Integer.MAX_VALUE;
        for (int slot : active) {
            lowest = Math.min(lowest, slot);
        }
        return lowest;   // 复用：maxGen+1 新世代在 tryOpenBucket 里推进
    }
}
