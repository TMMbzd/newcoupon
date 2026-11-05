package com.newcoupon.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.newcoupon.application.dto.RiskDtos.DecisionQueryRequest;
import com.newcoupon.application.dto.RiskDtos.DecisionQueryResult;
import com.newcoupon.application.dto.RiskDtos.DecisionView;
import com.newcoupon.application.dto.RiskDtos.ReplayResult;
import com.newcoupon.application.dto.RiskDtos.SkippedRuleView;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.domain.risk.RiskScene;
import com.newcoupon.infrastructure.persistence.mapper.RiskDecisionLogMapper;
import com.newcoupon.infrastructure.persistence.po.RiskDecisionLogPO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 风控决策查询与回放服务（V6 4A）。
 *
 * <p><b>回放的用途**（类级写清，它是这个端点存在的理由）：**误杀复盘**与**规则调参验证**——
 * <ul>
 *   <li>用户申诉"我没刷券为什么被拦"：取那行日志，按**当前**规则集只读重算——
 *       结果还是 REJECT 说明现行规则仍会拦他（要么证据确凿、要么规则误伤，
 *       配合 factors_json 里当时的因子输入定位）；结果变 PASS 说明拦他的规则
 *       已经改过了（比如名单已停用）——给申诉一个可执行的答案；</li>
 *   <li>运营想调阈值/改名单：拿历史 REJECT 行小批量回放，看"如果按新配置，
 *       昨天那些拦截会有多少变成放行/复核"——调参前先量化影响，不拍脑袋。</li>
 * </ul>
 *
 * <p><b>只读语义**：不改原日志行、不写新日志行、不产生任何规则副作用
 * （有副作用的规则被跳过并列出，见 RiskDecisionService.replayEvaluate）。
 * 回放动作≠当时动作不代表当时错了——它衡量的是"规则集变了多少"，不是"谁对谁错"。
 */
