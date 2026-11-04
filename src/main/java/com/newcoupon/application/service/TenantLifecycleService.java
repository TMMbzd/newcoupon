package com.newcoupon.application.service;

import com.newcoupon.application.service.TenantLifecycleTxService.ExportSnapshot;
import com.newcoupon.application.service.TenantLifecycleTxService.TransitionResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.open.AppLifecycle;
import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import com.newcoupon.infrastructure.persistence.po.AdminUserPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 租户生命周期编排（V6 2B）：参数校验、操作者解析、删除的双人复核，
 * 然后把"迁移 + 事件留痕"整体交给 {@link TenantLifecycleTxService} 的一个事务。
 *
 * <p><b>本刀的裁剪声明（双人删除）</b>：设计原文是"删除申请表 + 双人审批流"，
 * 本刀简化为"同一次请求里带第二管理员的用户名 + 密码"——第一管理员发起 delete，
 * 请求体须带 secondApproverUsername（另一个 enabled 管理员）与 secondApproverPassword
 * （该管理员的登录密码）。密码核对用与登录同一套 {@link PasswordEncoder}，
 * 等价于"第二管理员在场并亮了证件"。完整审批表（请求人≠审批人、双方审计）登记给 2C
 * 的 approval_request。风险差异：审批流允许第二人"事后追认"，本刀要求"当场到场"——
 * 对删除这种不可逆动作，当场到场其实是更严的姿势。
 */
@Service
public class TenantLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleService.class);

    private final TenantLifecycleTxService txService;
    private final AdminRbacMapper rbacMapper;
    private final PasswordEncoder passwordEncoder;
    private final com.newcoupon.infrastructure.security.AdminSessionService sessionService;

    public TenantLifecycleService(TenantLifecycleTxService txService,
                                  AdminRbacMapper rbacMapper,
                                  PasswordEncoder passwordEncoder,
                               com.newcoupon.infrastructure.security.AdminSessionService sessionService) {
        this.txService = txService;
        this.rbacMapper = rbacMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
    }

    /**
     * 执行一次生命周期动作。
     *
     * @throws BizException PARAM_ERROR——动作名未知 / 应用不存在；
     *                      STATE_CONFLICT——非法迁移 / legal_hold 拦截 / 双人复核不通过
     */
    public TransitionResult transition(String appId, LifecycleRequest req) {
        if (req == null || req.action() == null || req.action().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "action 不能为空");
        }
        String action = req.action().trim();
        // 动作名先于查库校验：拼错的动作名（如 delet）不该走到"应用不存在/状态冲突"
        // 那些需要查库才能回答的分支里
        if (!KNOWN_ACTIONS.contains(action)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "未知动作 " + action + "（支持：" + KNOWN_ACTIONS + "）");
        }

        // 审查修正①：reason 长度校验前置——>255 会撞列宽抛 SQL 异常整笔回滚，
        // 运营拿到的是 500 系统错而不是参数错
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        if (reason != null && reason.length() > 255) {
            throw new BizException(ErrorCode.PARAM_ERROR, "reason 不能超过 255 字符（当前 " + reason.length() + "）");
        }

        String operator = currentOperator();
        boolean dualPersonVerified = false;
        if (AppLifecycle.ACTION_DELETE.equals(action)) {
            // 审查修正②：不可逆删除必须由**具名会话**发起——旧管理令牌是共享凭证
            // （operator 恒 "admin"），无法与第二人比名，双人会退化成"一人两顶帽子"
            if ("admin".equals(operator)) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "删除是不可逆操作，必须由具名管理员会话发起（/console/api/auth 登录），不接受旧管理令牌");
            }
            // 审查修正③：先做存在性/状态预检（事务内仍终审）——原顺序对不存在的
            // appId 也能反复试第二人密码；预检过了才值得花 BCrypt 成本做双人核验
            txService.assertActionable(appId, action);
            verifySecondApprover(appId, operator, req);
            dualPersonVerified = true;
        }
        return txService.transitionInTx(appId, action, operator, reason, dualPersonVerified);
    }

    /** 数据导出（一次性；状态约束与闸门都在事务内，见 TxService.exportInTx） */
    public ExportSnapshot export(String appId) {
        return txService.exportInTx(appId, currentOperator());
    }

    /**
     * 操作者标识：优先取会话里的管理员用户名（2A 的 AdminPrincipal）；
     * 旧管理令牌持有者不是人（是共享令牌），记 "admin"；系统内调用同理。
     * 留痕的价值在"能找到人"，找不到人时也要留"是人干的"这个事实。
     */
    private static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof com.newcoupon.infrastructure.security.AdminPrincipal p) {
            return p.getUsername();
        }
        return "admin";
    }

    /**
     * 删除的第二管理员校验：存在 + enabled + 密码匹配 + 与发起人不是同一人。
     * 全部不通过一律 4002（不区分"用户不存在/停用/密码错"——与登录失败族同一个
     * 防枚举立场；真实原因只进日志）。
     */
    private void verifySecondApprover(String appId, String operator, LifecycleRequest req) {
        String username = req.secondApproverUsername();
        String password = req.secondApproverPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "删除需要双人复核：请求须带 secondApproverUsername 与 secondApproverPassword");
        }
        // 审查修正⑥：凭据核验走 AdminSessionService.verifyApproverCredential——
        // 与登录同一锁定计数体系；原来直接 matches，是绕过登录锁定的密码爆破旁路
        AdminUserPO approver = rbacMapper.findByUsername(username.trim());
        if (approver == null || !sessionService.verifyApproverCredential(username, password)) {
            log.warn("TENANT_DELETE dual-person rejected: appId={} approver={}",
                    appId, username);
            throw new BizException(ErrorCode.STATE_CONFLICT, "第二管理员复核未通过");
        }
        // 同人复核 = 一人两顶帽子，不是双人。会话发起人（operator）与旧令牌（"admin"）
        // 无法比较时放行——旧令牌本身就不是个人身份，双人语义由第二人的密码独立保证
        if (!"admin".equals(operator) && operator.equals(approver.getUsername())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "第二管理员不能与发起人是同一人（" + operator + "）");
        }
    }

    /** 动作白名单：与 AppLifecycle 迁移表的动作集一致（迁移合法性仍由那张表终审） */
    private static final java.util.Set<String> KNOWN_ACTIONS = java.util.Set.of(
            AppLifecycle.ACTION_SUBMIT_REVIEW,
            AppLifecycle.ACTION_APPROVE_SANDBOX,
            AppLifecycle.ACTION_ACTIVATE,
            AppLifecycle.ACTION_SUSPEND,
            AppLifecycle.ACTION_RESUME,
            AppLifecycle.ACTION_CLOSE,
            AppLifecycle.ACTION_CONFIRM_CLOSE,
            AppLifecycle.ACTION_DELETE);

    /** 生命周期请求体（内部面）：action 必填；reason 建议填；delete 另需双人字段 */
    public record LifecycleRequest(String action, String reason,
                                   String secondApproverUsername, String secondApproverPassword) {
    }
}
