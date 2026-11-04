package com.newcoupon.domain.settle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结算质量位掩码（V47，V6 3min 结算最小版）——L76 清偿的代码侧锚点。
 *
 * <p><b>解决什么</b>：V22 之前分账/退款明细的主体列只有哨兵 {@code __default__}，
 * "平台券合法空主体"和"历史商家主体丢失"在数据上不可区分（STATUS L76）。V47 起明细行带
 * {@code quality_flags} 位掩码 + {@code settle_eligible}：前者回答"这行数据哪里粗"，
 * 后者回答"这行进不进真实结算口径"（两张日账单聚合都只取 {@code settle_eligible=1}，
 * 主体合计=模板账单的等式因此不变）。
 *
 * <p><b>位语义</b>（与 V47 迁移列注释同源，改一处必须同步另一处）：
 * <ul>
 *   <li>bit0 {@link #LEGACY_DEFAULT}=1：商家/共担出资的钱落在 {@code __default__} 主体
 *       （灰度关期间的写入 / V22 前老行 / 模板主体丢失）——主体丢失，<b>不进真实结算</b>；</li>
 *   <li>bit1 {@link #NO_ITEMS}=2：老式订单级分账行（{@code order_line_no IS NULL}，无行级
 *       items）——与 {@code coupon_refund_order.funding_known=0} 同族的"粗粒度"标记。
 *       出资拆分是真实的，<b>只降观测粒度、保持可结算</b>（与 LEGACY_DEFAULT 的关键差别：
 *       钱的归属没错，只是没有行级明细）；</li>
 *   <li>bit2 {@link #FUNDING_UNKNOWN}=4：出资方不明的退款明细（关联退款单
 *       {@code funding_known=0}）——冲回金额的出资拆分不可信，<b>不进真实结算</b>；</li>
 *   <li>bit3 {@link #ORPHAN_TEMPLATE}=8：模板主数据缺失（写入路径防御位；回填未涉及）；</li>
 *   <li>bit4 {@link #NEGATIVE_ADJUSTMENT}=16：负账单调整（保留位——3B 调整单的领域，本刀不做）。</li>
 * </ul>
 *
 * <p><b>排除规则只有两条</b>（写入路径 {@code TradeTxService} 与 V47 回填同口径）：
 * LEGACY_DEFAULT、FUNDING_UNKNOWN 置 {@code settle_eligible=0}；NO_ITEMS 只打标不排除。
 * 想知道某天被排除多少钱：看主体账单的 {@code ineligible_share_count}/{@code ineligible_refund_count}
 * （行数口径），再按位掩码查明细。
 */
public final class SettleQualityFlags {

    /** 商家/共担出资的钱落在 __default__ 主体（主体丢失）——不进真实结算 */
    public static final int LEGACY_DEFAULT = 1;
    /** 老式订单级分账行（无行级 items）——只降观测粒度，保持可结算 */
    public static final int NO_ITEMS = 2;
    /** 出资方不明的退款明细（关联退款单 funding_known=0）——不进真实结算 */
    public static final int FUNDING_UNKNOWN = 4;
    /** 模板主数据缺失（保留位：写入路径防御性使用） */
    public static final int ORPHAN_TEMPLATE = 8;
    /** 负账单调整（保留位：3B 调整单领域，本刀不做） */
    public static final int NEGATIVE_ADJUSTMENT = 16;

    /** 名称 → 位的显式映射（describe/判读的唯一事实源；顺序=位序，LinkedHashMap 保序展示） */
    private static final Map<String, Integer> NAMED_BITS = buildNamedBits();

    private static Map<String, Integer> buildNamedBits() {
        Map<String, Integer> bits = new LinkedHashMap<>();
        bits.put("LEGACY_DEFAULT", LEGACY_DEFAULT);
        bits.put("NO_ITEMS", NO_ITEMS);
        bits.put("FUNDING_UNKNOWN", FUNDING_UNKNOWN);
        bits.put("ORPHAN_TEMPLATE", ORPHAN_TEMPLATE);
        bits.put("NEGATIVE_ADJUSTMENT", NEGATIVE_ADJUSTMENT);
        return java.util.Collections.unmodifiableMap(bits);
    }

    private SettleQualityFlags() {
    }

    /** 名称 → 位值；未知名称抛 IllegalArgumentException（配置/接口输入不允许发明新位） */
    public static int bitOf(String name) {
        Integer bit = NAMED_BITS.get(name);
        if (bit == null) {
            throw new IllegalArgumentException("未知的质量标记位: " + name);
        }
        return bit;
    }

    /** flags 是否含指定位（多位掩码入参时=是否含任一） */
    public static boolean has(int flags, int bits) {
        return (flags & bits) != 0;
    }

    /**
     * 人类可读描述：位名竖线拼接（无标记返回 "CLEAN"）。
     * 日志/runbook/排查用；未定义的裸位以 HEX 显示，不静默吞掉。
     */
    public static String describe(int flags) {
        if (flags == 0) {
            return "CLEAN";
        }
        StringBuilder sb = new StringBuilder();
        int known = 0;
        for (Map.Entry<String, Integer> e : NAMED_BITS.entrySet()) {
            if ((flags & e.getValue()) != 0) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(e.getKey());
                known |= e.getValue();
            }
        }
        int unknown = flags & ~known;
        if (unknown != 0) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append("UNKNOWN_0x").append(Integer.toHexString(unknown));
        }
        return sb.toString();
    }

    /** 全部位的名称（测试/runbook 生成用） */
    public static Map<String, Integer> namedBits() {
        return NAMED_BITS;
    }
}
