package com.newcoupon.open.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 优惠计算请求 DTO。
 *
 * <p><b>为什么没有 appId 和 requestId 字段</b>（S1 契约，2026-10-10 返工裁定）：
 * <ul>
 *   <li>身份（appId）来自签名认证，由过滤器写入 RequestContext——body 里再带一份就是"双身份"，
 *       两处不一致时无法裁决信哪个；</li>
 *   <li>幂等来自 X-Client-Token 请求头（OpenApiIdempotentFilter 处理）——重复请求返回缓存响应，
 *       与 body 无关。</li>
 * </ul>
 *
 * <p>两道校验分工：JSR-303（这里）管形态（非空/正数/长度），不合法 400；
 * 计算引擎管业务（券状态/时间/门槛），结果放 unusableCoupons。
 *
 * <p>@Positive/@Min：单价与数量必须为正——负价会把 orderAmount 拉低，
 * 可能绕过门槛判断（申报防线的第一层）。
 */
public record CalculateRequest(

        @NotNull(message = "用户ID不能为空")
        Long userId,

        @NotEmpty(message = "订单商品不能为空")
        @Size(max = 100, message = "订单商品最多100项")
        @Valid
        List<OrderItem> items,

        @NotEmpty(message = "优惠券ID列表不能为空")
        @Size(max = 10, message = "最多同时计算10张券")
        List<Long> couponIds
) {

    /** 订单商品明细（内嵌：只被本 DTO 使用，聚合根的一部分）。 */
    public record OrderItem(

            @NotBlank(message = "商品SKU不能为空")
            String skuId,

            @NotBlank(message = "商品品类不能为空")
            String categoryId,

            @NotNull(message = "商品单价不能为空")
            @Positive(message = "商品单价必须为正数（单位：分）")
            Long price,

            @NotNull(message = "商品数量不能为空")
            @Min(value = 1, message = "商品数量至少为1")
            Integer quantity
    ) {
    }
}
