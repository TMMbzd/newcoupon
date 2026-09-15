package com.newcoupon.infrastructure.persistence;

import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.mapper.CouponReleaseTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponReleaseTaskPO;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 锁定超时任务网关（V7，L5）。注册是唯一写入口约束的落点：
 * 锁券事务内调用 {@link #register}（同事务插任务表——"锁了必有兜底"）；
 * 任何未来产生锁定的新路径必须复用锁券的注册点，禁止旁路直插（T+1 审计兜底）。
 */
@Component
public class CouponReleaseTaskGateway {

    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CouponReleaseTaskMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    public CouponReleaseTaskGateway(CouponReleaseTaskMapper mapper, SnowflakeIdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    /** 锁券事务内注册超时任务：下次扫描时间=占用时间+锁定窗口（编排层算好 deadline 传入） */
    public void register(String usageNo, long couponId, long userId, LocalDateTime deadline) {
        CouponReleaseTaskPO po = new CouponReleaseTaskPO();
        po.setId(idGenerator.next());   // 雪花ID：任务表已分片，自增跨物理表重复
        po.setUsageNo(usageNo);
        po.setCouponId(couponId);
        po.setUserId(userId);
        po.setShard((int) (couponId % 16));
        po.setNextScanTime(deadline);
        po.setStatus(0);
        po.setRetryCount(0);
        mapper.insert(po);
    }

    public List<CouponReleaseTaskPO> scanDueShard(int shardIndex, int shardTotal, int limit) {
        return mapper.scanDueShard(shardIndex, shardTotal, limit);
    }

    public void finish(String usageNo) {
        mapper.finish(usageNo);
    }

    public void backoff(String usageNo) {
        mapper.backoff(usageNo);
    }

    /** 审计补注册（幂等）：漏注册的合同补任务；nextScanTime 传当前时刻（该立即处理） */
    public void registerIfAbsent(String usageNo, long couponId, long userId) {
        mapper.registerIfAbsent(idGenerator.next(), usageNo, couponId, userId,
                (int) (couponId % 16), LocalDateTime.now().format(SQL_TIME));
    }
}
