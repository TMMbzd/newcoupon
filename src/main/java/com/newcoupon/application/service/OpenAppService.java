package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.crypto.SecretCipher;
import com.newcoupon.domain.open.OpenApp;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.domain.repository.OpenAppRepository.SecretRecord;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 接入方管理（S1 开放平台的"运营面"：注册 / 启停 / 密钥轮换 / 查询）。
 *
 * <p><b>SK 只显示一次</b>：创建与轮换的响应里带明文 SK，之后任何查询接口都不返回它
 * （连掩码都不返回）。这不是"技术上做不到"（密文是可解的），而是<b>减少明文在网络、
 * 日志、接入方聊天记录里出现的次数</b>——业界（阿里云/AWS）都是这个策略。
 *
 * <p><b>轮换的语义</b>：新密钥立即生效，旧密钥<b>不是立刻作废</b>而是进入"轮换期"
 * （默认 7 天）：接入方换密钥要发版或重启，一刀切会让对方在切换瞬间全线 401。
 * 轮换期结束后旧密钥失效——失效动作本阶段是手动的（读接口能看到 `expireTime`），
 * 自动失效登记为 L50（见设计文档 §7）。
 *
 * <p><b>为什么 create/rotate 要事务</b>：两者都是"两次写"（应用行 + 密钥行）。
 * rotate 尤其危险——若"旧密钥降级成功、新密钥插入失败"，结果是该应用<b>一把当前密钥都没有</b>：
 * 轮换期一过全线 401。两个写要么都成、要么都不成。
 */
@Service
public class OpenAppService {

    private static final Pattern APP_ID = Pattern.compile(OpenApp.APP_ID_PATTERN);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 建表默认值（V14）：qps 100 / 日配额 100 万——请求未指定时按此落库 */
    static final int DEFAULT_QPS_LIMIT = 100;
    static final int DEFAULT_DAILY_QUOTA = 1_000_000;

    private final OpenAppRepository repository;
    private final SecretCipher cipher;
    private final CachedOpenAppReader authReader;

    /** 轮换期长度（默认 7 天）：新密钥生效后，旧密钥还能用多久 */
    private final Duration rotationWindow;

    public OpenAppService(OpenAppRepository repository,
                          SecretCipher cipher,
                          CachedOpenAppReader authReader,
                          @Value("${coupon.open.secret-rotation-hours:168}") long rotationHours) {
        this.repository = repository;
        this.cipher = cipher;
        this.authReader = authReader;
        this.rotationWindow = Duration.ofHours(rotationHours);
    }

