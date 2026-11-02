package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * worker 的单个任务事务（ADR-0019）：
 *
 * <pre>
 *   issueC1（每人日计数 → 模板日计数 → C1 库存分片 → 券/事件/outbox/过期任务）
 *   → 任务 SUCCESS
 * </pre>
 *
 * 所有步骤同一个本地事务：日限/库存/券/任务状态要么一起成功，要么一起回滚。
 * 日限计数已收进 {@link CouponIssueTxService#issueC1}，同步和异步共用同一份逻辑。
 * 业务拒绝（日限、库存满）会抛 {@code BizException} 或 {@link StockShardDeductRejectedException}，
 * 由 processor 在事务外分类成 REJECTED；不能在这个事务里把任务标成 REJECTED，否则回滚会把它抹掉。
 */
@Service
public class CouponIssueTaskTxService {

    private final CouponIssueTxService issueTxService;
    private final CouponIssueTaskMapper taskMapper;

    public CouponIssueTaskTxService(CouponIssueTxService issueTxService,
                                    CouponIssueTaskMapper taskMapper) {
        this.issueTxService = issueTxService;
        this.taskMapper = taskMapper;
    }

    @Transactional
    public UserCoupon process(CouponIssueTaskPO task, CouponTemplate template,
                              int seq, int layoutVersion, int shardNo, String owner) {
        LocalDate statDay = task.getStatDay();
        if (statDay == null) {
            throw new IllegalStateException("issue task stat_day is null: taskId=" + task.getId());
        }

        ReceiveRequest req = new ReceiveRequest(task.getRequestId(), task.getUserId(), task.getTemplateId());
        UserCoupon coupon = issueTxService.issueC1(
                req, template, seq, layoutVersion, shardNo, LocalDateTime.now(), statDay);

        // 任务状态和券在同一事务：markSuccess 失败（lease 被抢）会回滚券/库存/日计数。
        int updated = taskMapper.markSuccess(task.getId(), owner, coupon.getId());
        if (updated != 1) {
            throw new IssueTaskLeaseLostException(task.getId(), owner);
        }
        return coupon;
    }
}
