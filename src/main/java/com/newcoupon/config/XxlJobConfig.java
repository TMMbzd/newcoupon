package com.newcoupon.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-JOB 执行器装配（ADR-0010：调度平台终局选型，2026-09-12 落地）。
 *
 * <p>**双通道降级设计**：@Scheduled 保留为本地/降级触发通道（测试与单机开发零依赖），
 * XXL-JOB 为生产触发通道（控制台管理/执行留痕/分片广播/错过补跑）。两通道同时激活时
 * 由 RedisScanLock 仲裁（同轮次恰一个执行者）——正确性本不依赖任一调度器（任务全幂等），
 * 调度器只是"提醒者"。M2 验证 XXL-JOB 稳定运行一个里程碑后，可移除 @Scheduled 通道。
 *
 * <p>**开关**：`xxl.job.enabled`（默认 false）。测试环境保持 false（@SpringBootTest 全量上下文
 * 不应尝试连调度中心）；本地/生产启动时置 true（compose 环境变量 XXL_JOB_ENABLED=true）。
 */
@Configuration
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "true")
public class XxlJobConfig {

    private static final Logger log = LoggerFactory.getLogger(XxlJobConfig.class);

    @Value("${xxl.job.adminAddresses}")
    private String adminAddresses;

    @Value("${xxl.job.appname:newcoupon-executor}")
    private String appname;

    @Value("${xxl.job.port:9999}")
    private int port;

    @Value("${xxl.job.accessToken:}")
    private String accessToken;

    @Value("${xxl.job.logPath:/tmp/xxl-job/log}")
    private String logPath;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        log.info("XXL-JOB executor initializing: admin={}, appname={}", adminAddresses, appname);
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appname);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(7);
        return executor;
    }
}
