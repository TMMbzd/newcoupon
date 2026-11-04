package com.newcoupon.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 分组配置（V6 2B-2，ADR-0022）——对外契约只收开放面 v1。
 *
 * <p><b>它在做什么</b>：注册一个名为 {@code open-v1} 的分组，{@code pathsToMatch("/open/v1/**")}
 * 让 {@code GET /api/api-docs/open-v1} 只输出路径以 {@code /open/v1/} 开头的操作。
 * Controller 多数是双前缀映射（如 {@code @RequestMapping({"/api/templates", "/open/v1/templates"})}
 * ——同一个处理器同时服务管理面与开放面），不分组的话 spec 会把 /api 管理端点一并发进
 * "对外契约"，那是内部拓扑的泄漏而不是文档。
 *
 * <p><b>契约测试的泄漏防守就用这条不变式</b>：live spec 的所有 path 必须以 /open/v1/ 开头
 * （OpenApiContractIntegrationTest 用例1）——将来谁加了一个不落 /open/v1 前缀的新开放端点，
 * 或者分组过滤失效，测试直接红。
 *
 * <p><b>端点的安全面</b>：api-docs 路径在 application.yml 挪到了 /api/api-docs，落进兜底
 * 安全链的受保护前缀（V6 2A-2 翻面后 /api 所有方法都要旧令牌或会话）——spec 描述的是对外
 * 契约，但拉取它的口子属于管理面，不匿名开放（契约测试有用例钉 401）。
 */
@Configuration
public class SpringDocConfig {

    @Bean
    GroupedOpenApi openV1() {
        return GroupedOpenApi.builder()
                .group("open-v1")
                .pathsToMatch("/open/v1/**")
                .build();
    }
}
