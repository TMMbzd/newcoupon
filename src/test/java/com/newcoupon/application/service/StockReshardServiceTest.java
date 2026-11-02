package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockAdjustLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * StockReshardService 的前置门禁单测（不启动 Spring/DB）：
 *
 * <ul>
 *   <li>V38 未应用（旧唯一键仍存在）→ 拒绝，且不冻结、不读模板/分片；</li>
 *   <li>目标片数超安全上限 → 参数错误，不抢锁。</li>
 * </ul>
 *
 * <p>为什么不在集成测试里重建旧索引：共享容器里已有多个模板的多版本行，
 * 重建 {@code uk_tpl_shard(template_id, shard_no)} 会因重复 (template, shard) 直接失败。
 * 门禁本体是"查 information_schema"，用 mock DataSource 精确覆盖分支即可。
 */
class StockReshardServiceTest {

    private final RedisScanLock scanLock = mock(RedisScanLock.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final CachedTemplateReader cachedTemplateReader = mock(CachedTemplateReader.class);
    private final RedisIssueGate issueGate = mock(RedisIssueGate.class);
    private final TemplateMapper templateMapper = mock(TemplateMapper.class);
    private final CouponStockShardMapper shardMapper = mock(CouponStockShardMapper.class);
    private final CouponStockAdjustLogMapper logMapper = mock(CouponStockAdjustLogMapper.class);
    private final CouponIssueTaskMapper taskMapper = mock(CouponIssueTaskMapper.class);
    private final StockReshardTxService txService = mock(StockReshardTxService.class);
    private final DataSource rawDataSource = mock(DataSource.class);

    private StockReshardService service() {
        return new StockReshardService(scanLock, redis, cachedTemplateReader, issueGate,
                templateMapper, shardMapper, logMapper, taskMapper, txService, rawDataSource);
    }

    @Test
    @DisplayName("旧唯一键仍存在（V38 未应用）：拒绝 reshard，且不冻结/不写库")
    void legacyUniqueIndexPresent_refusesBeforeFreeze() throws Exception {
        long templateId = 9L;
        String requestId = "legacy-gate";
        when(scanLock.tryLock(eq("c:lock:stock-reshard:9"), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(logMapper.selectByRequest(templateId, requestId)).thenReturn(null);

        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rawDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(1L);   // uk_tpl_shard 还在

        assertThatThrownBy(() -> service().reshard(templateId, 4, requestId, "it"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);

        verifyNoInteractions(templateMapper, shardMapper, txService);
    }

    @Test
    @DisplayName("目标片数超过 1024：参数错误，不抢锁/不碰 Redis")
    void targetShardCountOverSafetyCap_rejected() {
        assertThatThrownBy(() -> service().reshard(9L, 1025, "too-many", "it"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);

        verifyNoInteractions(scanLock, rawDataSource, templateMapper);
    }
}
