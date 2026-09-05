package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.DeductCommand;
import com.newcoupon.infrastructure.redis.RedisStockGateway.DeductResult;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 领券编排层（阶段 1 形态，ADR-0006）。
 *
 * <p>⚠️ 本类【严禁】@Transactional（rollback-only 退化，study/04a 第 4 部分）。
 *
 * <p>领券全流程（每步失败都有明确去向）：
 * <pre>
 * ① DB 幂等快查（终审语义在 DB：跨模板的 requestId 一致性在这里判）
 * ② 读模板 + assertReceivable（内存快检；状态竞态由 DB CAS 兜底）
 * ③ Redis Lua 闸门：五重校验原子扣减（-7 未初始化→懒重建后重试一次）
 * ④ DB 事务（双防线扣账 + 落券(带seq) + 事件）——失败则 Lua 补偿，把 Redis 还原
 * ⑤ 各类冲突 → 回查 DB 分类成准确错误/幂等成功
 * </pre>
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
        // ① 读模板 + 可领性校验（弹2将替换为二级缓存；编排层无事务=每次读最新快照）
        CouponTemplate template = templateRepository.findById(req.templateId())
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + req.templateId()));
        template.assertReceivable(LocalDateTime.now());

        // ② Redis Lua 闸门——幂等检查内置于脚本第①道（EXISTS 幂等key → -8）。
        //    设计取舍（2026-09-07 评审修正）：入口幂等只用 Redis，不再先打 DB 快查——
        //    抢购洪峰中"首次领取"是多数派，每请求一次 DB SELECT 白耗连接池（实测2000并发已撞池）；
        //    重复请求是少数派，-8 后回 DB 确认一次即可（查到→duplicated；查不到→在途1001）。
        //    DB 的 uk_idem 唯一索引仍是终审：TTL过期/并发窗口/跨模板重放全部由它兜底。
        DeductCommand cmd = new DeductCommand(req.templateId(), req.userId(), req.requestId(),
                template.getPerUserLimit(), template.getPerUserDailyLimit(), template.getDailyLimit(),
                DAY_TTL_SEC, IDEM_TTL_SEC);
        DeductResult d = stockGateway.deduct(cmd);
        if (d.code() == -7) {
            reconcileService.rebuildStock(req.templateId());
            d = stockGateway.deduct(cmd);
            if (d.code() == -7) {
                throw new BizException(ErrorCode.DEGRADED, "库存服务暂不可用，请稍后重试");
            }
        }
        if (!d.success()) {
            return handleGateRejected(req, d);
        }

        // ④ DB 事务；任何失败都要把 Redis 扣掉的还回去（补偿）
        try {
            UserCoupon issued = issueTxService.issue(req, template, d.seq(), LocalDateTime.now());
            return ReceiveResult.of(issued, false);
        } catch (DuplicateKeyException e) {
            stockGateway.compensate(cmd);   // 计数/库存还原，幂等键删除让重试可再进
            return classifyAfterRollback(req, e);
        } catch (StockDeductRejectedException e) {
            stockGateway.compensate(cmd);
            return classifyDeductReject(req);
        } catch (RuntimeException e) {
            stockGateway.compensate(cmd);   // 落库失败补偿：不还=库存泄漏（少发）
            throw e;
        }
    }

    // ---------- 闸门拒绝分类 ----------

    /**
     * Redis 闸门拒绝（-1/-2/-3/-6/-8）。注意：idem 键 TTL(10分钟) 过期后的"陈旧重放"
     * 会走到限领/售罄分支——先查一次 DB 幂等键，命中则按重放处理（老请求的正确归宿）。
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

    // ---------- DB 冲突分类（回滚+补偿后，事务外新快照） ----------

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

    /** DB 条件扣账 0 行（事务外新快照分类，study/04a RR 教训） */
    private ReceiveResult classifyDeductReject(ReceiveRequest req) {
        CouponTemplate fresh = templateRepository.findById(req.templateId())
                .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND));
        if (fresh.getIssuedCount() >= fresh.getTotalCount()) {
            throw new BizException(ErrorCode.STOCK_OUT);
        }
        throw new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE, "模板状态已变化（被下线/审批回退）");
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
