package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C1 模板发布事务：状态 CAS + 分片布局建行必须同成同败。
 *
 * <p>为什么不能沿用旧链路“先 CAS 发布、再开桶”：旧链路中间态可以由 Redis 懒重建兜底；
 * C1 没有 Redis 库存可重建，发布后没有分片行就是“模板可见但不可领”。放进一个事务后，
 * 建行失败会连发布状态一起回滚，用户看到的仍然是未发布，可以安全重试。
 */
@Service
public class TemplatePublishTxService {

    private final TemplateMapper templateMapper;
    private final StockShardLayoutService layoutService;

    public TemplatePublishTxService(TemplateMapper templateMapper,
                                    StockShardLayoutService layoutService) {
        this.templateMapper = templateMapper;
        this.layoutService = layoutService;
    }

    @Transactional
    public void publishC1(long templateId) {
        CouponTemplatePO t = templateMapper.selectById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + templateId);
        }
        if (t.getApproveStatus() == null || t.getApproveStatus() != 1) {
            throw new BizException(ErrorCode.TEMPLATE_STATE_INVALID, "审批未通过，不可发布");
        }
        if (t.getPublishStatus() == null || t.getPublishStatus() != 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "模板状态已变化，请刷新后重试");
        }
        int updated = templateMapper.casUpdatePublishStatus(templateId, 0, 1);
        if (updated != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "发布失败：状态已被并发变更，请刷新后重试");
        }
        layoutService.initializeForPublish(templateId);
    }
}
