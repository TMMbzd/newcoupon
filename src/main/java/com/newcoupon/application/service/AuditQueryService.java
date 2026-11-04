package com.newcoupon.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.newcoupon.application.dto.AuditDtos.AuditDetailRequest;
import com.newcoupon.application.dto.AuditDtos.AuditDetailView;
import com.newcoupon.application.dto.AuditDtos.AuditFilter;
import com.newcoupon.application.dto.AuditDtos.AuditQueryRequest;
import com.newcoupon.application.dto.AuditDtos.AuditQueryResult;
import com.newcoupon.application.dto.AuditDtos.AuditRowView;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.audit.AuditActorResolver;
import com.newcoupon.infrastructure.persistence.mapper.AuditLogMapper;
import com.newcoupon.infrastructure.persistence.po.AuditLogPO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审计查询服务（V6 2C）：内部面查询/详情 + 供导出复用的过滤与扫描。
 *
 * <p><b>为什么游标分页而不是 OFFSET</b>：OFFSET 的代价随深度线性涨（翻到第 1000 页
 * = 数据库扫过前 999 页），审计翻页是"往后追新"的场景——游标=上一页最后一行的 id，
 * WHERE id > cursor ORDER BY id LIMIT n 走主键，任何深度同价。审计行只写不删，
 * 游标不会因行消失而错位（这也是"只写不改不删"给查询端的红利）。
 *
 * <p><b>为什么查询本身也写审计（action=AUDIT_QUERY）</b>：审计日志能回答"谁动了钱"，
 * 也该能回答"谁看过审计"——查询导出是内部面的敏感动作（数据出域的前一步），
 * 不留痕的话审计员自己也成了监守自盲区。
 */
@Service
public class AuditQueryService {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

    /** 时间窗上界（裁定值）：31 天内的窗口走既有 idx_created；再大就该用导出而不是翻页。 */
    static final Duration MAX_WINDOW = Duration.ofDays(31);
    /** 默认窗口：两个时间都空时查最近 1 天（日常巡检口径，不是全表——审计表只增不减）。 */
    static final Duration DEFAULT_WINDOW = Duration.ofDays(1);
    /** 列表快照摘要长度：大快照不进列表（详情按 id 单查拿全文）。 */
    static final int SUMMARY_LENGTH = 200;

    private final AuditLogMapper auditLogMapper;
    private final AuditService auditService;
    private final AuditActorResolver actorResolver;

    public AuditQueryService(AuditLogMapper auditLogMapper,
                             AuditService auditService,
                             AuditActorResolver actorResolver) {
        this.auditLogMapper = auditLogMapper;
        this.auditService = auditService;
        this.actorResolver = actorResolver;
    }

    // ------------------------------------------------------------------
    // 查询 / 详情
    // ------------------------------------------------------------------

    /** 游标分页查询：过滤 + 时间窗（规则见 normalizeWindow），按 id 升序返回。 */
    public AuditQueryResult query(AuditQueryRequest req) {
        AuditFilter filter = req.filter() == null
                ? new AuditFilter(null, null, null, null, null, null, null, null, null) : req.filter();
        LocalDateTime[] window = normalizeWindow(filter);
        int limit = req.limit() == null ? 50 : req.limit();

        // limit+1 探位：多取一行只为判断"还有没有下一页"，返回前丢掉——比 COUNT 便宜且无竞态
        LambdaQueryWrapper<AuditLogPO> wrapper = baseWrapper(filter, window)
                .gt(AuditLogPO::getId, req.cursor() == null ? 0L : req.cursor())
                .orderByAsc(AuditLogPO::getId)
                .last("LIMIT " + (limit + 1));
        List<AuditLogPO> rows = auditLogMapper.selectList(wrapper);

        boolean hasNext = rows.size() > limit;
        List<AuditLogPO> page = hasNext ? rows.subList(0, limit) : rows;
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;

        auditSelf("AUDIT_QUERY", describeFilter(filter, window),
                java.util.Map.of("returned", page.size(), "hasNext", hasNext));
        return new AuditQueryResult(page.stream().map(AuditQueryService::toRowView).toList(), nextCursor);
    }

