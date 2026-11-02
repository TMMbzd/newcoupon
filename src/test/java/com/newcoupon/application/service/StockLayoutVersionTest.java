package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.CouponExpireTaskGateway;
import com.newcoupon.infrastructure.persistence.mapper.CouponDailyCounterMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponOutboxMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponUserDailyCounterMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;
import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * V37 布局版本 Slice A 的单元合同：
 *
 * <ul>
 *   <li>stale version 在**任何日计数/库存更新之前**抛 {@link StockLayoutChangedException}；</li>
 *   <li>退役行必须 status=1 且 quota=used；全局 Σquota=total 是守恒前提；</li>
 *   <li>同步入口收到版本变化后失效模板缓存、重读一次并按新版本重路由；</li>
 *   <li>异步 worker 收到版本变化退回 PENDING（不是 REJECTED）。</li>
 * </ul>
 */
class StockLayoutVersionTest {

    private static final long TEMPLATE_ID = 501L;
    private static final long USER_ID = 7L;

    // ------------------------------------------------------------------
    // 热路径 fence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("stale layoutVersion：在日计数/库存更新之前抛出，不扣库存")
    void staleLayoutVersion_throwsBeforeAnyCounterOrStockUpdate() {
        UserCouponRepository userCouponRepository = mock(UserCouponRepository.class);
        TemplateRepository templateRepository = mock(TemplateRepository.class);
        CouponEventRepository eventRepository = mock(CouponEventRepository.class);
        CouponExpireTaskGateway expireTaskGateway = mock(CouponExpireTaskGateway.class);
        CouponStockShardMapper shardMapper = mock(CouponStockShardMapper.class);
        CouponOutboxMapper outboxMapper = mock(CouponOutboxMapper.class);
        CouponUserDailyCounterMapper userDailyCounterMapper = mock(CouponUserDailyCounterMapper.class);
        CouponDailyCounterMapper dailyCounterMapper = mock(CouponDailyCounterMapper.class);

        CouponTemplate current = template(TEMPLATE_ID, 2);
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(current));

        CouponIssueTxService service = new CouponIssueTxService(
                userCouponRepository, templateRepository, eventRepository, expireTaskGateway,
                shardMapper, outboxMapper, userDailyCounterMapper, dailyCounterMapper);

        ReceiveRequest req = new ReceiveRequest("stale-layout", USER_ID, TEMPLATE_ID);
        assertThatThrownBy(() -> service.issueC1(
                req, current, 1, 1, 0, LocalDateTime.now(), LocalDate.now()))
                .isInstanceOf(StockLayoutChangedException.class)
                .hasMessageContaining("expectedVersion=1")
                .hasMessageContaining("actualVersion=2");

