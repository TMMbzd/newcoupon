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
   - additive 变更（新增 path / 新增可选字段 / 新增枚举值 / 新增响应码 / 描述变化）→ 门禁保持绿，
     再跑一次基线再生成把新契约收进基线，`git diff` 审查后随代码提交；
   - 破坏性变更（删 path / 删 method / 删字段 / 类型变化 / 可选变必填 / 新增必填 / 枚举收窄）→
     门禁**必须红**。v1 冻结（ADR-0016/0022）：破坏性变更不允许出现在 v1，走 `/open/v2`。
3. **变更清单可读**。门禁红灯会逐条列出破坏性变更（规则名 + 端点 + 字段），把它贴进 PR 描述，
   评审看的不是 diff 的 JSON 而是这份清单。

## 基线再生成命令

```bash
mvn test -Dtest=OpenApiContractIntegrationTest -Dopenapi.baseline.update=true
```

带 `-Dopenapi.baseline.update=true` 的那一轮会跳过门禁本身（再生成与门禁不能同轮互相咬尾）、
重写 `openapi-v1.json` 并在控制台打印 paths 数量。之后正常跑一遍不带开关的命令确认绿灯。

## 判定规则与口径

- 比较器：`src/test/java/com/newcoupon/integration/OpenApiDriftComparator.java`
  ——手写显式规则集（零第三方依赖），口径对齐 openapi-diff 的 breaking 默认集与
  Stripe/GitHub 的 additive 兼容声明；完整规则清单与每条的理由见该类注释，
  自检用例（破坏性 15 变异逐条命中 + additive 9 变异全部放行）在 `OpenApiContractIntegrationTest`。
- spec 拉取端点：`GET /api/api-docs/open-v1`（需要 X-Admin-Token 或控制台会话——
  它挪进 /api 前缀就是为了吃管理面认证；匿名 401 有测试钉住）。
- `servers` 字段在生成与对比时都会剥掉：它带的是本环境的 host:port，是环境不是契约。
- 版本声明是 `3.0.1`（springdoc 2.6 的 OAS 3.0 档常量；配置 `springdoc.api-docs.version: openapi_3_0` 钉死档位）。
