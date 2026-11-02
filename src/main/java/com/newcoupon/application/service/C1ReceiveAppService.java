package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.DeadlineGuard;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * C1 领券编排（ADR-0018）：DB 条件更新是库存权威，Redis 只做限流/幂等快路径/售罄提示。
 *
 * <p>本类【严禁】@Transactional：它做的是“读快照 + 重试 + 分类”，真正的事务在
 * {@link CouponIssueTxService#issueC1}。把事务收在 TxService 里，才能保证
 * “库存扣减 + 落券 + 事件 + outbox + 过期任务”同成同败。
 *
 * <p>为什么 C1 不用 Redis 库存闸门：库存权威在 DB 分片行。Redis 只影响性能和限流；
 * 它丢了、挂了、被 FLUSHALL 了，都不会超发。代价是每笔请求多一次 DB 条件更新，
 * 这也是 C1 的容量边界（1k–2k 同步，5k 走异步）。
 */
@Service
public class C1ReceiveAppService {

    private static final Logger log = LoggerFactory.getLogger(C1ReceiveAppService.class);

    /** 售罄提示的短 TTL：只挡“已经确认售罄”的洪峰，库存调整/重上线会主动删除。 */
    private static final Duration SOLD_OUT_TTL = Duration.ofSeconds(10);
    private static final String SOLD_OUT_KEY_PREFIX = "c:so:{";

    /** 同一用户并发领券时，序号冲突后的有界重试次数。 */
    private static final int MAX_SEQ_ATTEMPTS = 3;

    private final UserCouponRepository userCouponRepository;
    private final TemplateRepository templateRepository;
    private final CachedTemplateReader cachedTemplateReader;
    private final CouponIssueTxService issueTxService;
    private final CouponStockShardMapper shardMapper;
    private final StringRedisTemplate redis;
    private final DependencyCircuitBreakers breakers;

    public C1ReceiveAppService(UserCouponRepository userCouponRepository,
                               TemplateRepository templateRepository,
                               CachedTemplateReader cachedTemplateReader,
                               CouponIssueTxService issueTxService,
                               CouponStockShardMapper shardMapper,
                               StringRedisTemplate redis,
                               DependencyCircuitBreakers breakers) {
        this.userCouponRepository = userCouponRepository;
        this.templateRepository = templateRepository;
        this.cachedTemplateReader = cachedTemplateReader;
        this.issueTxService = issueTxService;
        this.shardMapper = shardMapper;
        this.redis = redis;
        this.breakers = breakers;
    }

    /**
     * C1 领券入口。外层已完成模板可领性/归属/预算前置；这里只处理 C1 的库存、限领、
     * 幂等和错误分类。
     */
    public ReceiveResult receive(ReceiveRequest req, CouponTemplate template) {
        try {
            return doReceiveWithLayoutRetry(req, template);
        } catch (DataAccessException e) {
            // 读限领/读幂等键等前置查询失败：翻译成可重试的系统繁忙，不把 Spring 异常漏给接口层
            log.error("C1 receive DB failure: templateId={}, userId={}",
                    req.templateId(), req.userId(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
    }

    /**
     * 布局版本 fence 的同步重试：缓存快照与事务内 DB 版本不一致时，事务已在
     * 计数/扣库存之前回滚；失效模板缓存、重读 DB、按新版本重路由**一次**。
     * 第二次仍变化（reshard 与请求持续赛跑）返回 5001，让客户端稍后重试。
     */
    private ReceiveResult doReceiveWithLayoutRetry(ReceiveRequest req, CouponTemplate template) {
        try {
            return doReceive(req, template);
        } catch (StockLayoutChangedException first) {
            log.warn("C1 stock layout changed before deduction; reloading template once: "
                            + "templateId={}, expectedVersion={}, actualVersion={}",
                    req.templateId(), first.expectedVersion(), first.actualVersion());
            cachedTemplateReader.invalidate(req.templateId());
            CouponTemplate fresh = db(() -> templateRepository.findById(req.templateId()))
                    .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND,
                            "id=" + req.templateId()));
            try {
                return doReceive(req, fresh);
            } catch (StockLayoutChangedException again) {
                cachedTemplateReader.invalidate(req.templateId());
                log.error("C1 stock layout changed twice; rejecting request: templateId={}, "
                                + "expectedVersion={}, actualVersion={}",
                        req.templateId(), again.expectedVersion(), again.actualVersion());
                throw new BizException(ErrorCode.DEGRADED, "库存布局已变化，请刷新后重试");
            }
        }
    }

    private ReceiveResult doReceive(ReceiveRequest req, CouponTemplate template) {
        long templateId = req.templateId();

        // ① 幂等先于售罄提示：已经成功过的请求重试，必须返回原券，不能被“当前已售罄”覆盖。
        Optional<UserCoupon> existing = findByIdempotentKey(req, template.getAppId());
        if (existing.isPresent()) {
            return toDuplicatedResult(existing.get(), req);
        }

        // ② 售罄提示：只对“已确认售罄”的模板做快速失败；放在限领查询之前，
        //    否则售罄洪峰仍会每请求打两次 DB（限领/日限），提示就失去意义。
        if (soldOutHint(templateId)) {
            throw new BizException(ErrorCode.STOCK_OUT);
        }

        // ③ 每人总限在下面的 MAX(seq)+1 里判断；每人日限/模板日限在 issueC1 的
        //    V27 计数条件更新里判断。两者都在事务内，不再对 user_coupon/coupon_event 做热 COUNT。

        // ④ 两层有界重试：
        //    - 外层：同一用户并发时 seq 可能撞 uk_user_seq；撞了就看回幂等键，
        //      不是重放就重新取 MAX(seq)+1；
        //    - 内层：从用户的首选片开始，一片一个短事务。某片满就回滚换下一片，
        //      绝不在一个事务里同时探测多片（多片锁交叉会死锁）。
        // 路由使用模板配置的片数，而不是 min(配置, total)：
        // 调整库存可能把 total 降到片数以下，但已建的分片行不会消失；按配置片数探测
        // 才能看到那些 quota 被减到 0 之外仍有余额的片。小 total 时多探测几片，成本可忽略。
        int effective = template.getStockShardCount();
        if (effective < 1) {
            throw new BizException(ErrorCode.DEGRADED, "库存布局片数非法");
        }
        // 快照里的版本号：事务内 DB 复核不匹配会抛 StockLayoutChangedException，
        // 由 receive() 失效缓存、重读、重路由一次。
        int layoutVersion = template.getStockLayoutVersion();
        int start = StockShardPlan.route(req.userId(), effective);
        // 同一次同步请求的重试固定同一个受理日/领取时间：日限归属不因重试跨天而漂移。
        LocalDateTime requestTime = LocalDateTime.now();
        LocalDate statDay = requestTime.toLocalDate();

        for (int attempt = 0; attempt < MAX_SEQ_ATTEMPTS; attempt++) {
            long maxSeq = db(() -> userCouponRepository.maxSeqByUserAndTemplate(req.userId(), templateId));
            long nextSeq = maxSeq + 1;
            if (nextSeq > template.getPerUserLimit()) {
                // 冷路径：异步 gate 预分配的 seq 可能因任务拒绝留下空洞。MAX+1 超限时
                // 不能直接判 3002；先看已占用序号里有没有更小的空位。
                OptionalInt freeSeq = findFreeSeq(req.userId(), templateId, template.getPerUserLimit());
                if (freeSeq.isEmpty()) {
                    throw new BizException(ErrorCode.USER_LIMIT_EXCEEDED);
                }
                nextSeq = freeSeq.getAsInt();
            }
            if (DeadlineGuard.belowFloor(DeadlineGuard.WRITE_FLOOR_MS)) {
                throw new BizException(ErrorCode.REQUEST_BUDGET_EXHAUSTED);
            }

            final int seqToUse = (int) nextSeq;
            boolean retrySeq = false;
            for (int probe = 0; probe < effective; probe++) {
                int shardNo = (int) (((long) start + probe) % effective);
                try {
                    final int targetShard = shardNo;
                    UserCoupon issued = db(() -> issueTxService.issueC1(
                            req, template, seqToUse, layoutVersion, targetShard, requestTime, statDay));
                    clearSoldOutHint(templateId);
                    return ReceiveResult.of(issued, false);
                } catch (StockShardDeductRejectedException e) {
                    // 这一片满了/停发了：事务已回滚，继续下一片；不在当前事务里持有它的锁
                    continue;
                } catch (DuplicateKeyException e) {
                    // 可能是 uk_idem（并发重复）或 uk_user_seq（同用户序号相撞）。
                    // 先查幂等键：命中就是重放；没命中说明是序号相撞，换 seq 重来。
                    Optional<UserCoupon> byIdem = findByIdempotentKey(req, template.getAppId());
                    if (byIdem.isPresent()) {
                        return toDuplicatedResult(byIdem.get(), req);
                    }
                    retrySeq = true;
                    break;
                } catch (ConcurrencyFailureException e) {
                    // 行锁等待/死锁失败：换一个 seq 重来；分片更新本身是幂等候选（失败事务已回滚）
                    log.debug("C1 shard contention, retry: templateId={}, userId={}, shard={}",
                            templateId, req.userId(), shardNo);
                    retrySeq = true;
                    break;
                } catch (BizException e) {
                    // 并发重复请求可能在第一次幂等查询后才提交；日限计数拒绝时先回查一次，
                    // 命中同一 requestId 就返回原券，而不是把已成功的请求误报成 3002/3003。
                    if (e.getErrorCode() == ErrorCode.USER_LIMIT_EXCEEDED
                            || e.getErrorCode() == ErrorCode.DAILY_LIMIT_EXCEEDED) {
                        Optional<UserCoupon> byIdem = findByIdempotentKey(req, template.getAppId());
                        if (byIdem.isPresent()) {
                            return toDuplicatedResult(byIdem.get(), req);
                        }
                    }
                    throw e;
                } catch (DataAccessException e) {
                    log.error("C1 receive DB failure: templateId={}, userId={}",
                            templateId, req.userId(), e);
                    throw new BizException(ErrorCode.SYSTEM_ERROR);
                }
            }
            if (!retrySeq) {
                // 所有片都返回 0 行：真售罄、冻结、下线或布局缺失，交给冷路径分类
                throw classifyShardReject(templateId);
            }
        }
        throw new BizException(ErrorCode.DEGRADED, "领取序号校准中，请稍后重试");
    }

    /** 冷路径：只在 MAX(seq)+1 超过限领时查已占用序号，找最小空洞。 */
    private OptionalInt findFreeSeq(long userId, long templateId, int limit) {
        List<Integer> usedSeqs = db(() ->
                userCouponRepository.findSeqsByUserAndTemplate(userId, templateId));
        return UserSeqAllocator.firstFree(usedSeqs, limit);
    }

    /**
     * 分片条件更新 0 行后的分类。冷路径，允许读 DB 多一次。
     * 顺序：模板状态 → 布局存在 → 是否有活跃片 → 是否有可卖片。
     */
    private BizException classifyShardReject(long templateId) {
        CouponTemplate fresh = templateRepository.findById(templateId).orElse(null);
        if (fresh == null) {
            return new BizException(ErrorCode.TEMPLATE_NOT_FOUND);
        }
        if (fresh.getApproveStatus() != ApproveStatus.APPROVED
                || fresh.getPublishStatus() != PublishStatus.PUBLISHED) {
            return new BizException(ErrorCode.TEMPLATE_NOT_RECEIVABLE, "模板状态已变化（被下线/审批回退）");
        }
        int layoutVersion = fresh.getStockLayoutVersion();
        long rows = shardMapper.countByTemplateVersion(templateId, layoutVersion);
        if (rows == 0) {
            return new BizException(ErrorCode.DEGRADED, "库存布局未初始化，请稍后重试");
        }
        long active = shardMapper.countActive(templateId, layoutVersion);
        if (active == 0) {
            // 模板已发布但 active version 所有分片都停发：管理面冻结/下线中，
            // 或 reshard 冻结窗口；返回可重试的冻结码，不谎报售罄。
            return new BizException(ErrorCode.FROZEN);
        }
        long sellable = shardMapper.countSellable(templateId, layoutVersion);
        if (sellable == 0) {
            setSoldOutHint(templateId);
            return new BizException(ErrorCode.STOCK_OUT);
        }
        return new BizException(ErrorCode.DEGRADED, "库存扣账未成功，请稍后重试");
    }

    private Optional<UserCoupon> findByIdempotentKey(ReceiveRequest req, String appId) {
        return db(() -> userCouponRepository.findByIdempotentKey(req.userId(), appId, req.requestId()));
    }

    private ReceiveResult toDuplicatedResult(UserCoupon existing, ReceiveRequest req) {
        if (existing.getTemplateId() != req.templateId()) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                    "该 requestId 已用于 templateId=" + existing.getTemplateId() + " 的领取，不可用于其他模板");
        }
        return ReceiveResult.of(existing, true);
    }

    private boolean soldOutHint(long templateId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(soldOutKey(templateId)));
        } catch (RuntimeException e) {
            // Redis 故障只影响提示，不影响库存正确性
            return false;
        }
    }

    private void setSoldOutHint(long templateId) {
        try {
            redis.opsForValue().set(soldOutKey(templateId), "1", SOLD_OUT_TTL);
        } catch (RuntimeException e) {
            log.debug("sold-out hint write failed, ignore: templateId={}", templateId, e);
        }
    }

    private void clearSoldOutHint(long templateId) {
        try {
            redis.delete(soldOutKey(templateId));
        } catch (RuntimeException e) {
            log.debug("sold-out hint clear failed, TTL will expire: templateId={}", templateId, e);
        }
    }

    /** 与旧链路共用同一个键：C1 售罄提示/旧库存提示互不干扰，但格式一致便于运维排查。 */
    private static String soldOutKey(long templateId) {
        return SOLD_OUT_KEY_PREFIX + templateId + "}";
    }

    /** 数据库读/事务调用统一过依赖级熔断；业务异常（重复键等）不在熔断器的失败白名单里。 */
    private <T> T db(Supplier<T> call) {
        try {
            return breakers.mysql().executeSupplier(call);
        } catch (CallNotPermittedException e) {
            throw new BizException(ErrorCode.DEGRADED, "数据库依赖暂时不可用，请稍后重试");
        }
    }
}
