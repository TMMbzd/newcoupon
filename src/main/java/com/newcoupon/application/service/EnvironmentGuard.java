package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.open.OpenApp;
import com.newcoupon.domain.repository.OpenAppRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 环境资金边界守卫（V6 2B）：沙箱租户"券能发、钱不动"。
 *
 * <p>沙箱的用途是让接入方在不动真钱的前提下联调全链路——所以<b>建券/发放/核销照常</b>
 * （数据是假的，但链路是真的）；而一旦动作涉及真钱语义（出账单、真实退款、
 * 建真钱预算账本），就在入口处拒绝（4002）。挂点三处：
 * <ul>
 *   <li>{@code DailyBillService}——聚合 SQL 直接过滤沙箱模板（批量任务没有"拒绝给谁看"，
 *       跳过 + 日志才是正确形态）；</li>
 *   <li>{@code TradeTxService.refundConfirm} 入口——真实退款，assertProd 拒绝；</li>
 *   <li>{@code BudgetTxService.ensureAccount}——预算账户懒建（沙箱不发真钱账本），assertProd 拒绝。</li>
 * </ul>
 *
 * <p><b>为什么查库而不查缓存</b>：这三处都是低频路径（退款/懒建/管理动作），
 * 且环境归属是"钱的归属"——宁可多一次直查也不用可能滞后 60 秒的缓存。
 */
@Component
public class EnvironmentGuard {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentGuard.class);

    private final OpenAppRepository repository;

    public EnvironmentGuard(OpenAppRepository repository) {
        this.repository = repository;
    }

    /**
     * 断言应用是生产环境（沙箱 → 4002"沙箱环境不允许资金操作"）。
     *
     * <p><b>应用不存在时放行</b>：本守卫只回答"是不是沙箱"这一个问题，
     * 存在性校验是各自主流程的事（那边有自己的错误语义，如活动 2004/退款 4003）。
     * 在这里顺手抛"应用不存在"会把调用方的排障线索带偏。
     *
     * @param appId  资金动作涉及的归属方
     * @param action 动作名（错误文案与日志用，如 "refund" / "budget-account"）
     */
    public void assertProd(String appId, String action) {
        if (appId == null || appId.isBlank()) {
            return;   // 无归属（理论不可达）：交给主流程的存在性校验
        }
        repository.findApp(appId).ifPresent(app -> assertProd(app, action));
    }

    /** {@link #assertProd(String, String)} 的已加载重载：调用方已拿着 OpenApp 行时省一次查询 */
    public void assertProd(OpenApp app, String action) {
        if (app == null || !app.sandbox()) {
            return;
        }
        log.warn("ENVIRONMENT_GUARD 拒绝：沙箱租户触碰资金边界 appId={} action={}",
                app.appId(), action);
        throw new BizException(ErrorCode.STATE_CONFLICT,
                "沙箱环境不允许资金操作（action=" + action + ", appId=" + app.appId() + "）");
    }
}
