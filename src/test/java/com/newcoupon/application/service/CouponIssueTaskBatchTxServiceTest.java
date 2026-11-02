package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.newcoupon.application.service.CouponIssueTaskBatchTxService.BatchItem;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 裸 JDBC 批量写的“开关/资格”分支测试。
 *
 * <p>批量快路径是性能优化，不是唯一写通道：配置关闭、模板有日限、批内用户重复时，
 * 必须回到单任务事务。这里用 Mockito 验证“走哪条路”，真实落库正确性由
 * {@code AsyncIssueWorkerIntegrationTest} 与 {@code AsyncIssueWorkerRawBulkDisabledIntegrationTest} 覆盖。
 *
 * <p>两层的分流位置不同，测试也分开：
 * <ul>
 *   <li>{@code processAll} 是整批入口，调用方（Processor）必须已判定满足批量条件；
 *       不满足时这里抛守卫异常，而不是悄悄换一条更慢的路；</li>
 *   <li>{@code process} 是单分片组入口，自己判断资格；不满足时逐条回退。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("异步发放·批量协调器回退分支")
class CouponIssueTaskBatchTxServiceTest {

    @Mock
    private CouponIssueTaskTxService taskTxService;
    @Mock
    private CouponIssueTaskRawBulkWriter rawBulkWriter;

    @Test
    @DisplayName("开关关闭：整批逐条走单任务事务，绝不碰裸写")
    void disabledSwitchUsesPerTaskTransactions() {
        CouponIssueTaskBatchTxService service =
                new CouponIssueTaskBatchTxService(taskTxService, rawBulkWriter, false);
        List<BatchItem> items = items(template(1L, 0, 0), 3, false);

        service.processAll(items, "owner-1");

        verifyPerTask(items, "owner-1");
        verifyNoInteractions(rawBulkWriter);
    }

    @Test
    @DisplayName("开关打开且满足批量条件：整批一次裸写")
    void enabledSwitchUsesRawWriterForEligibleBatch() {
        CouponIssueTaskBatchTxService service =
                new CouponIssueTaskBatchTxService(taskTxService, rawBulkWriter, true);
        List<BatchItem> items = items(template(1L, 0, 0), 3, false);

        service.processAll(items, "owner-2");

        verify(rawBulkWriter).write(items, "owner-2");
        verifyNoInteractions(taskTxService);
    }