    /** 单条详情（快照全文 + 哈希两列）。 */
    public AuditDetailView detail(AuditDetailRequest req) {
        if (req == null || req.id() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "id 不能为空");
        }
        AuditLogPO po = auditLogMapper.selectById(req.id());
        if (po == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "审计记录不存在：id=" + req.id());
        }
        auditSelf("AUDIT_QUERY", describeFilter(null, null), java.util.Map.of("detailId", req.id()));
        return new AuditDetailView(po.getId(), po.getAppId(), po.getUserId(), po.getTraceId(),
                po.getAction(), po.getResourceType(), po.getResourceId(),
                po.getBeforeSnapshot(), po.getAfterSnapshot(),
                po.getIp(), po.getResultCode(),
                po.getActorType(), po.getActorId(), po.getSessionId(), po.getHashVersion(),
                po.getPrevHash(), po.getRecordHash(), po.getCreatedAt());
    }

    // ------------------------------------------------------------------
    // 供导出/校验复用的过滤与扫描
    // ------------------------------------------------------------------

    /**
     * 时间窗归一：都空=最近 1 天；只给一个/起点不早于终点/窗口超 31 天 → PARAM_ERROR。
     * 查询与导出共用一套口径——两处各自实现迟早漂移出"能查到的导不出来"。
     */
    LocalDateTime[] normalizeWindow(AuditFilter filter) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = filter.startTime();
        LocalDateTime end = filter.endTime();
        if (start == null && end == null) {
            return new LocalDateTime[]{now.minus(DEFAULT_WINDOW), now};
        }
        if (start == null || end == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "startTime 与 endTime 要么都空（默认最近 1 天），要么都给");
        }
        if (!start.isBefore(end)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "startTime 必须早于 endTime");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "时间范围不能超过 " + MAX_WINDOW.toDays() + " 天（当前 "
                            + Duration.between(start, end).toDays() + " 天）——更大范围请分次导出");
        }
        return new LocalDateTime[]{start, end};
    }

    /** 按过滤条件整窗扫描（id 升序、按批回调）——导出构建用；批大小固定，不 OFFSET。 */
    void scan(AuditFilter filter, LocalDateTime[] window, int batchSize, Consumer<AuditLogPO> consumer) {
        long cursor = 0L;
        while (true) {
            List<AuditLogPO> batch = auditLogMapper.selectList(baseWrapper(filter, window)
                    .gt(AuditLogPO::getId, cursor)
                    .orderByAsc(AuditLogPO::getId)
                    .last("LIMIT " + batchSize));
            batch.forEach(consumer);
            if (batch.size() < batchSize) {
                return;
            }
            cursor = batch.get(batch.size() - 1).getId();
        }
    }

    /** 组装公共过滤条件（不含游标/排序——分页与扫描各自追加）。 */
    private LambdaQueryWrapper<AuditLogPO> baseWrapper(AuditFilter filter, LocalDateTime[] window) {
        LambdaQueryWrapper<AuditLogPO> wrapper = new LambdaQueryWrapper<AuditLogPO>()
                .between(AuditLogPO::getCreatedAt, window[0], window[1]);
        if (filter.appId() != null && !filter.appId().isBlank()) {
            wrapper.eq(AuditLogPO::getAppId, filter.appId().trim());
        }
        if (filter.actorType() != null && !filter.actorType().isBlank()) {
            wrapper.eq(AuditLogPO::getActorType, filter.actorType().trim());
        }
        if (filter.actorId() != null && !filter.actorId().isBlank()) {
            wrapper.eq(AuditLogPO::getActorId, filter.actorId().trim());
        }
        if (filter.action() != null && !filter.action().isBlank()) {
            wrapper.eq(AuditLogPO::getAction, filter.action().trim());
        }
        if (filter.resourceType() != null && !filter.resourceType().isBlank()) {
            wrapper.eq(AuditLogPO::getResourceType, filter.resourceType().trim());
        }
        if (filter.resourceId() != null && !filter.resourceId().isBlank()) {
            wrapper.eq(AuditLogPO::getResourceId, filter.resourceId().trim());
        }
        if (filter.resultCode() != null) {
            wrapper.eq(AuditLogPO::getResultCode, filter.resultCode());
        }
        return wrapper;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 查询动作自身留痕：after 快照记过滤与返回规模，actor 取当前调用者。 */
    private void auditSelf(String action, String filterDesc, java.util.Map<String, Object> outcome) {
        try {
            AuditActorResolver.Actor actor = actorResolver.resolve();
            java.util.Map<String, Object> after = new java.util.LinkedHashMap<>(outcome);
            if (filterDesc != null) {
                after.put("filter", filterDesc);
            }
            auditService.record(AuditService.AuditContext.of(
                    null, null, org.slf4j.MDC.get(com.newcoupon.infrastructure.web.TraceIdFilter.TRACE_ID),
                    action, "AUDIT", null,
                    null, AuditService.serialize(after),
                    null, 0,
                    actor.type(), actor.actorId(), actor.sessionId()));
        } catch (Exception e) {
            log.error("audit self-record failed: action={}", action, e);
        }
    }

    /** 过滤条件的人话描述（进审计快照，运维 grep 用）。 */
    static String describeFilter(AuditFilter filter, LocalDateTime[] window) {
        if (filter == null) {
            return "detail";
        }
        StringBuilder sb = new StringBuilder("time=").append(window[0]).append('~').append(window[1]);
        if (filter.appId() != null) {
            sb.append(",appId=").append(filter.appId());
        }
        if (filter.actorType() != null) {
            sb.append(",actor=").append(filter.actorType());
        }
        if (filter.actorId() != null) {
            sb.append('/').append(filter.actorId());
        }
        if (filter.action() != null) {
            sb.append(",action=").append(filter.action());
        }
        if (filter.resourceType() != null) {
            sb.append(",resourceType=").append(filter.resourceType());
        }
        if (filter.resourceId() != null) {
            sb.append(",resourceId=").append(filter.resourceId());
        }
        if (filter.resultCode() != null) {
            sb.append(",resultCode=").append(filter.resultCode());
        }
        return sb.toString();
    }

    /** PO → 列表行：快照截断到 200 字符（原文进详情）。 */
    private static AuditRowView toRowView(AuditLogPO po) {
        return new AuditRowView(po.getId(), po.getAppId(), po.getActorType(), po.getActorId(),
                po.getSessionId(), po.getUserId(), po.getAction(), po.getResourceType(),
                po.getResourceId(), po.getResultCode(), po.getIp(), po.getTraceId(),
                po.getCreatedAt(), summarize(po.getBeforeSnapshot()), summarize(po.getAfterSnapshot()));
    }

    /** 截断摘要：超长时保留前 197 字符 + 省略号，长度恰为 200——前端不必再截。 */
    static String summarize(String snapshot) {
        if (snapshot == null || snapshot.length() <= SUMMARY_LENGTH) {
            return snapshot;
        }
        return snapshot.substring(0, SUMMARY_LENGTH - 3) + "…";
    }
}
