package com.newcoupon.application.dto;

import java.util.List;

/**
 * 兑换码批次受理结果（V6 阶段 1B）。
 *
 * <p><b>codes 是明文的唯一出口之一</b>：只在首次受理的响应里出现。
 * 幂等重放（idempotent=true）不回明文——codes 为空列表；明文离开系统后
 * 不应再可得，服务端也不再有（DB 只有哈希）。调用方必须当场持久化。
 *
 * <p>另一个出口是 CSV 一次性导出（export_state CAS 保证只成功一次）。
 */
public record CouponCodeBatchCreateResult(
        String batchNo,
        int total,
        List<String> codes,
        boolean idempotent
) {
}
