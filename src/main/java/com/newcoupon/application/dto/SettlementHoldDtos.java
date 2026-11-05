package com.newcoupon.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券侧应付冻结 DTO（V47，内部面 POST）。全部可空字段见各 record 注释。
 *
 * <p>hold 语义：只改主体账单应付口径（payable=分账-退款-hold），不动真实资金、
 * 不影响用户退款——见 SettlementHoldService 类注释。
 */
public final class SettlementHoldDtos {

    private SettlementHoldDtos() {
    }

    /** POST /api/settlement/holds 建冻结 */
    public record HoldPlaceRequest(
            @NotBlank(message = "holdNo 不能为空") @Size(max = 64) String holdNo,
            @NotBlank(message = "appId 不能为空") @Size(max = 32) String appId,
            @NotBlank(message = "settlePartyId 不能为空") @Size(max = 64) String settlePartyId,
            /** 可空 = 主体级（该主体全部模板行都扣）；非空 = 模板级（服务校验模板存在） */
            Long templateId,
            @NotNull(message = "amount 不能为空") @Min(value = 1, message = "冻结金额必须为正（分）")
            Long amount,
            @NotBlank(message = "reason 不能为空") @Size(max = 255) String reason,
            /** RISK_RULE / MANUAL；缺省 MANUAL（4B 风控接入时显式传 RISK_RULE） */
            String source) {
    }

    /** release / void 请求（终态迁移；操作人从当前认证上下文解析，不由请求自报——防冒名） */
    public record HoldTransitionRequest(
            @NotBlank(message = "holdNo 不能为空") @Size(max = 64) String holdNo) {
    }

    /** query 请求：游标分页（照 2C 审计查询口径——禁 OFFSET，主键翻页任意深度同价） */
    public record HoldQueryRequest(
            @Size(max = 32) String appId,
            @Size(max = 64) String settlePartyId,
            /** ACTIVE / RELEASED / VOIDED；空 = 全部 */
            @Size(max = 16) String status,
            /** 上一页最后一个 id；首页不传 */
            Long cursor,
            @Min(1) @Max(200) Integer limit) {
    }

    /** 冻结行视图（duplicated=true 幂等重放命中已存在行；matchedHistory=近 90 天该
     *  (appId,主体) 有分账行——false 大概率主体拼错，人工核对，不强校验） */
    public record HoldView(
            Long id,
            String holdNo,
            String appId,
            String settlePartyId,
            Long templateId,
            Long amount,
            String reason,
            String source,
            String status,
            String createdBy,
            String releasedBy,
            LocalDateTime createdAt,
            LocalDateTime releasedAt,
            boolean duplicated,
            boolean matchedHistory) {
    }

    /** 游标分页结果（nextCursor=null 表示没有下一页） */
    public record HoldQueryResult(List<HoldView> items, Long nextCursor) {
    }
}
