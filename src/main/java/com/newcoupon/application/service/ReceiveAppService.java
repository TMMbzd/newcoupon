package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.CancelCause;
import com.newcoupon.infrastructure.redis.RedisStockGateway.DeductCommand;
import com.newcoupon.infrastructure.redis.RedisStockGateway.DeductResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 领券编排层（ADR-0006 + ADR-0008 恒等式记账形态）。
 *
 * <p>⚠️ 本类【严禁】@Transactional（rollback-only 退化，study/04a 第 4 部分）。
 *
 * <p>领券全流程（Redis 优先，每步失败都有明确去向）：
 * <pre>
 * ① 读模板 + assertReceivable（内存快检；状态竞态由 DB CAS 兜底）
 * ② Redis Lua 闸门：五重校验原子扣减；同一脚本内完成复式记账
 *    （issued+1 且 在途令牌入 pend ZSET——恒等式借方与在途同增）
 *    -7 未初始化 → 随机退避后条件 SET 懒重建，重试一次
 * ③ DB 事务（双防线扣账 + 落券(带seq) + 事件）
 *    成功 → confirm：dbi+1 与令牌出清【一个 Lua 原子配对】（在途转贷方）
 *    失败 → 分类先行（A1'），按分类取消记账：
 *      售罄   → cancel(SOLD_OUT)：钉零 + 立售罄标志（B4）+ 回滚限领计数
 *      下线   → cancel(OFFLINE)：钉零 + 回滚
 *      瞬时   → cancel(TRANSIENT)：库存 INCR 回加 + 回滚
 *    令牌单次使用语义保证 confirm/cancel 天然幂等（重复/迟到全部 no-op）
 * </pre>
 *
 * <p>confirm 失败（Redis 抖动等）不阻断响应：DB 已提交、券已发放，用户结果已定；
 * 恒等式会以 dbi 滞后形态暴露，由对账按 confirm-lost 模式修复（ADR-0008）。
 */
@Service
public class ReceiveAppService {

    private static final Logger log = LoggerFactory.getLogger(ReceiveAppService.class);
    private static final int IDEM_TTL_SEC = 600;
    private static final int DAY_TTL_SEC = 172800;   // 48h：跨天滚动 key 的清理兜底
    /** P0 修复③：日限键的日期在编排层算一次、随命令固化——cancel 扣的必然是 deduct 加的那个键 */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final UserCouponRepository userCouponRepository;
    private final TemplateRepository templateRepository;
    private final CouponIssueTxService issueTxService;
    private final RedisStockGateway stockGateway;
    private final StockReconcileService reconcileService;
    private final StockDriftLogGateway driftLog;
    private final StockBucketRouter stockBucketRouter;
    private final CachedTemplateReader cachedTemplateReader;
    private final ReceiveMetrics metrics;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public ReceiveAppService(UserCouponRepository userCouponRepository,
                             TemplateRepository templateRepository,
                             CouponIssueTxService issueTxService,
                             RedisStockGateway stockGateway,
                             StockReconcileService reconcileService,
                             StockDriftLogGateway driftLog,
                             StockBucketRouter stockBucketRouter,
                             CachedTemplateReader cachedTemplateReader,
                             ReceiveMetrics metrics,
                             org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.userCouponRepository = userCouponRepository;
        this.templateRepository = templateRepository;
        this.issueTxService = issueTxService;
        this.stockGateway = stockGateway;
        this.reconcileService = reconcileService;
        this.driftLog = driftLog;
        this.stockBucketRouter = stockBucketRouter;
        this.cachedTemplateReader = cachedTemplateReader;
        this.metrics = metrics;
        this.redis = redis;
    }

    public ReceiveResult receive(ReceiveRequest req) {
        var sample = metrics.start();
        try {
            ReceiveResult r = doReceive(req, false);
            metrics.recordSuccess();
            return r;
        } catch (BizException e) {
            recordOutcome(e);
            throw e;
        } finally {
            metrics.stop(sample);
        }
    }

