package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponIssueGateRepairMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueGateRepairPO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 修复队列领取：短事务里 SELECT ... SKIP LOCKED + 打 lease，多实例不重复。 */
@Service
public class GateRepairClaimService {

    private final CouponIssueGateRepairMapper mapper;

    public GateRepairClaimService(CouponIssueGateRepairMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public List<CouponIssueGateRepairPO> claim(int limit, String owner, Duration lease) {
        LocalDateTime now = LocalDateTime.now();
        List<CouponIssueGateRepairPO> rows = mapper.selectDue(now, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(CouponIssueGateRepairPO::getId).toList();
        mapper.markProcessing(ids, owner, now.plus(lease), now);
        return rows;
    }
}
