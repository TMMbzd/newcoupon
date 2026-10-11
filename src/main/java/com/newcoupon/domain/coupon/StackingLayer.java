package com.newcoupon.domain.coupon;

/**
 * 券的叠加层级（五维模型第三维）——这张券在分层计价的哪一层生效。
 *
 * <p>分层计价规则（对标有赞/抖音/淘宝官方规则）：
 * <ul>
 *   <li>跨层可叠加：店铺级券 + 平台级券可以同时使用（各减各的）</li>
 *   <li>同层互斥：同一层级内只能用一张（选优惠金额最大的）</li>
 * </ul>
 *
 * <p>骨架按八层位次建模（单品级→限时折扣→满减→商家券→平台券→积分→运费→收尾），
 * 本期实装两层（SHOP / PLATFORM），其余层留接口写明点亮条件。
 */
public enum StackingLayer {
    /** 店铺级（商家券）：第 4 层位次 */
    SHOP("店铺级"),
    /** 平台级（平台券/红包）：第 5 层位次 */
    PLATFORM("平台级");

    private final String desc;

    StackingLayer(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }

    public static StackingLayer of(int code) {
        return switch (code) {
            case 0 -> SHOP;
            case 1 -> PLATFORM;
            default -> throw new IllegalArgumentException("未知的叠加层级编码: " + code);
        };
    }

    public int getCode() {
        return ordinal();
    }
}