    private void recordOutcome(BizException e) {
        switch (e.getErrorCode()) {
            case STOCK_OUT -> metrics.recordSoldOut();
            case FROZEN -> metrics.recordFrozen();
            case USER_LIMIT_EXCEEDED, DAILY_LIMIT_EXCEEDED -> metrics.recordUserLimit();
            case DEGRADED -> metrics.recordDegraded();
            default -> { }
        }
    }

    /**
     * @param retryOnce L3 自愈后的透明重试标志（仅允许一层——防失忆场景下撞索引→自愈→再撞的死循环）
     */
    private ReceiveResult doReceive(ReceiveRequest req, boolean retryOnce) {
        // ① 读模板 + 可领性校验（P2c：走本地缓存——每请求省一次 DB 主键查；分类/巡检等
        //    需要"事务外新快照"的路径仍直读 repository，绝不走缓存）
        CouponTemplate template = cachedTemplateReader.get(req.templateId())
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + req.templateId()));
        template.assertReceivable(LocalDateTime.now());

        // ② Redis Lua 闸门——幂等检查内置于脚本（EXISTS 幂等key → -8 回 DB 确认）。
        //    设计取舍（2026-09-07 评审修正）：入口幂等只用 Redis——抢购洪峰中"首次领取"
        //    是多数派，每请求先打一次 DB SELECT 白耗连接池（实测 2000 并发已撞池）；
        //    重复请求是少数派，-8 后回 DB 确认即可。DB uk_idem 唯一索引仍是终审。
        DeductCommand cmd = new DeductCommand(req.templateId(), req.userId(), req.requestId(),
                template.getPerUserLimit(), template.getPerUserDailyLimit(), template.getDailyLimit(),
                LocalDate.now().format(DAY), DAY_TTL_SEC, IDEM_TTL_SEC,
                stockBucketRouter.preferred(req.userId()));   // P2b 路由亲和（v2 §3）
        DeductResult d = stockGateway.deduct(cmd);
        d = healLimitRejectIfCounterLeaked(req, cmd, d);   // L42①：限领计数虚高时先复核再拒
        if (d.code() == -7) {
            // A2'：注册表空（清库/Redis 灾难）。随机退避把"同时再击"摊开；
            // ENSURE 按 DB 权威行一次性重建（按 quota-used 推导铸 stk，幂等）。
            backoffQuietly();
            stockBucketRouter.ensureRegisteredBuckets(req.templateId());
            d = stockGateway.deduct(cmd);
            if (d.code() == -7 && stockBucketRouter.tryOpenBucket(req.templateId())) {
                // 兜底（审查 P1）：DB 有额度但一行活跃桶都没有（如 V10 前形态/回填缺口）——
                // 把"首桶缺失"视为可开桶状态，而不是死路 DEGRADED
                d = stockGateway.deduct(cmd);
            }
            if (d.code() == -7) {
                throw new BizException(ErrorCode.DEGRADED, "库存服务暂不可用，请稍后重试");
            }
        }
        if (d.code() == -10) {
            // L47（2026-09-30 由修好的"清库风暴"用例实锤）：Redis 的**余量键**丢了、但活跃名册还在时，
            // 闸门报的是 -10（不是 -7），下面的常规循环会一路走到"真售罄"——用户拿到 3001 假售罄，
            // 而数据库里明明还有可卖的行。判据用数据库权威（存在"活跃且未用满"的行）：按行强制补键后重扣。
            // 判据是"名册里的余量键真的缺了"（纯 Redis 读，一次 Lua）——不能用"数据库还有可卖行"：
            // 正常惊群里尾量正被别人消费时它也成立，据它降级会把售罄风暴变成 5001 风暴（批 1 实锤）。
            if (stockGateway.anyStkKeyMissing(req.templateId())
                    && stockBucketRouter.rebuildMissingKeys(req.templateId())) {
                d = stockGateway.deduct(cmd);
            }
            // P2b v2 §5：全桶耗尽 → 开桶协议（single-flight+尾量+尾差恢复）。
            // 抢不到开桶锁≠售罄——别人正在开，短退避后重试（v2 §5 ①的原义，含夹缝自愈重放）；
            // 有界三轮，防开桶风暴下请求自旋。真售罄（DB allocated>=total）直接走售罄。
            // 每轮：先试开桶（自己开/别人已开/差额补建均无害）→ 真售罄才退 → 退避 → 重扣。
            // 注意顺序：soldOutInDb(allocated>=total) 在夹缝态为真但差额未补——必须先给
            // tryOpenBucket 一次补建机会再判售罄（2026-09-19 夹缝用例回退实锤）。
            for (int attempt = 0; attempt < 3 && !d.success(); attempt++) {
                // 批量重开优先（一波封空代+补满槽——防大盘模板退化单行热点）；false 回退
                // 单桶路径（它兼管孤儿重放/差额恢复这些边角）
                boolean opened = stockBucketRouter.refillExhaustedBuckets(req.templateId())
                        || stockBucketRouter.tryOpenBucket(req.templateId());
                if (!opened && stockBucketRouter.soldOutInDb(req.templateId())) {
                    // L38 修复（评审 P2-1：终态判据三条件）——判"售罄"前必须排除两种"还在生产"：
                    // ①有人正开着桶（锁被持有）：货可能马上到；②存在"已划出、未建行"的差额（含
                    // 被暂停的开桶者的在途划款）。有生产迹象就退避后**再问一次门**（而不是结案）。
                    if (stockGateway.openLockHeld(req.templateId())
                            || stockBucketRouter.hasAllocationGap(req.templateId())) {
                        backoffQuietly();
                        d = stockGateway.deduct(cmd);
                        continue;
                    }
                    d = stockGateway.deduct(cmd);   // 生产已停：门当场复核（可能已有货）
                    break;                          // 复核结果即终局（成功→继续发券；-10→真售罄）
                }
                backoffQuietly();
                d = stockGateway.deduct(cmd);
            }
            if (d.code() == -10 && (!stockBucketRouter.soldOutInDb(req.templateId())
                    || stockBucketRouter.hasAllocationGap(req.templateId()))) {
                // 审查 P1 + L38：重试预算(~0.5s)≪开桶锁 TTL(5s)——重试耗尽时若"额度没分完"或
                // 存在"已划出、未建行"的差额（被暂停开桶者的在途划款——生产确实没完成），
                // 终态 STOCK_OUT 是谎报（3001 不可重试=等同丢单），改可重试降级码。
                // 注意这里**不把"锁被持有"当生产信号**：高并发下大量请求只是抢锁试探（抢到发现
                // 无桶可开即放），若据此判降级会把正常售罄风暴变成 5001 风暴（惊群用例实锤）。
                throw new BizException(ErrorCode.DEGRADED, "库存补给中，请稍后重试");
            }
        }
        if (!d.success()) {
            return handleGateRejected(req, cmd, template, d);
        }

        // ③ DB 事务；任何失败都按分类取消记账（cancel 内部令牌门控保证幂等）。
        //    P0 修复①②（2026-09-12）：confirm 单独兜底——它失败绝不回滚已提交事务的记账；
        //    RuntimeException 分支先探 DB 定案再决定动作——"不确定提交"不得盲目补偿。
        try {
            UserCoupon issued = issueTxService.issue(req, template, d.seq(),
                    d.bucket(), d.gen(), LocalDateTime.now());
            confirmQuietly(cmd, d);   // 修复①：DB 已提交，用户结果已定——confirm 失败只记日志放行
            return ReceiveResult.of(issued, false);
        } catch (DuplicateKeyException e) {
            cancelQuietly(cmd, d, CancelCause.TRANSIENT);   // 计数还原+库存回加，令牌出清
            return classifyAfterRollback(req, cmd, retryOnce, e);
        } catch (StockDeductRejectedException e) {
            // A1' 分类先行：先取新快照判断"DB 为什么拒"，再选取消形态——
            // 售罄要钉零（防 N1 过补偿自持环），瞬时异常要回加（库存没真耗尽）
            throw classifyDeductRejectAndCancel(req, cmd, d);
        } catch (RuntimeException e) {
            return recoverUncertainOutcome(req, cmd, d, e);   // 修复②：先探 DB，命中=已提交
        }
    }

    /**
     * P0 修复①（2026-09-12）：confirm 的 Redis 异常绝不触发 cancel。
     * 旧代码 confirm 在 try 内，其异常落入 catch(RuntimeException) → cancel 会真回滚
     * （令牌还在）——DB 已发券、Redis 记账退回"没发生"、恒等式仍平（cancel 是配对回滚）、
     * 三层对账全盲：静默资损方向 + 用户收到 500 但券已发出，违反 ADR-0008 自身承诺。
     * 现在两种失败变体都安全：命令未抵达→令牌留 pend，180s 清扫后对账按 confirm-lost 修复；
     * 已抵达仅超时→状态本就一致，无需动作。
     */
    private void confirmQuietly(DeductCommand cmd, DeductResult d) {
        try {
            if (!stockGateway.confirm(cmd, d.bucket(), d.gen())) {
                log.debug("confirm no-op (token consumed): templateId={}, requestId={}",
                        cmd.templateId(), cmd.requestId());
            }
        } catch (RuntimeException e) {
            log.warn("confirm failed (DB committed, token left for reconcile to heal): "
                    + "templateId={}, requestId={}", cmd.templateId(), cmd.requestId(), e);
        }
    }

    /**
     * P0 修复②（2026-09-12）：不确定提交（如 COMMIT 阶段断连——MySQL 可能已落盘）不得盲目 cancel。
     * 探针=按幂等键查 DB：命中=事务其实成功了→记账确认+按幂等命中返回成功；
     * 未命中=真失败→cancel 回滚；探针自身失败→不 cancel 抛原异常（令牌交 180s 清扫，对账兜底）。
     * 残余窗口=「断连 且 COMMIT 落盘晚于探针一个 RTT」，IO 尖刺时秒级——有界、零超发、镜像自愈。
     */
    private ReceiveResult recoverUncertainOutcome(ReceiveRequest req, DeductCommand cmd,
                                                  DeductResult d, RuntimeException cause) {
        Optional<UserCoupon> landed;
        try {
            landed = userCouponRepository.findByIdempotentKey(req.userId(), req.requestId());
        } catch (RuntimeException probeFailure) {
            log.error("uncertain commit AND probe failed — leave token to TTL sweep: "
                    + "templateId={}, requestId={}", req.templateId(), req.requestId(), probeFailure);
            throw cause;
        }
        if (landed.isPresent()) {
            log.warn("uncertain commit RESOLVED as committed — confirm & return success: "
                    + "templateId={}, requestId={}", req.templateId(), req.requestId());
            confirmQuietly(cmd, d);
            return toDuplicatedResult(landed.get(), req);
        }
        cancelQuietly(cmd, d, CancelCause.TRANSIENT);   // 真失败：不还=库存泄漏（少发）
        throw cause;
    }

    /**
     * 取消记账的二次故障防线（答辩 A3）：cancel 自身抛异常（Redis 同时故障）时不得吞掉
     * 业务语义——记错误日志放行，令牌交 180s 清扫由对账修复（方向安全：少卖有界、不超发）。
     */
    private void cancelQuietly(DeductCommand cmd, DeductResult d, CancelCause cause) {
        try {
            stockGateway.cancel(cmd, d.bucket(), d.gen(), cause);
            if (cause == CancelCause.TRANSIENT) {
                reconcileService.noteTransientCancel(cmd.templateId());   // ADR-0009 §4：取消率熔断的计数
            }
        } catch (RuntimeException e) {
            log.error("cancel failed (token left to TTL sweep, reconcile will heal): "
                    + "templateId={}, requestId={}, cause={}", cmd.templateId(), cmd.requestId(), cause, e);
        }
    }

    /** A2'：-7 重试前的随机退避 50~200ms——削平"清库后同一批请求同时再击 DB"的雷击 */
    private void backoffQuietly() {
        try {
            Thread.sleep(50 + ThreadLocalRandom.current().nextInt(150));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 闸门拒绝分类 ----------

    /**
     * L42①（2026-09-30）：限领计数可能**虚高**——令牌清扫删在途令牌时不回滚计数、取消失败同样会漏。
     * 虚高的计数让闸门在 -2/-6 直接拒绝，而闸门拒绝发生在任何数据库动作之前，**不会自愈**：
     * 用户明明还能领，却一直拿到 3002（典型的"错误拒绝"）。
     *
     * <p>处置与 L3 计数自愈同一原则：**数据库是权威**——拒绝前复核一次，虚高则把计数校准回真实值，
     * 并当场重扣（用户无感）。复核失败（数据库也不通）时不改变结论——宁可少发一次，也不错发。
     *
     * <p>为什么把复核放在闸门之后而不是之前：多数请求在闸门就成功/被真限领挡住，只有 -2/-6 这一
     * 条冷路径才多一次 COUNT，热路径零额外开销。
     */
    private DeductResult healLimitRejectIfCounterLeaked(ReceiveRequest req, DeductCommand cmd, DeductResult d) {
        if (d.code() != -2 && d.code() != -6) {
            return d;
        }
        try {
            long actual;
            long limit;
            if (d.code() == -2) {   // 用户累计限领
                actual = userCouponRepository.countByUserAndTemplate(req.userId(), req.templateId());
                limit = cmd.maxPerUser();
                if (actual >= limit) {
                    return d;   // 真领满了：诚实拒绝，不做任何自愈
                }
                healUserCountQuietly(req, actual);   // 计数校准为真实张数
            } else {                // 用户当日限领
                actual = userCouponRepository.countTodayByUserAndTemplate(req.userId(), req.templateId(),
                        LocalDate.now().atStartOfDay());
                limit = cmd.perUserDaily();
                if (actual >= limit) {
                    return d;
                }
                stockGateway.setUserDailyCount(req.templateId(), req.userId(), cmd.day(), actual);
            }
            log.warn("limit counter leaked (redis said full, db says {}/{}): templateId={}, userId={}, code={}"
                    + " — healed & retrying", actual, limit, req.templateId(), req.userId(), d.code());
            return stockGateway.deduct(cmd);
        } catch (RuntimeException e) {
            log.error("limit counter verify failed (keep gate decision): templateId={}, userId={}",
                    req.templateId(), req.userId(), e);
            return d;
        }
    }

    /**
     * Redis 闸门拒绝（-1/-2/-3/-6/-8，-3 含 soldout 标志短路）。注意：idem 键 TTL(10分钟)
     * 过期后的"陈旧重放"会走到限领/售罄分支——先查一次 DB 幂等键，命中则按重放处理。
     */
    private ReceiveResult handleGateRejected(ReceiveRequest req, DeductCommand cmd,
                                          CouponTemplate template, DeductResult d) {
        // 2e-③ 假售罄当场修（P2 终审后改挂通用入口——原 -3 分支不可达因为 deduct 已续期牌）：
        // 到这里说明 Redis 拒了；DB 说还有额度 → 补开重试（零感知）
        if ((d.code() == -3 || d.code() == -10)
                && !stockBucketRouter.soldOutInDb(req.templateId())
                && !stockGateway.isSoldout(req.templateId())) {
            if (stockBucketRouter.refillExhaustedBuckets(req.templateId())
                    || stockBucketRouter.tryOpenBucket(req.templateId())) {
                backoffQuietly();
                DeductResult d2 = stockGateway.deduct(cmd);
                if (d2.success()) {
                    try {
                        UserCoupon issued = issueTxService.issue(req, template, d2.seq(),
                                d2.bucket(), d2.gen(), LocalDateTime.now());
                        confirmQuietly(cmd, d2);
                        metrics.recordFalseSelloutAid();
                        return ReceiveResult.of(issued, false);
                    } catch (RuntimeException e) {
                        return recoverUncertainOutcome(req, cmd, d2, e);
                    }
                }
            }
        }
        if (d.code() == -8) {   // Redis 幂等命中
            return duplicatedOrConflict(req);
        }
        // 陈旧重放检查
        Optional<UserCoupon> old = userCouponRepository.findByIdempotentKey(req.userId(), req.requestId());
        if (old.isPresent()) {
            return toDuplicatedResult(old.get(), req);
        }
        throw switch (d.code()) {
            case -3, -10 -> new BizException(ErrorCode.STOCK_OUT);   // -10=全桶耗尽且开桶失败=额度分完
            case -11 -> new BizException(ErrorCode.FROZEN);          // P2b v2 §7：冻结专用降级码，禁泄漏负数
            case -2 -> new BizException(ErrorCode.USER_LIMIT_EXCEEDED);
            case -6 -> new BizException(ErrorCode.USER_LIMIT_EXCEEDED, "已达每日限领上限");
            case -1 -> new BizException(ErrorCode.DAILY_LIMIT_EXCEEDED);
            default -> new BizException(ErrorCode.SYSTEM_ERROR, "库存闸门未知返回: " + d.code());
        };
    }

    /** Redis 幂等命中：回 DB 确认（带 templateId 一致性校验） */
    private ReceiveResult duplicatedOrConflict(ReceiveRequest req) {
        return userCouponRepository.findByIdempotentKey(req.userId(), req.requestId())
                .map(u -> toDuplicatedResult(u, req))
                .orElseThrow(() -> new BizException(ErrorCode.REQUEST_DUPLICATED,
                        "请求正在处理中，请勿重复提交"));
    }

    // ---------- DB 冲突分类（回滚+取消记账后，事务外新快照） ----------

    private ReceiveResult classifyAfterRollback(ReceiveRequest req, DeductCommand cmd,
                                                boolean retryOnce, DuplicateKeyException e) {
        log.warn("duplicate key on receive: userId={}, requestId={}, cause={}",
                req.userId(), req.requestId(), e.getMessage());
        Optional<UserCoupon> byIdem = userCouponRepository.findByIdempotentKey(req.userId(), req.requestId());
        if (byIdem.isPresent()) {
            return toDuplicatedResult(byIdem.get(), req);   // uk_idem：重放/并发命中
        }
        // uk_user_seq：Redis 计数与 DB 事实脱节（失忆/漂移）。L3 自愈（2026-09-15，多方评审#1/#6 修正）：
        // 两个 DB 读数分工——行数 COUNT 管"限领判断"（限领语义=张数）；最大序号 MAX(seq) 管"自愈写入值"
        // （下一序号必须大于全部现有——补偿路径泄漏的在途计数会造持久空洞 {1,2,3,4,6}，用 COUNT 自愈
        // 会让重试 seq=COUNT+1 撞在 MAX 行上、retryOnce 耗尽、永久 3002 死循环）。
        long actualRows = userCouponRepository.countByUserAndTemplate(req.userId(), req.templateId());
        long maxSeq = userCouponRepository.maxSeqByUserAndTemplate(req.userId(), req.templateId());
        healUserCountQuietly(req, maxSeq);
        if (actualRows >= cmd.maxPerUser()) {
            // 真达限：正确的拒绝——计数已同步修好，下次闸门在 Redis 层就拦住，不再白撞索引
            throw new BizException(ErrorCode.USER_LIMIT_EXCEEDED);
        }
        if (retryOnce) {
            // 重试后仍撞且行数未达限（评审#1/#13）：持久空洞或自愈竞态的特征签名——
            // 不是用户的问题，落漂移表告警 + 给可重试的错误（绝不谎报"已达限领"）
            log.error("UL_HOLE: retried collision with rows({}) < limit({}) — seq hole or heal race: "
                    + "templateId={}, userId={}, maxSeq={}", actualRows, cmd.maxPerUser(),
                    req.templateId(), req.userId(), maxSeq);
            recordUlHoleQuietly(req, actualRows, maxSeq);
            throw new BizException(ErrorCode.DEGRADED, "领取计数校准中，请稍后重试");
        }
        // 失忆（DB 证明没领满）：重置后透明重试一次——cancel 已 DEL Redis 幂等键，
        // DB 侧 uk_idem 因首次回滚不存在，重试安全；重试内 deduct 从 MAX(seq)+1 起算（必为空位）
        log.warn("ul counter healed after loss: templateId={}, userId={}, redis→{}(maxSeq) — retrying transparently",
                req.templateId(), req.userId(), maxSeq);
        return doReceive(req, true);
    }

    /** L3：自愈写入失败（Redis 同挂等）不改变业务结局——最坏再撞一次索引回到 DB 兜底，不超发 */
    private void healUserCountQuietly(ReceiveRequest req, long maxSeq) {
        try {
            stockGateway.setUserCount(req.templateId(), req.userId(), maxSeq);
        } catch (RuntimeException e) {
            log.error("ul heal failed (will retry on next collision): templateId={}, userId={}",
                    req.templateId(), req.userId(), e);
        }
    }

    /** 评审#1：持久空洞/自愈竞态落漂移表——此特征签名（重试仍撞+行数未达限）此前无任何告警 */
    private void recordUlHoleQuietly(ReceiveRequest req, long rows, long maxSeq) {
        try {
            driftLog.record(new StockDriftLogGateway.DriftEvent(req.templateId(), 0, 0, 0,
                    0, rows, maxSeq, null, 0, "UL_HOLE",
                    "重试后仍撞序号且行数未达限（seq空洞/自愈竞态）: userId=" + req.userId()
                            + ", rows=" + rows + ", maxSeq=" + maxSeq));
        } catch (RuntimeException e) {
            log.error("ul hole drift record failed: {}", e.getMessage());
        }
    }

    /**
     * DB 条件扣账 0 行：事务外取新快照分类（study/04a RR 教训），<b>先分类再取消</b>（A1'）。
     * 分类决定 cancel 形态——取消的 stock 修复语义（钉零 vs 回加）依赖"为什么被拒"。
     * 返回待抛出的业务异常（先完成取消记账，再抛给调用方）。
     */
    private BizException classifyDeductRejectAndCancel(ReceiveRequest req, DeductCommand cmd,
                                                        DeductResult d) {
        CouponTemplate fresh = templateRepository.findById(req.templateId()).orElse(null);
        if (fresh == null) {
            cancelQuietly(cmd, d, CancelCause.OFFLINE);
            return new BizException(ErrorCode.TEMPLATE_NOT_FOUND);
        }
        if (stockBucketRouter.soldOutInDb(req.templateId())) {
            // 售罄（P2b 换锚 v2 §6.3：allocated>=total——ic 已冻结为快照口径）：钉牌 + 退休令牌。
            // L38 修复（评审 P2-2）：钉模板级牌前复核两件事——①令牌所在桶行确实"用满"
            // （被封账/瞬时拒绝不算，否则单桶异常会把全模板停流）；②全模板确实没有别的可卖余量。
            // 两条都满足才立牌；否则按瞬时处理（回加不立牌——不把错误方向放大成全模板终态）。
            if (stockBucketRouter.bucketRowFull(req.templateId(), d.bucket(), d.gen())
                    && stockBucketRouter.noSellableRowInDb(req.templateId())) {
                cancelQuietly(cmd, d, CancelCause.SOLD_OUT);
                return new BizException(ErrorCode.STOCK_OUT);
            }
            cancelQuietly(cmd, d, CancelCause.TRANSIENT);
            return new BizException(ErrorCode.DEGRADED, "库存扣账未成功，请稍后重试");
        }
        if (fresh.getApproveStatus() != ApproveStatus.APPROVED
                || fresh.getPublishStatus() != PublishStatus.PUBLISHED) {
            // 下线/审批回退：同样钉零（模板停了，余量不该再放行；重新上线走回补协议）
            cancelQuietly(cmd, d, CancelCause.OFFLINE);
            return new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE, "模板状态已变化（被下线/审批回退）");
        }
        // 瞬时（扣账竞态输给并发/锁等待后的重试）：库存没真耗尽，正常回加
        cancelQuietly(cmd, d, CancelCause.TRANSIENT);
        return new BizException(ErrorCode.DEGRADED, "库存扣账未成功，请稍后重试");
    }

    /** 幂等命中出口：templateId 必须一致（requestId 绑定的是一次领取意图） */
    private ReceiveResult toDuplicatedResult(UserCoupon existing, ReceiveRequest req) {
        if (existing.getTemplateId() != req.templateId()) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                    "该 requestId 已用于 templateId=" + existing.getTemplateId() + " 的领取，不可用于其他模板");
        }
        return ReceiveResult.of(existing, true);
    }
}
