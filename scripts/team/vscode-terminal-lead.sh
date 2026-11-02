#!/usr/bin/env bash
# Start the agent-team lead in an interactive VS Code terminal, OUTSIDE tmux.
# Claude Code then creates a private claude-swarm tmux server, which you attach
# to from a second terminal with attach-swarm.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1

if [ -n "${TMUX:-}" ]; then
    echo "当前已经在 tmux 里。终端-only 稳定模式请运行：" >&2
    echo "  bash $ROOT/scripts/team/team-session.sh" >&2
    exit 1
fi

if ! command -v claude >/dev/null 2>&1; then
    echo "claude 不在 PATH；先确认终端版 Claude Code 可用。" >&2
    exit 1
fi

cd "$ROOT"
exec claude --teammate-mode tmux
