package com.newcoupon.controller;

import com.newcoupon.application.service.PlanApplyService;
import com.newcoupon.application.service.SwitchPlan;
import com.newcoupon.common.Result;
import com.newcoupon.infrastructure.persistence.po.OpenAppPlanApplyPO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 三级预案的管理面（S2 第 10 步）。
 *
 * <p><b>路径为什么挂在 {@code /api/apps/{appId}/plans} 而不是新开一个前缀</b>：
 * 管理面鉴权（{@code AdminTokenFilter}）保护的是**显式列举的路径前缀**，
 * 新前缀若不在清单里，就等于在网上开了一个<b>无鉴权的"一键关闭全站"按钮</b>——
 * 任何能访问管理端口的人都能把核心领券/核销全量打成 5001。
 * 挂在 {@code /api/apps} 下天然落入受保护范围，不需要改鉴权清单。
 *
 * <p>三个入口：<b>套用</b>、<b>恢复</b>、<b>查看当前与历史</b>。
 * 只读的查看不设防（与 {@code GET /api/switches} 一致）。
 */
@RestController
@RequestMapping("/api/apps/{appId}/plans")
public class SwitchPlanController {

    private final PlanApplyService planService;

    public SwitchPlanController(PlanApplyService planService) {
        this.planService = planService;
    }

    /**
     * 套用一份预案。{@code level} 取 {@code BLUE} / {@code YELLOW} / {@code RED}。
     *
     * <p>请求体可选：{@code {"reason":"..."}}——**演练脚本应当带 drill 标记**，
     * 否则事后复盘分不清哪些变更是演练产生的。
     */
    @PostMapping("/apply/{level}")
    public Result<PlanApplyService.ApplyResult> apply(@PathVariable String appId,
                                                      @PathVariable String level,
                                                      @RequestBody(required = false) PlanRequest body,
                                                      HttpServletRequest request) {
        SwitchPlan plan = SwitchPlan.fromLevel(level);
        return Result.success(planService.apply(appId, plan, operatorOf(request),
                body == null ? null : body.reason()));
    }

    /** 恢复：按当前生效预案的快照做 CAS 回滚，返回被跳过的冲突键 */
    @PostMapping("/revert")
    public Result<PlanApplyService.RevertResult> revert(@PathVariable String appId,
                                                        @RequestBody(required = false) PlanRequest body,
                                                        HttpServletRequest request) {
        return Result.success(planService.revert(appId, operatorOf(request),
                body == null ? null : body.reason()));
    }

    /** 当前生效的预案 + 套用历史 + 可选的预案清单（含每份会改哪些键） */
    @GetMapping
    public Result<PlanView> list(@PathVariable String appId) {
        OpenAppPlanApplyPO active = planService.findActive(appId);
        return Result.success(new PlanView(
                active == null ? null : new ActiveView(active.getPlanLevel(), active.getOperator(),
                        active.getReason(), active.getAppliedAt()),
                planService.history(appId),
                Arrays.stream(SwitchPlan.values())
                        .map(p -> new AvailableView(p.name(), p.label(), p.intent(), p.entries().stream()
                                .map(e -> new EntryView(e.configKey(), e.targetValue())).toList()))
                        .toList()));
    }

    /** 管理面没有用户身份，操作者记"令牌 + 来源 IP"（与配置写入一致） */
    private static String operatorOf(HttpServletRequest request) {
        return "admin@" + com.newcoupon.infrastructure.web.RiskControlFilter.clientIp(request);
    }

    /** @param reason 备注；演练传 drill，便于与真实操作区分 */
    public record PlanRequest(String reason) {
    }

    public record EntryView(String configKey, String targetValue) {
    }

    public record AvailableView(String level, String label, String intent, List<EntryView> entries) {
    }

    public record ActiveView(String level, String operator, String reason,
                             java.time.LocalDateTime appliedAt) {
    }

    public record PlanView(ActiveView active, List<PlanApplyService.ApplyView> history,
                           List<AvailableView> available) {
    }
}
