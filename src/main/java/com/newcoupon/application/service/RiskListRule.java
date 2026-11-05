package com.newcoupon.application.service;

import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.domain.risk.RiskListType;
import com.newcoupon.domain.risk.RiskScene;
import com.newcoupon.domain.risk.RiskValueType;
import com.newcoupon.infrastructure.persistence.mapper.RiskListEntryMapper;
import com.newcoupon.infrastructure.persistence.po.RiskListEntryPO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 名单规则（ruleId=R_LIST_BLACK，V6 4A）——黑/白/灰一体的三条名单判定。
 *
 * <p><b>ruleId 叫 R_LIST_BLACK 但白名单也归它管</b>：裁剪线的命名。黑/白/灰本质是
 * 同一张表的三个类型、同一次查询、同一个判定序列（白→黑→灰），拆成三条规则
 * 会把"白名单优先"这条语义切成三处拼装——一条规则读着更直。
 *
 * <p><b>判定顺序（语义，不是配置）</b>：
 * <ol>
 *   <li>用户/设备/IP 三维度各查一次（等值 + 平台级/租户级两层 + ACTIVE 未过期）；</li>
 *   <li>任一维度命中 WHITE → PASS 且**短路**（后续规则不再评估）——运营放行是
 *       明确的意思表示，盖过黑名单与频控；</li>
 *   <li>否则任一维度命中 BLACK → REJECT；</li>
 *   <li>否则任一维度命中 GRAY → REVIEW（放行但留痕，等人工复核）。</li>
 * </ol>
 *
 * <p><b>查询成本</b>：决策开关开时每决策最多 3 次等值索引查询（value_type,value,status
 * 前缀索引）。无缓存——名单量级小、等值查询走索引，加 Caffeine+Redis 失效
 * （V6 原文方案）的复杂度不值得，登记 runbook 作升级路径。
 *
 * <p><b>可回放</b>（replayable=true）：判定只读当前名单状态——"改了名单之后旧决策
 * 是否会变"正是回放要回答的问题。
 */
@Component
@Order(100)   // 评估序第一：白名单短路必须发生在频控计数之前（被放行的请求不该被计数消耗）
public class RiskListRule implements RiskRule {

    private final RiskListEntryMapper listMapper;

    public RiskListRule(RiskListEntryMapper listMapper) {
        this.listMapper = listMapper;
    }

    @Override
    public String ruleId() {
        return "R_LIST_BLACK";
    }

    @Override
    public boolean supports(RiskScene scene) {
        return true;   // 场景任意：名单对领券/锁券/核销/退款都成立
    }

    @Override
    public boolean replayable() {
        return true;
    }

    @Override
    public Optional<RiskAction> evaluate(RiskContext ctx, RiskFactors factors) {
        // 租户层为空串=平台级（PO 存储口径，见 RiskListEntryPO）：只匹配平台级条目
        String scope = ctx.appId() == null ? "" : ctx.appId();
        factors.put("list.scope", scope.isEmpty() ? "platform-only" : scope);
        LocalDateTime now = LocalDateTime.now();

        // ① 三维度各查一次；记录每个维度命中了哪些名单类型（快照=回放稿）
        List<RiskListType> allHits = new ArrayList<>();
        if (ctx.userId() != null) {
            allHits.addAll(collect(factors, "list.user",
                    RiskValueType.USER_ID, String.valueOf(ctx.userId()), scope, now));
        }
        if (ctx.deviceId() != null) {
            allHits.addAll(collect(factors, "list.device",
                    RiskValueType.DEVICE_ID, ctx.deviceId(), scope, now));
        }
        if (ctx.ip() != null) {
            allHits.addAll(collect(factors, "list.ip",
                    RiskValueType.IP, ctx.ip(), scope, now));
        }

        // ② 白→黑→灰（跨维度整体判定：任一维度的 WHITE 盖过所有 BLACK）
        if (allHits.contains(RiskListType.WHITE)) {
            return Optional.of(RiskAction.PASS);   // 短路：由决策服务终止后续规则
        }
        if (allHits.contains(RiskListType.BLACK)) {
            return Optional.of(RiskAction.REJECT);
        }
        if (allHits.contains(RiskListType.GRAY)) {
            return Optional.of(RiskAction.REVIEW);
        }
        return Optional.empty();
    }

    /** 查一个维度的命中并把名单类型写进因子（"list.user" → "BLACK" / "none"） */
    private List<RiskListType> collect(RiskFactors factors, String factorKey,
                                       RiskValueType valueType, String value,
                                       String scope, LocalDateTime now) {
        List<RiskListEntryPO> rows = listMapper.selectActiveHits(
                valueType.name(), value, scope, now);
        if (rows.isEmpty()) {
            factors.put(factorKey, "none");
            return List.of();
        }
        StringJoiner joiner = new StringJoiner(",");
        for (RiskListEntryPO row : rows) {
            joiner.add(row.getListType());
        }
        factors.put(factorKey, joiner.toString());
        List<RiskListType> types = new ArrayList<>(rows.size());
        for (RiskListEntryPO row : rows) {
            try {
                types.add(RiskListType.valueOf(row.getListType()));
            } catch (IllegalArgumentException e) {
                // 库里出现未知类型只可能来自手工改库：跳过该行（不参与判定），因子里已可见
            }
        }
        return types;
    }
}
