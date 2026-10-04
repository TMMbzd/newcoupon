package com.newcoupon.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppPlanApplyMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppPlanApplyPO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预案的套用与恢复（S2 第 10 步）。
 *
 * <p><b>套用 = 快照 + 一次批量写</b>：
 * <ol>
 *   <li>先把每个键的<b>旧值</b>（含"原本没有"）记下来——这是恢复的依据；</li>
 *   <li>再走 {@link TenantConfigService#put} 一次写入全部目标值
 *       （它保证"先全部校验、再整体写入"，且值没变的键自动跳过）；</li>
 *   <li>最后记一条套用记录，并把该租户上一条生效记录置为"被取代"。</li>
 * </ol>
 * 全程单事务：要么全成、要么全不成——**不允许出现"套了一半"的中间态**，
 * 那会让运维在故障中面对一个谁也不认识的状态。
 *
 * <p><b>恢复 = 快照补偿回滚（CAS）</b>：逐键判断"当前值是否仍等于预案写入的值"，
 * 是则回滚到快照值（快照为 null 则删除），否则<b>跳过并报冲突</b>。
 * 为什么不是"无条件回滚"：这期间运维可能手工改过某个键——<b>那是人的意图，机器不该覆盖它</b>。
 * 把冲突报告出来，决定权交回人手里，比闷头覆盖安全。
 *
 * <p><b>为什么不复用键级审计（{@code open_app_config_log}）做恢复</b>：
 * 键级日志能回答"这个键被谁改成什么"，但回答不了"我当时套的是哪份预案、
 * 这份预案<b>应该</b>改哪几个键"——没有这个上下文，就分不清
 * "某个键现在这个样子是预案写的"还是"是人后来改的"。
 */
@Service
public class PlanApplyService {

    private static final Logger log = LoggerFactory.getLogger(PlanApplyService.class);

    /** 生效中：一个租户同时最多一条 */
    public static final int STATUS_ACTIVE = 1;
    /** 已恢复（快照回滚完成） */
    public static final int STATUS_REVERTED = 2;
    /** 被新的套用取代（还没恢复，但已经不是"当前预案"了） */
    public static final int STATUS_SUPERSEDED = 3;

    private final OpenAppPlanApplyMapper applyMapper;
    private final TenantConfigService tenantConfig;
    private final OpenAppRepository openAppRepository;
    private final ObjectMapper objectMapper;

    public PlanApplyService(OpenAppPlanApplyMapper applyMapper,
                            TenantConfigService tenantConfig,
                            OpenAppRepository openAppRepository,
                            ObjectMapper objectMapper) {
        this.applyMapper = applyMapper;
        this.tenantConfig = tenantConfig;
        this.openAppRepository = openAppRepository;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------

    /**
     * 套用预案。
     *
     * @param reason 备注——演练脚本会传 {@code drill}，让事后复盘能区分演练与真实操作
     */
    @Transactional
    public ApplyResult apply(String appId, SwitchPlan plan, String operator, String reason) {
        requireApp(appId);

        // ① 快照：记下每个键"套用前是什么"（null = 原本没有这一项）
        Map<String, String> snapshot = new LinkedHashMap<>();
        Map<String, String> targets = new LinkedHashMap<>();
        int changed = 0;
        for (SwitchPlan.PlanEntry entry : plan.entries()) {
            String old = tenantConfig.getRaw(appId, entry.configKey());
            snapshot.put(entry.configKey(), old);
            targets.put(entry.configKey(), entry.targetValue());
            if (!Objects.equals(old, entry.targetValue())) {
                changed++;
            }
        }

        // ② 写配置：put 内部保证"先全部校验、再整体写入"，值没变的键自动跳过（幂等）
        tenantConfig.put(appId, targets, operator);

        // ③ 一个租户同时只有一份"当前预案"：旧的置为被取代（它的快照不再可用，
        //    因为配置已经被本次套用改写——想回到更早的状态要靠人工，这是有意的简化）
        supersedeActive(appId);

        // ④ 记本次套用
        OpenAppPlanApplyPO po = new OpenAppPlanApplyPO();
        po.setAppId(appId);
        po.setPlanLevel(plan.name());
        po.setOperator(operator);
        po.setReason(reason);
        po.setSnapshot(toJson(snapshot));
        po.setStatus(STATUS_ACTIVE);
        applyMapper.insert(po);

        log.info("预案已套用：appId={} plan={} 键数={}（实际变化 {}）operator={} reason={}",
                appId, plan.name(), targets.size(), changed, operator, reason);
        return new ApplyResult(plan.name(), plan.label(), changed, targets.size());
    }

    /**
     * 恢复：按当前生效预案的快照做 CAS 回滚。
     *
     * <p>单事务：先写回、再删除，中间不会留下"删了没写回"的半恢复状态
     * （本项目真的遇到过会话被断电中止——半恢复态正是那种场景的产物）。
     */
    @Transactional
    public RevertResult revert(String appId, String operator, String reason) {
        requireApp(appId);

        OpenAppPlanApplyPO active = findActive(appId);
        if (active == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该租户当前没有生效中的预案，无需恢复：" + appId);
        }
        SwitchPlan plan = SwitchPlan.valueOf(active.getPlanLevel());
        Map<String, String> snapshot = fromJson(active.getSnapshot());

        Map<String, String> toRestore = new LinkedHashMap<>();
        List<String> toDelete = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();

        for (SwitchPlan.PlanEntry entry : plan.entries()) {
            String current = tenantConfig.getRaw(appId, entry.configKey());
            if (!Objects.equals(current, entry.targetValue())) {
                // 当前值已经不等于预案写入的值 → 期间有人（或另一份预案）改过它。
                // **那是人的意图，不覆盖**——跳过并报告，让人来决定。
                conflicts.add(entry.configKey());
                continue;
            }
            String original = snapshot.get(entry.configKey());
            if (original == null) {
                toDelete.add(entry.configKey());   // 原本没有这一项 → 删掉它（回落默认）
            } else {
                toRestore.put(entry.configKey(), original);
            }
        }

        String revertOperator = operator + "(预案恢复)";
        if (!toRestore.isEmpty()) {
            tenantConfig.put(appId, toRestore, revertOperator);
        }
        for (String key : toDelete) {
            tenantConfig.remove(appId, key, revertOperator);
        }

        active.setStatus(STATUS_REVERTED);
        active.setRevertedAt(LocalDateTime.now());
        applyMapper.updateById(active);

        log.info("预案已恢复：appId={} plan={} 写回={} 删除={} 冲突={} reason={}",
                appId, plan.name(), toRestore.size(), toDelete.size(), conflicts, reason);
        return new RevertResult(plan.name(), toRestore.size(), toDelete.size(), conflicts);
    }

    /** 该租户当前生效的预案（没有则 null） */
    public OpenAppPlanApplyPO findActive(String appId) {
        List<OpenAppPlanApplyPO> list = applyMapper.selectList(new QueryWrapper<OpenAppPlanApplyPO>()
                .eq("app_id", appId).eq("status", STATUS_ACTIVE).orderByDesc("id").last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 套用历史（管理面展示；最近的在最前） */
    public List<ApplyView> history(String appId) {
        return applyMapper.selectList(new QueryWrapper<OpenAppPlanApplyPO>()
                        .eq("app_id", appId).orderByDesc("id").last("LIMIT 50"))
                .stream()
                .map(po -> new ApplyView(po.getId(), po.getPlanLevel(), po.getOperator(),
                        po.getReason(), po.getStatus(), po.getAppliedAt(), po.getRevertedAt()))
                .toList();
    }

    // ------------------------------------------------------------------

    private void supersedeActive(String appId) {
        OpenAppPlanApplyPO active = findActive(appId);
        if (active != null) {
            active.setStatus(STATUS_SUPERSEDED);
            applyMapper.updateById(active);
        }
    }

    private void requireApp(String appId) {
        if (appId == null || appId.isBlank() || openAppRepository.findApp(appId).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "应用不存在：" + appId);
        }
    }

    private String toJson(Map<String, String> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            // 快照是本服务的核心数据（没有它就无法恢复），序列化失败必须让整个套用失败
            throw new BizException(ErrorCode.SYSTEM_ERROR, "预案快照序列化失败：" + e.getMessage());
        }
    }

    private Map<String, String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "预案快照解析失败（数据可能被手工改坏）：" + e.getMessage());
        }
    }

    /** @param changedKeys 实际发生变化的键数（值本来就等于目标值的不计） */
    public record ApplyResult(String level, String label, int changedKeys, int totalKeys) {
    }

    /** @param conflicts 因"当前值已被改动"而跳过回滚的键——需要人工确认 */
    public record RevertResult(String level, int restoredKeys, int deletedKeys, List<String> conflicts) {
    }

    public record ApplyView(Long id, String level, String operator, String reason,
                            Integer status, LocalDateTime appliedAt, LocalDateTime revertedAt) {
    }
}
