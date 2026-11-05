package com.newcoupon.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.newcoupon.application.dto.SettlementHoldDtos.HoldPlaceRequest;
import com.newcoupon.application.dto.SettlementHoldDtos.HoldQueryRequest;
import com.newcoupon.application.dto.SettlementHoldDtos.HoldQueryResult;
import com.newcoupon.application.dto.SettlementHoldDtos.HoldView;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.audit.AdminActionAuditor;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.mapper.CouponSettlementHoldMapper;
import com.newcoupon.infrastructure.persistence.po.CouponSettlementHoldPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 券侧应付冻结服务（V47，V6 3min 结算最小版）——**阶段 4B 风控的挂点**：
 * 风控规则将来直接调 {@link #place} / {@link #release}（source=RISK_RULE），
 * 管理端点（SettlementHoldController）是同一服务的 HTTP 面。
 *
 * <p><b>语义边界（迁移列注释同源）</b>：hold 只改主体账单的应付口径
 * （{@code payable = discount_total - hold_amount}，生成时算好存列），
 * **不动真实资金、不影响用户退款**。因此：
 * <ul>
 *   <li><b>不接 EnvironmentGuard</b>：那是"真钱动作"的资金边界（退款/预算账本/账单聚合），
 *       hold 是账单口径的记号——沙箱租户建 hold 也动不了任何真钱，反而保留现场更好排查；
 *       若 4B 接入后出现"沙箱 hold 干扰生产账单"的实际风险再收紧（登记 runbook）。</li>
 *   <li>不做余额校验（hold 总额可以超过主体净额）：超额的后果是账单 payable 为负并如实展示，
 *       负数本身就是"需人工关注"的信号——钳制或拒绝会掩盖这个事实。</li>
 * </ul>
 *
 * <p><b>状态机</b>（条件 UPDATE 强制，并发 release/void 只有一个能改中）：
 * <pre>
 *            release()            void()
 *   ACTIVE ────────────▶ RELEASED（终态）
 *     │
 *     └────────────────▶ VOIDED（终态）
 * </pre>
 * 终态重放 = 幂等成功返回原状态（迟到重试不是错误）；RELEASED↔VOIDED 互斥拒绝（4002）——
 * "释放"是把这笔 hold 从应付口径放回（下期账单恢复扣减前口径），"作废"是判定建错了：
 * 两个终态的财务含义不同，建成终态后历史不可改写。
 *
 * <p><b>审计</b>：place/release/void 在服务层挂 {@link AdminActionAuditor}（2C 模式）——
 * 挂服务层而非控制器，是为了 4B 风控规则（非 HTTP 上下文，actor=SYSTEM）调用时同样留痕。
 */
@Service
public class SettlementHoldService {

    private static final Logger log = LoggerFactory.getLogger(SettlementHoldService.class);

    /** hold 状态机字面量（与 V47 列注释一致；不建 enum——三个值、无分支逻辑，字面量够用） */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String STATUS_VOIDED = "VOIDED";

    private static final Set<String> SOURCES = Set.of("RISK_RULE", "MANUAL");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    /** 单笔金额上限（分）：防算术回绕——hold 求和与 payable=净额-hold 都在 long 域 */
    private static final long MAX_AMOUNT = 10_000_000_000_000_000L;   // 1e13 分

    private final CouponSettlementHoldMapper holdMapper;
    private final OpenAppRepository openAppRepository;
    private final TemplateRepository templateRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final AdminActionAuditor adminAudit;

    public SettlementHoldService(CouponSettlementHoldMapper holdMapper,
                                 OpenAppRepository openAppRepository,
                                 TemplateRepository templateRepository,
                                 SnowflakeIdGenerator idGenerator,
                                 AdminActionAuditor adminAudit) {
        this.holdMapper = holdMapper;
        this.openAppRepository = openAppRepository;
        this.templateRepository = templateRepository;
        this.idGenerator = idGenerator;
        this.adminAudit = adminAudit;
    }

    /**
     * 建冻结。幂等：holdNo 撞唯一键（先查后插 + DuplicateKey 兜底）重放返回已存在的行
     * （duplicated=true），不建新行、不改原行状态——同号重试是调用方的常态，不是错误。
     *
     * <p><b>同号异参拒绝（审查 B 簇）</b>：与 replayRefund 的 requestDigest 同一纪律——
     * 幂等锚点只认"同参重放"，amount/appId/主体/模板任一不同抛 1000。否则 4B 风控调参重发
     * 会拿到 200+duplicated，误以为新金额已生效而库里还是旧值。
     */
    public HoldView place(HoldPlaceRequest req) {
        // 服务层自查（审查 H 簇，照 doRefundConfirm 先例）：@Valid 只护 HTTP 面，
        // 4B 风控直调本方法绕过控制器——金额方向性错误（负/零）会算出虚增 payable，必须挡
        validatePlaceParams(req);
        String holdNo = req.holdNo().trim();
        String appId = req.appId().trim();
        String partyId = req.settlePartyId().trim();
        String source = req.source() == null || req.source().isBlank()
                ? "MANUAL" : req.source().trim();
        if (!SOURCES.contains(source)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "source 只能是 RISK_RULE 或 MANUAL: " + source);
        }
        // 幂等优先（同号重放在存在性校验之前）：重放是对既有事实的确认，不是新请求——
        // app/模板事后被删不应让重放 404；同号异参在这里就拦（错误信息也更准确）
        CouponSettlementHoldPO existing = holdMapper.selectByHoldNo(holdNo);
        if (existing != null) {
            assertSameParams(existing, req);
            return toView(existing, true);
        }
        // app 必须真实存在：把 hold 挂到拼写错误的租户上，账单永远命不中——错误在入口暴露
        if (openAppRepository.findApp(appId).isEmpty()) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "应用不存在: " + appId);
        }
        // 模板级 hold 的模板必须存在：悬空 template_id 同样永远命不中任何账单行
        if (req.templateId() != null && templateRepository.findById(req.templateId()).isEmpty()) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "模板不存在: " + req.templateId());
        }

        CouponSettlementHoldPO po = new CouponSettlementHoldPO();
        po.setId(idGenerator.next());
        po.setHoldNo(holdNo);
        po.setAppId(appId);
        po.setSettlePartyId(partyId);
        po.setTemplateId(req.templateId());
        po.setAmount(req.amount());
        po.setReason(req.reason().trim());
        po.setSource(source);
        po.setStatus(STATUS_ACTIVE);
        po.setCreatedBy(currentOperator());
        po.setCreatedAt(LocalDateTime.now().withNano(0));
        try {
            holdMapper.insert(po);
        } catch (DuplicateKeyException e) {
            // 并发同号：唯一键裁决，赢家先落——重读返回已存在的那条（同号异参照比对拒绝）
            CouponSettlementHoldPO raced = holdMapper.selectByHoldNo(holdNo);
            if (raced != null) {
                assertSameParams(raced, req);
                return toView(raced, true);
            }
            throw e;
        }
        log.info("SETTLEMENT_HOLD_PLACED holdNo={} appId={} party={} template={} amount={} source={}"
                        + "（提示：hold 变化后该期间主体账单需重跑才反映）",
                holdNo, appId, partyId, req.templateId(), req.amount(), source);
        adminAudit.record("SETTLEMENT_HOLD_CREATE", "SETTLEMENT_HOLD", holdNo,
                java.util.Map.of("appId", appId, "settlePartyId", partyId,
                        "amount", req.amount(), "source", source,
                        "templateId", req.templateId() == null ? 0L : req.templateId()));
        return toView(po, false);
    }

    /** place 的服务层形态校验（4B 直调入口没有 @Valid）：金额/单号/reason 三查 */
    private static void validatePlaceParams(HoldPlaceRequest req) {
        if (req.holdNo() == null || req.holdNo().isBlank() || req.holdNo().trim().length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "holdNo 不能为空且最长 64 字符（= 存储列宽）");
        }
        if (req.amount() == null || req.amount() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "冻结金额必须为正（分）: " + req.amount());
        }
        // 上限 1e13 分（=100 万亿元）：防算术回绕——payable=净额-hold 与账单 SUM 都在 long 域，
        // 单笔接近 Long.MAX 时多笔 hold 求和会溢出成负数、方向反转
        if (req.amount() > MAX_AMOUNT) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "冻结金额超上限 " + MAX_AMOUNT + " 分（超大额请拆多笔并人工复核）");
        }
        if (req.reason() == null || req.reason().isBlank()
                || req.reason().trim().length() > 255) {
            throw new BizException(ErrorCode.PARAM_ERROR, "reason 必填且最长 255 字符（= 存储列宽）");
        }
    }

    /** 同号异参检测：amount/appId/主体/模板构成冻结的业务身份，任一不同=幂等锚点被复用。
     *  amount 用 equals 比（Long 装箱的 == 是引用比较，>127 的金额必误报——1C 审查同款坑复发） */
    private static void assertSameParams(CouponSettlementHoldPO existing, HoldPlaceRequest req) {
        boolean same = java.util.Objects.equals(existing.getAmount(), req.amount())
                && existing.getAppId().equals(req.appId().trim())
                && existing.getSettlePartyId().equals(req.settlePartyId().trim())
                && java.util.Objects.equals(existing.getTemplateId(), req.templateId());
        if (!same) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "同一冻结单号请求参数不一致（已存在 amount=" + existing.getAmount()
                            + " appId=" + existing.getAppId() + " party=" + existing.getSettlePartyId()
                            + " template=" + existing.getTemplateId() + "）");
        }
    }

    /**
     * 释放：ACTIVE→RELEASED（下期账单重新生成时 hold 不再扣减）。
     * 已 RELEASED 重放=幂等成功；VOIDED 后释放=非法迁移拒绝（建错请走 void，语义不同）。
     */
    public HoldView release(String holdNo) {
        return transition(holdNo, STATUS_RELEASED, "SETTLEMENT_HOLD_RELEASE");
    }

    /** 作废：ACTIVE→VOIDED（判定建错；与"释放"终态互斥）。已 VOIDED 重放=幂等成功。 */
    public HoldView voidHold(String holdNo) {
        return transition(holdNo, STATUS_VOIDED, "SETTLEMENT_HOLD_VOID");
    }

    /**
     * 终态迁移公共路径：条件 UPDATE（WHERE status='ACTIVE'）是并发裁决点——
     * 1=本次迁移；0=**重读当前行**再分类（审查 G 簇）：并发同目标重试的输家阻塞在赢家
     * 行锁上，UPDATE 求值时赢家已提交——用迁移前快照分类会把"赢家刚提交的目标态"误读成
     * "ACTIVE 不能迁移"的自相矛盾 4002。重读后：目标态=幂等成功 / 另一终态=真冲突 /
     * 不存在=1002。
     */
    private HoldView transition(String holdNo, String target, String auditAction) {
        CouponSettlementHoldPO po = holdMapper.selectByHoldNo(holdNo);
        if (po == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "冻结单不存在: " + holdNo);
        }
        int updated = holdMapper.transitionFromActive(holdNo, target, currentOperator(),
                LocalDateTime.now().withNano(0));
        if (updated == 0) {
            // 0 行 ≠ 沿用上面的 po 快照：并发赢家的提交可能就发生在本方法两次查询之间
            CouponSettlementHoldPO current = holdMapper.selectByHoldNo(holdNo);
            if (current != null && target.equals(current.getStatus())) {
                return toView(current, true);   // 并发赢家（或此前重试）已迁移到目标态：幂等成功
            }
            String actual = current == null ? "已删除" : current.getStatus();
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "冻结单已处于终态 " + actual + "，不能迁移到 " + target + "（终态互斥）");
        }
        CouponSettlementHoldPO fresh = holdMapper.selectByHoldNo(holdNo);
        log.info("SETTLEMENT_HOLD_{} holdNo={} status={}（提示：hold 变化后该期间主体账单需重跑才反映）",
                target, holdNo, fresh == null ? target : fresh.getStatus());
        adminAudit.record(auditAction, "SETTLEMENT_HOLD", holdNo,
                java.util.Map.of("from", STATUS_ACTIVE, "to", target,
                        "amount", po.getAmount()));
        return toView(fresh == null ? po : fresh, false);
    }

    /**
     * 游标分页查询（照 2C 审计查询口径：按主键倒序 + 游标翻页，禁 OFFSET——任何深度同价）。
     * 过滤维度 appId/settlePartyId/status 全部可空；游标=上一页最后一条的 id。
     */
    public HoldQueryResult query(HoldQueryRequest req) {
        int limit = req.limit() == null ? DEFAULT_LIMIT : Math.min(req.limit(), MAX_LIMIT);
        QueryWrapper<CouponSettlementHoldPO> wrapper = new QueryWrapper<>();
        if (req.cursor() != null) {
            wrapper.lt("id", req.cursor());   // 按 id 倒序翻页：游标=上一页最后一条的 id
        }
        if (req.appId() != null && !req.appId().isBlank()) {
            wrapper.eq("app_id", req.appId().trim());
        }
        if (req.settlePartyId() != null && !req.settlePartyId().isBlank()) {
            wrapper.eq("settle_party_id", req.settlePartyId().trim());
        }
        if (req.status() != null && !req.status().isBlank()) {
            wrapper.eq("status", req.status().trim());
        }
        wrapper.orderByDesc("id").last("LIMIT " + (limit + 1));   // 多取一条判断有没有下一页
        List<CouponSettlementHoldPO> rows = holdMapper.selectList(wrapper);
        boolean hasNext = rows.size() > limit;
        List<CouponSettlementHoldPO> page = hasNext ? rows.subList(0, limit) : rows;
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new HoldQueryResult(page.stream().map(po -> toView(po, false)).toList(), nextCursor);
    }

    private static HoldView toView(CouponSettlementHoldPO po, boolean duplicated) {
        return new HoldView(po.getId(), po.getHoldNo(), po.getAppId(), po.getSettlePartyId(),
                po.getTemplateId(), po.getAmount(), po.getReason(), po.getSource(),
                po.getStatus(), po.getCreatedBy(), po.getReleasedBy(),
                po.getCreatedAt(), po.getReleasedAt(), duplicated);
    }

    /**
     * 操作人：管理面会话→登录名；开放面→appId；后台/风控任务→SYSTEM。
     * 不由请求自报——"谁干的"必须来自认证上下文，否则审计行可以伪造。
     */
    private static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal()
                instanceof com.newcoupon.infrastructure.security.AdminPrincipal admin) {
            return admin.getUsername();
        }
        String appId = RequestContext.current().appId();
        return appId != null ? appId : "SYSTEM";
    }
}
