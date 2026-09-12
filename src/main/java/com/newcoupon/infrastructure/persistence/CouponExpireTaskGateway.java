package com.newcoupon.infrastructure.persistence;

import com.newcoupon.infrastructure.persistence.mapper.CouponExpireTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponExpireTaskPO;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 过期任务网关（V5，L2）。注册是唯一写入口约束的落点：
 * 领券事务内调用 {@link #register}（同事务插任务表，docs/06 踩坑5——防旁路漏注册）；
 * 未来任何产生券的新路径（补发/客服/直发）必须复用领券的注册点，禁止旁路直插。
 */
@Component
public class CouponExpireTaskGateway {

    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CouponExpireTaskMapper mapper;

    public CouponExpireTaskGateway(CouponExpireTaskMapper mapper) {
        this.mapper = mapper;
    }

    /** 领券事务内注册过期任务：下次扫描时间=券有效期终点（到点该处理） */
    public void register(long couponId, long userId, LocalDateTime validEnd) {
        CouponExpireTaskPO po = new CouponExpireTaskPO();
        po.setCouponId(couponId);
        po.setUserId(userId);
        po.setShard((int) (couponId % 16));
        po.setNextScanTime(validEnd);
        po.setStatus(0);
        po.setRetryCount(0);
        mapper.insert(po);
    }

    public List<CouponExpireTaskPO> scanDue(int limit) {
        return mapper.scanDue(limit);
    }

    public void finish(long couponId) {
        mapper.finish(couponId);
    }

    public void backoff(long couponId) {
        mapper.backoff(couponId);
    }

    /** 审计补注册（幂等）：漏注册的券补任务。nextScanTime 传券的有效期终点，已过期则立即到期可扫 */
    public void registerIfAbsent(long couponId, long userId, LocalDateTime validEnd) {
        mapper.registerIfAbsent(couponId, userId, (int) (couponId % 16), validEnd.format(SQL_TIME));
    }
}
