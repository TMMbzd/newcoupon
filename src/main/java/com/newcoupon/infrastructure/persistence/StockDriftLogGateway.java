package com.newcoupon.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.newcoupon.infrastructure.persistence.mapper.StockDriftLogMapper;
import com.newcoupon.infrastructure.persistence.po.StockDriftLogPO;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 漂移日志记录器（V3 表，ADR-0008）——对账的"黑匣子"。
 *
 * <p>为什么独立成类而不塞进对账服务：落库失败绝不能阻断告警/修复主流程
 * （日志写挂了不能让对账跟着挂），调用方 catch 住即可；且"只追加"的写模型
 * 与业务仓储的领域语义不同，不混入端口。
 */
@Component
public class StockDriftLogGateway {

    /** 一条漂移事实：读数快照 + 判定动作 + 归因（字段含义见 V3 表注释） */
    /** ADR-0009 V8：redisVoid=守恒第四态读数；disposition=处置回填（true/false误报/manual，可空）。
     *  redisIssued 已废弃（issued 键族删除）——历史构造调用传 0，列保留 NULL 语义由 mapper 映射。 */
    public record DriftEvent(long templateId,
                             long redisIssued, long redisDbi, long redisPending,
                             long dbTotal, long dbIssued, Long couponCount,
                             Long redisStock, long delta,
                             String action, String detail,
                             Long redisVoid, String disposition) {

        /** 兼容构造（无 void/disposition）——存量调用点 */
        public DriftEvent(long templateId, long redisIssued, long redisDbi, long redisPending,
                          long dbTotal, long dbIssued, Long couponCount, Long redisStock, long delta,
                          String action, String detail) {
            this(templateId, redisIssued, redisDbi, redisPending, dbTotal, dbIssued,
                    couponCount, redisStock, delta, action, detail, null, null);
        }
    }

    private final StockDriftLogMapper mapper;

    public StockDriftLogGateway(StockDriftLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(DriftEvent e) {
        StockDriftLogPO po = new StockDriftLogPO();
        po.setTemplateId(e.templateId());
        po.setCheckTime(LocalDateTime.now().withNano(0));
        po.setRedisIssued(e.redisIssued());
        po.setRedisDbi(e.redisDbi());
        po.setRedisPending(e.redisPending());
        po.setDbTotal(e.dbTotal());
        po.setDbIssued(e.dbIssued());
        po.setCouponCount(e.couponCount());
        po.setRedisStock(e.redisStock());
        po.setDelta(e.delta());
        po.setAction(e.action());
        po.setRedisVoid(e.redisVoid());
        po.setDisposition(e.disposition());
        po.setDetail(e.detail());
        mapper.insert(po);
    }

    /** 近 window 内该模板的漂移记录数——"连续漂移"升级人工的依据（A8） */
    public long countSince(long templateId, LocalDateTime since) {
        return mapper.selectCount(new QueryWrapper<StockDriftLogPO>()
                .eq("template_id", templateId)
                .ge("check_time", since));
    }
}