@Service
public class RiskDecisionQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    /** 查询时间窗上限（天）：索引 (scene,created_at) 的扫描范围保护——31 天已是运营复盘的单次视野 */
    private static final int MAX_WINDOW_DAYS = 31;
    /** 不传时间时的默认窗（天）：决策日志无归档任务，无界扫描会越查越慢（审查 C9） */
    private static final int DEFAULT_WINDOW_DAYS = 1;

    private final RiskDecisionLogMapper logMapper;
    private final RiskDecisionService decisionService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RiskDecisionQueryService(RiskDecisionLogMapper logMapper,
                                    RiskDecisionService decisionService,
                                    com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.logMapper = logMapper;
        this.decisionService = decisionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 游标分页查询（主键倒序）。过滤键分两组：
     * 态度过滤（scene/action/appId，看盘用）与**定位键**（userId/traceId/requestId——
     * 误杀工单的真实入口，审查 C4）。时间窗：不传=**默认最近 1 天**（审查 C9：
     * 决策日志无归档任务，无界扫描会越查越慢；照审计查询口径给默认值而不是全量），
     * 传了则成对 + 顺序 + ≤31 天。
     */
    public DecisionQueryResult query(DecisionQueryRequest req) {
        int limit = req.limit() == null ? DEFAULT_LIMIT : Math.min(req.limit(), MAX_LIMIT);
        QueryWrapper<RiskDecisionLogPO> wrapper = new QueryWrapper<>();
        if (req.cursor() != null) {
            wrapper.lt("id", req.cursor());
        }
        if (req.scene() != null && !req.scene().isBlank()) {
            wrapper.eq("scene", parseEnum(RiskScene.class, req.scene().trim(), "scene"));
        }
        if (req.action() != null && !req.action().isBlank()) {
            wrapper.eq("action", parseEnum(RiskAction.class, req.action().trim(), "action"));
        }
        if (req.appId() != null && !req.appId().isBlank()) {
            wrapper.eq("app_id", req.appId().trim());
        }
        if (req.userId() != null) {
            wrapper.eq("user_id", req.userId());
        }
        if (req.traceId() != null && !req.traceId().isBlank()) {
            wrapper.eq("trace_id", req.traceId().trim());
        }
        if (req.requestId() != null && !req.requestId().isBlank()) {
            wrapper.eq("request_id", req.requestId().trim());
        }
        LocalDateTime start = req.startTime();
        LocalDateTime end = req.endTime();
        if (start == null && end == null) {
            // 默认窗=最近 1 天：无限流查询的护栏，不是"查全量的约定"。要查更早，显式给窗口
            wrapper.ge("created_at", LocalDateTime.now().minusDays(DEFAULT_WINDOW_DAYS));
        } else {
            // 成对 + 顺序 + 跨度三查：只配一头没法构成窗口；倒挂/超长都是无意义的扫描条件
            if (start == null || end == null) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "startTime 与 endTime 必须成对出现（要么都不传=默认最近 1 天）");
            }
            if (!end.isAfter(start)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "endTime 必须晚于 startTime");
            }
            if (Duration.between(start, end).toDays() > MAX_WINDOW_DAYS) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "时间窗最长 " + MAX_WINDOW_DAYS + " 天（缩小范围分次查）");
            }
            wrapper.ge("created_at", start).le("created_at", end);
        }
        wrapper.orderByDesc("id").last("LIMIT " + (limit + 1));
        List<RiskDecisionLogPO> rows = logMapper.selectList(wrapper);
        boolean hasNext = rows.size() > limit;
        List<RiskDecisionLogPO> page = hasNext ? rows.subList(0, limit) : rows;
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new DecisionQueryResult(page.stream().map(this::toView).toList(), nextCursor);
    }

    /**
     * 只读回放：按日志行重建上下文，用**当前**规则集重算（副作用规则走
     * "日志因子×当前配置"的纯重算，如设备日限的 count×当前阈值），对照原动作与原命中清单。
     * 不改原行（V6 原文语义）、不写新行。
     */
    public ReplayResult replay(Long id) {
        RiskDecisionLogPO po = logMapper.selectById(id);
        if (po == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "决策日志不存在: id=" + id);
        }
        RiskScene scene = parseEnum(RiskScene.class, po.getScene(), "scene");
        RiskContext ctx = new RiskContext(scene, po.getAppId(), po.getUserId(),
                po.getDeviceId(), po.getIp(), po.getRequestId());
        RiskDecisionService.ReplayEvaluation replay = decisionService.replayEvaluate(
                ctx, parseFactors(po.getFactorsJson()));

        RiskAction originalAction = parseEnum(RiskAction.class, po.getAction(), "action");
        RiskAction replayAction = replay.action();
        List<String> originalMatched = parseMatched(po.getMatchedRules());
        List<String> replayMatched = replay.matchedRules();
        Set<String> skippedIds = new LinkedHashSet<>();
        for (RiskDecisionService.SkippedRule s : replay.skippedRules()) {
            skippedIds.add(s.ruleId());
        }

        // diff 粒度=命中与否 + 最终动作（规则内部的动作值不逐规则落库，只有命中清单——
        // 想看逐规则的当时判定，读该行 factors_json 快照）。**skipped 规则必须标
        // "未评估"而不是"未命中"**（审查 B 簇）：把"没算"渲染成"命中→未命中"会让
        // 运维把回放 PASS 误读成"拦他的条件已变"——那是假答案。
        List<String> ruleDiff = new ArrayList<>();
        Set<String> union = new LinkedHashSet<>(originalMatched);
        union.addAll(replayMatched);
        union.addAll(skippedIds);
        for (String ruleId : union) {
            if (skippedIds.contains(ruleId)) {
                ruleDiff.add(ruleId + ": "
                        + (originalMatched.contains(ruleId) ? "命中" : "未命中")
                        + " → 未评估（有副作用且日志因子不足以重算）");
                continue;
            }
            boolean was = originalMatched.contains(ruleId);
            boolean now = replayMatched.contains(ruleId);
            if (was && !now) {
                ruleDiff.add(ruleId + ": 命中 → 未命中");
            } else if (!was && now) {
                ruleDiff.add(ruleId + ": 未命中 → 命中");
            }
        }
        if (originalAction != replayAction) {
            ruleDiff.add("action: " + originalAction + " → " + replayAction);
        }

        return new ReplayResult(id, originalAction.name(), replayAction.name(),
                List.copyOf(ruleDiff), replay.replayedRuleIds(),
                replay.skippedRules().stream()
                        .map(s -> new SkippedRuleView(s.ruleId(), s.reason())).toList());
    }

    /** factors_json → Map（阈值重算类回放的输入）；无快照/解析失败给空 Map（规则自己声明算不了） */
    private Map<String, Object> parseFactors(String factorsJson) {
        if (factorsJson == null || factorsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(factorsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return Map.of();   // 老行/损坏快照：不因快照不可读拒绝回放——名单类重算不依赖快照
        }
    }

    private DecisionView toView(RiskDecisionLogPO po) {
        return new DecisionView(po.getId(), po.getScene(), po.getAppId(), po.getUserId(),
                po.getDeviceId(), po.getIp(), po.getFactorsJson(), parseMatched(po.getMatchedRules()),
                po.getAction(), po.getDegraded() != null && po.getDegraded() == 1,
                po.getTraceId(), po.getRequestId(), po.getCreatedAt());
    }

    private static List<String> parseMatched(String matchedRules) {
        if (matchedRules == null || matchedRules.isBlank()) {
            return List.of();
        }
        return Arrays.stream(matchedRules.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 查询/回放的枚举校验：拼错的过滤值静默返回空列表是查询陷阱（"没有 REJECT"≠"拼错了"） */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    field + " 只能是 " + Arrays.toString(type.getEnumConstants()) + "，收到: " + raw);
        }
    }
}
