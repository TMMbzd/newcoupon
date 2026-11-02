package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务领取（ADR-0019 worker）：一个短事务里 `FOR UPDATE SKIP LOCKED` + 打 lease。
 *
 * <p>行锁只保持到本方法提交，之后任务由 worker 自己的事务处理；lease_until 保证
 * worker 崩溃后其它实例能回收。多实例竞争不依赖 Redis 锁。
 */
@Service
public class CouponIssueTaskClaimService {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueTaskClaimService.class);

    private final CouponIssueTaskMapper taskMapper;

    public CouponIssueTaskClaimService(CouponIssueTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Transactional
    public List<CouponIssueTaskPO> claim(int limit, String owner, Duration lease) {
        LocalDateTime now = LocalDateTime.now();
        List<CouponIssueTaskPO> rows = taskMapper.selectClaimable(now, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(CouponIssueTaskPO::getId).toList();
        int updated = taskMapper.markProcessing(ids, owner, now.plus(lease), now);
        if (updated != ids.size()) {
            // 极小概率：领取后行被其它事务改了 lease。返回仍处理，markSuccess 的 owner 条件会兜底。
            log.warn("issue task claim partial: selected={}, leased={}, owner={}",
                    ids.size(), updated, owner);
        }
        return rows;
    }
}
