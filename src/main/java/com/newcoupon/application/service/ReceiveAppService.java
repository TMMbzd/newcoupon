package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.CancelCause;
import com.newcoupon.infrastructure.redis.RedisStockGateway.DeductCommand;
import com.newcoupon.infrastructure.redis.RedisStockGateway.DeductResult;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 领券编排层（ADR-0006 + ADR-0008 恒等式记账形态）。
 *
 * <p>⚠️ 本类【严禁】@Transactional（rollback-only 退化，study/04a 第 4 部分）。
 *
 * <p>领券全流程（Redis 优先，每步失败都有明确去向）：
 * <pre>
 * ① 读模板 + assertReceivable（内存快检；状态竞态由 DB CAS 兜底）
 * ② Redis Lua 闸门：五重校验原子扣减；同一脚本内完成复式记账
 *    （issued+1 且 在途令牌入 pend ZSET——恒等式借方与在途同增）
 *    -7 未初始化 → 随机退避后条件 SET 懒重建，重试一次
 * ③ DB 事务（双防线扣账 + 落券(带seq) + 事件）
 *    成功 → confirm：dbi+1 与令牌出清【一个 Lua 原子配对】（在途转贷方）
 *    失败 → 分类先行（A1'），按分类取消记账：
 *      售罄   → cancel(SOLD_OUT)：钉零 + 立售罄标志（B4）+ 回滚限领计数
 *      下线   → cancel(OFFLINE)：钉零 + 回滚
 *      瞬时   → cancel(TRANSIENT)：库存 INCR 回加 + 回滚
 *    令牌单次使用语义保证 confirm/cancel 天然幂等（重复/迟到全部 no-op）
 * </pre>
 *
 * <p>confirm 失败（Redis 抖动等）不阻断响应：DB 已提交、券已发放，用户结果已定；
 * 恒等式会以 dbi 滞后形态暴露，由对账按 confirm-lost 模式修复（ADR-0008）。
 */
@Service
public class ReceiveAppService {

    private static final Logger log = LoggerFactory.getLogger(ReceiveAppService.class);
    private static final int IDEM_TTL_SEC = 600;
    private static final int DAY_TTL_SEC = 172800;   // 48h：跨天滚动 key 的清理兜底

    private final UserCouponRepository userCouponRepository;
    private final TemplateRepository templateRepository;
    private final CouponIssueTxService issueTxService;
    private final RedisStockGateway stockGateway;
    private final StockReconcileService reconcileService;

    public ReceiveAppService(UserCouponRepository userCouponRepository,
                             TemplateRepository templateRepository,
                             CouponIssueTxService issueTxService,
                             RedisStockGateway stockGateway,
                             StockReconcileService reconcileService) {
        this.userCouponRepository = userCouponRepository;
        this.templateRepository = templateRepository;
        this.issueTxService = issueTxService;
        this.stockGateway = stockGateway;
        this.reconcileService = reconcileService;
    }

