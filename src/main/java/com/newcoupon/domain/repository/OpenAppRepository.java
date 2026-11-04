package com.newcoupon.domain.repository;

import com.newcoupon.domain.open.AppLifecycle;
import com.newcoupon.domain.open.OpenApp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 接入方仓储端口（S1）。领域层定义"我需要存取什么"，基础设施层给实现。
 *
 * <p>方法仍遵循"一个方法 = 一种真实的业务场景"：{@link #findUsableSecrets} 表达的是
 * "<b>验签时可用的密钥</b>"（当前密钥 + 未过期的轮换期密钥），而不是"按状态查密钥"——
 * 后者是通用 CRUD，会把"什么算可用"的知识泄漏到调用方，两处实现迟早不一致。
 */
public interface OpenAppRepository {

    /** 新建接入方。appId 撞唯一索引时抛 DuplicateKeyException，由服务层分类为业务错误 */
    void insertApp(OpenApp app);

    Optional<OpenApp> findApp(String appId);

    /** 全部接入方（管理面列表；量级小，不分页） */
    List<OpenApp> findAllApps();

    /**
     * 行锁读（V6 2B）：生命周期迁移/数据导出的事务首步。
     * 锁住应用行再查迁移表与事件表，并发的两个操作被串行化——
     * 后拿锁的事务必然读到先提交者的结果（迁移状态 / 已导出标记）。
     */
    Optional<OpenApp> findAppForUpdate(String appId);

    /** 启停。@return true=影响 1 行；false=应用不存在 */
    boolean updateStatus(String appId, int status);

    /**
     * 生命周期迁移落库（V6 2B）。只写状态列与注销两列——环境/密钥/配额
     * 各有各的入口，不借道这条 SQL 顺带修改。
     *
     * @return true=影响 1 行；false=应用不存在（调用方按参数错误分类）
     */
    boolean updateLifecycle(String appId, AppLifecycle to,
                            LocalDateTime closedAt, LocalDate retentionUntil);

    /** 追加一把密钥（status 见 {@link SecretRecord}） */
    void insertSecret(String appId, String cipher, int status, LocalDateTime expireTime);

    /**
     * 验签时可用的密钥：当前密钥（status=1）+ 轮换期未过期的旧密钥（status=2 且 expire_time 为空或未到）。
     * 轮换期存在的意义就在这里——接入方换密钥要发版，一刀切会让对方在切换瞬间全线 401。
     */
    List<SecretRecord> findUsableSecrets(String appId);

    /** 该应用的全部密钥记录（管理面展示状态与时间，不返回明文） */
    List<SecretRecord> findAllSecrets(String appId);

    /** 轮换第一步：把当前密钥整体降为轮换期并设失效时刻。@return 被降级的行数 */
    int demoteCurrentToRotation(String appId, LocalDateTime expireTime);

    /** 手动失效（轮换期提前结束 / 密钥疑似泄漏时的手动止血）。@return true=影响 1 行 */
    boolean invalidateSecret(long secretId);

    /**
     * 密钥记录。<b>cipher 是密文</b>——解密只在需要验签的那一刻发生（见 SecretCipher），
     * 仓储层不持有主密钥，也不做加解密。
     */
    record SecretRecord(long id,
                        String appId,
                        String cipher,
                        int status,
                        LocalDateTime expireTime,
                        LocalDateTime createTime) {

        /** 当前密钥（新签发） */
        public static final int STATUS_INVALID = 0;
        public static final int STATUS_CURRENT = 1;
        public static final int STATUS_ROTATING = 2;

        public boolean current() {
            return status == STATUS_CURRENT;
        }
    }

    /** 注销收尾：把该应用所有轮换期（ROTATING）密钥立即置 INVALID——rotate 只撤 CURRENT 键。 */
    int expireAllRotatingSecrets(String appId);

    /** 沙箱晋升（activate）翻转环境：SANDBOX→PROD，资金边界按 environment 实时判定。 */
    boolean updateEnvironment(String appId, String environment);
}
