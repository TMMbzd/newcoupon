package com.newcoupon.application.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 因子收集器（V6 4A）：规则评估过程中往里写"我看了什么、结果是什么"，
 * 决策结束时整体序列化进 risk_decision_log.factors_json。
 *
 * <p><b>为什么是个类而不是让规则直接拿 Map</b>：degraded 标记必须与因子写入走同一个
 * 入口（写因子与标记降级是同一件事的两面——"这个规则为什么没说话"必须能在快照里
 * 找到答案），散落在规则里手工维护一个 boolean 迟早会漏。
 *
 * <p><b>LinkedHashMap</b>：保持规则评估顺序——快照读起来就是决策过程的回放稿
 * （先名单后设备），排障时不用脑补顺序。
 */
public final class RiskFactors {

    private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    private boolean degraded;

    /** 记一个因子（键建议带规则前缀，如 device.count / list.user） */
    public void put(String key, Object value) {
        values.put(key, value);
    }

    /**
     * 标记本次决策因子不完整（仍出决策，fail-open）。
     * 已知的两类原因：透传维度缺失（无 X-Device-Id）、因子源不可用（设备计数 Redis down）。
     * 调用方应同时 put 一个说明性因子，让"为什么 degraded"在快照里可读。
     */
    public void markDegraded() {
        this.degraded = true;
    }

    /** 因子是否不完整（落库 risk_decision_log.degraded） */
    public boolean degraded() {
        return degraded;
    }

    /** 快照（决策日志序列化用；保持插入序） */
    public Map<String, Object> snapshot() {
        return values;
    }
}
