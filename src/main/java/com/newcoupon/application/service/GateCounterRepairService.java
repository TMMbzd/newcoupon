package com.newcoupon.application.service;

import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponDailyCounterMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponUserDailyCounterMapper;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import org.springframework.stereotype.Service;

/**
 * Redis gate 限领计数的 DB 重建（ADR-0021 Phase 1.5）。
 *
 * <p>目标值 = DB 已发券 + 在途任务，防止两件事：
 * <ul>
 *   <li>Redis 计数偏高导致假限领：DB 目标低于 limit 时修低并重试；</li>
 *   <li>Redis 计数丢失/偏低导致 over-admission：目标至少覆盖已发 + 在途；DB 仍是终审。</li>
 * </ul>
 *
 * <p>修复不是事务：计数和任务可能在读与写之间变化；允许短暂 over-admission，
 * 绝不能把“DB 已达到”误修成“还能领”。修复后仍由 Lua 原子检查和 DB 条件更新裁决。
 */
@Service
public class GateCounterRepairService {

    private final UserCouponRepository userCouponRepository;
    private final CouponIssueTaskMapper taskMapper;
    private final CouponDailyCounterMapper dailyCounterMapper;
    private final CouponUserDailyCounterMapper userDailyCounterMapper;
    private final RedisIssueGate issueGate;

    public GateCounterRepairService(UserCouponRepository userCouponRepository,
                                    CouponIssueTaskMapper taskMapper,
                                    CouponDailyCounterMapper dailyCounterMapper,
                                    CouponUserDailyCounterMapper userDailyCounterMapper,
                                    RedisIssueGate issueGate) {
        this.userCouponRepository = userCouponRepository;
        this.taskMapper = taskMapper;
        this.dailyCounterMapper = dailyCounterMapper;
        this.userDailyCounterMapper = userDailyCounterMapper;
        this.issueGate = issueGate;
    }

    /** 全量修复：GATE_MISSING/GATE_CORRUPT/COUNTER_CORRUPT/COUNTER_MISSING 后调用。 */
    public void repairAll(RedisIssueGate.GateCommand cmd) {
        issueGate.repairUserTotal(cmd, userTotalTarget(cmd));
        if (cmd.perUserDaily() > 0) {
            issueGate.repairUserDaily(cmd, userDailyTarget(cmd));
        }
        if (cmd.dailyLimit() > 0) {
            issueGate.repairTemplateDaily(cmd, templateDailyTarget(cmd));
        }
        issueGate.ensureSeqAtLeast(cmd,
                userCouponRepository.maxSeqByUserAndTemplate(cmd.userId(), cmd.templateId()));
    }

    /**
     * 限领拒绝后的 DB 二次确认。
     *
     * @return true=DB 目标仍低于 limit，已修复计数，调用方可以重试一次；
     *         false=DB 确实达到 limit，应按限领拒绝。
     */
    public boolean repairLimitIfAllowed(RedisIssueGate.GateCommand cmd, int gateCode) {
        switch (gateCode) {
            case RedisIssueGate.USER_TOTAL_LIMIT -> {
                long target = userTotalTarget(cmd);
                if (target >= cmd.maxPerUser()) {
                    return false;
                }
                issueGate.repairUserTotal(cmd, target);
                return true;
            }
            case RedisIssueGate.USER_DAILY_LIMIT -> {
                if (cmd.perUserDaily() <= 0) {
                    return false;
                }
                long target = userDailyTarget(cmd);
                if (target >= cmd.perUserDaily()) {
                    return false;
                }
                issueGate.repairUserDaily(cmd, target);
                return true;
            }
            case RedisIssueGate.TEMPLATE_DAILY_LIMIT -> {
                if (cmd.dailyLimit() <= 0) {
                    return false;
                }
                long target = templateDailyTarget(cmd);
                if (target >= cmd.dailyLimit()) {
                    return false;
                }
                issueGate.repairTemplateDaily(cmd, target);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private long userTotalTarget(RedisIssueGate.GateCommand cmd) {
        return computeUserTotalTarget(cmd.userId(), cmd.templateId());
    }

    private long userDailyTarget(RedisIssueGate.GateCommand cmd) {
        return computeUserDailyTarget(cmd.userId(), cmd.templateId(), cmd.day());
    }

    private long templateDailyTarget(RedisIssueGate.GateCommand cmd) {
        return computeTemplateDailyTarget(cmd.templateId(), cmd.day());
    }

    // ------------------------------------------------------------------
    // 裸 id 版本：对账任务（GateCounterReconcileJob）复用同一公式，避免两处漂移。
    // ------------------------------------------------------------------

    /** ul 目标 = DB 已发券 + 在途任务。 */
    public long computeUserTotalTarget(long userId, long templateId) {
        return userCouponRepository.countByUserAndTemplate(userId, templateId)
                + taskMapper.countInFlightByUserAndTemplate(userId, templateId);
    }

    /** uld 目标 = DB 日限计数 + 当日在途任务。 */
    public long computeUserDailyTarget(long userId, long templateId, java.time.LocalDate day) {
        Long dbUsed = userDailyCounterMapper.selectUsed(userId, templateId, day);
        return nz(dbUsed)
                + taskMapper.countInFlightByUserTemplateDay(userId, templateId, day);
    }

    /** dly 目标 = DB 日限计数 + 当日在途任务。 */
    public long computeTemplateDailyTarget(long templateId, java.time.LocalDate day) {
        Long dbUsed = dailyCounterMapper.selectUsed(templateId, day);
        return nz(dbUsed) + taskMapper.countInFlightByTemplateDay(templateId, day);
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
