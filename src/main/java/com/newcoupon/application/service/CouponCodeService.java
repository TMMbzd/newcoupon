package com.newcoupon.application.service;

import com.newcoupon.application.dto.CouponCodeBatchCreateRequest;
import com.newcoupon.application.dto.CouponCodeBatchCreateResult;
import com.newcoupon.application.dto.CouponCodeRedeemRequest;
import com.newcoupon.application.dto.CouponCodeRedeemResult;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.domain.risk.RiskScene;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.mapper.CouponCodeBatchMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponCodeMapper;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetReservationPO;
import com.newcoupon.infrastructure.persistence.po.CouponCodeBatchPO;
import com.newcoupon.infrastructure.persistence.po.CouponCodePO;
import com.newcoupon.infrastructure.risk.RiskRequestAttributes;
import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 兑换码域编排（V6 阶段 1B）。三件事：建批（生成+哈希+一次性明文）、
 * 明文一次性 CSV 导出、兑换（复用 C1 发券核心）。
 *
 * <p><b>明文生命周期</b>：生成即哈希，DB 只见哈希；明文全程只出现在两处——
 * 建批响应（一次性：幂等重放不回明文）与首次 CSV 导出（export_state CAS 一次性）。
 * 两次出口之间明文暂存在 Redis（TTL 24h）——不落库，过期未导即永久不可导，
 * 这是"明文离开系统后不应再可得"的代价，调用方必须当场持久化响应或尽快导出。
 *
 * <p><b>兑换复用而非重建</b>：发券最终走 {@link C1ReceiveAppService#receive}，
 * 幂等键派生 {@code redeem-{requestId}}——售罄/限领/日限的分类与文案全部沿用。
 * 本域新增的只是"码状态机"（ACTIVE→LOCKED→USED）与防枚举的统一失败口径。
 *
 * <p><b>防枚举口径</b>：码不存在/批停用/码停用/已兑换/兑换中/限流拦截，
 * 对外一律 {@link ErrorCode#CODE_INVALID} 同一句文案；真实原因只进
 * coupon_code_redeem_log。跨应用的码也按"不存在"处理（回 6011 等于承认码存在）。
 *
 * <p>本类不写 @Transactional（编排者持事务会把校验读拖进事务，见
 * application/package-info 分层铁律）；事务在 {@link CouponCodeTxService}。
 */
@Service
public class CouponCodeService {

    private static final Logger log = LoggerFactory.getLogger(CouponCodeService.class);

    /** application.yml 里 CODE_PEPPER 的默认值：检测到它=生产没配，启动告警。 */
    static final String DEV_DEFAULT_PEPPER = "dev-only-coupon-code-pepper-2026";

    /** 明文暂存 Redis 键前缀；值=明文 JSON 数组（与码行 id 同序）。 */
    static final String EXPORT_ESCROW_PREFIX = "c:code:export:";
    /** 明文暂存 TTL：留足"建批→导出"的运营窗口，过期未导即永久不可导。 */
    static final Duration EXPORT_ESCROW_TTL = Duration.ofHours(24);

    /** 兑换限流键前缀（60 秒固定窗口计数）。 */
    static final String RATE_KEY_PREFIX = "c:code:rl:";
    /** 每用户每分钟兑换尝试上限（含失败——防的就是失败探测）。 */
    static final int RATE_LIMIT_PER_MINUTE = 10;

    /** LOCKED 残留兜底阈值（分钟）：超过视为进程崩溃残留，回收回 ACTIVE。 */
    static final int LOCK_REAP_MINUTES = 5;

    /**
     * 原子限流脚本。KEYS[1]=限流键；ARGV[1]=窗口毫秒数。
     * INCR 后值为 1 说明是新窗口的第一个请求，原子地补 PEXPIRE——
     * 分开的 INCR/EXPIRE 在两步之间失败会留下无 TTL 的永久计数器（用户被终身限流）。
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n", Long.class);

    private final TemplateRepository templateRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponCodeBatchMapper batchMapper;
    private final CouponCodeMapper codeMapper;
    private final CouponCodeTxService txService;
    private final C1ReceiveAppService receiveService;
    private final SnowflakeIdGenerator idGenerator;
    private final StringRedisTemplate redis;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final String pepper;
    private final BudgetService budgetService;
    private final RiskDecisionService riskDecisionService;
    private final DependencyCircuitBreakers breakers;

    public CouponCodeService(TemplateRepository templateRepository,
                             UserCouponRepository userCouponRepository,
                             CouponCodeBatchMapper batchMapper,
                             CouponCodeMapper codeMapper,
                             CouponCodeTxService txService,
                             C1ReceiveAppService receiveService,
                             SnowflakeIdGenerator idGenerator,
                             StringRedisTemplate redis,
                             com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                             BudgetService budgetService,
                             RiskDecisionService riskDecisionService,
                             DependencyCircuitBreakers breakers,
                             @Value("${coupon.code.pepper}") String pepper) {
        this.templateRepository = templateRepository;
        this.userCouponRepository = userCouponRepository;
        this.batchMapper = batchMapper;
        this.codeMapper = codeMapper;
        this.txService = txService;
        this.receiveService = receiveService;
        this.idGenerator = idGenerator;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.budgetService = budgetService;
        this.riskDecisionService = riskDecisionService;
        this.breakers = breakers;
        this.pepper = pepper;
        if (DEV_DEFAULT_PEPPER.equals(pepper)) {
            // 不 fail-fast（默认值合法可用），但生产忘配 CODE_PEPPER 时必须有人看见：
            // pepper 一换，存量码的哈希全部对不上——等于整库码作废
            log.warn("coupon.code.pepper 使用 dev 默认值；生产必须注入 CODE_PEPPER。"
                    + "注意：更换 pepper 会使全部存量兑换码失效");
        }
    }

    // ------------------------------------------------------------------
    // 建批
    // ------------------------------------------------------------------

    /**
     * 受理建批：模板校验 → 幂等预查 → 生成（明文+哈希）→ 明文暂存 → 单事务落库。
     *
     * <p>幂等口径照批量发放任务：同 (app, requestId) 重放返回现状，但**不回明文**
     * （明文只在首次响应出现过——重放再回一份违反一次性语义）；
     * 同键换模板=键复用，拒绝并指回原批次。
     */
    public CouponCodeBatchCreateResult createBatch(CouponCodeBatchCreateRequest req) {
        // 模板加载与归属/发布态检查照 GrantTaskService.createTask 的模式：
        // 过滤器知道"你是谁"，模板归属要贴着资源读取点判
        CouponTemplate template = templateRepository.findById(req.templateId())
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND,
                        "id=" + req.templateId()));
        AppOwnership.assertAccessible(template.getAppId(), "template:" + req.templateId());
        if (template.getApproveStatus() != ApproveStatus.APPROVED
                || template.getPublishStatus() != PublishStatus.PUBLISHED) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE, "仅已发布模板可生成兑换码");
        }

        String appId = AppOwnership.ownerForNewResource();
        CouponCodeBatchPO existing = batchMapper.selectByAppAndRequest(appId, req.requestId());
        if (existing != null) {
            // 幂等重放。模板不同=键被复用到另一张券，拒绝（equals：两侧装箱 Long）
            if (!existing.getTemplateId().equals(req.templateId())) {
                throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                        "该 requestId 已用于 templateId=" + existing.getTemplateId()
                                + " 的兑换码批次，不可用于其他模板");
            }
            return new CouponCodeBatchCreateResult(existing.getBatchNo(), existing.getTotal(),
                    List.of(), true);
        }

        SecureRandom random = new SecureRandom();
        for (int attempt = 1; attempt <= 3; attempt++) {
            String batchNo = "CB" + idGenerator.next();
            List<String> plaintexts = generatePlaintexts(random, req.count(), req.codeType());
            // 明文暂存先于落库：暂存失败就不建批（否则库里有一批永远导不出明文的死码）；
            // 落库失败留下的暂存孤儿由 TTL 自然回收，无害
            stashPlaintexts(batchNo, plaintexts);

            CouponCodeBatchPO batch = new CouponCodeBatchPO();
            batch.setBatchNo(batchNo);
            batch.setAppId(appId);
            batch.setTemplateId(req.templateId());
            batch.setCodeType(req.codeType());
            batch.setTotal(req.count());
            batch.setUsed(0);
            batch.setStatus(CouponCodeBatchPO.STATUS_ENABLED);
            batch.setExportState(CouponCodeBatchPO.EXPORT_NOT_DONE);
            batch.setRequestId(req.requestId());
            batch.setOperator(req.operator());

            List<CouponCodePO> codes = new ArrayList<>(plaintexts.size());
            for (String plaintext : plaintexts) {
                CouponCodePO code = new CouponCodePO();
                code.setCodeHash(CouponCodeCodec.hash(plaintext, pepper));
                codes.add(code);
            }
            try {
                txService.createBatchInTx(batch, codes);
            } catch (DuplicateKeyException e) {
                // 并发同 requestId：uk(app_id, request_id) 终审，输家读现状返回（受理幂等）
                CouponCodeBatchPO winner = batchMapper.selectByAppAndRequest(appId, req.requestId());
                if (winner != null) {
                    return new CouponCodeBatchCreateResult(winner.getBatchNo(), winner.getTotal(),
                            List.of(), true);
                }
                // 不是幂等键撞车=code_hash 撞了存量码（跨批撞明文，概率天文级）：
                // 换一批随机码重来。批次行未插入（撞的是码行），无残留
                log.warn("code hash collision with existing code, regenerate: attempt={}", attempt);
                continue;
            }
            log.info("code batch created: batchNo={}, templateId={}, count={}, appId={}",
                    batchNo, req.templateId(), plaintexts.size(), appId);
            return new CouponCodeBatchCreateResult(batchNo, plaintexts.size(), plaintexts, false);
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR, "兑换码生成连续撞哈希，请重试");
    }

    // 随机空间口径：手工码末位是推导出的校验符不贡献随机性，真空间 32^15（二维码 32^26）；
    // SecureRandom 下批内碰撞可忽略，撞 uk 重抽兜底
    private List<String> generatePlaintexts(SecureRandom random, int count, int codeType) {
        HashSet<String> distinct = new HashSet<>(count * 2);
        List<String> ordered = new ArrayList<>(count);
        while (ordered.size() < count) {
            String code = CouponCodeCodec.generate(random, codeType);
            if (distinct.add(code)) {
                ordered.add(code);
            }
        }
        return ordered;
    }

    /** 明文暂存 Redis（TTL 24h）。暂存写失败=导出通道必断，宁可现在拒绝建批。 */
    private void stashPlaintexts(String batchNo, List<String> plaintexts) {
        try {
            redis.opsForValue().set(escrowKey(batchNo),
                    objectMapper.writeValueAsString(plaintexts), EXPORT_ESCROW_TTL);
        } catch (RuntimeException e) {
            // 序列化失败（明文均为字母表字符，理论不可达）或 Redis 故障都走这里：
            // fail-closed——没有暂存就没有导出，一批导不出明文的码是死资产
            log.error("code plaintext escrow write failed: batchNo={}", batchNo, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "码明文暂存失败，请稍后重试");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "码明文序列化失败");
        }
    }

    // ------------------------------------------------------------------
    // 一次性导出
    // ------------------------------------------------------------------

    /**
     * 明文 CSV 导出（一次性）：归属校验 → export_state 0→1 CAS → 读明文暂存 →
     * 拼 CSV → 删暂存。
     *
     * <p>顺序约定：一次性闸门（CAS）在读暂存**之前**——首次导出成功后暂存即删，
     * 后续请求必须稳定收到 4002"只允许一次"，而不是因暂存已删而误报"暂存丢失"。
     * 暂存丢失（TTL 过、从未导出）只发生在首次：CAS 赢了但读不到明文，导出资格
     * 随之烧掉——那本来就是永久失败（明文再也拿不回来），语义自洽。
     * 拼好 CSV 才删暂存：中途失败暂存还在，但 export_state 已 1，重试按已导出拒绝
     * （窗口内明文只离开过零次或一次，不会两次——失败路径人工介入换批次）。
     */
    public byte[] exportCodesCsv(String batchNo) {
        CouponCodeBatchPO batch = batchMapper.selectByBatchNo(batchNo);
        if (batch == null) {
            throw new BizException(ErrorCode.CODE_BATCH_NOT_FOUND, "batchNo=" + batchNo);
        }
        AppOwnership.assertAccessible(batch.getAppId(), "code-batch:" + batchNo);

        // 已导出的快速通道：明文随首次导出即删暂存，二次请求必须在这里就拿到
        // "只允许一次"的明确语义，而不是落到"暂存不存在"的含混文案
        if (batch.getExportState() != null
                && batch.getExportState() == CouponCodeBatchPO.EXPORT_DONE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "码明文只允许导出一次");
        }
        // 顺序是审查修正：先读暂存（Redis 瞬断抛错时 export_state 未动，重试真能重试），
        // 再 CAS 一次性闸门终审（并发双导出只活一个）。原顺序先 CAS 后读——一次瞬时
        // 读失败就把"请稍后重试"变成谎言：重试被 4002 挡死，明文永久无 API 出口。
        List<String> plaintexts = readEscrow(batchNo);
        if (batchMapper.markExported(batch.getId()) != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "码明文只允许导出一次");
        }
        List<CouponCodePO> rows = codeMapper.selectByBatch(batch.getId());
        if (rows.size() != plaintexts.size()) {
            // 防御：暂存与库不同源（理论不可达——同一次生成写两端）。报冲突人工查
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "明文暂存与批次码数不一致，请人工核查: batchNo=" + batchNo);
        }

        StringBuilder csv = new StringBuilder(48 * (rows.size() + 1));
        csv.append("code,user,status,redeemTime");
        for (int i = 0; i < rows.size(); i++) {
            CouponCodePO row = rows.get(i);
            csv.append("\r\n")
                    .append(CsvExportSupport.csvCell(plaintexts.get(i))).append(',')
                    .append(CsvExportSupport.csvCell(row.getUserId() == null
                            ? "" : String.valueOf(row.getUserId()))).append(',')
                    .append(CsvExportSupport.csvCell(String.valueOf(row.getStatus()))).append(',')
                    .append(CsvExportSupport.csvCell(row.getRedeemTime() == null
                            ? "" : row.getRedeemTime().toString()));
        }
        // CSV 走 ResponseEntity<byte[]> 不经过 Result 工厂，resultCode 没人写——
        // 不补这句，审计表 code 列为 NULL、治理指标把成功导出记成 rejected
        RequestContext.current().resultCode(ErrorCode.SUCCESS.getCode());
        try {
            redis.delete(escrowKey(batchNo));
        } catch (RuntimeException e) {
            // 删失败靠 TTL 过期；export_state 已置 1，不会二次导出
            log.warn("code escrow delete failed, TTL will expire: batchNo={}", batchNo, e);
        }
        return CsvExportSupport.withBom(csv.toString());
    }

    /** 读明文暂存。暂存读不到分两种：Redis 故障（可重试）与过期/不存在（永久失败）。 */
    private List<String> readEscrow(String batchNo) {
        String json;
        try {
            json = redis.opsForValue().get(escrowKey(batchNo));
        } catch (RuntimeException e) {
            log.error("code escrow read failed: batchNo={}", batchNo, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "明文暂存暂不可读，请稍后重试");
        }
        if (json == null) {
            // export_state 还是 0 但明文已不在：从未导出过也永远导不出了
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "码明文暂存已过期或不存在，无法导出（明文仅在建批响应与首次导出出现）");
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "码明文暂存损坏，无法导出");
        }
    }

    static String escrowKey(String batchNo) {
        return EXPORT_ESCROW_PREFIX + batchNo;
    }

    // ------------------------------------------------------------------
    // 兑换
    // ------------------------------------------------------------------

    /**
     * 兑换一个码：限流 → 规范化/查码（租户口径）→ 风控决策（V6 4B，REJECT 在 CAS 前不烧码）
     * → CAS 抢占 → receive 发券 → 结算。
     *
     * <p><b>幂等重放路径</b>：码已 USED 时按 {@code redeem-{requestId}} 查
     * user_coupon——命中说明"上次兑换其实成功了（响应丢失）"，返回原券
     * duplicated=true；未命中才是真的被别人兑过/用完，统一文案。
     *
     * <p><b>已知边界（注释如实声明）</b>：崩窗内"券已发用户 A、码未置 USED"被
     * 兜底回收回 ACTIVE 后，用户 B 兑走同码会再发一张券给 B、码归 B——A 的券
     * 不损失（幂等键兜住不双发），仅码的归因瑕疵，非资损。
     */
    public CouponCodeRedeemResult redeem(CouponCodeRedeemRequest req) {
        if (overRateLimit(AppOwnership.currentAppId(), req.userId())) {
            // 不提示限流：多一种失败文案=多一个探测信号（限流只在流水里可见）。
            // 限流放在最前（连形态校验也计数）：垃圾码探测不碰 DB 也必须被计数，
            // 否则"只对合法形态码限流"等于给探测者留了免费通道
            throw unifiedInvalid(req, null, null, "rate_limited");
        }
        String normalized = CouponCodeCodec.normalize(req.code());
        if (!CouponCodeCodec.wellFormed(normalized)) {
            throw unifiedInvalid(req, null, null, "malformed");
        }
        CouponCodePO code = codeMapper.selectByHash(CouponCodeCodec.hash(normalized, pepper));
        if (code == null) {
            throw unifiedInvalid(req, null, null, "not_found");
        }
        CouponCodeBatchPO batch = batchMapper.selectById(code.getBatchId());
        String callerApp = AppOwnership.currentAppId();
        // 跨应用的码视同不存在：6011 会承认"码存在但不归你"，给探测者留了分辨手段
        if (batch == null) {
            throw unifiedInvalid(req, null, code.getId(), "batch_missing");
        }
        if (callerApp != null && !callerApp.equals(batch.getAppId())) {
            throw unifiedInvalid(req, null, code.getId(), "cross_app");
        }
        if (batch.getStatus() != CouponCodeBatchPO.STATUS_ENABLED) {
            throw unifiedInvalid(req, batch.getId(), code.getId(), "batch_disabled");
        }
        if (code.getStatus() == CouponCodePO.STATUS_DISABLED) {
            throw unifiedInvalid(req, batch.getId(), code.getId(), "code_disabled");
        }
        if (code.getStatus() == CouponCodePO.STATUS_USED) {
            return replayOrInvalid(req, code, batch);
        }
        if (code.getStatus() == CouponCodePO.STATUS_LOCKED) {
            // 他人正在兑换中（或崩窗残留未到回收时限）：与其它失败同文案
            throw unifiedInvalid(req, batch.getId(), code.getId(), "locked");
        }

        // V6 4B 风控决策（scene=RECEIVE，4A 遗留边界）：**抢占 CAS 之前**——被拒的兑换
        // 不烧码（码保持 ACTIVE，解除名单后可重兑）。幂等豁免（审查 C2 同款）：码已 USED
        // 的合法重试在上方 replayOrInvalid 已提前返回；这里再兜同 requestId 已发过券的
        // 重放（跨码复用 requestId）——已成立的兑换事实不被风控改写成 7003。
        // 决策段（名单/日志=MySQL）包进 mysql 依赖级熔断（4A C8 语义，7003 不计失败）。
        if (riskDecisionService.enabled(batch.getAppId())) {
            try {
                breakers.dependency(DependencyCircuitBreakers.MYSQL).executeSupplier(() -> {
                    boolean replayed = userCouponRepository.findByIdempotentKey(
                            req.userId(), batch.getAppId(), redeemIdemKey(req.requestId()))
                            .isPresent();
                    if (replayed) {
                        return null;
                    }
                    RiskDecisionService.RiskDecision risk = riskDecisionService.decide(
                            new RiskContext(RiskScene.RECEIVE, batch.getAppId(), req.userId(),
                                    RiskRequestAttributes.deviceId(),
                                    RiskRequestAttributes.clientIp(), req.requestId()));
                    if (risk.action() == RiskAction.REJECT) {
                        throw new BizException(ErrorCode.RISK_REJECTED);
                    }
                    return null;
                });
            } catch (CallNotPermittedException e) {
                log.warn("依赖熔断打开，兑换风控决策快速失败：codeId={}", code.getId());
                throw new BizException(ErrorCode.SYSTEM_ERROR, "依赖暂时不可用（风控决策），请稍后重试");
            }
        }

        // ACTIVE：抢占 CAS。1000 并发同码只有 1 个赢家，输家全部统一文案
        if (codeMapper.casLock(code.getId()) != 1) {
            throw unifiedInvalid(req, batch.getId(), code.getId(), "cas_lost");
        }
        CouponTemplate template = templateRepository.findById(batch.getTemplateId()).orElse(null);
        if (template == null) {
            // 模板被物理删除（理论不可能）：码回池 + 失败流水 + 原样报错（不属防枚举面）
            txService.reactivateWithLog(code.getId(), batch.getId(), callerApp, req.userId(),
                    req.requestId(), ErrorCode.TEMPLATE_NOT_FOUND.getCode(), "模板不存在");
            throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND);
        }
        // 预算租约（V6 阶段 1C）：同同步领取的口径——活动开预算才预占一张，先于库存。
        // 幂等重放（同 requestId 已有券）不占：那张券的预算在首次兑换时已确认。
        // 预占放在 try 内：3009 走 BizException 出口，码必须同样回 ACTIVE
        // （放在 try 外的话预算拒绝会把码永久卡在 LOCKED，直到超时回收）
        CouponBudgetReservationPO budgetLease = null;
        // 幂等预查只在预算闸门开着时才做：它是一次真实的 user_coupon SELECT，
        // 放在闸门外会让"预算关=发放链路不多一条 SQL"的回滚承诺失效（审查发现）。
        // 但预占本身必须留在 try 内：3009 要走 BizException 出口把码回 ACTIVE
        boolean budgetOn = budgetService.gateOn(template);
        boolean replayed = budgetOn && userCouponRepository.findByIdempotentKey(
                req.userId(), template.getAppId(), redeemIdemKey(req.requestId())).isPresent();
        try {
            if (budgetOn && !replayed) {
                budgetLease = budgetService.tryReserveSingle(template,
                        CouponBudgetReservationPO.BIZ_CODE, req.requestId());
            }
            // 发券复用 C1：幂等/限领/库存/日限全部沿用既有链路。
            // 幂等键 redeem-{requestId} 派生自 requestId——同码同人重试返回原券；
            // 兜底回收后的重兑也在这一步命中幂等自愈（码回 USED、券不双发）
            ReceiveResult result = receiveService.receive(
                    // V43 事件归因：兑换链路标 CODE，单据背景=码批次（batchNo 运营可读，
                    // 比内部 codeId 好用——"这批码发出了哪些券"按 biz_id 一查即得）
                    new ReceiveRequest(redeemIdemKey(req.requestId()), req.userId(),
                            batch.getTemplateId(), CouponEvent.BizContext.SOURCE_CODE,
                            CouponEvent.BizContext.BIZ_TYPE_CODE, batch.getBatchNo()),
                    template);
            if (result.duplicated()) {
                // 幂等命中的券可能是**别的码**在同 requestId 下发的（调用方跨码复用）——
                // 本码没有对应任何新券，绝不能置 USED/used+1（否则码被无声烧掉、
                // used 与实际发券数失衡）。返回原券（幂等承诺成立）+ 本码回池；
                // 真正的同码自愈（崩窗后重兑）走的也是这条：拿到原券、码回 ACTIVE
                // ——没发过券的码本来就不该是 USED，这比"补记 USED"更守恒。
                txService.reactivateWithLog(code.getId(), batch.getId(), callerApp, req.userId(),
                        req.requestId(), ErrorCode.REQUEST_DUPLICATED.getCode(),
                        "duplicated_reactivate:命中券 id=" + result.couponId()
                                + " 非本码新发，返回原券不结算");
                // duplicated 的券是之前那次兑换发的：预算当时已确认，本次预占释放
                budgetService.settleSingle(budgetLease, false);
                budgetLease = null;
                return new CouponCodeRedeemResult(batch.getBatchNo(), result.couponId(),
                        result.templateCode(), result.faceValue(), result.validEnd(), true);
            }
            txService.markRedeemedWithLog(code.getId(), batch.getId(), callerApp,
                    req.userId(), req.requestId(), result.couponId());
            // 新发的券：预占确认成消耗（一发一收）
            budgetService.settleSingle(budgetLease, true);
            budgetLease = null;
            return new CouponCodeRedeemResult(batch.getBatchNo(), result.couponId(),
                    result.templateCode(), result.faceValue(), result.validEnd(),
                    result.duplicated());
        } catch (BizException e) {
            // 售罄/限领/日限/模板不可领：券没发出去，码回 ACTIVE 允许补库存后再兑；
            // 业务错原样抛给调用方（这类失败与码本身无关，不属于防枚举面）。
            // 预算同因释放（3009 预算耗尽也走这里：码没兑出去，预算不该被吃掉）
            budgetService.releaseSingle(budgetLease);
            budgetLease = null;
            txService.reactivateWithLog(code.getId(), batch.getId(), callerApp, req.userId(),
                    req.requestId(), e.getErrorCode().getCode(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // receive 已把 DataAccess 类翻译成 SYSTEM_ERROR，这里只剩真正的意外。
            // 状态未知（券可能已发）：码**保持 LOCKED** 交给超时回收——此刻盲目回
            // ACTIVE，若券已发就打开"同码他人兑走"的归因窗口，保持 LOCKED 最保守。
            // 预算租约同样保持开口（券可能已发，确认与否未知），交给僵尸预占回收——
            // 盲目释放会在"券已发"时把预算退回池子造成超发
            log.error("code redeem unexpected failure: codeId={}, userId={}",
                    code.getId(), req.userId(), e);
            budgetLease = null;
            txService.insertFailLog(batch.getId(), code.getId(), callerApp, req.userId(),
                    req.requestId(), "system_error:" + e.getClass().getSimpleName());
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
    }

    /** 码已 USED 的分流：本请求是"响应丢失后的合法重试"（回原券）还是迟到者（统一失败）。 */
    private CouponCodeRedeemResult replayOrInvalid(CouponCodeRedeemRequest req,
                                                   CouponCodePO code, CouponCodeBatchPO batch) {
        // 券行 app_id=模板归属（issue 时写 template.getAppId()），查询必须同口径——
        // 内部面建的批 batch.appId=内置应用"1"，拿它查开放应用模板的券必然查不到
        String couponAppId = templateRepository.findById(batch.getTemplateId())
                .map(CouponTemplate::getAppId).orElse(batch.getAppId());
        Optional<UserCoupon> existing = userCouponRepository.findByIdempotentKey(
                req.userId(), couponAppId, redeemIdemKey(req.requestId()));
        if (existing.isPresent()) {
            UserCoupon coupon = existing.get();
            if (coupon.getTemplateId() != batch.getTemplateId()) {
                // 同一 requestId 曾兑过另一张模板的券=键复用，指回原模板（照 receive 的口径）
                throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                        "该 requestId 已用于 templateId=" + coupon.getTemplateId()
                                + " 的兑换，不可用于其他模板");
            }
            return new CouponCodeRedeemResult(batch.getBatchNo(), coupon.getId(),
                    coupon.getTemplateCode(), coupon.getFaceValue(), coupon.getValidEnd(), true);
        }
        throw unifiedInvalid(req, batch.getId(), code.getId(), "used");
    }

    /**
     * 统一失败出口：记一条失败流水（真实原因只进这里），返回**不带任何差异信息**的
     * CODE_INVALID 异常供调用处 throw——所有走这里的失败对外完全同码同文案。
     */
    private BizException unifiedInvalid(CouponCodeRedeemRequest req,
                                        Long batchId, Long codeId, String reason) {
        txService.insertFailLog(batchId, codeId, AppOwnership.currentAppId(),
                req.userId(), req.requestId(), reason);
        return new BizException(ErrorCode.CODE_INVALID);
    }

    /**
     * 60 秒固定窗口限流：每身份 10 次尝试/分钟（含失败）。键=appId+userId——
     * userId 是请求体字段（调用方可控），只按 userId 计数的话枚举者每次探测换一个
     * userId 就永不触阈；appId 在开放面来自验签身份，轮换不动。Redis 故障 fail-open。
     */
    private boolean overRateLimit(String appId, long userId) {
        String identity = (appId == null ? "api" : appId) + ":" + userId;
        try {
            Long n = redis.execute(RATE_LIMIT_SCRIPT, List.of(RATE_KEY_PREFIX + identity), "60000");
            return n != null && n > RATE_LIMIT_PER_MINUTE;
        } catch (RuntimeException e) {
            // 限流是防探测的软闸，不是正确性的一部分：Redis 故障放行（每次尝试仍会查库/查哈希）
            log.warn("code redeem rate limiter unavailable (fail-open): identity={}", identity, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // LOCKED 残留兜底
    // ------------------------------------------------------------------

    /**
     * 回收兑换中超 {@link #LOCK_REAP_MINUTES} 分钟未结算的码（进程崩在 receive 与
     * 结算之间）：一条 UPDATE 回 ACTIVE。调用方有二——GrantTaskWorker 每轮领取前
     * 顺带回收（grant worker 开=兑换码域免费搭车），以及独立调度器
     * {@code CouponCodeReaper}（没开 grant worker 但用兑换码的部署单独打开）。
     */
    public int reapStaleLockedCodes() {
        return codeMapper.reapStaleLocked(LOCK_REAP_MINUTES);
    }

    /** receive 幂等键派生：redeem-{requestId}（与 grant-{taskNo}-{seq} 同一体系）。 */
    static String redeemIdemKey(String requestId) {
        return "redeem-" + requestId;
    }
}
