package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetReservationMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantChunkMapper;
import com.newcoupon.infrastructure.persistence.po.CouponActivityPO;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetReservationPO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantChunkPO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantTaskPO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 预算闸门编排（V6 阶段 1C）。三个接入面共用一套账本原语：
 * <ul>
 *   <li><b>同步单人领取</b>（{@link ReceiveAppService}）：库存闸门之前预占一张，
 *       发券成功确认、任何失败释放（受理即预占-确认-发-收，一发一收）；</li>
 *   <li><b>兑换码兑换</b>（{@link CouponCodeService}）：同单人，biz_type=3；</li>
 *   <li><b>批量/定时任务</b>（{@link GrantTaskService} 建任务时按 chunk 预占，
 *       {@link GrantTaskTxService} chunk 终态时结算）——worker 逐 item 不再碰预算，
 *       chunk 预占已把总量挡住。</li>
 * </ul>
 *
 * <p><b>开关语义（回滚承诺）</b>：一切预算逻辑挂在活动 {@code budget_enabled=1} 之后。
 * 活动没开预算时 {@link #gateOn} 是一次缓存内存判断，直接放行——存量发放链路
 * 不多一条 SQL、不多一次判断，行为与 1C 之前完全一致。
 *
 * <p><b>已知边界（裁剪裁定）</b>：异步单人（/receive-async）暂不接预算——受理层
 * 预占需要任务行携带 reserve_no 且 worker 成败双向回写，1C 裁剪不做；登记于
 * docs/STATUS.md。核销/退款期的预算 adjust（占用即消耗的口径迁移）同为后续刀。
 */
@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final CachedActivityReader activityReader;
    private final CachedTemplateReader templateReader;
    private final BudgetTxService txService;
    private final BudgetMetrics metrics;
    private final CouponBudgetReservationMapper reservationMapper;
    private final CouponGrantChunkMapper chunkMapper;

    private final long singleTtlMinutes;
    private final long chunkTtlMinutes;
    private final int expireBatchSize;
    /** 单轮过期回收的上限：防止积压时一轮吃满时间盒（下一轮继续）。 */
    private static final int EXPIRE_ROUND_LIMIT = 2_000;

    public BudgetService(CachedActivityReader activityReader,
                         CachedTemplateReader templateReader,
                         BudgetTxService txService,
                         BudgetMetrics metrics,
                         CouponBudgetReservationMapper reservationMapper,
                         CouponGrantChunkMapper chunkMapper,
                         @Value("${coupon.budget.single-ttl-minutes:10}") long singleTtlMinutes,
                         @Value("${coupon.budget.chunk-ttl-minutes:1440}") long chunkTtlMinutes,
                         @Value("${coupon.budget.expire-batch-size:200}") int expireBatchSize) {
        this.activityReader = activityReader;
        this.templateReader = templateReader;
        this.txService = txService;
        this.metrics = metrics;
        this.reservationMapper = reservationMapper;
        this.chunkMapper = chunkMapper;
        this.singleTtlMinutes = singleTtlMinutes;
        this.chunkTtlMinutes = chunkTtlMinutes;
        this.expireBatchSize = expireBatchSize;
    }

    /**
     * 预算闸门是否打开：活动存在且 budget_enabled=1。
     * 这是全部接入面的第一判断——关=一次缓存内存判断直接放行（零 DB、零行为变化）。
     */
    public boolean gateOn(CouponTemplate template) {
        if (template.getActivityId() <= 0) {
            return false;
        }
        return activityReader.get(template.getActivityId())
                .map(a -> a.getBudgetEnabled() != null
                        && a.getBudgetEnabled() == CouponActivityPO.BUDGET_ENABLED)
                .orElse(false);
    }

    /**
     * 单人发放的预占（同步领取/兑换码）：预占一张的成本，受理成功即持有。
     * 开关关着返回 null（调用方把 null 当"无预算租约"，跳过后续确认/释放）。
     *
     * @throws BizException BUDGET_EXHAUSTED 预算池不够本次预占
     */
    public CouponBudgetReservationPO tryReserveSingle(CouponTemplate template, int bizType,
                                                      String requestId) {
        if (!gateOn(template)) {
            return null;
        }
        long unitCost = template.getBudgetUnitCost();
        LocalDateTime expireAt = LocalDateTime.now().plusMinutes(singleTtlMinutes);
        try {
            return reserveWithAccountRaceRetry(() -> txService.reserve(
                    template.getActivityId(), template.getAppId(),
                    bizType, requestId, unitCost, expireAt));
        } catch (BizException e) {
            if (e.getErrorCode() == ErrorCode.BUDGET_EXHAUSTED) {
                metrics.recordRejected(bizType == CouponBudgetReservationPO.BIZ_CODE ? "code" : "single");
            }
            throw e;
        }
    }

    /**
     * 账户懒建撞并发的重试（一次即够）：第一个事务因撞 uk 回滚时，赢家账户必已
     * 提交——新事务的快照能看到它，直接走到条件更新。仍撞（理论不可达）说明
     * 不是并发懒建而是别的键冲突，原样抛出。
     */
    private CouponBudgetReservationPO reserveWithAccountRaceRetry(
            java.util.function.Supplier<CouponBudgetReservationPO> reserveCall) {
        try {
            return reserveCall.get();
        } catch (BudgetTxService.AccountRaceRetry race) {
            return reserveCall.get();
        }
    }

    /**
     * 发券成功后的收口：确认预占额（预留→消耗）并关闭预占行。
     * duplicated=true 时券是之前那次请求发的（那次的预算已确认过）——本次预占
     * 必须释放而不是二次确认，否则同一张券吃两份预算。
     */
    public void settleSingle(CouponBudgetReservationPO reservation, boolean couponNewlyIssued) {
        if (reservation == null) {
            return;
        }
        if (couponNewlyIssued) {
            txService.confirm(reservation.getId(), reservation.getAmount());
        }
        // release 内部按开口余额回池：全额确认后开口=0，只做终态关闭（不产生流水）
        txService.release(reservation.getId());
    }

    /**
     * 失败收口：预占全额释放回池（库存失败/限领/降级都走这里——券没发出去，
     * 预算就不能算消耗）。
     */
    public void releaseSingle(CouponBudgetReservationPO reservation) {
        if (reservation == null) {
            return;
        }
        txService.release(reservation.getId());
    }

    /**
     * 批量任务建任务时按 chunk 预占：每个 chunk 一条预占（张数×单张成本）。
     * 任何一条打不中预算即整体失败——先到的任务先占池，后到的任务建不成，
     * 这就是批量面的"预算硬顶"。调用方在任务落库失败时须 {@link #releaseQuietly}。
     *
     * @param scheduleTime 定时开始时间：未来定时任务的预占过期时刻顺延到定时点之后
     *                     （否则任务还没到期预占先过期回池，预算闸门失效）
     */
    public List<CouponBudgetReservationPO> tryReserveChunks(
            CouponTemplate template, String taskNo, List<CouponGrantChunkPO> chunks,
            LocalDateTime scheduleTime) {
        List<CouponBudgetReservationPO> reserved = new ArrayList<>();
        if (!gateOn(template) || chunks.isEmpty()) {
            return reserved;
        }
        long unitCost = template.getBudgetUnitCost();
        // 过期锚点：定时任务=定时点+TTL；立即任务=现在+TTL
        LocalDateTime base = scheduleTime != null && scheduleTime.isAfter(LocalDateTime.now())
                ? scheduleTime : LocalDateTime.now();
        LocalDateTime expireAt = base.plusMinutes(chunkTtlMinutes);
        try {
            for (CouponGrantChunkPO chunk : chunks) {
                long amount = chunk.getTotalCount() * unitCost;
                if (amount <= 0) {
                    continue;
                }
                reserved.add(reserveWithAccountRaceRetry(() -> txService.reserve(
                        template.getActivityId(), template.getAppId(),
                        CouponBudgetReservationPO.BIZ_CHUNK,
                        chunkBizId(taskNo, chunk.getChunkNo()), amount, expireAt)));
            }
        } catch (BizException e) {
            // 部分成功后失败：已预占的回池，再抛——不让半截预占悬在池里
            releaseQuietly(reserved);
            if (e.getErrorCode() == ErrorCode.BUDGET_EXHAUSTED) {
                metrics.recordRejected("chunk");
            }
            throw e;
        }
        return reserved;
    }

    /**
     * chunk 终态结算（DONE/FAILED 收尾同事务调用）：
     * 应收 = chunk 累计成功张数 × 单张成本；已在历史轮次确认的部分不重复确认
     * （retry-failed 重开的 chunk 会计入新预占）；差额按开口预占顺序吃掉，
     * 剩余开口全部释放——"确认实际成功数 + 释放失败/跳过部分"一个入口完成。
     */
    public void settleChunk(CouponGrantTaskPO task, CouponGrantChunkPO chunk) {
        CouponTemplate template = templateReader.get(task.getTemplateId()).orElse(null);
        if (template == null || !gateOn(template)) {
            return;   // 模板没了/预算关着：当初就没预占（或模板物理删除的理论不可达），无事可结
        }
        String bizId = chunkBizId(task.getTaskNo(), chunk.getChunkNo());
        long unitCost = template.getBudgetUnitCost();
        // 重读 chunk 的 success_count：本事务里 item 记账的累加值对自己可见
        CouponGrantChunkPO fresh = chunkMapper.selectById(chunk.getId());
        long successCount = fresh == null ? 0 : fresh.getSuccessCount();
        long needed = successCount * unitCost;
        long alreadyConfirmed = reservationMapper.sumConfirmedByBiz(
                CouponBudgetReservationPO.BIZ_CHUNK, bizId);
        long delta = needed - alreadyConfirmed;
        if (delta > 0) {
            long remaining = delta;
            for (CouponBudgetReservationPO open : reservationMapper.selectOpenByBiz(
                    CouponBudgetReservationPO.BIZ_CHUNK, bizId)) {
                if (remaining <= 0) {
                    break;
                }
                long capacity = open.getAmount() - open.getConfirmedAmount();
                if (capacity <= 0) {
                    continue;
                }
                long take = Math.min(capacity, remaining);
                if (txService.confirm(open.getId(), take)) {
                    remaining -= take;
                }
            }
            if (remaining > 0) {
                // 结算想吃掉的历史成功没有开口预占覆盖：预占被过期回收/人工干预动过。
                // 不虚构确认（账实不符宁可少记消耗），漂移交给对账任务揪出来
                log.error("budget settle under-covered: taskNo={}, chunkNo={}, shortfall={} 分",
                        task.getTaskNo(), chunk.getChunkNo(), remaining);
            }
        }
        // 关闭全部开口预占：全额确认的关成 CONFIRMED（release 内部按开口额判断），
        // 有剩余的回池——此后该 chunk 在预算账上彻底收口
        for (CouponBudgetReservationPO open : reservationMapper.selectOpenByBiz(
                CouponBudgetReservationPO.BIZ_CHUNK, bizId)) {
            txService.release(open.getId());
        }
    }

    /**
     * 僵尸预占回收：超 expire_time 仍未终态的预占回池（进程崩在"预占后、收口前"）。
     * 逐条短事务；返回本轮回收条数。调用方：GrantTaskWorker 每轮 reap 阶段顺带执行
     * （开了 grant worker 的部署免费获得回收），测试/运维可直调。
     */
    public int expireStale() {
        LocalDateTime now = LocalDateTime.now();
        int reaped = 0;
        while (reaped < EXPIRE_ROUND_LIMIT) {
            List<CouponBudgetReservationPO> stale = txService.selectStale(now, expireBatchSize);
            if (stale.isEmpty()) {
                break;
            }
            for (CouponBudgetReservationPO reservation : stale) {
                reaped += txService.expire(reservation.getId(), now);
            }
            if (stale.size() < expireBatchSize) {
                break;   // 扫到底了，不必再查一次
            }
        }
        return reaped;
    }

    /** 批量释放（建任务失败/并发输家的回滚路径）：逐条释放，单条异常不中断其余。 */
    public void releaseQuietly(List<CouponBudgetReservationPO> reservations) {
        for (CouponBudgetReservationPO reservation : reservations) {
            try {
                txService.release(reservation.getId());
            } catch (RuntimeException e) {
                log.error("budget release failed (reaper will recover): reserveNo={}",
                        reservation.getReserveNo(), e);
            }
        }
    }

    /** chunk 预占的业务定位键：{taskNo}-{chunkNo}（与 worker 的 item 幂等键同一风格）。 */
    static String chunkBizId(String taskNo, int chunkNo) {
        return taskNo + "-" + chunkNo;
    }
}
