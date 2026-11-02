package com.newcoupon.application.service;

import com.newcoupon.application.dto.GrantTaskCancelResult;
import com.newcoupon.application.dto.GrantTaskCreateRequest;
import com.newcoupon.application.dto.GrantTaskCreateResult;
import com.newcoupon.application.dto.GrantTaskRetryResult;
import com.newcoupon.application.dto.GrantTaskStatus;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantChunkMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantItemMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponGrantChunkPO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantItemPO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantTaskPO;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 批量发放任务编排（V6 阶段 1A 第一刀）。
 *
 * <p><b>复用而非重建</b>：每用户发券最终走
 * {@link C1ReceiveAppService#receive}——幂等/限领/库存/错误分类全部沿用
 * 既有链路（V6 明示不建第二套）。本域新增的只是"任务受理、分片分组、
 * 进度计数、取消、崩溃恢复"这些编排语义。
 *
 * <p><b>归属校验在服务层</b>（照 {@link TemplateAppService} 的模式）：
 * 过滤器只知道"你是谁"，模板/任务归属要贴着资源读取点判
 * （{@link AppOwnership#assertAccessible}）。
 *
 * <p>本类不写 @Transactional：建任务的多写事务在 {@link GrantTaskTxService}
 * （见 application/package-info 分层铁律——编排者持事务会把校验读也拖进事务）。
 */
@Service
public class GrantTaskService {

    private static final Logger log = LoggerFactory.getLogger(GrantTaskService.class);

    /** 单任务用户上限：去重后最多 1 万——单事务建明细的规模护栏（MVP 取整）。 */
    static final int MAX_USERS_PER_TASK = 10_000;

    /** 模板分片数异常时的回退值（设计口径：null→16；域模型是 int 永不为 null，&lt;1 视为异常）。 */
    static final int FALLBACK_SHARD_COUNT = 16;

    private final TemplateRepository templateRepository;
    private final CouponGrantTaskMapper taskMapper;
    private final CouponGrantChunkMapper chunkMapper;
    private final CouponGrantItemMapper itemMapper;
    private final GrantTaskTxService txService;
    private final SnowflakeIdGenerator idGenerator;

    public GrantTaskService(TemplateRepository templateRepository,
                            CouponGrantTaskMapper taskMapper,
                            CouponGrantChunkMapper chunkMapper,
                            CouponGrantItemMapper itemMapper,
                            GrantTaskTxService txService,
                            SnowflakeIdGenerator idGenerator) {
        this.templateRepository = templateRepository;
        this.taskMapper = taskMapper;
        this.chunkMapper = chunkMapper;
        this.itemMapper = itemMapper;
        this.txService = txService;
        this.idGenerator = idGenerator;
    }

    /**
     * 受理批量发放任务：校验 → 幂等预查 → 单事务建 task/chunks/items。
     *
     * <p>userIds 服务端去重（同任务一个用户最多一张目标券）；taskNo 服务端
     * 生成（GT+雪花ID）；(app_id, request_id) 唯一键是受理幂等终审——
     * 并发同 requestId 双写时输家在这里被翻译成"返回现状"而不是报错。
     */
    public GrantTaskCreateResult createTask(GrantTaskCreateRequest req) {
        List<Long> users = dedupUsers(req.userIds());
        // 模板加载与归属/发布态检查照 TemplateAppService.publish 的模式：
        // 先取到行、再判归属（过滤器阶段不知道模板属于谁）
        CouponTemplate template = templateRepository.findById(req.templateId())
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND,
                        "id=" + req.templateId()));
        AppOwnership.assertAccessible(template.getAppId(), "template:" + req.templateId());
        if (template.getApproveStatus() != ApproveStatus.APPROVED
                || template.getPublishStatus() != PublishStatus.PUBLISHED) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE, "仅已发布模板可批量发放");
        }

        String appId = AppOwnership.ownerForNewResource();
        CouponGrantTaskPO existing = taskMapper.selectByAppAndRequest(appId, req.requestId());
        if (existing != null) {
            // 幂等重放：同 requestId 已有任务。模板不同=键被复用到另一张券，
            // 拒绝并指回原任务，而不是静默返回别人的任务。
            // 必须 equals：两侧都是装箱 Long，!= 是引用比较——模板 id 超出
            // Long 缓存区（>127）后同模板重放会被误判成键复用而拒绝
            if (!existing.getTemplateId().equals(req.templateId())) {
                throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                        "该 requestId 已用于 templateId=" + existing.getTemplateId()
                                + " 的批量发放，不可用于其他模板");
            }
            return new GrantTaskCreateResult(existing.getTaskNo(), existing.getStatus(),
                    existing.getTotalCount(), true);
        }

        String taskNo = "GT" + idGenerator.next();
        TaskArtifacts artifacts = buildArtifacts(appId, taskNo, req, users, template);
        try {
            txService.createTaskInTx(artifacts.task(), artifacts.chunks(), artifacts.items());
        } catch (DuplicateKeyException e) {
            // 并发同 requestId：唯一键终审，输家读现状返回（受理幂等）
            CouponGrantTaskPO winner = taskMapper.selectByAppAndRequest(appId, req.requestId());
            if (winner != null) {
                return new GrantTaskCreateResult(winner.getTaskNo(), winner.getStatus(),
                        winner.getTotalCount(), true);
            }
            throw e;
        }
        log.info("grant task created: taskNo={}, templateId={}, users={}, appId={}",
                taskNo, req.templateId(), users.size(), appId);
        return new GrantTaskCreateResult(taskNo, CouponGrantTaskPO.STATUS_PENDING,
                users.size(), false);
    }

    /** 进度查询：计数 + percent=(total-pending)*100/total；scheduleTime 原样回显（1B）。 */
    public GrantTaskStatus getStatus(String taskNo) {
        CouponGrantTaskPO task = loadAccessibleTask(taskNo);
        long total = task.getTotalCount();
        long settled = total - task.getPendingCount();
        long percent = total == 0 ? 100 : settled * 100 / total;
        return new GrantTaskStatus(task.getTaskNo(), task.getStatus(), total,
                task.getSuccessCount(), task.getFailCount(), task.getSkipCount(),
                task.getPendingCount(), percent, task.getScheduleTime());
    }

    /**
     * 取消受理：CAS PENDING/RUNNING → CANCELING。返回受理结果；
     * 已终态的任务拒绝取消（accepted=false），重复取消幂等。
     * 剩余 chunk 由 worker 领取后走取消收尾（不调用 receive）。
     */
    public GrantTaskCancelResult cancel(String taskNo) {
        CouponGrantTaskPO task = loadAccessibleTask(taskNo);
        boolean accepted = taskMapper.markCanceling(task.getId()) == 1;
        if (accepted) {
            log.info("grant task cancel accepted: taskNo={}, appId={}", taskNo, task.getAppId());
            return new GrantTaskCancelResult(taskNo, CouponGrantTaskPO.STATUS_CANCELING, true);
        }
        // CAS 打不中=任务已终态或已在取消：回当前状态，调用方据此收敛
        Integer current = taskMapper.selectStatus(task.getId());
        return new GrantTaskCancelResult(taskNo,
                current == null ? task.getStatus() : current, false);
    }

    /**
     * 失败项重发受理：对**终态完成**（SUCCESS；FAILED 若未来启用同理）的任务把全部
     * FAIL item 重置回 PENDING，worker 下一轮自然领取重开后的 chunk 继续发放。
     *
     * <p>幂等键不变（grant-{taskNo}-{seq}）：SUCCESS item 不参与重置；FAIL 路径
     * 从未实际发过券（发券成功只会走 SUCCESS 记账），重发不会重复发放。补库存后
     * 售罄转成功、取消的任务明确拒绝——"完成态才可重试"与 cancel 的终态闸门对称。
     */
    public GrantTaskRetryResult retryFailed(String taskNo) {
        CouponGrantTaskPO task = loadAccessibleTask(taskNo);
        if (task.getStatus() != CouponGrantTaskPO.STATUS_SUCCESS
                && task.getStatus() != CouponGrantTaskPO.STATUS_FAILED) {
            // 0/1 进行中没到结算点、4/5 取消族不可复活：都打回状态冲突，
            // 消息里带当前 status，调用方能区分"取消过"和"还在跑"
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "仅已完成任务可重试失败项，当前 status=" + task.getStatus());
        }
        int retried = txService.retryFailedInTx(task.getId());
        if (retried == 0) {
            // 没有 FAIL item 可重置：上一受理已重开（任务在 RUNNING 重跑）或本就零失败。
            // 回当前实时状态而不是方法开头的快照——重开后的任务此刻多半已是 RUNNING
            Integer current = taskMapper.selectStatus(task.getId());
            return new GrantTaskRetryResult(taskNo,
                    current == null ? task.getStatus() : current, false, 0);
        }
        log.info("grant task retry-failed accepted: taskNo={}, retried={}, appId={}",
                taskNo, retried, task.getAppId());
        return new GrantTaskRetryResult(taskNo, CouponGrantTaskPO.STATUS_RUNNING, true, retried);
    }

    /**
     * 失败明细 CSV 导出：任意状态可查（进行中也能导出已失败的部分，运营不必等终态）。
     * 输出 = UTF-8 BOM + 表头 + 每个 FAIL item 一行（按 seq 升序）。
     *
     * <p>BOM 与单元格转义（公式注入防护 + RFC 4180）抽到了 {@link CsvExportSupport}，
     * 与兑换码明文导出共用——两处各留一份副本迟早漂移。
     */
    public byte[] exportFailuresCsv(String taskNo) {
        CouponGrantTaskPO task = loadAccessibleTask(taskNo);
        // CSV 走 ResponseEntity<byte[]> 不经过 Result 工厂，resultCode 没人写——
        // 不补这句，审计表 code 列为 NULL、治理指标把成功导出记成 rejected
        com.newcoupon.common.context.RequestContext.current()
                .resultCode(com.newcoupon.common.ErrorCode.SUCCESS.getCode());
        List<CouponGrantItemPO> failed = itemMapper.selectFailedByTask(task.getId());
        StringBuilder csv = new StringBuilder(64 * (failed.size() + 1));
        csv.append("userId,seq,failCode,failMsg,attempt");
        for (CouponGrantItemPO item : failed) {
            csv.append("\r\n")
                    .append(CsvExportSupport.csvCell(String.valueOf(item.getUserId()))).append(',')
                    .append(CsvExportSupport.csvCell(String.valueOf(item.getSeq()))).append(',')
                    .append(CsvExportSupport.csvCell(item.getFailCode() == null
                            ? "" : String.valueOf(item.getFailCode()))).append(',')
                    .append(CsvExportSupport.csvCell(item.getFailMsg() == null
                            ? "" : item.getFailMsg())).append(',')
                    .append(CsvExportSupport.csvCell(item.getAttempt() == null
                            ? "0" : String.valueOf(item.getAttempt())));
        }
        return CsvExportSupport.withBom(csv.toString());
    }

    // ------------------------------------------------------------------
    // 内部：校验与组装
    // ------------------------------------------------------------------

    /** 按任务号取行 + 归属校验；任务不存在/越权在这里统一拦截。 */
    private CouponGrantTaskPO loadAccessibleTask(String taskNo) {
        CouponGrantTaskPO task = taskMapper.selectByTaskNo(taskNo);
        if (task == null) {
            throw new BizException(ErrorCode.GRANT_TASK_NOT_FOUND, "taskNo=" + taskNo);
        }
        AppOwnership.assertAccessible(task.getAppId(), "grant-task:" + taskNo);
        return task;
    }

    /** userIds 去重与规模校验；返回保持受理顺序的去重列表（seq 由该顺序决定）。 */
    private List<Long> dedupUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "userIds 不能为空");
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId == null || userId < 1) {
                throw new BizException(ErrorCode.PARAM_ERROR, "userId 必须为正整数");
            }
            distinct.add(userId);
        }
        if (distinct.size() > MAX_USERS_PER_TASK) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "userIds 去重后不得超过 " + MAX_USERS_PER_TASK);
        }
        return new ArrayList<>(distinct);
    }

    /**
     * 按模板当前分片数 N 把用户分到 shard：user_id % N（与 C1 路由同算法，
     * 同 shard 的 item 聚成一个 chunk）。TreeMap 让 chunk_no 与 shard_no
     * 同序递增，排障时"chunk 3 = 分片 3"可直接对上。
     */
    private Map<Integer, List<IndexedUser>> groupByShard(List<Long> users, int shardCount) {
        Map<Integer, List<IndexedUser>> byShard = new java.util.TreeMap<>();
        for (int i = 0; i < users.size(); i++) {
            long userId = users.get(i);
            int shard = (int) Math.floorMod(userId, (long) shardCount);
            byShard.computeIfAbsent(shard, k -> new ArrayList<>()).add(new IndexedUser(userId, i));
        }
        return byShard;
    }

    /** 组装任务/分片/明细三组 PO（未落库；task.id 与子表关联在事务内回填）。 */
    private TaskArtifacts buildArtifacts(String appId, String taskNo, GrantTaskCreateRequest req,
                                         List<Long> users, CouponTemplate template) {
        int shardCount = template.getStockShardCount();
        if (shardCount < 1) {
            shardCount = FALLBACK_SHARD_COUNT;
        }
        Map<Integer, List<IndexedUser>> byShard = groupByShard(users, shardCount);

        CouponGrantTaskPO task = new CouponGrantTaskPO();
        task.setTaskNo(taskNo);
        task.setAppId(appId);
        task.setTemplateId(req.templateId());
        task.setGrantType(1);
        task.setSourceType(1);
        task.setRequestId(req.requestId());
        task.setTotalCount((long) users.size());
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        task.setSkipCount(0L);
        // 守恒起点：pending=total，此后每次 item 迁移都是"pending-1、对应桶+1"
        task.setPendingCount((long) users.size());
        task.setStatus(CouponGrantTaskPO.STATUS_PENDING);
        // 定时开始时间原样落库（过去时间=补跑，到期门在 chunk 领取 SQL 里判——见
        // CouponGrantChunkMapper.selectClaimable）；null=立即执行，行为与 1A 完全一致
        task.setScheduleTime(req.scheduleTime());
        task.setRateLimit(0);
        task.setMaxConcurrency(1);
        task.setOperator(req.operator());
        task.setVersion(0L);

        List<CouponGrantChunkPO> chunks = new ArrayList<>();
        List<CouponGrantItemPO> items = new ArrayList<>();
        int chunkNo = 0;
        for (Map.Entry<Integer, List<IndexedUser>> entry : byShard.entrySet()) {
            CouponGrantChunkPO chunk = new CouponGrantChunkPO();
            chunk.setChunkNo(chunkNo);
            chunk.setShardNo(entry.getKey());
            chunk.setTotalCount((long) entry.getValue().size());
            chunk.setStatus(CouponGrantChunkPO.STATUS_PENDING);
            chunks.add(chunk);
            for (IndexedUser indexed : entry.getValue()) {
                CouponGrantItemPO item = new CouponGrantItemPO();
                item.setChunkNo(chunkNo);
                item.setUserId(indexed.userId());
                item.setSeq(indexed.seq());
                item.setStatus(CouponGrantItemPO.STATUS_PENDING);
                items.add(item);
            }
            chunkNo++;
        }
        return new TaskArtifacts(task, chunks, items);
    }

    /** 建任务的产物三元组。 */
    record TaskArtifacts(CouponGrantTaskPO task,
                         List<CouponGrantChunkPO> chunks,
                         List<CouponGrantItemPO> items) {
    }

    /** 用户在任务内的受理序号（=item.seq，幂等键的一部分）。 */
    record IndexedUser(long userId, int seq) {
    }
}
