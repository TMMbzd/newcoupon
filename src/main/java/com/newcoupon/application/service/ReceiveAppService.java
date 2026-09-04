package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.UserCouponRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 领券编排层。
 *
 * <p>⚠️ 本类【严禁】添加 @Transactional（含类级注解）——ADR-0004 增补5：
 * 若本类开启事务，内层 CouponIssueTxService 的事务会加入外层共享事务；
 * 撞唯一索引时 Spring 把共享事务标记 rollback-only，本类 catch 之后外层提交
 * 会抛 UnexpectedRollbackException——幂等重放从"当成功返回"退化成 500。
 * 正确结构：外层无事务做编排与冲突翻译，内层独立事务做三写。
 *
 * <p>职责：①幂等快查（性能优化，安全靠唯一索引——两层关系见 study 讲解）
 * ②调用事务服务 ③DuplicateKey 冲突分类（回滚后重查，不解析异常消息，ADR-0004 增补3）。
 */
@Service
public class ReceiveAppService {

    private static final Logger log = LoggerFactory.getLogger(ReceiveAppService.class);

    private final UserCouponRepository userCouponRepository;
    private final CouponIssueTxService issueTxService;

    public ReceiveAppService(UserCouponRepository userCouponRepository,
                             CouponIssueTxService issueTxService) {
        this.userCouponRepository = userCouponRepository;
        this.issueTxService = issueTxService;
    }

    public ReceiveResult receive(ReceiveRequest req) {
        // ① 幂等快查：90% 的重放在这里直接返回，不进事务不碰库存。
        //    注意：这不是安全依赖——并发窗口内两个同 requestId 会同时穿过这里，
        //    最后防线是 uk_idem 唯一索引（下方 catch 分支）。
        Optional<UserCoupon> fast = userCouponRepository.findByIdempotentKey(
                req.userId(), req.requestId());
        if (fast.isPresent()) {
            return toDuplicatedResult(fast.get(), req);
        }

        // ② 进事务：扣库存 → 落券 → 记事件
        try {
            UserCoupon issued = issueTxService.issue(req, LocalDateTime.now());
            return ReceiveResult.of(issued, false);
        } catch (DuplicateKeyException e) {
            // ③ 内层事务已在代理处回滚（库存扣减一并撤销），此处做冲突分类
            return classifyAfterRollback(req, e);
        }
    }

    /**
     * 冲突分类（ADR-0004 增补3）：不解析异常消息里的索引名——
     * MySQL 方言依赖，且一次 INSERT 可能同时违反 uk_idem 与 uk_user_template 两个唯一索引
     * （MySQL 只报其中一个名字）。改为按确定性顺序重查：
     * 先幂等键（带 templateId 一致性校验），再用户+模板，都未命中按系统异常兜底。
     */
    private ReceiveResult classifyAfterRollback(ReceiveRequest req, DuplicateKeyException e) {
        log.warn("duplicate key on receive, classify after rollback: userId={}, requestId={}, cause={}",
                req.userId(), req.requestId(), e.getMessage());   // 索引名仅作日志辅助

        Optional<UserCoupon> byIdem = userCouponRepository.findByIdempotentKey(
                req.userId(), req.requestId());
        if (byIdem.isPresent()) {
            return toDuplicatedResult(byIdem.get(), req);
        }
        if (userCouponRepository.findByUserAndTemplate(req.userId(), req.templateId()).isPresent()) {
            // 并发/重复用不同 requestId 领同一张券：第一次已成功，本次按限领拒绝
            throw new BizException(ErrorCode.USER_LIMIT_EXCEEDED);
        }
        // 理论不可达（撞了唯一索引却查不到对应行）；防御性兜底，绝不静默吞掉
        log.error("duplicate key but no matching row found: userId={}, requestId={}",
                req.userId(), req.requestId());
        throw new BizException(ErrorCode.SYSTEM_ERROR, "领券冲突分类失败，请联系管理员");
    }

    /**
     * 幂等命中的统一出口：必须校验 templateId 一致——幂等键绑定的是"一次领取意图"，
     * 同一 requestId 换个模板再来（如前端复用陈旧 requestId），不是重放而是冲突，拒绝。
     */
    private ReceiveResult toDuplicatedResult(UserCoupon existing, ReceiveRequest req) {
        if (existing.getTemplateId() != req.templateId()) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                    "该 requestId 已用于 templateId=" + existing.getTemplateId() + " 的领取，不可用于其他模板");
        }
        return ReceiveResult.of(existing, true);
    }
}