        verifyNoInteractions(userDailyCounterMapper, dailyCounterMapper, shardMapper);
    }

    @Test
    @DisplayName("退役行：version<active + status=1 + quota=used 才合法；active 行不按退役规则")
    void retiredRow_mustBeStoppedAndBalanced() {
        CouponStockShardPO retired = shard(1, 1, 7, 7);
        assertThat(StockLayoutInvariants.retiredRowBalanced(retired, 2))
                .as("退役行 quota=used、status=1").isTrue();
        assertThat(StockLayoutInvariants.retiredRowBalanced(shard(1, 1, 8, 7), 2))
                .as("退役行剩余额度未清零").isFalse();
        assertThat(StockLayoutInvariants.retiredRowBalanced(shard(1, 0, 7, 7), 2))
                .as("退役行仍可卖").isFalse();
        assertThat(StockLayoutInvariants.retiredRowBalanced(shard(2, 1, 7, 7), 2))
                .as("active 行不是退役行").isFalse();
        assertThat(StockLayoutInvariants.retiredRowBalanced(shard(3, 1, 7, 7), 2))
                .as("版本号大于 active 是脏数据，不是退役").isFalse();
    }

    @Test
    @DisplayName("全局守恒：Σquota(all versions)=total、Σused(all)=券数、active 行数=N_eff")
    void globalInvariants() {
        assertThat(StockLayoutInvariants.globalQuotaMatches(100, 100)).isTrue();
        assertThat(StockLayoutInvariants.globalQuotaMatches(99, 100)).isFalse();
        assertThat(StockLayoutInvariants.globalUsedMatches(30, 30)).isTrue();
        assertThat(StockLayoutInvariants.globalUsedMatches(31, 30)).isFalse();
        assertThat(StockLayoutInvariants.activeRowCountMatches(8, 8)).isTrue();
        assertThat(StockLayoutInvariants.activeRowCountMatches(7, 8)).isFalse();
        assertThat(StockLayoutInvariants.activeRowCountMatches(1, 0))
                .as("total 很小时至少 1 行").isTrue();
    }

    // ------------------------------------------------------------------
    // 同步重试一次
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同步入口：版本变化 → 失效缓存、重读模板、按新版本重路由一次")
    void syncReceive_retriesOnceAfterCacheInvalidateAndReload() {
        UserCouponRepository userCouponRepository = mock(UserCouponRepository.class);
        TemplateRepository templateRepository = mock(TemplateRepository.class);
        CachedTemplateReader cachedTemplateReader = mock(CachedTemplateReader.class);
        CouponIssueTxService issueTxService = mock(CouponIssueTxService.class);
        CouponStockShardMapper shardMapper = mock(CouponStockShardMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DependencyCircuitBreakers breakers = mock(DependencyCircuitBreakers.class);

        CouponTemplate stale = template(TEMPLATE_ID, 1);
        CouponTemplate fresh = template(TEMPLATE_ID, 2);
        when(breakers.mysql()).thenReturn(CircuitBreaker.ofDefaults("layout-retry"));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(fresh));
        when(userCouponRepository.findByIdempotentKey(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(userCouponRepository.maxSeqByUserAndTemplate(USER_ID, TEMPLATE_ID)).thenReturn(0L);
        when(redis.hasKey(anyString())).thenReturn(false);
        when(issueTxService.issueC1(any(), eq(stale), eq(1), eq(1), anyInt(),
                any(LocalDateTime.class), any(LocalDate.class)))
                .thenThrow(new StockLayoutChangedException(TEMPLATE_ID, 1, 2));
        UserCoupon coupon = UserCoupon.issue(fresh, USER_ID, "layout-retry", 1,
                LocalDateTime.now()).withId(9001L);
        when(issueTxService.issueC1(any(), eq(fresh), eq(1), eq(2), anyInt(),
                any(LocalDateTime.class), any(LocalDate.class)))
                .thenReturn(coupon);

        C1ReceiveAppService service = new C1ReceiveAppService(
                userCouponRepository, templateRepository, cachedTemplateReader,
                issueTxService, shardMapper, redis, breakers);

        ReceiveResult result = service.receive(
                new ReceiveRequest("layout-retry", USER_ID, TEMPLATE_ID), stale);

        assertThat(result.couponId()).isEqualTo(9001L);
        assertThat(result.duplicated()).isFalse();
        verify(cachedTemplateReader).invalidate(TEMPLATE_ID);
        verify(issueTxService).issueC1(any(), eq(fresh), eq(1), eq(2), anyInt(),
                any(LocalDateTime.class), any(LocalDate.class));
    }

    // ------------------------------------------------------------------
    // 异步重试到 PENDING
    // ------------------------------------------------------------------

    @Test
    @DisplayName("异步 worker：版本变化退回 PENDING（markRetry），绝不标记 REJECTED")
    void asyncProcessor_retriesPendingOnLayoutChange() {
        TemplateRepository templateRepository = mock(TemplateRepository.class);
        UserCouponRepository userCouponRepository = mock(UserCouponRepository.class);
        CouponIssueTaskTxService taskTxService = mock(CouponIssueTaskTxService.class);
        CouponIssueTaskBatchTxService batchTxService = mock(CouponIssueTaskBatchTxService.class);
        CouponIssueTaskStatusService statusService = mock(CouponIssueTaskStatusService.class);

        CouponTemplate template = template(TEMPLATE_ID, 2);
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(userCouponRepository.maxSeqByUserAndTemplate(USER_ID, TEMPLATE_ID)).thenReturn(0L);

        CouponIssueTaskPO task = new CouponIssueTaskPO();
        task.setId(77L);
        task.setTemplateId(TEMPLATE_ID);
        task.setUserId(USER_ID);
        task.setAppId("1");
        task.setRequestId("layout-async");
        task.setRetryCount(0);
        when(taskTxService.process(eq(task), eq(template), eq(1), eq(2), anyInt(), eq("owner")))
                .thenThrow(new StockLayoutChangedException(TEMPLATE_ID, 2, 3));

        CouponIssueTaskProcessor processor = new CouponIssueTaskProcessor(
                templateRepository, userCouponRepository, taskTxService, batchTxService,
                statusService, mock(CouponStockShardMapper.class), 5, 1);

        processor.process(task, "owner");

        verify(statusService).markRetry(eq(77L), eq("owner"), any(LocalDateTime.class),
                eq(ErrorCode.SYSTEM_ERROR), contains("stock layout changed"));
        verify(statusService, never()).markRejected(anyLong(), anyString(), any(), anyString());
        verify(statusService, never()).markDead(anyLong(), anyString(), any(), anyString());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static CouponTemplate template(long id, int layoutVersion) {
        return CouponTemplate.reconstitute(id, CouponTemplate.Creation.builder()
                .templateCode("LAYOUT-" + id + "-v" + layoutVersion)
                .activityId(1L)
                .name("布局版本测试券")
                .couponType(CouponType.NO_THRESHOLD)
                .faceValue(100L)
                .threshold(0L)
                .totalCount(100L)
                .perUserLimit(1)
                .dailyLimit(5L)
                .perUserDailyLimit(5)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.now().minusDays(1))
                .validEnd(LocalDateTime.now().plusDays(30))
                .stockShardCount(8)
                .stockLayoutVersion(layoutVersion)
                .build(), 0L, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }

    private static CouponStockShardPO shard(int layoutVersion, int status, long quota, long used) {
        CouponStockShardPO row = new CouponStockShardPO();
        row.setLayoutVersion(layoutVersion);
        row.setStatus(status);
        row.setQuota(quota);
        row.setUsed(used);
        row.setReserved(0L);
        return row;
    }
}
