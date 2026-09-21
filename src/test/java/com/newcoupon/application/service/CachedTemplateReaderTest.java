package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.repository.TemplateRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2c 模板本地缓存的单元合同（红灯先行——CachedTemplateReader 尚不存在时本类编译即红）。
 *
 * <p>三兄弟防护的可观测断言：
 * ① 命中：第二次读不打数据库（委托计数=1）；
 * ② 防穿透：不存在的 id 记负缓存，重复打不通到数据库；
 * ③ 防击穿：过期后的并发读只触发**一次**回源（refreshAfterWrite）；
 * ④ 失效：invalidate 后下一次读重新回源（发布链路的正确性根基）。
 */
class CachedTemplateReaderTest {

    /** 回源计数：每次透到"数据库"（委托）+1——命中/负命中/刷新都从这里观测 */
    private final AtomicInteger loads = new AtomicInteger();


    private CouponTemplate tpl(long id) {
        return CouponTemplate.reconstitute(id,
                CouponTemplate.Creation.builder()
                        .templateCode("T" + id).activityId(1L).name("n")
                        .couponType(CouponType.NO_THRESHOLD).faceValue(500L).threshold(0L)
                        .totalCount(100L).perUserLimit(1)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                0L, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }

    private TemplateRepository countingRepo() {
        return new TemplateRepository() {
            @Override
            public Optional<CouponTemplate> findById(long id) {
                loads.incrementAndGet();
                return id > 0 ? Optional.of(tpl(id)) : Optional.empty();
            }
            @Override
            public CouponTemplate save(CouponTemplate t) { throw new UnsupportedOperationException(); }
            @Override
            public java.util.List<CouponTemplate> findPublished() { throw new UnsupportedOperationException(); }
            @Override
            public boolean casUpdatePublishStatus(long id, PublishStatus from, PublishStatus to) { throw new UnsupportedOperationException(); }
            @Override
            public int casDeductStock(long templateId) { throw new UnsupportedOperationException(); }
        };
    }

    @Test
    @DisplayName("命中：第二次读不打库（loads=1）")
    void secondRead_hitsCache() {
        CachedTemplateReader r = new CachedTemplateReader(countingRepo(),
                Duration.ofSeconds(300).toMillis(), Duration.ofSeconds(200).toMillis(), Duration.ofSeconds(60).toMillis());
        assertThat(r.get(1L)).isPresent();
        assertThat(r.get(1L)).isPresent();
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("防穿透：不存在的 id 记负缓存，重复读不透到库")
    void negativeEntry_penetrationGuard() {
        CachedTemplateReader r = new CachedTemplateReader(countingRepo(),
                Duration.ofSeconds(300).toMillis(), Duration.ofSeconds(200).toMillis(), Duration.ofSeconds(60).toMillis());
        assertThat(r.get(-1L)).isEmpty();      // 负缓存写入
        assertThat(r.get(-1L)).isEmpty();      // 命中负缓存
        assertThat(r.get(-1L)).isEmpty();
        assertThat(loads.get()).isEqualTo(1);  // 只打过一次库
    }

    @Test
    @DisplayName("防击穿：过期后 20 并发读只回源一次（refreshAfterWrite）")
    void afterExpiry_concurrentReads_singleReload() throws Exception {
        CachedTemplateReader r = new CachedTemplateReader(countingRepo(),
                150, 100, 60);
        assertThat(r.get(1L)).isPresent();     // 首次装载
        Thread.sleep(250);                      // 越过刷新线
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                r.get(1L);
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);                       // 等最后一个刷新完成
        assertThat(loads.get()).as("20 个并发读越过刷新线，回源应恰好 1 次（其余拿旧值）").isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("失效：invalidate 后下一次读重新回源（发布链路的正确性根基）")
    void invalidate_forcesReload() {
        CachedTemplateReader r = new CachedTemplateReader(countingRepo(),
                Duration.ofSeconds(300).toMillis(), Duration.ofSeconds(200).toMillis(), Duration.ofSeconds(60).toMillis());
        assertThat(r.get(1L)).isPresent();
        r.invalidate(1L);
        assertThat(r.get(1L)).isPresent();
        assertThat(loads.get()).isEqualTo(2);   // 失效后真的重新读了
    }
}
