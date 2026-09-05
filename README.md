# newcoupon · 优惠券系统中台

对标字节/美团体量的优惠券系统中台。**设计文档集在 [docs/](docs/README.md)（架构总览 + 9 个领域分册 + 统一规范 + ADR），实现严格按文档推进。**

## 当前阶段：M1 债务清偿与正确性收口（路线图 v2）

> 系统现状：[docs/STATUS.md](docs/STATUS.md)（能力矩阵/遗留债——唯一权威状态来源）
> 技术路线：[docs/ROADMAP.md](docs/ROADMAP.md)（企业环境设定 + M1-M6 里程碑 + 开发流程模板）
> 已完成：阶段0（纯DB三验收全过）+ 阶段1（Redis Lua 五重校验闸门/DB双防线/通用限领N），76 测试全绿。

## 快速开始

```bash
# 1. 启动本地依赖（需要 Docker；见下方环境说明）
make up

# 2. 跑单测（不依赖数据库，秒级）
make test

# 3. 启动应用（Flyway 会在启动时自动建表）
make run

# 4. 冒烟
curl http://localhost:8080/api/ping
curl http://localhost:8080/actuator/health
```

环境要求：JDK 17+、Maven 3.8+、Docker（WSL2 需在 Docker Desktop 开启发行版集成）。

## 工程结构（DDD-lite 四层）

```
src/main/java/com/newcoupon/
├── common/            # 共享内核：统一响应体 Result、错误码 ErrorCode、业务异常
├── controller/        # 接入层：REST 适配，只做协议转换，不写业务
├── application/       # 应用层：用例编排（领券/锁券/核销...），事务边界
├── domain/            # 领域层：聚合、状态机、领域枚举 —— 不依赖任何框架
└── infrastructure/    # 基础设施层：仓储实现、Web 过滤器、外部适配
```

依赖方向：`controller → application → domain ← infrastructure`（domain 是内核，谁都不许反向依赖它）。

## 工程规范（对标大厂，每条的"为什么"见提交说明与 docs/）

| 规范 | 内容 |
|---|---|
| SQL 管理 | 一切建表/变更走 `src/main/resources/db/migration/` 的 Flyway 脚本，**禁止手工改库** |
| 建表 | InnoDB + utf8mb4；每表/每列必须有 COMMENT；必备 id/create_time/update_time；**禁用外键与级联**（一致性在应用层保证） |
| 接口契约 | 统一 `Result<T>` 响应体；错误码分段（0 成功 / 1xxx 参数 / 2xxx 模板域 / 3xxx 领券域 / 4xxx 用券域 / 5xxx 系统）；入参一律 JSR-303 校验 |
| 异常 | 业务异常抛 `BizException`，由全局处理器兜底；**堆栈只进日志，不出响应** |
| 日志 | 每个请求自动注入 traceId（MDC + 响应头 X-Trace-Id），一条日志能用 traceId 串起全链路 |
| 单测 | 秒级、相互独立、不依赖 Spring 容器与真实中间件；状态机用迁移表驱动测试 |
| 架构决策 | 重大取舍写 ADR（`docs/adr/`），记录背景/决策/后果，防止半年后没人记得为什么 |
| Git | 分支 main；提交信息 `type: 中文描述`（feat/fix/docs/refactor/test/chore） |
| 配置 | 环境差异走 profile（application-dev.yml）；敏感信息不入库（application-local.yml 已 ignore） |
| 优雅停机 | `server.shutdown=graceful`，发版时不切断在途请求 |
