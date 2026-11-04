# ADR-0022：API 版本注册表与契约门禁（V6 2B-2）

- **状态**：已接受（已实施，2026-11-04）
- **日期**：2026-11-04
- **关系**：实施 [ADR-0016](ADR-0016-api-versioning.md)（API 版本与兼容策略）的注册表/契约测试/废弃头三块；依赖 V6 2A-2 的管理面认证翻面（/api 全方法认证）；设计输入 docs/research-2026-10-21/01 §4 2B
- **一句话**：版本存在性放代码表（ApiVersionRegistry），v1 契约用 springdoc 生成 + 手写"只增不改"比较器做门禁，废弃机制（Deprecation/Sunset/Link 头）真实存在但暂无 DEPRECATED 版本。

## 1. 背景

ADR-0016 定了框架（路径版本、只增不改、错误码稳定、废弃流程、契约测试），标注"参数待拍板、部分未实现"。V6 2B-2 这刀补齐其中三块，并把"v1 冻结"从口头承诺变成 CI 证据：

1. **版本判据硬编码**：开放面认证链第一步是 `path.startsWith("/open/v1/")`——v2 上线时没有单一事实源登记"现在有哪些版本、各自什么状态"；
2. **没有契约测试**：字段只增不改靠人肉 review，spec 甚至不存在（Controller 双前缀映射，`/api` 与 `/open/v1` 同一个处理器，"开放面到底暴露了什么"无人能回答）；
3. **废弃流程无机制**：6010 只有一句话文案，接入方拿到后不知道往哪迁；宣布废弃时没有标准的告知通道。

## 2. 决策

### 2.1 ApiVersionRegistry：表驱动代码表（无 DB / 无配置面）

`com.newcoupon.common.web.ApiVersionRegistry`，Spring `@Component`，当前只登记 `v1 / ACTIVE`：

- `resolve(servletPath)`：解析 `/open/{seg}/{rest}`。seg 已登记 → `ResolvedVersion(version, restPath)`；未登记（含 v2）→ empty → 认证链拒 6010；
- `supportedVersions()`：所有登记版本的 segment（**DEPRECATED 也算支持中**——它只是宣布了废弃日期，Sunset 前仍在服务；不把它列进 6010 的引导信息等于在迁移期说谎）；
- `deprecationHeaders(ApiVersion)`：纯函数，DEPRECATED 版本生成 `Deprecation: @<unix秒>`（RFC 9745）、`Sunset: <HTTP-date>`（RFC 8594，IMF-fixdate）、`Link: <url>; rel="deprecation"`（RFC 8288）。v1=ACTIVE 不触发，但逻辑与格式有单测钉住。

**为什么是代码表而不是配置/DB**：版本的存在性决定"请求能不能进门"，与新代码（新 Controller/新字段）必然同生共死——放进配置面等于把"开一个新 API 面"的权力交给改一行配置，而"配置先开、代码后到"只会制造一个 404 面。配置适合放参数，不适合放契约存在性；当前版本数=1，更没有引入配置面的收益。

**边界语义零变更**：旧判据 `startsWith("/open/v1/")` 对 `/open/v1`（无尾斜杠）是 false → 6010。注册表实现保持同一答案（无尾斜杠不解析），`/open/v1/`（空 restPath）同样放行到认证链后续步骤。换实现不改对外行为，由 ApiVersionRegistryTest 钉住。

**6010 的引导信息**：响应体 `data.supportedVersions`（新增 `Result.fail(ErrorCode, T data)` 工厂承载结构化 data）。接入方代码拿到 6010 就知道现存版本——这是 Stripe 式"错误里带迁移线索"的最小实现。

**响应头 X-API-Version**：认证通过即设 `X-API-Version: v1`（必须在 doFilter 之前——OpenApiAuthFilter 类注释里记着"响应提交后 setHeader 无效"的实测教训）。

### 2.2 路径版本，不加请求头版本

维持 ADR-0016 的裁定并补充本刀的加固理由：HMAC 签名的 canonical string **包含路径**——版本若走请求头，"这个请求按哪个版本执行"就有路径与头两处真相，验签时以哪处为准都会留下绕过面。路径版本让版本段天然在签名材料里，单一真相。

### 2.3 springdoc + spec 端点安全

- 引入 `springdoc-openapi-starter-webmvc-ui:2.6.0`（版本显式声明，Boot BOM 不纳管 springdoc）；
- `GroupedOpenApi` 分组 `open-v1`，`pathsToMatch("/open/v1/**")`——Controller 双前缀映射意味着不分组会把 /api 管理端点泄进对外契约；
- spec 端点挪 `/api/api-docs`：springdoc 默认路径 `/v3/api-docs` 不在任何安全链的受保护前缀里，等于把整个 API 面拓扑匿名裸奔；挪进 /api 后自动落进 V6 2A-2 的管理面认证（旧令牌或会话），匿名 401 有测试钉住；
- swagger-ui 关闭（生产不需要交互 UI）；响应 media type 钉 `application/json`（默认 `*/*` 是占位不是契约）；OAS 档位钉 3.0（实际渲染 3.0.1——springdoc 2.6 的 3.0 档常量）。

