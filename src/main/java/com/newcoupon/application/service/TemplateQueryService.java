package com.newcoupon.application.service;

import com.newcoupon.application.dto.TemplateQueryDtos.TemplateListItem;
import com.newcoupon.application.dto.TemplateQueryDtos.TemplatePage;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 模板列表查询（V6 2D 控制台最小页，仅内部面）。
 *
 * <p><b>与读侧先例的关系</b>：与 FinanceQueryService/AuditQueryService 同一立场——
 * 列表是投给表格的投影查询，不走聚合仓储（那套为"单实体的业务不变量"服务，
 * 拉全字段再转换是白付的），直接用 Mapper + COUNT/LIMIT（项目无 MyBatis 分页插件）。
 *
 * <p><b>归属</b>：/api 内部面 = 平台视角看全部（AppOwnership 的"管理员例外"，
 * 与 /api/coupons 同语义），不按 app_id 过滤。
 *
 * <p><b>上限</b>：size 默认 20 上限 100——控制台一页表格的真实上限，
 * 顺带挡住"一次拉全表"。参数合法性（page≥1、size≤100、拒绝与 400）在
 * Controller 层做（协议语义）；这里收到的已是干净的页码。
 */
@Service
public class TemplateQueryService {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final TemplateMapper templateMapper;

    public TemplateQueryService(TemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    /** id 倒序分页：新券排前面（运营视角：刚建的最关心）。 */
    public TemplatePage list(int page, int size) {
        long offset = (long) (page - 1) * size;
        long total = templateMapper.countAll();
        List<TemplateListItem> rows = templateMapper
                .selectPageByIdDesc(size, offset).stream()
                .map(TemplateQueryService::toItem)
                .toList();
        return new TemplatePage(total, page, size, rows);
    }

    /** PO → 表格行：只投影八列；couponType/publishStatus 翻成中文描述。 */
    private static TemplateListItem toItem(CouponTemplatePO po) {
        return new TemplateListItem(
                po.getId(),
                po.getName(),
                CouponType.of(po.getCouponType()).getDesc(),
                po.getFaceValue(),
                PublishStatus.of(po.getPublishStatus()).getDesc(),
                po.getTotalCount(),
                po.getIssuedCount(),
                po.getCreateTime());
    }
}
