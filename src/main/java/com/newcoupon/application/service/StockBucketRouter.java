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
     * ④ 选空闲槽位 + 世代=该桶 maxGen+1；INSERT stock_bucket 行
     * ⑤ Redis 开桶 Lua（幂等门防 c:alloc 双计）+ 释放锚
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
            // 夹缝差额恢复（CHAO-1 自愈路径，2026-09-19 故障用例逼出）：上次开桶死在
            // 「DB CAS 已提交、行/Redis 未建」——allocated 已含那笔 n 但 Σ(行quota) 没有。
            // 补建差额，不重复 CAS（否则差额永远卡死成"已分完"的假售罄）。
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
            if (gen > 1) {
                // 复用槽位=旧代存在——封账（status=1：ENSURE 取 maxGen、镜像臂转活跃口径的前提）
                sealOldGenerations(templateId, slot, gen);
            }
            StockBucketPO row = new StockBucketPO();
            row.setTemplateId(templateId);
            row.setBucketNo(slot);
            row.setGeneration(gen);
            row.setQuota(n);
            row.setUsed(0L);
            row.setStatus(0);
            bucketMapper.insert(row);
            gateway.openBucket(templateId, slot, gen, n);
            log.info("bucket opened: templateId={}, slot={}, gen={}, quota={}", templateId, slot, gen, n);
            return true;
        } finally {
            gateway.releaseOpenLock(templateId, lockToken);
        }
    }

    /**
     * -7 灾后重建（v2 §6.1 ENSURE；CHAO-4：Redis 重启/AOF 丢 act）：
     * DB 有活跃行而 act 注册表空 → 按 DB 权威行一次性重建（SET 非累加，不叠崩溃残余）。
     * 返回 true=执行了重建（调用方重试 deduct）。
     */
    public boolean ensureActiveBuckets(long templateId) {
        var rows = bucketMapper.selectActive(templateId);
        if (rows.isEmpty()) {
            return false;
        }
        boolean rebuilt = gateway.ensureBuckets(templateId,
                rows.stream().map(r -> new long[]{r.getBucketNo(), r.getGeneration(),
                        r.getQuota(), r.getUsed()})
                        .toList());   // 四元组：ENSURE 按 quota-used 推导铸 stk（2026-09-20 修复：
                                      // 此前传三元组与网关四元组消费不匹配——-7 重建路径运行时越界）
        if (rebuilt) {
            log.warn("buckets ENSURED from DB rows (redis lost registry?): templateId={}, rows={}",
                    templateId, rows.size());
        }
        return true;
    }

    /** 孤儿桶重放：DB 活跃行未注册进 Redis act 的，重放开桶 Lua（幂等）。true=有补登记 */
    private boolean replayOrphanBuckets(long templateId) {
        var registered = gateway.actEntries(templateId);   // 槽 -> 当代世代
        boolean replayed = false;
        for (StockBucketPO row : bucketMapper.selectActive(templateId)) {
            String cur = registered.get(String.valueOf(row.getBucketNo()));
            if (cur == null || Long.parseLong(cur) < row.getGeneration()) {
                gateway.openBucket(templateId, row.getBucketNo(), row.getGeneration(), row.getQuota());
                log.warn("orphan bucket replayed (row committed but EVAL never ran): "
                        + "templateId={}, slot={}, gen={}, quota={}",
                        templateId, row.getBucketNo(), row.getGeneration(), row.getQuota());
                replayed = true;
            }
        }
        return replayed;
    }

    /** 封账旧代（status=1）——世代账本闭合的 DB 侧写方 */
    private void sealOldGenerations(long templateId, int slot, int newGen) {
        for (StockBucketPO row : bucketMapper.selectActive(templateId)) {
            if (row.getBucketNo() == slot && row.getGeneration() < newGen) {
                row.setStatus(1);
                bucketMapper.updateById(row);
            }
        }
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
