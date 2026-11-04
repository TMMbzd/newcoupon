package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.mapper.CouponActivityMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetAccountMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetLedgerMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetReservationMapper;
import com.newcoupon.infrastructure.persistence.po.CouponActivityPO;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetAccountPO;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetLedgerPO;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetReservationPO;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预算账本的事务内部分（V6 阶段 1C）。拆分理由与 {@link GrantTaskTxService} 相同：
 * 编排（{@link BudgetService}）不持事务，每个账本动作一个短事务、行锁只在动作内持有。
 *
 * <p><b>并发安全的锚点</b>：预占是账户行的条件 UPDATE（reserved+consumed+amount<=total），
 * 1000 并发抢最后一份预算也只有打得中条件的赢家成功——与 C1 库存分片的条件更新同构。
 * 账户行 UPDATE 持有的行锁把同一账户的所有账本动作串成全序，因此 ledger 的
 * balance_after 快照按 id 严格可回放（对账等式①的依据）。
 *
 * <p><b>结算模型</b>：预占行支持累进 confirm（confirmed_amount 累加、终态后 CAS 让路）。
 * chunk 结算 = "按累计应收确认差额 + 释放剩余开口"，一个入口同时覆盖正常完成、
 * 部分失败、取消、retry-failed 重开再完成四种收场。
 */
@Service
public class BudgetTxService {

    private static final Logger log = LoggerFactory.getLogger(BudgetTxService.class);

    private final CouponBudgetAccountMapper accountMapper;
    private final CouponBudgetReservationMapper reservationMapper;
    private final CouponBudgetLedgerMapper ledgerMapper;
    private final CouponActivityMapper activityMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final BudgetMetrics metrics;
    private final EnvironmentGuard environmentGuard;

    public BudgetTxService(CouponBudgetAccountMapper accountMapper,
                           CouponBudgetReservationMapper reservationMapper,
                           CouponBudgetLedgerMapper ledgerMapper,
                           CouponActivityMapper activityMapper,
                           SnowflakeIdGenerator idGenerator,
                           BudgetMetrics metrics,
                           EnvironmentGuard environmentGuard) {
        this.accountMapper = accountMapper;
        this.reservationMapper = reservationMapper;
        this.ledgerMapper = ledgerMapper;
        this.activityMapper = activityMapper;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
        this.environmentGuard = environmentGuard;
    }

    /**
     * 预占一个事务：账户条件更新 + 预占行 + RESERVE 流水三写同事务。
     * 账户行不存在时先按活动的 budget_amount 懒建（预算开关晚开的活动没有建账户时机，
     * 第一次预占就是时机）；并发懒建撞 uk 由输家重读继续，不报错。
     *
     * @throws BizException BUDGET_EXHAUSTED 条件更新打 0 行（预算不足）
     */
    @Transactional
    public CouponBudgetReservationPO reserve(long activityId, String appId, int bizType,
                                             String bizId, long amount, LocalDateTime expireTime) {
        CouponBudgetAccountPO account = ensureAccount(activityId, appId);
        if (accountMapper.tryReserve(account.getId(), amount) != 1) {
            // 条件打不中=池不够（账户刚懒建过总额，不存在"不存在"歧义）
            throw new BizException(ErrorCode.BUDGET_EXHAUSTED,
                    "活动 " + activityId + " 预算已耗尽（本次预占 " + amount + " 分）");
        }
        CouponBudgetReservationPO reservation = insertReservation(account.getId(),
                bizType, bizId, amount, expireTime);
        appendLedger(account.getId(), reservation.getId(), CouponBudgetLedgerPO.OP_RESERVE,
                amount, "biz=" + bizType + ":" + bizId + " 预占");
        return reservation;
    }

    /**
     * 累进确认一个事务：reservation.confirmed += delta（CAS 终态让路）、
     * 账户 reserved-=delta、consumed+=delta、CONFIRM 流水。
     * delta 打不中（超预占额/已终态）静默返回 false——调用方（chunk 结算）按
     * "开口额度顺序吃掉"的算法保证不打超，打不中只发生在双跑竞态，让路即幂等。
     */
    @Transactional
    public boolean confirm(long reservationId, long delta) {
        if (delta <= 0) {
            return false;   // 0/负数确认无意义（success=0 的 chunk 不产生确认）
        }
        CouponBudgetReservationPO reservation = reservationMapper.selectById(reservationId);
        if (reservation == null) {
            return false;
        }
        if (reservationMapper.confirmDelta(reservationId, delta) != 1) {
            return false;   // 已终态或额度不足：双跑输家让路
        }
        // 确认转移：这笔 delta 从预留回池、进消耗账（reserved 与 consumed 同步动）
        accountMapper.confirmTransfer(reservation.getAccountId(), delta, delta);
        appendLedger(reservation.getAccountId(), reservationId,
                CouponBudgetLedgerPO.OP_CONFIRM, delta,
                "biz=" + reservation.getBizType() + ":" + reservation.getBizId() + " 确认 " + delta);
        return true;
    }