    @Test
    @DisplayName("整批入口的守卫：调用方传了日限任务时拒绝，不误走裸写")
    void processAllRejectsIneligibleDailyLimitBatch() {
        CouponIssueTaskBatchTxService service =
                new CouponIssueTaskBatchTxService(taskTxService, rawBulkWriter, true);
        List<BatchItem> items = items(template(1L, 1, 0), 2, false);

        assertThatThrownBy(() -> service.processAll(items, "owner-3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-bulk-eligible");
        verifyNoInteractions(taskTxService, rawBulkWriter);
    }

    @Test
    @DisplayName("单分片组：模板有日限时逐条走单任务事务")
    void shardGroupFallsBackOnTemplateDailyLimit() {
        CouponIssueTaskBatchTxService service =
                new CouponIssueTaskBatchTxService(taskTxService, rawBulkWriter, true);
        List<BatchItem> items = items(template(1L, 1, 0), 2, false);

        service.process(items, "owner-4");

        verifyPerTask(items, "owner-4");
        verifyNoInteractions(rawBulkWriter);
    }

    @Test
    @DisplayName("单分片组：批内用户重复时逐条走单任务事务")
    void shardGroupFallsBackOnDuplicateUser() {
        CouponIssueTaskBatchTxService service =
                new CouponIssueTaskBatchTxService(taskTxService, rawBulkWriter, true);
        List<BatchItem> items = items(template(1L, 0, 0), 2, true);

        service.process(items, "owner-5");

        verifyPerTask(items, "owner-5");
        verifyNoInteractions(rawBulkWriter);
    }

    @Test
    @DisplayName("同一模板 id 的两个实例仍视为同模板（Long 不能比引用）")
    void sameTemplateIdOnDifferentInstancesIsStillEligible() {
        CouponIssueTaskBatchTxService service =
                new CouponIssueTaskBatchTxService(taskTxService, rawBulkWriter, true);
        CouponTemplate first = template(1000L, 0, 0);
        CouponTemplate second = template(1000L, 0, 0);
        List<BatchItem> items = List.of(
                new BatchItem(task(1L, 100L, 1000L), first, 1, first.getStockLayoutVersion(), 0),
                new BatchItem(task(2L, 101L, 1000L), second, 1, second.getStockLayoutVersion(), 1));

        service.processAll(items, "owner-same-id");

        verify(rawBulkWriter).write(items, "owner-same-id");
        verifyNoInteractions(taskTxService);
    }

    @Test
    @DisplayName("模板有每人日限：整批入口守卫拒绝，不能绕过用户日计数")
    void processAllRejectsIneligiblePerUserDailyLimitBatch() {
        CouponIssueTaskBatchTxService service =
                new CouponIssueTaskBatchTxService(taskTxService, rawBulkWriter, true);
        List<BatchItem> items = items(template(1L, 0, 1), 2, false);

        assertThatThrownBy(() -> service.processAll(items, "owner-per-user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-bulk-eligible");
        verifyNoInteractions(taskTxService, rawBulkWriter);
    }

    @Test
    @DisplayName("单分片组：模板有每人日限时逐条走单任务事务")
    void shardGroupFallsBackOnPerUserDailyLimit() {
        CouponIssueTaskBatchTxService service =
                new CouponIssueTaskBatchTxService(taskTxService, rawBulkWriter, true);
        List<BatchItem> items = items(template(1L, 0, 1), 2, false);

        service.process(items, "owner-per-user-group");

        verifyPerTask(items, "owner-per-user-group");
        verifyNoInteractions(rawBulkWriter);
    }

    @Test
    @DisplayName("单分片组且满足条件：一次裸写")
    void shardGroupUsesRawWriterWhenEligible() {
        CouponIssueTaskBatchTxService service =
                new CouponIssueTaskBatchTxService(taskTxService, rawBulkWriter, true);
        List<BatchItem> items = items(template(1L, 0, 0), 2, false);

        service.process(items, "owner-6");

        verify(rawBulkWriter).write(items, "owner-6");
        verifyNoInteractions(taskTxService);
    }

    @Test
    @DisplayName("单分片组入口同样遵守开关：关闭时不走裸写")
    void shardGroupHonorsDisabledSwitch() {
        CouponIssueTaskBatchTxService service =
                new CouponIssueTaskBatchTxService(taskTxService, rawBulkWriter, false);
        List<BatchItem> items = items(template(1L, 0, 0), 2, false);

        service.process(items, "owner-7");

        verifyPerTask(items, "owner-7");
        verifyNoInteractions(rawBulkWriter);
    }

    private void verifyPerTask(List<BatchItem> items, String owner) {
        for (BatchItem item : items) {
            verify(taskTxService).process(
                    eq(item.task()), eq(item.template()), eq(item.seq()),
                    eq(item.layoutVersion()), eq(item.shardNo()), eq(owner));
        }
    }

    private static List<BatchItem> items(CouponTemplate template, int count, boolean sameUser) {
        List<BatchItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long userId = sameUser ? 42L : 100L + i;
            CouponIssueTaskPO task = task(1L + i, userId, template.getId());
            items.add(new BatchItem(task, template, i + 1,
                    template.getStockLayoutVersion(), i % 16));
        }
        return items;
    }

    private static CouponIssueTaskPO task(long id, long userId, long templateId) {
        CouponIssueTaskPO task = new CouponIssueTaskPO();
        task.setId(id);
        task.setUserId(userId);
        task.setTemplateId(templateId);
        task.setRequestId("req-" + id);
        task.setSeq(1);
        return task;
    }

    private static CouponTemplate template(long id, long dailyLimit, int perUserDailyLimit) {
        CouponTemplate.Creation creation = CouponTemplate.Creation.builder()
                .templateCode("BT-" + id)
                .activityId(1L)
                .name("批量开关测试券")
                .couponType(CouponType.NO_THRESHOLD)
                .faceValue(100L)
                .threshold(0L)
                .totalCount(100L)
                .perUserLimit(5)
                .dailyLimit(dailyLimit)
                .perUserDailyLimit(perUserDailyLimit)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.now().minusDays(1))
                .validEnd(LocalDateTime.now().plusDays(1))
                .build();
        return CouponTemplate.reconstitute(id, creation, 0L,
                ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }
}
