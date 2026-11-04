package com.newcoupon.application.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模板列表查询 DTO（V6 2D 控制台最小页）。列表是**投给表格的投影**，不复用
 * {@link TemplateResponse}——那是详情视图（25 个字段），列表只需要八列；
 * 两个形状各自演化，别让"详情加字段"变成"列表响应变大"。
 *
 * <p>couponType/publishStatus 直接给中文描述（与 TemplateResponse 同口径）：
 * 控制台是给人看的页面，前端不再维护一份码表翻译。
 */
public final class TemplateQueryDtos {

    private TemplateQueryDtos() {
    }

    /** 分页结果：total 全库条数（COUNT 同参），rows 为当前页。 */
    public record TemplatePage(long total, int page, int size, List<TemplateListItem> rows) {
    }

    /**
     * 表格一行：id/名称/券类型/面额/状态/总量/已领/创建时间。
     * issuedCount 是**分片真实口径** Σcoupon_stock_shard.used（审查 Ma2）——
     * coupon_template.issued_count 是 V10 冻结快照、C1 下恒 0，不能当"已领"展示。
     */
    public record TemplateListItem(
            Long id,
            String name,
            String couponType,
            Long faceValue,
            String publishStatus,
            Long totalCount,
            Long issuedCount,
            LocalDateTime createTime) {
    }
}
