# open-api 基线纪律

本目录放开放面 v1 的**机器生成** OpenAPI 契约基线：

| 文件 | 是什么 |
|---|---|
| `openapi-v1.json` | 黄金基线（springdoc 2.6 从 Controller 注解生成，OAS 3.0 档）。**禁止手改** |
| `接入指南-v1.md` | 人读的接入指南（含字段表、错误码、幂等口径） |

## 三条纪律

1. **基线是生成物，不是文档**。`openapi-v1.json` 只能由测试再生成产出（见下），手改会在下次
   对比时被 live spec 揭穿（或更糟：把真实契约漂移掩盖掉）。
2. **改契约 = 先红后绿**。改了 Controller/DTO 后先跑
   `mvn test -Dtest=OpenApiContractIntegrationTest`：
   - additive 变更（新增 path / 新增可选字段 / 枚举扩值 / 新增响应码 / 约束放宽 / 描述变化）→
     门禁保持绿，再跑一次基线再生成把新契约收进基线，`git diff` 审查后随代码提交；
   - 破坏性变更（见下方规则表）→ 门禁**必须红**。v1 冻结（ADR-0016/0022）：破坏性变更不允许
     出现在 v1，走 `/open/v2`。
3. **变更清单可读**。门禁红灯会逐条列出破坏性变更（规则名 + 端点 + 字段），把它贴进 PR 描述，
   评审看的不是 diff 的 JSON 而是这份清单。

## 基线再生成命令

```bash
mvn test -Dtest=OpenApiContractIntegrationTest -Dopenapi.baseline.update=true
```

带 `-Dopenapi.baseline.update=true` 的那一轮会跳过常规门禁用例，但**再生成自带前置门禁**：
覆写基线前先对现有基线跑一遍破坏性 diff，发现破坏性变更直接红并**拒绝写文件**（堵住
"开关一开、破坏静默进基线"的旁路——属性若泄漏进 CI 环境变量也一样被拦）。
确属误报/人工裁定接受时：先删除基线文件再跑再生成（= 显式承认这次重置），并把裁定记录进 PR。

## 判定规则与口径

- 比较器：`src/test/java/com/newcoupon/integration/OpenApiDriftComparator.java`
  ——手写显式规则集（18 条规则，零第三方依赖），口径对齐 openapi-diff 的 breaking 默认集（含
  枚举/必填的请求-响应镜像方向）与 Stripe/GitHub 的 additive 兼容声明。下钻覆盖：
  **items（数组元素）**、**additionalProperties（Map 值）**、**组合 schema（allOf/anyOf/oneOf
  成员按规范化序列化串排序配对，顺序漂移不假红）**、**pathItem 级共享参数**、**参数 schema
  内部**、**校验约束键（maxLength/minLength/minimum/maximum/maxItems/minItems/pattern，
  方向敏感：收紧=红、放宽/删除=绿）**。完整规则表与"已知不比"清单见该类注释。
- 自检用例（常驻测试套件，每次跑套件都在验证比较器本身）：33 类破坏性变异逐条命中 +
  17 类 additive/放宽变异全部放行。
- spec 拉取端点：`GET /api/api-docs/open-v1`（需要 X-Admin-Token 或控制台会话——
  它挪进 /api 前缀就是为了吃管理面认证；匿名 401 有测试钉住）。
- **关闭的多余端点**（有测试钉住不复活）：默认未分组 spec 端点 `/api/api-docs` 与
  `/api/api-docs.yaml`（`springdoc.enable-default-api-docs: false`——它们输出未过滤的全量
  spec，任何控制台会话无视 RBAC 都能拉）；`/api/api-docs/swagger-config` 随
  `springdoc.swagger-ui.enabled: false` 一并消失（**不存在** `springdoc.api-docs.swagger-config.enabled`
  这个键——2026-11-04 实测裁决：有无该键行为相同、springdoc 2.6.0 字节码亦无此属性名，
  曾误配为死键已删）。
- 本应用对"不存在的路径"的可观测响应是 **HTTP 200 + Result(5000)**（Spring 6.1 的
  NoResourceFoundException 被 GlobalExceptionHandler 兜底收敛），不是 404——安全用例据此
  以"响应不是 OpenAPI 文档"为判据。
- `servers` 字段在生成与对比时都会剥掉：它带的是本环境的 host:port，是环境不是契约。
- 版本声明是 `3.0.1`（springdoc 2.6 的 OAS 3.0 档常量；配置 `springdoc.api-docs.version: openapi_3_0` 钉死档位）。