    /**
     * 注册接入方。
     *
     * @throws BizException {@link ErrorCode#PARAM_ERROR}——appId 格式非法 / 名称缺失 / appId 已存在
     */
    @Transactional
    public AppWithSecret create(CreateAppRequest req) {
        String appName = req.appName() == null ? "" : req.appName().trim();
        if (appName.isEmpty() || appName.length() > 128) {
            throw new BizException(ErrorCode.PARAM_ERROR, "appName 不能为空且不超过 128 字符");
        }
        String appId = (req.appId() == null || req.appId().isBlank())
                ? generateAppId()
                : req.appId().trim();
        if (!APP_ID.matcher(appId).matches()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "appId 需满足 " + OpenApp.APP_ID_PATTERN + "（小写字母/数字/连字符，3~32 位，首字符为字母或数字）");
        }
        int qpsLimit = req.qpsLimit() == null ? DEFAULT_QPS_LIMIT : req.qpsLimit();
        int dailyQuota = req.dailyQuota() == null ? DEFAULT_DAILY_QUOTA : req.dailyQuota();
        if (qpsLimit < 0 || dailyQuota < 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "配额不能为负（0=不限）");
        }

        String secret = SecretCipher.newSecret();
        try {
            repository.insertApp(new OpenApp(appId, appName, OpenApp.STATUS_ENABLED,
                    qpsLimit, dailyQuota, req.contact(), null));
        } catch (DuplicateKeyException e) {
            // 唯一索引终审：不做"先查再插"（两者之间总有竞态窗口），直接让数据库判
            throw new BizException(ErrorCode.PARAM_ERROR, "appId 已存在：" + appId);
        }
        repository.insertSecret(appId, cipher.encrypt(secret), SecretRecord.STATUS_CURRENT, null);
        authReader.invalidate(appId);   // 让认证缓存立刻看到新密钥（不等 TTL）

        return new AppWithSecret(appId, appName, secret, OpenApp.STATUS_ENABLED, qpsLimit, dailyQuota);
    }

    /** 查询单个接入方（含密钥状态，不含明文）。不存在则 PARAM_ERROR（管理面按"查无此应用"处理） */
    public AppView get(String appId) {
        OpenApp app = requireApp(appId);
        LocalDateTime now = LocalDateTime.now();
        List<SecretView> secrets = repository.findAllSecrets(appId).stream()
                .map(r -> toView(r, now))
                .toList();
        return new AppView(app.appId(), app.appName(), app.status(), app.qpsLimit(),
                app.dailyQuota(), app.contact(), app.createTime(), secrets);
    }

    /** 全部接入方（管理面列表） */
    public List<AppView> list() {
        return repository.findAllApps().stream().map(app -> get(app.appId())).toList();
    }

    /** 启停：停用后该应用的一切签名调用被拒（6002），数据与密钥保留（可恢复） */
    public void setStatus(String appId, int status) {
        if (status != OpenApp.STATUS_ENABLED && status != OpenApp.STATUS_DISABLED) {
            throw new BizException(ErrorCode.PARAM_ERROR, "status 只能是 0（停用）或 1（启用）");
        }
        if (!repository.updateStatus(appId, status)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "应用不存在：" + appId);
        }
        // 停用必须立刻生效——"等 60 秒 TTL 自然过期"意味着这 60 秒里被停用的接入方还能调
        authReader.invalidate(appId);
    }

    /** 密钥轮换：签发新 SK，旧 SK 进入轮换期（默认 7 天）。返回**新 SK 明文（只此一次）** */
    public AppWithSecret rotate(String appId) {
        return rotate(appId, rotationWindow);
    }

    /**
     * 密钥轮换（显式指定轮换期）。存在的意义：轮换期长度在测试里必须是可确定的
     * （零窗口 / 负窗口用来验证"过期即失效"这条边界），而不是靠等 7 天。
     */
    @Transactional
    public AppWithSecret rotate(String appId, Duration window) {
        OpenApp app = requireApp(appId);

        // 零/负窗口 = 旧密钥立即失效：测试用，也是"密钥疑似泄漏"时的紧急止血手段
        LocalDateTime expireAt = (window.isZero() || window.isNegative())
                ? LocalDateTime.now()
                : LocalDateTime.now().plus(window);

        repository.demoteCurrentToRotation(appId, expireAt);
        String secret = SecretCipher.newSecret();
        repository.insertSecret(appId, cipher.encrypt(secret), SecretRecord.STATUS_CURRENT, null);
        // 轮换后缓存里还是旧密钥集合：必须立刻失效，否则新密钥要等 TTL 才能用
        authReader.invalidate(appId);

        return new AppWithSecret(app.appId(), app.appName(), secret,
                app.status(), app.qpsLimit(), app.dailyQuota());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private OpenApp requireApp(String appId) {
        if (appId == null || appId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "appId 不能为空");
        }
        return repository.findApp(appId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, "应用不存在：" + appId));
    }

    /**
     * 密钥"还能不能用"的判定——与仓储 {@link OpenAppRepository#findUsableSecrets} 的口径必须一致
     * （那边用 SQL 比较，这边用 Java 比较；两处判据一致由用例钉住）。
     */
    private SecretView toView(SecretRecord r, LocalDateTime now) {
        boolean usable = r.status() == SecretRecord.STATUS_CURRENT
                || (r.status() == SecretRecord.STATUS_ROTATING
                    && (r.expireTime() == null || r.expireTime().isAfter(now)));
        return new SecretView(r.id(), r.status(), r.expireTime(), r.createTime(), usable);
    }

    /** app- + 12 位十六进制（48 bit 随机）：碰撞靠 uk_app_id 兜底，不靠"运气好" */
    private static String generateAppId() {
        byte[] raw = new byte[6];
        RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder("app-");
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 请求/响应模型
    // ------------------------------------------------------------------

    /** 注册请求。appId 可空（系统生成）；qpsLimit/dailyQuota 可空（用建表默认值） */
    public record CreateAppRequest(String appId, String appName,
                                   Integer qpsLimit, Integer dailyQuota, String contact) {
    }

    /** 创建/轮换的返回：secret 是明文 SK，**只在这一次返回** */
    public record AppWithSecret(String appId, String appName, String secret,
                                int status, int qpsLimit, int dailyQuota) {
    }

    /** 密钥视图：不返回明文，但返回"还能不能用"（运营排查"我的旧密钥怎么失效了"时的第一手信息） */
    public record SecretView(long id, int status, LocalDateTime expireTime,
                             LocalDateTime createTime, boolean usable) {
    }

    public record AppView(String appId, String appName, int status, int qpsLimit, int dailyQuota,
                          String contact, LocalDateTime createTime, List<SecretView> secrets) {
    }
}
