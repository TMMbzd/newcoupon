package com.newcoupon.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.newcoupon.domain.open.AppLifecycle;
import com.newcoupon.domain.open.OpenApp;
import com.newcoupon.domain.repository.OpenAppRepository;
import java.time.LocalDate;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppMapper;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppSecretMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppPO;
import com.newcoupon.infrastructure.persistence.po.OpenAppSecretPO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 接入方仓储实现（S1）。
 *
 * <p><b>为什么"可用密钥"的时间过滤放在 Java 而不是 SQL 的 NOW()</b>：本项目已有过
 * "容器 UTC / JVM +08 错位 8 小时"的踩坑史（见测试基座的 TZ 注释）。凡是"到点即生效"的判据，
 * 时间源必须单一——这里以 JVM 时间为准，SQL 只做等值与状态的筛选。
 */
@Repository
public class OpenAppRepositoryImpl implements OpenAppRepository {

    private final OpenAppMapper appMapper;
    private final OpenAppSecretMapper secretMapper;

    public OpenAppRepositoryImpl(OpenAppMapper appMapper, OpenAppSecretMapper secretMapper) {
        this.appMapper = appMapper;
        this.secretMapper = secretMapper;
    }

    @Override
    public void insertApp(OpenApp app) {
        OpenAppPO po = new OpenAppPO();
        po.setAppId(app.appId());
        po.setAppName(app.appName());
        po.setStatus(app.status());
        po.setQpsLimit(app.qpsLimit());
        po.setDailyQuota(app.dailyQuota());
        po.setContact(app.contact());
        po.setEnvironment(app.environment());
        po.setLifecycleStatus(app.lifecycle().name());
        appMapper.insert(po);   // appId 撞唯一索引 → DuplicateKeyException，交服务层分类
    }

    @Override
    public Optional<OpenApp> findApp(String appId) {
        OpenAppPO po = appMapper.selectOne(new QueryWrapper<OpenAppPO>().eq("app_id", appId));
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public Optional<OpenApp> findAppForUpdate(String appId) {
        OpenAppPO po = appMapper.selectByAppIdForUpdate(appId);
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public List<OpenApp> findAllApps() {
        return appMapper.selectList(new QueryWrapper<OpenAppPO>().orderByAsc("id"))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public boolean updateLifecycle(String appId, AppLifecycle to,
                                   LocalDateTime closedAt, LocalDate retentionUntil) {
        // 只写状态列与注销两列：closedAt/retentionUntil 仅 CLOSED 迁移带值，其余迁移不带——
        // 且"不带"必须是真的不 SET（条件拼接）而不是 SET null：DELETED 迁移若 SET null
        // 会把 CLOSED 时写下的注销时刻抹掉，审计上等于"注销时间消失"
        UpdateWrapper<OpenAppPO> wrapper = new UpdateWrapper<OpenAppPO>()
                .eq("app_id", appId)
                .set("lifecycle_status", to.name());
        if (closedAt != null) {
            wrapper.set("closed_at", closedAt);
        }
        if (retentionUntil != null) {
            wrapper.set("retention_until", retentionUntil);
        }
        return appMapper.update(null, wrapper) == 1;
    }

    @Override
    public boolean updateStatus(String appId, int status) {
        return appMapper.update(null, new UpdateWrapper<OpenAppPO>()
                .eq("app_id", appId)
                .set("status", status)) == 1;
    }

    @Override
    public void insertSecret(String appId, String cipher, int status, LocalDateTime expireTime) {
        OpenAppSecretPO po = new OpenAppSecretPO();
        po.setAppId(appId);
        po.setSecretCipher(cipher);
        po.setStatus(status);
        po.setExpireTime(expireTime);
        secretMapper.insert(po);
    }

    @Override
    public List<SecretRecord> findUsableSecrets(String appId) {
        LocalDateTime now = LocalDateTime.now();
        return secretMapper.selectList(new QueryWrapper<OpenAppSecretPO>()
                        .eq("app_id", appId)
                        .in("status", SecretRecord.STATUS_CURRENT, SecretRecord.STATUS_ROTATING)
                        .and(w -> w.isNull("expire_time").or().gt("expire_time", now)))
                .stream().map(this::toRecord).toList();
    }

    @Override
    public List<SecretRecord> findAllSecrets(String appId) {
        return secretMapper.selectList(new QueryWrapper<OpenAppSecretPO>()
                        .eq("app_id", appId)
                        .orderByDesc("id"))
                .stream().map(this::toRecord).toList();
    }

    @Override
    public int demoteCurrentToRotation(String appId, LocalDateTime expireTime) {
        return secretMapper.update(null, new UpdateWrapper<OpenAppSecretPO>()
                .eq("app_id", appId)
                .eq("status", SecretRecord.STATUS_CURRENT)
                .set("status", SecretRecord.STATUS_ROTATING)
                .set("expire_time", expireTime));
    }

    @Override
    public int expireAllRotatingSecrets(String appId) {
        // 注销收尾专用：轮换期旧键立即死亡（status→INVALID 且 expire_time 归零），
        // 不等它们自己的 7 天 expire_time——CLOSED 的语义是"这把钥匙串整串作废"
        return secretMapper.update(null, new UpdateWrapper<OpenAppSecretPO>()
                .eq("app_id", appId)
                .eq("status", SecretRecord.STATUS_ROTATING)
                .set("status", SecretRecord.STATUS_INVALID)
                .set("expire_time", LocalDateTime.now()));
    }

    @Override
    public boolean updateEnvironment(String appId, String environment) {
        // 只在 activate（沙箱晋升）一条路径调用；账单/资金边界按本列实时判定
        return appMapper.update(null, new UpdateWrapper<OpenAppPO>()
                .eq("app_id", appId)
                .set("environment", environment)) == 1;
    }

    @Override
    public boolean invalidateSecret(long secretId) {
        return secretMapper.update(null, new UpdateWrapper<OpenAppSecretPO>()
                .eq("id", secretId)
                .set("status", SecretRecord.STATUS_INVALID)) == 1;
    }

    private OpenApp toDomain(OpenAppPO po) {
        // lifecycle 容错解析：库里值不在枚举里（理论不可达——只有本服务写这一列）时
        // 按 null 上抛，由调用方 fail-closed（开放面 6013），绝不默认放行
        AppLifecycle lifecycle = AppLifecycle.parse(po.getLifecycleStatus()).orElse(null);
        return new OpenApp(po.getAppId(), po.getAppName(), po.getStatus(),
                po.getQpsLimit(), po.getDailyQuota(), po.getContact(), po.getCreateTime(),
                po.getEnvironment(), lifecycle, po.getClosedAt(), po.getRetentionUntil(),
                po.getLegalHold() == null ? 0 : po.getLegalHold());
    }

    private SecretRecord toRecord(OpenAppSecretPO po) {
        return new SecretRecord(po.getId(), po.getAppId(), po.getSecretCipher(),
                po.getStatus(), po.getExpireTime(), po.getCreateTime());
    }
}
