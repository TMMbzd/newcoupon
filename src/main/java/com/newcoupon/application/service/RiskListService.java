package com.newcoupon.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.newcoupon.application.dto.RiskDtos.ListAddRequest;
import com.newcoupon.application.dto.RiskDtos.ListDisableRequest;
import com.newcoupon.application.dto.RiskDtos.ListView;
import com.newcoupon.application.dto.RiskDtos.ListQueryRequest;
import com.newcoupon.application.dto.RiskDtos.ListQueryResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.risk.RiskListType;
import com.newcoupon.domain.risk.RiskValueType;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.infrastructure.audit.AdminActionAuditor;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.mapper.RiskListEntryMapper;
import com.newcoupon.infrastructure.persistence.po.RiskListEntryPO;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 风控名单管理服务（V6 4A）——管理面端点的服务层（刀 2 的规则/任务将来也走这里加名单）。
 *
 * <p><b>幂等</b>：自然键 (list_type,value_type,value,app_id) 唯一。同键重放返回已存在行
 * （duplicated=true）——运营重复提交是常态；同键不同 reason 不算异参冲突
 * （reason 是说明不是身份，新 reason 不改旧行，要看新 reason 就 disable 后重建）。
 *
 * <p><b>停用=软删</b>（DISABLED）：名单行是申诉与复核的第一现场，物理删除会让
 * "当时为什么拦他"失去证据；命中查询只看 ACTIVE（SQL 侧过滤）。
 *
 * <p><b>审计</b>：增/停用挂 {@link AdminActionAuditor}（2C 模式，服务层挂——
 * 刀 2 风控规则自动加名单时同样留痕）；query 不记。
 */
@Service
public class RiskListService {

    private static final Logger log = LoggerFactory.getLogger(RiskListService.class);

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final RiskListEntryMapper listMapper;
    private final OpenAppRepository openAppRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final AdminActionAuditor adminAudit;

    public RiskListService(RiskListEntryMapper listMapper,
                           OpenAppRepository openAppRepository,
                           SnowflakeIdGenerator idGenerator,
                           AdminActionAuditor adminAudit) {
        this.listMapper = listMapper;
        this.openAppRepository = openAppRepository;
        this.idGenerator = idGenerator;
        this.adminAudit = adminAudit;
    }