    /**
     * 释放一个事务：开口余额（amount-confirmed）回池 + reservation 终态 + RELEASE 流水。
     * 开口=0（已全额确认过）时终态关成 CONFIRMED 而不是 RELEASED——财务上它全部
     * 消耗了，关成"释放"会让对账/排障读出误导语义（且无流水可写：账户没变）。
     * 已终态打不中 close 的 CAS：不回池、不记流水——释放天然幂等。
     */
    @Transactional
    public void release(long reservationId) {
        CouponBudgetReservationPO reservation = reservationMapper.selectById(reservationId);
        if (reservation == null || reservation.getStatus() != CouponBudgetReservationPO.STATUS_RESERVED) {
            return;
        }
        long openAmount = reservation.getAmount() - reservation.getConfirmedAmount();
        int targetStatus = openAmount == 0
                ? CouponBudgetReservationPO.STATUS_CONFIRMED
                : CouponBudgetReservationPO.STATUS_RELEASED;
        if (reservationMapper.close(reservationId, targetStatus, reservation.getConfirmedAmount()) != 1) {
            return;   // 并发对手（过期回收/双跑）先关掉了：它的回池已配对，不再动
        }
        if (openAmount > 0) {
            accountMapper.release(reservation.getAccountId(), openAmount);
            appendLedger(reservation.getAccountId(), reservationId,
                    CouponBudgetLedgerPO.OP_RELEASE, -openAmount,
                    "biz=" + reservation.getBizType() + ":" + reservation.getBizId() + " 释放 " + openAmount);
        }
    }

    /**
     * 过期回收一个事务：与 {@link #release} 同款回池，只是终态为 3（EXPIRE 流水）。
     * 过期判据（expire_time &lt; now）两侧都是应用钟——expire_time 由应用写入，
     * now 由调用方传 LocalDateTime.now()，不与 DB NOW(3) 混用。
     */
    @Transactional
    public int expire(long reservationId, LocalDateTime now) {
        CouponBudgetReservationPO reservation = reservationMapper.selectById(reservationId);
        if (reservation == null || reservation.getStatus() != CouponBudgetReservationPO.STATUS_RESERVED) {
            return 0;
        }
        // 复核过期：SELECT 到 UPDATE 之间可能已被 confirm 关闭（不算过期）
        if (reservation.getExpireTime() != null && reservation.getExpireTime().isAfter(now)) {
            return 0;
        }
        // 开口余额按快照算，close 带上 seenConfirmed 乐观锁：并发 confirm 在本读与
        // close 之间提交时（confirm 只加 confirmed 不关行），close 打 0 行认输——
        // 否则按旧快照回池会把 confirm 已转走的额度再退一次（预算双花）
        long openAmount = reservation.getAmount() - reservation.getConfirmedAmount();
        if (reservationMapper.close(reservationId, CouponBudgetReservationPO.STATUS_EXPIRED,
                reservation.getConfirmedAmount()) != 1) {
            return 0;
        }
        if (openAmount > 0) {
            accountMapper.release(reservation.getAccountId(), openAmount);
            appendLedger(reservation.getAccountId(), reservationId,
                    CouponBudgetLedgerPO.OP_EXPIRE, -openAmount,
                    "biz=" + reservation.getBizType() + ":" + reservation.getBizId() + " 僵尸预占回收");
        }
        log.warn("budget reservation expired and returned to pool: reserveNo={}, openAmount={}",
                reservation.getReserveNo(), openAmount);
        return 1;
    }

    // ------------------------------------------------------------------
    // 内部：账户懒建 / 预占行 / 流水
    // ------------------------------------------------------------------