    public ReceiveResult receive(ReceiveRequest req) {
        // ① 读模板 + 可领性校验（M3 将替换为二级缓存；编排层无事务=每次读最新快照）
        CouponTemplate template = templateRepository.findById(req.templateId())
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + req.templateId()));
        template.assertReceivable(LocalDateTime.now());

        // ② Redis Lua 闸门——幂等检查内置于脚本（EXISTS 幂等key → -8 回 DB 确认）。
        //    设计取舍（2026-09-07 评审修正）：入口幂等只用 Redis——抢购洪峰中"首次领取"
        //    是多数派，每请求先打一次 DB SELECT 白耗连接池（实测 2000 并发已撞池）；
        //    重复请求是少数派，-8 后回 DB 确认即可。DB uk_idem 唯一索引仍是终审。
        DeductCommand cmd = new DeductCommand(req.templateId(), req.userId(), req.requestId(),
                template.getPerUserLimit(), template.getPerUserDailyLimit(), template.getDailyLimit(),
                DAY_TTL_SEC, IDEM_TTL_SEC);
        DeductResult d = stockGateway.deduct(cmd);
        if (d.code() == -7) {
            // A2'：清库风暴中几十个请求同时撞 -7。随机退避把"同时再击"摊开；
            // 条件 SET 保证只有一个请求真正重建，其余 no-op；其后的 DECR 天然保留。
            backoffQuietly();
            reconcileService.lazyRebuildStock(req.templateId());
            d = stockGateway.deduct(cmd);
            if (d.code() == -7) {
                throw new BizException(ErrorCode.DEGRADED, "库存服务暂不可用，请稍后重试");
            }
        }
        if (!d.success()) {
            return handleGateRejected(req, d);
        }

        // ③ DB 事务；任何失败都按分类取消记账（cancel 内部令牌门控保证幂等）。
        try {
            UserCoupon issued = issueTxService.issue(req, template, d.seq(), LocalDateTime.now());
            if (!stockGateway.confirm(cmd)) {
                // 令牌已被消费（重复 confirm）或对账清理——正常幂等路径；
                // 真正的 Redis 故障会抛异常进 RuntimeException 分支由对账兜底
                log.debug("confirm no-op (token consumed): templateId={}, requestId={}",
                        req.templateId(), req.requestId());
            }
            return ReceiveResult.of(issued, false);
        } catch (DuplicateKeyException e) {
            stockGateway.cancel(cmd, CancelCause.TRANSIENT);   // 计数还原+库存回加，令牌出清
            return classifyAfterRollback(req, e);
        } catch (StockDeductRejectedException e) {
            // A1' 分类先行：先取新快照判断"DB 为什么拒"，再选取消形态——
            // 售罄要钉零（防 N1 过补偿自持环），瞬时异常要回加（库存没真耗尽）
            throw classifyDeductRejectAndCancel(req, cmd);
        } catch (RuntimeException e) {
            stockGateway.cancel(cmd, CancelCause.TRANSIENT);   // 落库失败：不还=库存泄漏（少发）
            throw e;
        }
    }

    /** A2'：-7 重试前的随机退避 50~200ms——削平"清库后同一批请求同时再击 DB"的雷击 */
    private void backoffQuietly() {
        try {
            Thread.sleep(50 + ThreadLocalRandom.current().nextInt(150));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 闸门拒绝分类 ----------

    /**
     * Redis 闸门拒绝（-1/-2/-3/-6/-8，-3 含 soldout 标志短路）。注意：idem 键 TTL(10分钟)
     * 过期后的"陈旧重放"会走到限领/售罄分支——先查一次 DB 幂等键，命中则按重放处理。
     */
    private ReceiveResult handleGateRejected(ReceiveRequest req, DeductResult d) {
        if (d.code() == -8) {   // Redis 幂等命中
            return duplicatedOrConflict(req);
        }
        // 陈旧重放检查
        Optional<UserCoupon> old = userCouponRepository.findByIdempotentKey(req.userId(), req.requestId());
        if (old.isPresent()) {
            return toDuplicatedResult(old.get(), req);
        }
        throw switch (d.code()) {
            case -3 -> new BizException(ErrorCode.STOCK_OUT);
            case -2 -> new BizException(ErrorCode.USER_LIMIT_EXCEEDED);
            case -6 -> new BizException(ErrorCode.USER_LIMIT_EXCEEDED, "已达每日限领上限");
            case -1 -> new BizException(ErrorCode.DAILY_LIMIT_EXCEEDED);
            default -> new BizException(ErrorCode.SYSTEM_ERROR, "库存闸门未知返回: " + d.code());
        };
    }

    /** Redis 幂等命中：回 DB 确认（带 templateId 一致性校验） */
    private ReceiveResult duplicatedOrConflict(ReceiveRequest req) {
        return userCouponRepository.findByIdempotentKey(req.userId(), req.requestId())
                .map(u -> toDuplicatedResult(u, req))
                .orElseThrow(() -> new BizException(ErrorCode.REQUEST_DUPLICATED,
                        "请求正在处理中，请勿重复提交"));
    }

    // ---------- DB 冲突分类（回滚+取消记账后，事务外新快照） ----------

    private ReceiveResult classifyAfterRollback(ReceiveRequest req, DuplicateKeyException e) {
        log.warn("duplicate key on receive: userId={}, requestId={}, cause={}",
                req.userId(), req.requestId(), e.getMessage());
        Optional<UserCoupon> byIdem = userCouponRepository.findByIdempotentKey(req.userId(), req.requestId());
        if (byIdem.isPresent()) {
            return toDuplicatedResult(byIdem.get(), req);   // uk_idem：重放/并发命中
        }
        // uk_user_seq：Redis 计数丢失导致序号重复 → 该用户实际已达限领（DB 是最后防线）
        throw new BizException(ErrorCode.USER_LIMIT_EXCEEDED);
    }

    /**
     * DB 条件扣账 0 行：事务外取新快照分类（study/04a RR 教训），<b>先分类再取消</b>（A1'）。
     * 分类决定 cancel 形态——取消的 stock 修复语义（钉零 vs 回加）依赖"为什么被拒"。
     * 返回待抛出的业务异常（先完成取消记账，再抛给调用方）。
     */
    private BizException classifyDeductRejectAndCancel(ReceiveRequest req, DeductCommand cmd) {
        CouponTemplate fresh = templateRepository.findById(req.templateId()).orElse(null);
        if (fresh == null) {
            stockGateway.cancel(cmd, CancelCause.OFFLINE);
            return new BizException(ErrorCode.TEMPLATE_NOT_FOUND);
        }
        if (fresh.getIssuedCount() >= fresh.getTotalCount()) {
            // 售罄：钉零 + 立标志。Redis 余量已无意义——回加只会重启"过闸→被拒→回加"
            // 的自持环（N1）；标志让后续请求在 Lua 第一行就短路，DB 售罄 QPS 归 1。
            stockGateway.cancel(cmd, CancelCause.SOLD_OUT);
            return new BizException(ErrorCode.STOCK_OUT);
        }
        if (fresh.getApproveStatus() != ApproveStatus.APPROVED
                || fresh.getPublishStatus() != PublishStatus.PUBLISHED) {
            // 下线/审批回退：同样钉零（模板停了，余量不该再放行；重新上线走回补协议）
            stockGateway.cancel(cmd, CancelCause.OFFLINE);
            return new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE, "模板状态已变化（被下线/审批回退）");
        }
        // 瞬时（扣账竞态输给并发/锁等待后的重试）：库存没真耗尽，正常回加
        stockGateway.cancel(cmd, CancelCause.TRANSIENT);
        return new BizException(ErrorCode.DEGRADED, "库存扣账未成功，请稍后重试");
    }

    /** 幂等命中出口：templateId 必须一致（requestId 绑定的是一次领取意图） */
    private ReceiveResult toDuplicatedResult(UserCoupon existing, ReceiveRequest req) {
        if (existing.getTemplateId() != req.templateId()) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                    "该 requestId 已用于 templateId=" + existing.getTemplateId() + " 的领取，不可用于其他模板");
        }
        return ReceiveResult.of(existing, true);
    }
}