    /**
     * 增名单条目。服务层自查（照 SettlementHoldService 先例）：@Valid 只护 HTTP 面，
     * 刀 2 的规则/任务直调本方法绕过控制器——枚举值/长度/过期方向必须在入口挡住。
     */
    public ListView add(ListAddRequest req) {
        RiskListType listType = parseEnum(RiskListType.class, req.listType(), "listType");
        RiskValueType valueType = parseEnum(RiskValueType.class, req.valueType(), "valueType");
        String value = requireText(req.value(), "value", 128);
        String reason = requireText(req.reason(), "reason", 255);
        String appId = req.appId() == null || req.appId().isBlank()
                ? "" : req.appId().trim();
        LocalDateTime expiresAt = req.expiresAt() == null ? null : req.expiresAt().withNano(0);
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "expiresAt 必须晚于当前时间（建出来就过期的条目永远不生效）: " + expiresAt);
        }
        // 租户级条目要求 app 真实存在：把名单挂到拼写错误的租户上永远命不中——错误在入口暴露
        if (!appId.isEmpty() && openAppRepository.findApp(appId).isEmpty()) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "应用不存在: " + appId);
        }

        // 幂等优先：同自然键已有 **ACTIVE** 行 → 返回已存在行（duplicated=true）；
        // 已有 **DISABLED** 行 → 复活（审查 A 簇）：停用行占着自然键唯一约束，
        // 若走 duplicated 短路，"停用后重建"永远建不回来——黑名单静默缺失且无任何信号。
        RiskListEntryPO existing = listMapper.selectByNaturalKey(
                listType.name(), valueType.name(), value, appId);
        if (existing != null) {
            if (STATUS_ACTIVE.equals(existing.getStatus())) {
                return toView(existing, true, false);
            }
            return reactivate(existing, reason, expiresAt);
        }

        RiskListEntryPO po = new RiskListEntryPO();
        po.setId(idGenerator.next());
        po.setListType(listType.name());
        po.setValueType(valueType.name());
        po.setValue(value);
        po.setAppId(appId);
        po.setReason(reason);
        po.setExpiresAt(expiresAt);
        po.setStatus(STATUS_ACTIVE);
        po.setCreatedBy(currentOperator());
        try {
            listMapper.insert(po);
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一键裁决，赢家先落——重读分类（ACTIVE=幂等成功；DISABLED=复活）
            RiskListEntryPO raced = listMapper.selectByNaturalKey(
                    listType.name(), valueType.name(), value, appId);
            if (raced != null) {
                if (STATUS_ACTIVE.equals(raced.getStatus())) {
                    return toView(raced, true, false);
                }
                return reactivate(raced, reason, expiresAt);
            }
            throw e;
        }
        log.info("RISK_LIST_ADDED id={} {} {}={} appId={} expiresAt={}",
                po.getId(), listType, valueType, value,
                appId.isEmpty() ? "(平台级)" : appId, expiresAt);
        adminAudit.record("RISK_LIST_ADD", "RISK_LIST_ENTRY", String.valueOf(po.getId()),
                java.util.Map.of("listType", listType.name(), "valueType", valueType.name(),
                        "value", value, "appId", appId,
                        "expiresAt", expiresAt == null ? "永久" : expiresAt.toString()));
        return toView(po, false, false);
    }

    /**
     * 复活：DISABLED→ACTIVE 并覆盖 reason/expires_at/created_by（审查 A 簇）。
     * 新参数覆盖旧值——重建的语义就是"按这次提交的样子重新生效"；旧行的 id/创建时间不动
     * （审计线索连续）。0 行=并发赢家已复活，重读按当前状态分类。
     */
    private ListView reactivate(RiskListEntryPO disabled, String reason, LocalDateTime expiresAt) {
        int updated = listMapper.reactivateById(disabled.getId(), reason, expiresAt,
                currentOperator());
        if (updated == 0) {
            RiskListEntryPO current = listMapper.selectById(disabled.getId());
            if (current != null && STATUS_ACTIVE.equals(current.getStatus())) {
                return toView(current, true, true);   // 并发赢家刚复活：幂等成功
            }
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "名单条目状态冲突（非 DISABLED，无法复活）: id=" + disabled.getId());
        }
        RiskListEntryPO fresh = listMapper.selectById(disabled.getId());
        log.info("RISK_LIST_REACTIVATED id={} {} {}={} appId={}（停用后同键重建）",
                disabled.getId(), disabled.getListType(), disabled.getValueType(),
                disabled.getValue(), disabled.getAppId());
        adminAudit.record("RISK_LIST_REACTIVATE", "RISK_LIST_ENTRY",
                String.valueOf(disabled.getId()),
                java.util.Map.of("listType", disabled.getListType(),
                        "value", disabled.getValue(), "appId", disabled.getAppId()));
        return toView(fresh == null ? disabled : fresh, false, true);
    }

    /** 停用（软删）：条件 UPDATE 并发裁决；已停用重放=幂等成功；不存在=1002 */
    public ListView disable(ListDisableRequest req) {
        RiskListEntryPO po = listMapper.selectById(req.id());
        if (po == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "名单条目不存在: id=" + req.id());
        }
        int updated = listMapper.disableById(req.id());
        if (updated == 0) {
            RiskListEntryPO current = listMapper.selectById(req.id());
            if (current != null && STATUS_DISABLED.equals(current.getStatus())) {
                return toView(current, true, false);   // 重复停用：幂等成功
            }
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "名单条目状态不允许停用: " + (current == null ? "已删除" : current.getStatus()));
        }
        RiskListEntryPO fresh = listMapper.selectById(req.id());
        log.info("RISK_LIST_DISABLED id={} {} {}={} appId={}", req.id(),
                fresh == null ? po.getListType() : fresh.getListType(),
                fresh == null ? po.getValueType() : fresh.getValueType(),
                fresh == null ? po.getValue() : fresh.getValue(),
                (fresh == null ? po : fresh).getAppId());
        adminAudit.record("RISK_LIST_DISABLE", "RISK_LIST_ENTRY", String.valueOf(req.id()),
                java.util.Map.of("listType", po.getListType(), "value", po.getValue(),
                        "appId", po.getAppId()));
        return toView(fresh == null ? po : fresh, false, false);
    }

    /** 游标分页查询（主键倒序；过滤维度全部可空） */
    public ListQueryResult query(ListQueryRequest req) {
        int limit = req.limit() == null ? DEFAULT_LIMIT : Math.min(req.limit(), MAX_LIMIT);
        QueryWrapper<RiskListEntryPO> wrapper = new QueryWrapper<>();
        if (req.cursor() != null) {
            wrapper.lt("id", req.cursor());
        }
        if (notBlank(req.listType())) {
            wrapper.eq("list_type", req.listType().trim());
        }
        if (notBlank(req.valueType())) {
            wrapper.eq("value_type", req.valueType().trim());
        }
        if (notBlank(req.value())) {
            wrapper.eq("value", req.value().trim());
        }
        if (Boolean.TRUE.equals(req.platformOnly())) {
            wrapper.eq("app_id", "");   // 平台级的存储形态是空串（见 RiskListEntryPO）
        } else if (notBlank(req.appId())) {
            wrapper.eq("app_id", req.appId().trim());
        }
        if (notBlank(req.status())) {
            wrapper.eq("status", req.status().trim());
        }
        wrapper.orderByDesc("id").last("LIMIT " + (limit + 1));
        List<RiskListEntryPO> rows = listMapper.selectList(wrapper);
        boolean hasNext = rows.size() > limit;
        List<RiskListEntryPO> page = hasNext ? rows.subList(0, limit) : rows;
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new ListQueryResult(page.stream().map(po -> toView(po, false, false)).toList(), nextCursor);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private ListView toView(RiskListEntryPO po, boolean duplicated, boolean reactivated) {
        return new ListView(po.getId(), po.getListType(), po.getValueType(), po.getValue(),
                po.getAppId() == null || po.getAppId().isEmpty() ? null : po.getAppId(),
                po.getReason(), po.getExpiresAt(), po.getStatus(), po.getCreatedBy(),
                po.getCreatedAt(), po.getUpdatedAt(), duplicated, reactivated);
    }

    /** 服务层枚举校验：拼错的 listType/valueType 落库后永远命不中任何判定——入口就拒 */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, field + " 不能为空");
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    field + " 只能是 " + java.util.Arrays.toString(type.getEnumConstants())
                            + "，收到: " + raw);
        }
    }

    private static String requireText(String raw, String field, int max) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, field + " 不能为空");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > max) {
            throw new BizException(ErrorCode.PARAM_ERROR, field + " 最长 " + max + " 字符（= 存储列宽）");
        }
        return trimmed;
    }

    /**
     * 操作人：管理面会话→登录名；开放面→appId；后台/风控任务→SYSTEM
     * （与 SettlementHoldService.currentOperator 同一纪律——"谁干的"来自认证上下文，
     * 不由请求自报，否则审计行可以伪造）。
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
