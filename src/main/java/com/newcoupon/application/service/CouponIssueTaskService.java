package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 异步发放任务 WAL 的写入/查询（ADR-0019）。
 *
 * <p>这里只做一件事：把“已受理”持久化。它不扣库存、不写券、不碰 Redis；
 * 真正发券在 worker 的 C1 事务里。单条 INSERT 本身就是原子的，不再额外包事务。
 *
 * <p>唯一键冲突不是错误：同一 app+user+requestId 的并发/重放请求应返回同一任务，
 * 由调用方再校验 templateId 是否一致。
 */
@Service
public class CouponIssueTaskService {

    private final CouponIssueTaskMapper taskMapper;

    public CouponIssueTaskService(CouponIssueTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 插入受理任务；若唯一键已存在则返回已有行。
     *
     * @param statDay 受理日，日限归属日；不能由 worker 重新计算
     * @param seq     Redis gate 预分配的序号；0 表示未分配，worker 仍以 DB MAX(seq)+1 终审
     */
    public CouponIssueTaskPO accept(ReceiveRequest req, CouponTemplate template,
                                    LocalDate statDay, long seq) {
        return acceptWithResult(req, template, statDay, seq).task();
    }

    /**
     * 与 {@link #accept} 相同，但返回“本次是否真的新建”：
     * 并发/Redis idem 丢失时，调用方不能把已有任务的重复受理再预扣一次 gate。
     */
    public AcceptResult acceptWithResult(ReceiveRequest req, CouponTemplate template,
                                         LocalDate statDay, long seq) {
        return acceptWithResult(req, template, statDay, seq, "async");
    }

    /** source 用于标记 Redis 故障降级受理等非标准路径，便于 worker/审计识别。 */
    public AcceptResult acceptWithResult(ReceiveRequest req, CouponTemplate template,
                                         LocalDate statDay, long seq, String source) {
        CouponIssueTaskPO po = new CouponIssueTaskPO();
        po.setAppId(template.getAppId());
        po.setUserId(req.userId());
        po.setTemplateId(template.getId());
        po.setRequestId(req.requestId());
        po.setStatDay(statDay);
        po.setSeq(Math.toIntExact(seq));
        po.setSource(source == null || source.isBlank() ? "async" : source);
        po.setStatus(CouponIssueTaskPO.STATUS_PENDING);
        po.setRetryCount(0);
        po.setNextScanTime(LocalDateTime.now());
        po.setGateReleaseState(CouponIssueTaskPO.GATE_PENDING);
        po.setGateNextRetryTime(LocalDateTime.now());
        // 快照：释放时据此判断 ul/uld/dly 是否真的预扣过；模板后续改限不影响已受理任务。
        po.setGateMaxPerUser(template.getPerUserLimit());
        po.setGatePerUserDaily(template.getPerUserDailyLimit());
        po.setGateDailyLimit(template.getDailyLimit());
        try {
            taskMapper.insert(po);
            return new AcceptResult(po, true);
        } catch (DuplicateKeyException e) {
            // 并发/重放：把已有任务返回给编排层，由它校验 templateId 并返回当前状态。
            CouponIssueTaskPO existing = taskMapper.selectByUniqueKey(
                    template.getAppId(), req.userId(), req.requestId());
            if (existing == null) {
                throw e;   // 唯一键刚被删除等极小概率：原样抛出，让调用方补偿
            }
            return new AcceptResult(existing, false);
        }
    }

    public record AcceptResult(CouponIssueTaskPO task, boolean created) {
    }

    public CouponIssueTaskPO findByUniqueKey(String appId, long userId, String requestId) {
        return taskMapper.selectByUniqueKey(appId, userId, requestId);
    }

    /** gate 重建用：该模板尚未发券、且仍占用 gate 的受理数。 */
    public long countInFlight(long templateId) {
        return taskMapper.countInFlight(templateId);
    }
}
