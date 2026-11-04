package com.newcoupon.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.newcoupon.application.dto.AuditDtos.AuditExportDownloadRequest;
import com.newcoupon.application.dto.AuditDtos.AuditExportJobView;
import com.newcoupon.application.dto.AuditDtos.AuditFilter;
import com.newcoupon.application.dto.AuditDtos.AuditQueryRequest;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.audit.AuditActorResolver;
import com.newcoupon.infrastructure.persistence.mapper.AuditExportJobMapper;
import com.newcoupon.infrastructure.persistence.po.AuditExportJobPO;
import com.newcoupon.infrastructure.persistence.po.AuditLogPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 审计导出服务（V6 2C）：异步任务模式——受理即回 jobNo，构建在专用单线程执行，
 * 完成后凭 jobNo 一次性下载，过期行惰性清理。
 *
 * <p><b>为什么异步而不是同步出 CSV</b>：查询端点限 200 行/页，导出没有这个上界
 * （窗口 ≤31 天）——几万行的 CSV 构建叠上 SHA-256，同步做会把一次 HTTP 请求
 * 拖到超时；任务化还天然留下了"谁在什么时候导出过什么范围"的任务行，与
 * AUDIT_EXPORT 审计互为佐证。
 *
 * <p><b>文件放哪</b>：CSV 本体直接落 audit_export_job.file_content（LONGTEXT）。
 * 不落磁盘——实例重启/漂移后本地文件就丢了，而任务行还在（下载会 404 出不一致）；
 * 库内一行一文件，过期清理=删行，没有"清了表漏了文件"的第二套状态。
 *
 * <p><b>崩溃语义</b>：构建线程是进程内的，实例重启时在途任务停在 PENDING/RUNNING
 * 永不再跑——不做断点续跑（审计导出是幂等只读快照，重新发起一个任务即可），
 * 停滞行随 expires_at 过期被惰性清理。
 */
@Service
public class AuditExportService {

    private static final Logger log = LoggerFactory.getLogger(AuditExportService.class);

