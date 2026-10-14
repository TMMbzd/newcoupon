package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TemplateResponse;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 五维模型·运营入口贯通验收（2026-10-15 补）。
 *
 * <p>背景：五维模型（第 2 环）此前只在领域层与引擎里成立——建券 DTO 没有五维字段，
 * 运营经 HTTP 建出来的券永远是默认值（全场/店铺级/平台出资/无组）。本组测试钉死：
 * <ol>
 *   <li>运营入口能配五维、配了能读回（写→读往返一致）；</li>
 *   <li>不配 = 存量默认，老调用方零感知；</li>
 *   <li>非法组合（共担缺比例、品类缺范围值）被领域防线拒绝——入口打开不等于防线放开。</li>
 * </ol>
 */
class TemplateFiveDimensionsIntegrationTest extends ShardedIntegrationTestSupport {

    @Autowired
    private TemplateAppService templateAppService;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static TemplateCreateRequest fullRequest(String name, Integer scope, String scopeIds,
                                                     Integer layer, String group,
                                                     Integer owner, Integer ratio) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        return new TemplateCreateRequest(name, 9001L, 1, 2000L, null, 10000L,
                100L, 1, 1, start, end, null, 0L, 0,
                scope, scopeIds, layer, group, owner, ratio);
    }

    @Test
    @DisplayName("五维往返：品类券+平台层+互斥组+共担70/30 → 建券成功且详情读回一致")
    void fiveDimensionsRoundTrip() {
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                fullRequest("五维往返-品类平台共担", 1, "[\"CAT_FOOD\"]", 1, "NEW_USER", 3, 70));

        TemplateResponse t = templateAppService.get(created.id());
        assertThat(t.applicableScope()).isEqualTo("品类");
        assertThat(t.scopeIds()).isEqualTo("[\"CAT_FOOD\"]");
        assertThat(t.stackingLayer()).isEqualTo("平台级");
        assertThat(t.exclusionGroup()).isEqualTo("NEW_USER");
        assertThat(t.budgetOwner()).isEqualTo("共担");
        assertThat(t.budgetRatio()).isEqualTo(70);
    }

    @Test
    @DisplayName("缺省兼容：老 14 参构造器建券 → 五维全部落默认（全场/店铺级/平台/无组）")
    void defaultsPreservedForLegacyCallers() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest("缺省兼容-老调用方", 9002L, 1, 2000L, null, 10000L,
                        100L, 1, 1, start, end, null, 0L, 0));

        TemplateResponse t = templateAppService.get(created.id());
        assertThat(t.applicableScope()).isEqualTo("全场");
        assertThat(t.stackingLayer()).isEqualTo("店铺级");
        assertThat(t.budgetOwner()).isEqualTo("平台");
        assertThat(t.exclusionGroup()).isNull();
        assertThat(t.scopeIds()).isNull();
        assertThat(t.budgetRatio()).isNull();
    }

    @Test
    @DisplayName("非法组合拒绝：共担缺比例 / 品类缺范围值 → 领域防线拦截（入口打开≠防线放开）")
    void invalidCombinationRejected() {
        assertThatThrownBy(() -> templateAppService.create(
                fullRequest("非法-共担缺比例", 0, null, 0, null, 3, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("共担出资必须配平台百分比");

        assertThatThrownBy(() -> templateAppService.create(
                fullRequest("非法-品类缺范围值", 1, null, 0, null, 1, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须提供范围值");
    }
}
