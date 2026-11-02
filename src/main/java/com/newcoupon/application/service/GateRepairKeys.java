package com.newcoupon.application.service;

import com.newcoupon.infrastructure.redis.RedisIssueGate;

/**
 * gate 修复的键与作用域规则。
 *
 * <p>gate 类错误和模板日限是模板级；每人总限/日限和计数损坏是用户级。
 * 用户级许可避免一个用户的坏计数阻塞其他用户。
 */
public final class GateRepairKeys {

    private GateRepairKeys() {
    }

    public static boolean templateWide(int code) {
        return code == RedisIssueGate.GATE_MISSING
                || code == RedisIssueGate.STOCK_GATE_EMPTY
                || code == RedisIssueGate.GATE_CORRUPT
                || code == RedisIssueGate.TEMPLATE_DAILY_LIMIT;
    }

    public static String permitKey(long templateId, long userId, int code) {
        String prefix = "c:issue:gate:repair:{" + templateId + "}";
        return templateWide(code) ? prefix : prefix + ":" + userId;
    }
}
