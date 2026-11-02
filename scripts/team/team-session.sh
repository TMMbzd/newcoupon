#!/usr/bin/env bash
# Terminal-first team launcher: start (or attach to) a tmux session and run
# Claude Code with tmux teammate panes.
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

# Already inside tmux: run Claude in the current pane; it will split this
# window for teammates.
if [ -n "${TMUX:-}" ]; then
    if [ -f "$CONF" ]; then
        tmux source-file "$CONF" 2>/dev/null || true
    fi
    exec "$CLAUDE_BIN" --teammate-mode tmux
fi

if tmux has-session -t "$SESSION" 2>/dev/null; then
    if [ -f "$CONF" ]; then
        tmux source-file "$CONF" 2>/dev/null || true
    fi
    exec tmux attach-session -t "$SESSION"
fi

# If a tmux server already exists (for another session), source the project
# settings before creating our session.  Otherwise use -f so the new server
# starts with them.
if tmux list-sessions >/dev/null 2>&1; then
    if [ -f "$CONF" ]; then
        tmux source-file "$CONF" 2>/dev/null || true
    fi
    exec tmux new-session -s "$SESSION" -c "$ROOT" "$CLAUDE_BIN --teammate-mode tmux"
fi

exec tmux -f "$CONF" new-session -s "$SESSION" -c "$ROOT" \
    "$CLAUDE_BIN --teammate-mode tmux"