### 2.4 黄金 spec + 手写比较器（门禁裁剪）

- **基线** `docs/open-api/openapi-v1.json`：机器生成（25 path），纪律见 docs/open-api/README.md（改契约=先红后绿更新基线、破坏性变更禁止、基线禁止手改）；
- **门禁** `OpenApiContractIntegrationTest` 四守卫：泄漏防守（spec 所有 path 以 `/open/v1/` 开头）、防漂移（live 对基线只增不改）、黄金样例（GET `/open/v1/templates/{id}` 真实响应关键字段级对拍 + 6010 形状 + X-API-Version 头）、安全面（匿名拉 spec 401）；
- **比较器** `OpenApiDriftComparator`：手写显式规则集（14 条规则，每条一个枚举/方法），**不引第三方**。规则集对齐 openapi-diff 的 breaking 默认集与 Stripe（"non-breaking version 只允许 additive"）/GitHub（"新增可选字段/端点不算 breaking"）的公开口径：
  - 破坏：删 path / 删 method / 删参数 / 参数类型变化 / 参数可选变必填 / 新增必填参数 / 请求体变必填 / 请求体字段删除·类型变化·新增必填 / 删响应码 / 响应字段删除·类型变化 / 枚举收窄；
  - 允许：新增 path / method / 可选参数 / 可选字段 / 枚举值 / 响应码 / 描述变化 / **响应侧 required 新增**（服务端多保证一个字段不伤害客户端；请求侧新增必填才是破坏）。
- **为什么手写而不用 openapi-diff CLI / Pact / Spectral**：本刀判据只有"只增不改"一条，写成显式规则表比引一个通用工具更可防守（每条规则可独立变异取证，绿灯输出可读）；与 V6 计划 L208 的裁剪一致——外部消费方（openapi-diff CLI 进 CI、Spectral lint、前端 openapi-typescript 生成类型）出现时再换/再叠加。同理，swagger-request-validator 的"真实请求/响应校验"这刀只做黄金样例两点采样（模板查询 + 6010），不全量铺。
- **取证**：比较器自检用例（15 类破坏性变异逐条命中 + 9 类 additive 变异全部放行）常驻测试套件；基线注入假响应字段（模拟 live 删字段）红灯输出单行清单 `[RESPONSE_FIELD_REMOVED…] GET /open/v1/templates/{id} → 200 application/json.data → faceValueMutant`。

### 2.5 L73 复核结论

- 异步链路：`AsyncIssueSchemaIntegrationTest`（V28）已有"跨租户同 userId+requestId 不冲突"用例（任务表唯一键 + user_coupon uk_idem，DB 层）；
- **同步链路（C1 receive）此前没有同类用例**——本刀在 OpenApiOwnershipIntegrationTest 补：租户 A 与 B 用相同 userId+requestId 各领各的模板，两笔都成功、钱包互不干扰（钉死 `(user_id, app_id, idempotent_key)` 三元组在同步链路端到端成立，不只 DB 约束层）；
- 接入指南 §6 补幂等隔离口径：requestId 幂等范围是 `(userId, appId, requestId)` 三元组，跨应用同号各自有效；同应用内同号换模板仍拒绝。

## 3. 后果

### 正面

- "v1 冻结"有 CI 证据：破坏性变更当轮红灯 + 可读清单，不再靠人肉 review 16 个 Controller；
- v2 上线时只需注册表加一行 + v1 状态置 DEPRECATED（附日期/迁移链接），6010 引导与废弃头自动生效——机制先于需求就位；
- spec 本身成为可交付物（接入评审、外部工具链的输入）。

### 代价与风险

- **springdoc 依赖进生产 classpath**（仅生成 spec；UI 已关，无静态资源端点）——每次 Boot 大版本升级要复核 springdoc 兼容矩阵（2.6.0 ↔ Boot 3.3.x）；
- **基线是活的生成物**：每次 additive 改契约都要再生成 + diff 审查，忘了就会在下一轮 CI 红灯提醒（这是特性不是缺陷）；
- 比较器是自家维护的规则集：新场景（如 webhooks/hooks、cookie 参数）出现时要扩规则，openapi-diff 的覆盖面更全——换工具的触发条件写在 2.4；
- `servers` 字段在生成与对比时剥掉（host:port 是环境不是契约）——若未来 spec 需要固定 server 描述，要改基线纪律。

## 4. 验收（已达成，2026-11-04）

- ApiVersionRegistryTest 9 例（边界语义/supportedVersions/废弃头格式）全绿；
- OpenApiContractIntegrationTest 9 例 + 1 例门控（基线再生成）全绿；
- 变异取证：破坏性（基线+假字段）红、additive（基线-可选字段）绿，输出已贴工作记录；
- 回归：OpenApiAuthIntegrationTest（6010 含 supportedVersions）、OpenApiContextIntegrationTest（resource 口径零回归）、OpenApiOwnershipIntegrationTest（含 L73 新例）、AdminSurfaceProtectionTest（springdoc 端点落 /api 受保护面）全绿。