    /**
     * 取预算账户；不存在则按活动 budget_amount 懒建。
     *
     * <p><b>并发懒建的输家处理（为什么不 SELECT FOR UPDATE 后 INSERT）</b>：
     * "锁住不存在行（间隙锁）再插入"是 find-or-create 的死锁反模式——两个事务的
     * 间隙锁互相兼容、插入意向锁又都被对方的间隙锁挡住，三方并发即死锁（实测）。
     * 正确姿势是<b>先 INSERT</b>：并发插入同一 uk 只有一方等另一方，无环。
     * 撞键的事务以 {@link AccountRaceRetry} 标记回滚，由**外层新事务**重试整个
     * reserve——新事务的快照能看到赢家已提交的账户行（INSERT 报撞键时赢家必已
     * 提交，MySQL 的插入意向锁会等到对方事务结束）。
     */
    private CouponBudgetAccountPO ensureAccount(long activityId, String appId) {
        CouponBudgetAccountPO existing = accountMapper.selectByScope(
                CouponBudgetAccountPO.SCOPE_ACTIVITY, activityId,
                CouponBudgetAccountPO.PERIOD_TOTAL, CouponBudgetAccountPO.PERIOD_KEY_TOTAL);
        if (existing != null) {
            return existing;
        }
        CouponActivityPO activity = activityMapper.selectById(activityId);
        if (activity == null) {
            // 调用方（BudgetService）已按"开关开=活动存在"放行到这里；
            // 活动被并发删除属理论不可达，按参数错误拒绝而不是 NPE
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND, "id=" + activityId);
        }
        // 资金边界守卫（V6 2B）：懒建=第一笔真钱进账本，沙箱租户到此为止（4002）。
        // 放在 activity 存在性之后、金额校验之前——环境问题比配置问题更早暴露。
        // 语义约定：沙箱活动想测发放就不要开 budget_enabled（券照发、账本不建）。
        environmentGuard.assertProd(activity.getAppId() != null ? activity.getAppId() : appId,
                "budget-account");
        if (activity.getBudgetAmount() == null || activity.getBudgetAmount() <= 0) {
            // budget_amount 只有 NOT NULL 没有 >0 约束（存量行可为 0；开关还支持
            // 事后直改 DB 打开）——放任 INSERT 会撞账户 CHECK 的裸
            // DataIntegrityViolationException，领取/兑换/建任务全部 500 且每试必现。
            // 配置问题挡在账本之外，给可运营处置的业务错
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "活动预算必须为正：activityId=" + activityId
                            + ", budget_amount=" + activity.getBudgetAmount());
        }
        CouponBudgetAccountPO account = new CouponBudgetAccountPO();
        account.setScopeType(CouponBudgetAccountPO.SCOPE_ACTIVITY);
        account.setScopeId(activityId);
        account.setAppId(activity.getAppId() != null ? activity.getAppId() : appId);
        account.setPeriodType(CouponBudgetAccountPO.PERIOD_TOTAL);
        account.setPeriodKey(CouponBudgetAccountPO.PERIOD_KEY_TOTAL);
        // total 首建取活动 budget_amount；此后账户行是权威（改活动列不再影响账本）
        account.setTotal(activity.getBudgetAmount());
        account.setReserved(0L);
        account.setConsumed(0L);
        account.setVersion(0L);
        try {
            accountMapper.insert(account);
            log.info("budget account lazily created: activityId={}, total={}",
                    activityId, account.getTotal());
            return account;
        } catch (DuplicateKeyException e) {
            // 本事务 RR 快照建立于开头的 selectByScope，看不到赢家已提交的行——
            // 在本事务里重读必然空。让整笔 reserve 回滚，外层换新事务重试
            throw new AccountRaceRetry();
        }
    }

    /**
     * 账户懒建撞并发的内部标记：不进对外的错误语义（不是业务失败），
     * {@link BudgetService} 捕获后换一个新事务重试一次。
     */
    static final class AccountRaceRetry extends RuntimeException {
        AccountRaceRetry() {
            super(null, null, false, false);   // 无栈无消息：高频路径的栈构造是纯浪费
        }
    }

    /** 插入预占行（reserve_no=BR+雪花ID；biz 上不设唯一键——retry 重开 chunk 会合法追加）。 */
    private CouponBudgetReservationPO insertReservation(long accountId, int bizType,
                                                        String bizId, long amount,
                                                        LocalDateTime expireTime) {
        CouponBudgetReservationPO reservation = new CouponBudgetReservationPO();
        reservation.setReserveNo("BR" + idGenerator.next());
        reservation.setAccountId(accountId);
        reservation.setBizType(bizType);
        reservation.setBizId(bizId);
        reservation.setAmount(amount);
        reservation.setStatus(CouponBudgetReservationPO.STATUS_RESERVED);
        reservation.setConfirmedAmount(0L);
        reservation.setExpireTime(expireTime);
        reservationMapper.insert(reservation);
        return reservation;
    }

    /**
     * 追加一条流水（带 balance_after 快照）。必须在账户行锁仍被本事务持有时调用
     * （紧跟账户 UPDATE 之后）——否则快照会与并发对手的变动交错，回放失真。
     * 同点更新可用额 gauge：指标与流水同源，不会各自漂移。
     */
    private void appendLedger(long accountId, long reservationId, String op,
                              long signedAmount, String note) {
        CouponBudgetAccountPO after = accountMapper.selectByIdForSnapshot(accountId);
        CouponBudgetLedgerPO ledger = new CouponBudgetLedgerPO();
        ledger.setAccountId(accountId);
        ledger.setReservationId(reservationId);
        ledger.setOp(op);
        ledger.setAmount(signedAmount);
        ledger.setReservedAfter(after.getReserved());
        ledger.setConsumedAfter(after.getConsumed());
        ledger.setNote(note);
        ledgerMapper.insert(ledger);
        metrics.updateAvailable(accountId, after.getScopeId(),
                after.getTotal() - after.getReserved() - after.getConsumed());
    }

    /** 过期扫描的批量入口（reaper 每轮调用；逐行走 {@link #expire} 短事务）。 */
    public List<CouponBudgetReservationPO> selectStale(LocalDateTime now, int limit) {
        return reservationMapper.selectStale(now, limit);
    }
}
