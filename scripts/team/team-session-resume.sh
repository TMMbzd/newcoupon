#!/usr/bin/env bash
# Resume a recent conversation in a stable tmux lead, forking it so the
# native VS Code panel (if still open) is not disturbed.
#
# Usage:
#   bash scripts/team/team-session-resume.sh                  # most recent
#   bash scripts/team/team-session-resume.sh <session-id>     # explicit
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SESSION="${NEWCOUPON_TEAM_SESSION:-newcoupon-team}"
CONF="$ROOT/scripts/team/tmux.conf"
export CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1

if ! command -v tmux >/dev/null 2>&1; then
    echo "tmux 未安装。先执行：sudo apt-get update && sudo apt-get install -y tmux" >&2
    exit 1
fi
if ! command -v claude >/dev/null 2>&1; then
    echo "claude 不在 PATH；先确认终端版 Claude Code 可用。" >&2
    exit 1
fi
CLAUDE_BIN="$(command -v claude)"

if [ -n "${1:-}" ]; then
    CLAUDE_ARGS=(--resume "$1" --fork-session --teammate-mode tmux)
else
    CLAUDE_ARGS=(--continue --fork-session --teammate-mode tmux)
fi

if [ -n "${TMUX:-}" ]; then
    if [ -f "$CONF" ]; then
        tmux source-file "$CONF" 2>/dev/null || true
    fi
    exec "$CLAUDE_BIN" "${CLAUDE_ARGS[@]}"
fi

if tmux has-session -t "$SESSION" 2>/dev/null; then
    echo "tmux 会话 $SESSION 已存在；先 attach 查看，或执行：" >&2
    echo "  tmux kill-session -t $SESSION" >&2
    echo "确认没有活跃 lead 后再运行本脚本。" >&2
    exit 1
fi

if tmux list-sessions >/dev/null 2>&1; then
    if [ -f "$CONF" ]; then
        tmux source-file "$CONF" 2>/dev/null || true
    fi
    exec tmux new-session -s "$SESSION" -c "$ROOT" "$CLAUDE_BIN ${CLAUDE_ARGS[*]}"
fi

exec tmux -f "$CONF" new-session -s "$SESSION" -c "$ROOT" \
    "$CLAUDE_BIN ${CLAUDE_ARGS[*]}"
