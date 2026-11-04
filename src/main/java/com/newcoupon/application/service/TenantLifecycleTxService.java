package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.open.AppLifecycle;
import com.newcoupon.domain.open.OpenApp;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppLifecycleEventMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppLifecycleEventPO;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户生命周期的事务内部分（V6 2B）。拆分理由与 {@link GrantTaskTxService} 相同：
 * 编排（{@link TenantLifecycleService}，含双人审批的只读校验）不持事务，
 * 每次迁移/导出一个事务——<b>状态列更新与事件留痕必须同事务</b>，
 * 分开会出现"状态变了但没留痕"或反过来的审计断层。
 *
 * <p><b>并发锚点</b>：事务首步 {@code SELECT ... FOR UPDATE} 锁应用行——
 * 两个运营并发对同一应用做迁移时后拿锁者必见先提交的状态（迁移表据此拒绝第二笔）；
 * 数据导出的"一次性"闸门也靠这把锁串行化。
 *
 * <p><b>注销的密钥处理</b>：confirm_close 迁移内复用 {@link OpenAppService#rotate}
 * 做零窗口轮换——旧密钥当场过期（轮换期=0），新密钥的明文<b>直接丢弃</b>
 * （不发任何人）。此后即便有人把 lifecycle 手工改回去，旧密钥也已死。
 */
@Service
public class TenantLifecycleTxService {

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleTxService.class);

    /**
     * 注销后的默认数据保留期（天）：confirm_close 写入 retention_until = closed_at + 180。
     * 只写不做——到期清理没有自动化（裁剪登记），是 runbook 手动事项。
     */
    static final int DEFAULT_RETENTION_DAYS = 180;

    private final OpenAppRepository repository;
    private final OpenAppLifecycleEventMapper eventMapper;
    private final OpenAppService openAppService;
    private final CachedOpenAppReader authReader;
    private final TemplateMapper templateMapper;
    private final CouponStockShardMapper stockShardMapper;

    public TenantLifecycleTxService(OpenAppRepository repository,
                                    OpenAppLifecycleEventMapper eventMapper,
                                    OpenAppService openAppService,
                                    CachedOpenAppReader authReader,
                                    TemplateMapper templateMapper,
                                    CouponStockShardMapper stockShardMapper) {
        this.repository = repository;
        this.eventMapper = eventMapper;
        this.openAppService = openAppService;
        this.authReader = authReader;
        this.templateMapper = templateMapper;
        this.stockShardMapper = stockShardMapper;
    }

    /**
     * 状态迁移一个事务：锁行 → 查迁移表 → 写状态列 → 写事件。
     *
     * @throws BizException STATE_CONFLICT——非法迁移（白名单外）或 delete 的 legal_hold 拦截；
     *                      PARAM_ERROR——应用不存在
     */
    @Transactional
    public TransitionResult transitionInTx(String appId, String action, String operator, String reason,
                                           boolean dualPersonVerified) {
        // 行锁先行：串行化并发迁移 + 让导出闸门与迁移互斥（见类注释）
        OpenApp app = lockApp(appId);

        AppLifecycle from = requireLifecycle(app);
        AppLifecycle to = AppLifecycle.targetOf(from, action).orElseThrow(() -> new BizException(
                ErrorCode.STATE_CONFLICT,
                "非法生命周期迁移：" + appId + " 当前 " + from + " 不支持动作 " + action
                        + "（允许：" + AppLifecycle.allowedActions(from) + "）"));

        if (AppLifecycle.ACTION_DELETE.equals(action)) {
            // legal_hold 在锁内复核：请求校验到执行的窗口里运营可能刚挂上法务保留
            if (app.legalHold() != 0) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "应用 " + appId + " 处于法务保留（legal_hold=1），禁止删除");
            }
            // 双人在编排层已校验（用户名+密码+enabled）；这里再断言一次标记，
            // 防止将来有人绕过编排层直接调本方法
            if (!dualPersonVerified) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "删除需要双人复核（secondApproverUsername + secondApproverPassword）");
            }
        }

        LocalDateTime closedAt = null;
        LocalDate retentionUntil = null;
        if (to == AppLifecycle.CLOSED) {
            closedAt = LocalDateTime.now();
            retentionUntil = closedAt.toLocalDate().plusDays(DEFAULT_RETENTION_DAYS);
            // 撤密钥（审查修正④）：必须作废**全部**未失效密钥。原来只 rotate(ZERO)——
            // 它只把 CURRENT 键降级，注销前 7 天轮换期里的旧键（ROTATING 状态）能活到
            // 自己的 expire_time，"CLOSED 后旧密钥全死"不成立（手工改回 lifecycle 的
            // 场景正是本防御目标）。rotate 保留（CURRENT 键零窗口）+ ROTATING 全部置
            // INVALID 立即过期
            openAppService.rotate(appId, Duration.ZERO);
            repository.expireAllRotatingSecrets(appId);
        }
        if (to == AppLifecycle.ACTIVE && app.sandbox()) {
            // 审查修正⑤：activate 必须翻转 environment——否则沙箱晋升租户生命周期到
            // ACTIVE，账单/退款/预算三处资金边界仍按 SANDBOX 永远拒绝/漏收。
            // 口径登记（runbook）：环境对账单是"当下可变"过滤，沙箱期核销行在翻转后
            // 重跑当日账单会开始计入；要绝对口径干净可跨日晋升
            repository.updateEnvironment(appId, "PROD");
        }

        repository.updateLifecycle(appId, to, closedAt, retentionUntil);
        insertEvent(appId, from, to, action, operator, reason);
        // 生命周期变化必须立刻反映到开放面验签（缓存最长 60 秒 TTL，停用等不了）
        authReader.invalidateAfterCommit(appId);

        log.info("TENANT_LIFECYCLE appId={} {} -> {} action={} operator={}",
                appId, from, to, action, operator);
        return new TransitionResult(appId, from, to, action, operator);
    }

    /**
     * 数据导出一个事务：锁行 → 校验状态（CLOSED/DELETED 后不可导）→ 校验一次性
     * （事件表里已有 data_export 即拒）→ 组装快照 → 写 data_export 事件。
     *
     * <p><b>一次性为什么用事件行而不是 app 加 export_state 列</b>：事件表本来就要建
     * （迁移留痕），复用它免加列；"行存在"就是"导过"的判据。闸门原子性靠应用行锁：
     * 两个并发首次导出，后拿锁的事务重读事件表时必然看到赢家已提交的行。
     *
     * <p><b>快照内容的裁剪</b>（升级路径写在 runbook §6）：一次性 JSON =
     * app 行 + 模板 id 清单 + 每模板累计发放数。不含券明细全量导出
     * （NDJSON + manifest 管道已裁剪）——需要明细时按模板 id 走 DBA 离线导出。
     * 每模板发放数取 coupon_stock_shard.Σused（C1 对账等式②锚定过 Σused=券行数）；
     * C1 之前的存量模板（stock_model=0）没有分片行，计数缺 0——导出方按"模板清单为准"理解。
     */
    @Transactional
    public ExportSnapshot exportInTx(String appId, String operator) {
        OpenApp app = lockApp(appId);
        AppLifecycle status = requireLifecycle(app);

        if (status == AppLifecycle.CLOSED || status == AppLifecycle.DELETED) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "应用 " + appId + " 已注销（" + status + "），数据导出须在注销完成前进行");
        }
        if (eventMapper.existsByAppAndAction(appId, AppLifecycle.ACTION_DATA_EXPORT)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "应用 " + appId + " 的数据导出是一次性操作，已导出过（留痕见 lifecycle 事件）");
        }

        // 同事务内读快照：与闸门事件同一个锁定读视图，不会出现"导出的数和扣掉闸门的状态对不上"
        List<Long> templateIds = templateMapper.selectIdsByApp(appId);
        Map<Long, Long> issuedCountByTemplate = new LinkedHashMap<>();
        if (!templateIds.isEmpty()) {
            for (Long templateId : templateIds) {
                // 逐模板 SUM(used)：导出是低频管理动作，N 次索引查询（N=模板数）可接受，
                // 不值得为它写一条 IN + GROUP BY 的动态 SQL
                issuedCountByTemplate.put(templateId, stockShardMapper.sumUsed(templateId));
            }
        }

        insertEvent(appId, status, status, AppLifecycle.ACTION_DATA_EXPORT, operator, "租户数据导出");
        log.info("TENANT_DATA_EXPORT appId={} templates={} operator={}",
                appId, templateIds.size(), operator);
        return new ExportSnapshot(app.appId(), app.appName(), app.environment(), status.name(),
                app.createTime(), templateIds, issuedCountByTemplate);
    }

    /**
     * 高危动作预检（供编排层在花 BCrypt 成本做双人核验**之前**调用）：应用存在 +
     * 当前状态确能执行该动作。不持锁——真实迁移由 transitionInTx 行锁下终审，
     * 这里只是掐掉"对不存在 appId 无限试第二人密码"的爆破面。
     */
    public void assertActionable(String appId, String action) {
        OpenApp app = repository.findApp(appId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR,
                        "应用不存在：" + appId));
        AppLifecycle from = requireLifecycle(app);
        AppLifecycle.targetOf(from, action).orElseThrow(() -> new BizException(
                ErrorCode.STATE_CONFLICT, "非法生命周期迁移：" + appId + " 当前 " + from
                        + " 不支持动作 " + action));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 锁行 + 存在性校验（应用不存在按参数错误——与 OpenAppService.requireApp 同口径） */
    private OpenApp lockApp(String appId) {
        if (appId == null || appId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "appId 不能为空");
        }
        return repository.findAppForUpdate(appId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, "应用不存在：" + appId));
    }

    /** 生命周期列必须可解析（NOT NULL + 只有本服务写，理论不可达；fail-closed 拒绝） */
    private static AppLifecycle requireLifecycle(OpenApp app) {
        AppLifecycle lifecycle = app.lifecycle();
        if (lifecycle == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "应用 " + app.appId() + " 的 lifecycle_status 不可解析");
        }
        return lifecycle;
    }

    private void insertEvent(String appId, AppLifecycle from, AppLifecycle to,
                             String action, String operator, String reason) {
        OpenAppLifecycleEventPO event = new OpenAppLifecycleEventPO();
        event.setAppId(appId);
        event.setFromStatus(from.name());
        event.setToStatus(to.name());
        event.setAction(action);
        event.setOperator(operator == null || operator.isBlank() ? "admin" : operator);
        event.setReason(reason);
        eventMapper.insert(event);
    }

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /** 迁移结果（编排层原样返回给内部面调用方） */
    public record TransitionResult(String appId, AppLifecycle from, AppLifecycle to,
                                   String action, String operator) {
    }

    /** 导出快照：JSON 体与 SHA-256 由控制器层生成（那里才有序列化器与响应头） */
    public record ExportSnapshot(String appId, String appName, String environment,
                                 String lifecycleStatus, LocalDateTime createTime,
                                 List<Long> templateIds, Map<Long, Long> issuedCountByTemplate) {
    }
}
