package com.newcoupon.infrastructure.security;

import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import com.newcoupon.infrastructure.persistence.po.AdminUserPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 种子管理员初始化（V6 2A）：启动时确保 admin 用户存在（不存在则建）。
 *
 * <p><b>为什么用户种子不在 V44 SQL 里</b>：初始密码来自配置
 * {@code coupon.security.seed-admin-password}（环境差异），而 BCrypt 哈希必须按明文现算——
 * SQL 里写死任何一个哈希都等于给全部环境预置同一个密码。权限码/角色是静态数据，留在 SQL。
 *
 * <p><b>只补建、不覆盖</b>：admin 已存在时**不**用当前配置的密码重写——
 * 运维改了 SEED_ADMIN_PASSWORD 重启，多半是给**新环境**准备的值，拿它覆盖存量
 * 密码等于"改环境变量就能重置任意环境的管理员密码"，比默认密码更危险。
 * 忘记种子密码的处理路径：UPDATE admin_user + bump auth_version（强制全部会话失效）。
 *
 * <p>幂等：多个测试上下文共享同一个库（共享容器基座），本 Runner 会被反复执行，
 * "查无则插 + 唯一键兜底"保证重复执行无副作用。
 */
@Component
public class AdminSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeedInitializer.class);

    /** 种子用户登录名（平台管理员的初始账号；密码改走配置）。 */
    static final String SEED_USERNAME = "admin";

    private final AdminRbacMapper rbacMapper;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    public AdminSeedInitializer(AdminRbacMapper rbacMapper,
                                PasswordEncoder passwordEncoder,
                                @Value("${coupon.security.seed-admin-password:}") String seedPassword) {
        this.rbacMapper = rbacMapper;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword == null ? "" : seedPassword.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (rbacMapper.findByUsername(SEED_USERNAME) != null) {
            return;   // 已有：不覆盖（见类注释的"只补建"理由）
        }
        if (seedPassword.isEmpty()) {
            // 一个也不建：宁可没有管理员（走 DB 手工建号），不留一个空密码/默认密码的后门
            log.warn("SECURITY: coupon.security.seed-admin-password 未配置——不创建种子管理员。"
                    + "需要控制台登录的环境请配置 SEED_ADMIN_PASSWORD 后重启（仅首次建号时读取）");
            return;
        }
        if ("dev-seed-admin-123".equals(seedPassword)) {
            log.warn("SECURITY: 种子管理员使用 dev 默认密码——仅限本地/测试环境，"
                    + "生产必须配置 SEED_ADMIN_PASSWORD，并在首次登录后改密");
        }

        AdminUserPO user = new AdminUserPO();
        user.setUsername(SEED_USERNAME);
        user.setPasswordHash(passwordEncoder.encode(seedPassword));   // {bcrypt} + BCrypt(12)
        user.setDisplayName("平台管理员（种子）");
        user.setEnabled(1);
        user.setAuthVersion(0L);
        try {
            rbacMapper.insert(user);
        } catch (DuplicateKeyException e) {
            return;   // 并发启动竞态：另一个实例刚建完——不覆盖，直接收工
        }
        rbacMapper.bindRole(user.getId(), "PLATFORM_ADMIN");
        log.info("SECURITY: 种子管理员已创建（username=admin，角色=PLATFORM_ADMIN）——"
                + "该账号仅在建号时读取配置密码，之后以库内哈希为准");
    }
}
