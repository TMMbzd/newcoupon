package com.newcoupon.application.service;

import com.newcoupon.application.dto.TemplateQueryDtos.TemplateListItem;
import com.newcoupon.application.dto.TemplateQueryDtos.TemplatePage;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
 * <p><b>已领列的真实口径（审查 Ma2）</b>：coupon_template.issued_count 是 V10 的
 * 冻结快照，C1（ADR-0018 §3.1）下新模板恒 0——真实已发 = Σcoupon_stock_shard.used。
 * 这里按本页模板 id 集合做**一条 IN 聚合**（不是逐行 SUM）：size 上限 100 → IN ≤ 100，
 * 主键索引范围扫，代价可控。未发布过的模板没有分片行 → 0（没发布过不可能发出去券）。
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
    private final CouponStockShardMapper shardMapper;

    public TemplateQueryService(TemplateMapper templateMapper,
                                CouponStockShardMapper shardMapper) {
        this.templateMapper = templateMapper;
        this.shardMapper = shardMapper;
    }

    /** id 倒序分页：新券排前面（运营视角：刚建的最关心）。 */
    public TemplatePage list(int page, int size) {
        long offset = (long) (page - 1) * size;
        long total = templateMapper.countAll();
        List<CouponTemplatePO> pageRows = templateMapper.selectPageByIdDesc(size, offset);
        Map<Long, Long> usedByTemplate = sumUsedForPage(pageRows);
        List<TemplateListItem> rows = pageRows.stream()
                .map(po -> toItem(po, usedByTemplate.getOrDefault(po.getId(), 0L)))
                .toList();
        return new TemplatePage(total, page, size, rows);
    }

    /** 本页模板的 Σused（真实已发）：一条 IN 聚合，空页短路不发 SQL。 */
    private Map<Long, Long> sumUsedForPage(List<CouponTemplatePO> pageRows) {
        if (pageRows.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = pageRows.stream().map(CouponTemplatePO::getId).toList();
        return shardMapper.sumUsedBatch(ids).stream()
                .collect(Collectors.toMap(CouponStockShardPO::getTemplateId,
                        CouponStockShardPO::getUsed));
    }

    /**
     * PO → 表格行：只投影八列；issuedCount 用分片真实口径（见类注释 Ma2）。
     * 枚举码走降级路径（见 {@link #enumDesc}）——列表是扇出读，一行脏数据不能掀翻整页。
     */
    private static TemplateListItem toItem(CouponTemplatePO po, long realUsed) {
        return new TemplateListItem(
                po.getId(),
                po.getName(),
                enumDesc("券类型", po.getCouponType(), code -> CouponType.of(code).getDesc()),
                po.getFaceValue(),
                enumDesc("发布状态", po.getPublishStatus(), code -> PublishStatus.of(code).getDesc()),
                po.getTotalCount(),
                realUsed,
                po.getCreateTime());
    }

    /**
     * 枚举码 → 描述，未知码降级显示"未知码 N"而不抛（审查 mi4）。
     *
     * <p><b>为什么列表降级、详情维持现状（TemplateResponse 的 of() 会抛）</b>：列表是
     * 扇出读——版本回滚后库里存在新枚举值时，一行脏数据若抛 IllegalArgumentException，
     * 整页 500 且翻页绕不过（新模板 id 必在第一页）；详情是单行语义，未知码抛错
     * 反而把"这行数据与当前代码版本不一致"尽早暴露给调用方。两条路径的语义差异
     * 是有意为之。
     */
    private static String enumDesc(String what, Integer code, java.util.function.IntFunction<String> resolver) {
        try {
            return resolver.apply(code);
        } catch (IllegalArgumentException e) {
            return what + "未知码 " + code;
        }
    }
}
