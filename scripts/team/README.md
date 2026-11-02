# Agent team 分屏（WSL2 + tmux）

这里配置的是 Claude Code 的 **agent teams** 分屏，不是普通 subagent。
项目级 `.claude/settings.local.json` 已设置 `"teammateMode": "tmux"`；该文件在
`.gitignore` 里，不会进入 git。

## 先看结论

- **VSCode 原生 Claude 面板不能 spawn teammate**：它用
  `--output-format stream-json --input-format stream-json` 启动，是非交互会话；
  `teammateMode` 在这里不生效。原生面板只能跑 lead 的普通 subagent。
- **Run Task 可以用**：它只是让 VSCode 执行一条 shell 命令；`team:*` 任务打开的是
  **VSCode 终端**，不是原生 Claude 面板。teammate pane 仍然在 tmux 里。
- **VSCode 终端模式可以**：在 VSCode 的集成终端里跑 `claude`（交互式 TTY），
  且 **不要** 先进入 tmux。Claude Code 会创建一个私有 tmux server：
  - socket：`claude-swarm-<lead-pid>`
  - session：`claude-swarm`
  - window：`swarm-view`
  再用第二个终端 attach，就能看到并排的 teammate pane。
- **终端 tmux 模式最稳**：lead 直接在 tmux 会话里，Claude 会在同一个 window 里分 pane。

## 模式 A：VSCode 终端 lead + 外部 swarm（推荐给 VSCode 用户）

1. `Ctrl+Shift+P` → **Tasks: Run Task** → **team: start VSCode terminal lead**。
   这个任务在编辑器区开一个 VSCode 终端，并在 **tmux 外**运行
   `claude --teammate-mode tmux`。如果任务终端不能正常显示 TUI，直接开一个
   VSCode 终端并运行 `bash scripts/team/vscode-terminal-lead.sh`。
2. 在这个 lead 终端里让 Claude spawn 至少一个 teammate。底部会出现：
   `View teammates: tmux -L claude-swarm-<pid> a`
3. 再开一个终端（同一个任务面板里新建，或运行
   **team: attach teammate panes**）。attach 脚本会自动找最新的
   `claude-swarm-*` socket 并 attach。
4. 把两个终端 tab 拖到并排；`.vscode/settings.json` 已把新终端默认放在
   编辑器区（`terminal.integrated.defaultLocation: "editor"`）。
5. 如果 VSCode 集成终端 attach 不正常，运行
   **team: open panes in Windows Terminal**。

检查：

```bash
bash scripts/team/attach-swarm.sh --check
# 例：socket=claude-swarm-2454029 target=claude-swarm:swarm-view
```

## 模式 B：终端 tmux（官方文档路径，最稳）

```bash
bash scripts/team/team-session.sh
```

如果想把**当前这段对话**带到 tmux lead，用任务
**team: resume this conversation (2026-10-25) in stable tmux**，或执行：

```bash
bash scripts/team/team-session-resume.sh ae35a121-ac08-4199-86bc-1d1704b77224
```

不要用不带 id 的 `--continue`：同一目录里有多个会话时，它会选最近修改的那一段，
可能选到之前的空 lead，而不是当前对话。显式 `--resume <id> --fork-session` 才会
复制指定的这段对话；当前 VSCode 面板不会被占用。
若 `newcoupon-team` 已存在，先确认没有活跃 lead，再
`tmux kill-session -t newcoupon-team`。

- 已在 tmux 内：直接在当前 pane 启动 `claude --teammate-mode tmux`，Claude 拆当前 window。
- 不在 tmux 内：创建或复用 `newcoupon-team` 会话。
- `Ctrl+B d` detach；`tmux attach -t newcoupon-team` 回来。
- 想要外置窗口：运行任务 **team: open stable tmux lead in Windows Terminal**，
  或执行 `bash scripts/team/open-team-window.sh`。

### 启动后的第一段话（可直接粘贴）

```text
创建 agent team，队友命名为 backend-issue、backend-trade、verifier、architect。
第一轮全部只读，不改文件、不 commit：
- architect：读 STATUS/ADR，输出 Phase 1 剩余项缺口矩阵后退出
- backend-issue：读 CachedTemplateReader/CachedOpenAppReader/CachedTenantConfigService，给缓存跨实例 pub-sub 方案
- backend-trade：读 DailyBillService/PricingSnapshotCleanupService，给 daily bill/pricing 调度锁方案
- verifier：审计 GateRepairStormIntegrationTest 与 scripts/cluster/verify-cluster.sh，给两进程修复风暴/Redis 演练方案
每个队友报告：读了哪些文件、发现、方案、风险、验收命令。
```

## 模式 C：VSCode 原生面板

只能跑普通 subagent/agent map（`/tasks`）；**不能**做 agent-team 分屏。
需要分屏时不要用它当 lead。

## 常用命令

```bash
# 找私有 socket（普通 tmux ls 看不到）
ls -l /tmp/tmux-$(id -u)/claude-swarm-*

# 看某个 socket 的会话/pane
tmux -L claude-swarm-<pid> list-sessions
tmux -L claude-swarm-<pid> list-panes -t claude-swarm -F '#{pane_index} #{pane_title} #{pane_current_command}'

# 手动 attach
tmux -L claude-swarm-<pid> attach -t claude-swarm

# 清理残留（确认没有活跃团队）
tmux -L claude-swarm-<pid> kill-server
tmux kill-session -t newcoupon-team
```

## 导航

- 鼠标点击 pane（已开 `mouse on`）。
- `Ctrl+B` 然后方向键切换 pane。
- `Ctrl+B z` 当前 pane 全屏/还原。
- `Ctrl+B d` detach。
- `Ctrl+B [` 进入 copy mode，方向键/PageUp 翻历史。

## 排障

- **`attach-swarm.sh` 说没有 server**：确认 lead 是交互式终端，不是原生面板；
  确认已经 spawn 了至少一个 teammate；看 lead 底部是否有 `View teammates`。
- **lead 说“N background agents launched”但没有 `View teammates`**：它启动的是
  未命名的普通 subagent，不是 teammate。让 lead 显式用 Agent 工具的 `name` 参数
  （例如 `name="probe"`）重新创建；不要只传 `run_in_background`。
- **模式没生效**：`teammateMode` 在会话启动时快照；改设置后重开终端/会话。
- **VSCode 集成终端里 Claude 不分 pane**：官方明确不支持 VSCode 集成终端、
  Windows Terminal、Ghostty 的 split-pane 模式；本仓库走的是“外部 swarm + attach”
  这条 best-effort 路径。要稳定分屏，用模式 B（lead 在 tmux 里）。
- **多个团队混在一起**：lead 进程 PID 不同，socket 也不同；attach 脚本按最新
  socket 选。若不确定，用 `--check` 或手动 `tmux -L ... ls`。
- **窗口 reload / `/resume`**：in-process 队友不会恢复；tmux 里的队友也可能成为
  orphan，重新 spawn 或清理 socket。

## 回滚

删除 `.claude/settings.local.json` 里的 `"teammateMode": "tmux"` 即可恢复默认
`in-process`；终端仍可显式用 `claude --teammate-mode tmux`。

## 来源

- https://code.claude.com/docs/en/agent-teams
- https://code.claude.com/docs/en/vs-code#switch-to-terminal-mode
- 本地扩展 2.1.310 / 终端 2.1.299 二进制的 external session mode
  （`claude-swarm` / `swarm-view` / `claude-swarm-<pid>` socket）
