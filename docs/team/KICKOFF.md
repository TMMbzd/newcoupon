你接手 newcoupon 的团队 lead。先按顺序读：

1. `docs/team/CHARTER.md`
2. `docs/team/HANDOFF.md`
3. `docs/STATUS.md` 顶部交接快照
4. `docs/adr/ADR-0019-async-issue-task-wal.md`
5. `docs/adr/ADR-0021-inventory-multi-db-tcc.md`

这些文档就是团队记忆；不要假设你还记得上一段聊天。

你的任务：

1. 用 Agent 工具创建命名 teammate，名字必须是：
   - `backend-issue`
   - `backend-trade`
   - `verifier`
   - `architect`（只读，出完评审后退出）

   必须显式传 `name`，不要传 `isolation`，不要只传 `run_in_background`，不要创建未命名的 background agent。如果创建后没有出现 teammate pane 或 `View teammates`，停下并报告，不要静默退化成 subagent。

2. 第一轮全部只读，不改文件、不跑全量测试、不 commit。各自输出：
   - 读了哪些文件
   - 发现
   - 方案（复用现有代码）
   - 风险
   - 验收命令（只写不执行）

   分工：
   - `architect`：复核 Phase 1 剩余项的排序，重点看 `layout_version` / 扩片重排和 Phase 2 预约单的版本 fence；一页评审后退出。
   - `backend-issue`：配置校验（时区/连接池/XXL）+ 缓存跨实例 pub-sub 失效；标出共享文件改动。
   - `backend-trade`：daily bill / pricing 调度锁；确认不碰 issue 代码。
   - `verifier`：两个真实进程的修复风暴 + Redis 主从切换/重启演练的最小方案；不写脚本。

3. 第一轮结束后，你合并成任务板，按文件集分配实现。第一个实现项建议是配置校验（`config/` 共享文件由你或指定单人写）。之后按 `CHARTER.md` 的路线继续，不要反复把技术选择推给用户。

4. 工作约束：
   - Java 17；不写 Java 21 语法。
   - 不 commit、不 push，除非用户明确要求。
   - 测试串行；全量 `mvn clean test` 只由 `verifier` 跑。
   - 两个后端不能同时改共享文件或抢同一个 Flyway 版本号。
   - 每个完成声明带命令和真实输出。
   - 先调研再实施；不重复造轮子。

5. 每次阶段收尾同步 `docs/STATUS.md` 和 `study/工作日志.md`，并把新的交接信息写回 `docs/team/HANDOFF.md`。

现在开始：先读文档，然后创建四个命名 teammate，做第一轮只读侦察。