    /** CSV 列（与 AuditDetailView 同序多一列 hash——导出件要能离线自校验）。 */
    static final String CSV_HEADER = "id,created_at,app_id,actor_type,actor_id,session_id,user_id,"
            + "action,resource_type,resource_id,result_code,ip,trace_id,hash_version,"
            + "prev_hash,record_hash,before_snapshot,after_snapshot";

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** JSON 序列化器（静态——与 AuditService 同款立场，不依赖 Spring 定制 mapper）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 导出构建专用单线程（守护——不阻止 JVM 关闭；串行也限住了导出对 DB 的并发压力）。 */
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audit-export-writer");
        t.setDaemon(true);
        return t;
    });

    private final AuditExportJobMapper jobMapper;
    private final AuditQueryService queryService;
    private final AuditService auditService;
    private final AuditActorResolver actorResolver;
    private final int expireHours;
    private final long maxRows;

    public AuditExportService(AuditExportJobMapper jobMapper,
                              AuditQueryService queryService,
                              AuditService auditService,
                              AuditActorResolver actorResolver,
                              @Value("${coupon.audit.export.expire-hours:24}") int expireHours,
                              @Value("${coupon.audit.export.max-rows:200000}") long maxRows) {
        this.jobMapper = jobMapper;
        this.queryService = queryService;
        this.auditService = auditService;
        this.actorResolver = actorResolver;
        this.expireHours = expireHours;
        this.maxRows = maxRows;
    }

    // ------------------------------------------------------------------
    // 受理 / 轮询 / 下载
    // ------------------------------------------------------------------

    /** 受理导出：校验过滤（与查询同一套）→ 建任务行 → 提交异步构建 → 立即回 jobNo。 */
    public AuditExportJobView create(AuditQueryRequest req) {
        AuditFilter filter = req != null && req.filter() != null ? req.filter()
                : new AuditFilter(null, null, null, null, null, null, null, null, null);
        // 时间窗在**受理时**解析成绝对值再落任务行（m1）：默认窗（两个时间都空=最近 1 天）
        // 若留空到构建时再解析，构建排队跨过零点/小时后窗口就漂了——导出件必须
        // 与受理时刻的语义一致（"我申请的是那个时段的数据"）
        LocalDateTime[] window = queryService.normalizeWindow(filter);
        AuditFilter absolute = new AuditFilter(filter.appId(), filter.actorType(), filter.actorId(),
                filter.action(), filter.resourceType(), filter.resourceId(), filter.resultCode(),
                window[0], window[1]);

        // 惰性清理：过期任务（含崩溃停滞的 PENDING/RUNNING）在每次受理时一并清掉。
        // 无独立调度——导出低频，为删几行养一个定时线程不值（runbook 有登记）。
        int purged = jobMapper.delete(new LambdaQueryWrapper<AuditExportJobPO>()
                .lt(AuditExportJobPO::getExpiresAt, LocalDateTime.now()));

        AuditActorResolver.Actor actor = actorResolver.resolve();
        AuditExportJobPO job = new AuditExportJobPO();
        job.setJobNo("AUDIT-EXP-" + UUID.randomUUID());
        job.setRequestedBy(actor.actorId() == null ? "UNKNOWN" : actor.actorId());
        job.setFiltersJson(filterToJson(absolute));
        job.setStatus(AuditExportJobPO.STATUS_PENDING);
        job.setExpiresAt(LocalDateTime.now().plusHours(expireHours));
        jobMapper.insert(job);

        auditExport("受理导出任务", Map.of("jobNo", job.getJobNo(),
                "filter", AuditQueryService.describeFilter(filter, window)));
        if (purged > 0) {
            log.info("audit export: purged {} expired job(s)", purged);
        }

        exportExecutor.submit(() -> runJob(job.getId()));
        return new AuditExportJobView(job.getJobNo(), job.getStatus(), null, null, null);
    }

    /** 轮询任务状态（downloaded 前）。 */
    public AuditExportJobView status(String jobNo) {
        AuditExportJobPO job = load(jobNo);
        return new AuditExportJobView(job.getJobNo(), job.getStatus(),
                job.getRowCount(), job.getSha256(), job.getErrorMessage());
    }

    /**
     * 一次性下载：仅 DONE 且未下载过且未过期。CAS 抢 downloaded_at——
     * 并发双击只有一次拿得到文件，第二个人收 4002（数据出域动作必须可数）。
     *
     * @return CSV 字节（UTF-8 BOM 开头）
     */
    public byte[] download(AuditExportDownloadRequest req) {
        String jobNo = req == null ? null : req.jobNo();
        if (jobNo == null || jobNo.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "jobNo 不能为空");
        }
        AuditExportJobPO job = load(jobNo);
        if (AuditExportJobPO.STATUS_FAILED.equals(job.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "导出任务失败：" + job.getErrorMessage());
        }
        if (!AuditExportJobPO.STATUS_DONE.equals(job.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "导出任务尚未完成（status=" + job.getStatus() + "），请稍后凭 jobNo 轮询");
        }
        if (job.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "导出已过期（expiresAt=" + job.getExpiresAt() + "），请重新发起任务");
        }
        if (job.getDownloadedAt() != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "导出文件只能下载一次（首次下载于 " + job.getDownloadedAt() + "）");
        }
        if (jobMapper.markDownloaded(jobNo, LocalDateTime.now()) == 0) {
            // CAS 输给并发请求：行已被另一请求标记下载
            throw new BizException(ErrorCode.STATE_CONFLICT, "导出文件已被下载（并发请求已抢先）");
        }
        auditExport("下载导出文件", Map.of("jobNo", jobNo, "rowCount", job.getRowCount()));
        // BOM 在下载出口拼（字节级前置，与 1A failures CSV 同一 CsvExportSupport）：
        // 库里存纯文本（manifest/sha256 语义干净），Excel 双击打开靠下载件的 BOM 认 UTF-8
        return CsvExportSupport.withBom(job.getFileContent());
    }

    // ------------------------------------------------------------------
    // 构建（异步）
    // ------------------------------------------------------------------

    /** 构建一个任务：RUNNING → 逐批扫行拼 CSV → DONE（或 FAILED）。 */
    private void runJob(long jobId) {
        AuditExportJobPO job = jobMapper.selectById(jobId);
        if (job == null) {
            return;   // 受理后立刻被过期清理的极端窗口：无事可做
        }
        mark(job, AuditExportJobPO.STATUS_RUNNING, null, null);
        try {
            AuditFilter filter = filterFromJson(job.getFiltersJson());
            LocalDateTime[] window = queryService.normalizeWindow(filter);

            StringBuilder data = new StringBuilder(256 * 1024).append(CSV_HEADER);
            long[] count = {0};
            queryService.scan(filter, window, 500, po -> {
                if (++count[0] > maxRows) {
                    // 行数上限在回调里熔断：抛出后由外层统一落 FAILED
                    throw new IllegalStateException("结果行数超过上限 " + maxRows
                            + "，请缩小时间范围或加过滤后重试");
                }
                data.append("\r\n").append(csvLine(po));
            });

            String dataText = data.toString();
            String sha256 = sha256(dataText);
            // manifest 是文件第一行：行数 + 数据段 SHA-256 + 时间窗 + 过滤条件。
            // 哈希只盖"表头+数据"（manifest 自己不含自己——先有内容后有指纹）
            String csv = "MANIFEST," + CsvExportSupport.csvCell(String.valueOf(count[0])) + ','
                    + CsvExportSupport.csvCell(sha256) + ','
                    + CsvExportSupport.csvCell(window[0].format(TS) + "~" + window[1].format(TS)) + ','
                    + CsvExportSupport.csvCell(AuditQueryService.describeFilter(filter, window))
                    + "\r\n" + dataText;

            mark(job, AuditExportJobPO.STATUS_DONE, null,
                    done -> {
                        done.setRowCount(count[0]);
                        done.setSha256(sha256);
                        done.setFileContent(csv);
                    });
            log.info("audit export done: jobNo={} rows={} sha256={}",
                    job.getJobNo(), count[0], sha256.substring(0, 12) + "…");
        } catch (Exception e) {
            log.error("audit export failed: jobNo={}", job.getJobNo(), e);
            mark(job, AuditExportJobPO.STATUS_FAILED,
                    e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage().substring(0, Math.min(500, e.getMessage().length())),
                    null);
        }
    }

    /**
     * 过滤条件 → 任务行 JSON。手工拼字符串键值而不是 serialize(record)：
     * AuditService 的静态 mapper 不带 JavaTimeModule（审计快照从不含时间类型——
     * 这是它"静态可依赖"的边界），LocalDateTime 字段会让 writeValueAsString
     * 抛异常退化成 toString。这里显式转 ISO 字符串，读写两端都确定。
     */
    private static String filterToJson(AuditFilter filter) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("appId", filter.appId());
        map.put("actorType", filter.actorType());
        map.put("actorId", filter.actorId());
        map.put("action", filter.action());
        map.put("resourceType", filter.resourceType());
        map.put("resourceId", filter.resourceId());
        map.put("resultCode", filter.resultCode());
        map.put("startTime", filter.startTime() == null ? null : filter.startTime().format(TS));
        map.put("endTime", filter.endTime() == null ? null : filter.endTime().format(TS));
        return AuditService.serialize(map);
    }

    /** 任务行 JSON → 过滤条件（构建线程用；字段缺失=该过滤不生效）。 */
    private static AuditFilter filterFromJson(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(json);
            return new AuditFilter(
                    text(node, "appId"), text(node, "actorType"), text(node, "actorId"),
                    text(node, "action"), text(node, "resourceType"), text(node, "resourceId"),
                    node.hasNonNull("resultCode") ? node.get("resultCode").asInt() : null,
                    node.hasNonNull("startTime") ? LocalDateTime.parse(text(node, "startTime"), TS) : null,
                    node.hasNonNull("endTime") ? LocalDateTime.parse(text(node, "endTime"), TS) : null);
        } catch (Exception e) {
            throw new IllegalStateException("导出任务过滤条件损坏：无法回放 " + json, e);
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** 一行 CSV：全部经 csvCell（先公式注入防护后 RFC4180 转义——CsvExportSupport 的既定顺序）。 */    private static String csvLine(AuditLogPO po) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(cell(po.getId())).append(',')
                .append(cell(po.getCreatedAt() == null ? null : po.getCreatedAt().format(TS))).append(',')
                .append(cell(po.getAppId())).append(',')
                .append(cell(po.getActorType())).append(',')
                .append(cell(po.getActorId())).append(',')
                .append(cell(po.getSessionId())).append(',')
                .append(cell(po.getUserId())).append(',')
                .append(cell(po.getAction())).append(',')
                .append(cell(po.getResourceType())).append(',')
                .append(cell(po.getResourceId())).append(',')
                .append(cell(po.getResultCode())).append(',')
                .append(cell(po.getIp())).append(',')
                .append(cell(po.getTraceId())).append(',')
                .append(cell(po.getHashVersion())).append(',')
                .append(cell(po.getPrevHash())).append(',')
                .append(cell(po.getRecordHash())).append(',')
                .append(cell(po.getBeforeSnapshot())).append(',')
                .append(cell(po.getAfterSnapshot()));
        return sb.toString();
    }

    private static String cell(Object value) {
        return CsvExportSupport.csvCell(value == null ? "" : String.valueOf(value));
    }

    private void mark(AuditExportJobPO job, String status, String error,
                      java.util.function.Consumer<AuditExportJobPO> extras) {
        AuditExportJobPO update = new AuditExportJobPO();
        update.setId(job.getId());
        update.setStatus(status);
        update.setErrorMessage(error);
        if (extras != null) {
            extras.accept(update);
        }
        jobMapper.updateById(update);
    }

    private AuditExportJobPO load(String jobNo) {
        AuditExportJobPO job = jobMapper.selectOne(new LambdaQueryWrapper<AuditExportJobPO>()
                .eq(AuditExportJobPO::getJobNo, jobNo));
        if (job == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "导出任务不存在：jobNo=" + jobNo);
        }
        return job;
    }

    /** 导出动作留痕（受理与下载两个时刻各一条，action=AUDIT_EXPORT）。 */
    private void auditExport(String phase, Map<String, Object> detail) {
        try {
            AuditActorResolver.Actor actor = actorResolver.resolve();
            Map<String, Object> after = new LinkedHashMap<>(detail);
            after.put("phase", phase);
            auditService.record(AuditService.AuditContext.of(
                    null, null, org.slf4j.MDC.get(com.newcoupon.infrastructure.web.TraceIdFilter.TRACE_ID),
                    "AUDIT_EXPORT", "AUDIT", null,
                    null, AuditService.serialize(after),
                    null, 0,
                    actor.type(), actor.actorId(), actor.sessionFingerprint()));
        } catch (Exception e) {
            log.error("audit export self-record failed: phase={}", phase, e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
