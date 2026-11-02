#!/usr/bin/env bash
# Attach to the private tmux server Claude Code creates for teammate panes
# when an interactive lead runs outside tmux (VS Code terminal mode).
#
# Claude Code uses:
#   socket:  claude-swarm-<lead-pid>
#   session: claude-swarm
#   window:  swarm-view
# The socket is private, so plain `tmux ls` will not show it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONF="$ROOT/scripts/team/tmux.conf"
TMUX_DIR="${TMUX_TMPDIR:-/tmp}/tmux-$(id -u)"
SESSION="${NEWCOUPON_SWARM_SESSION:-claude-swarm}"
WINDOW="${NEWCOUPON_SWARM_WINDOW:-swarm-view}"
EXPLICIT_SOCKET="${NEWCOUPON_SWARM_SOCKET:-}"

if ! command -v tmux >/dev/null 2>&1; then
    echo "tmux 未安装。先执行：sudo apt-get update && sudo apt-get install -y tmux" >&2
    exit 1
fi

find_socket() {
    if [ -n "$EXPLICIT_SOCKET" ]; then
        if tmux -L "$EXPLICIT_SOCKET" list-sessions >/dev/null 2>&1; then
            printf '%s\n' "$EXPLICIT_SOCKET"
            return 0
        fi
        return 1
    fi

    local sock name
    while IFS= read -r sock; do
        name="$(basename "$sock")"
        if tmux -L "$name" list-sessions >/dev/null 2>&1; then
            printf '%s\n' "$name"
            return 0
        fi
    done < <(find "$TMUX_DIR" -maxdepth 1 -type s -name 'claude-swarm-*' \
        -printf '%T@ %p\n' 2>/dev/null | sort -nr | cut -d' ' -f2-)
    return 1
}

SOCKET="$(find_socket || true)"
if [ -z "$SOCKET" ]; then
    cat >&2 <<EOF
没有找到活跃的 claude-swarm tmux server。

先用交互式终端启动 lead（不是 VS Code 原生面板），并在里面 spawn 至少一个队友：
  bash scripts/team/vscode-terminal-lead.sh
或：
  bash scripts/team/team-session.sh

lead 的底部会打印：
  View teammates: \`tmux -L claude-swarm-<pid> a\`

排查：
  ls -l "$TMUX_DIR"/claude-swarm-*
  tmux -L claude-swarm-<pid> list-sessions
EOF
    exit 1
fi

TARGET="$SESSION"
if tmux -L "$SOCKET" list-windows -t "$SESSION" -F '#{window_name}' 2>/dev/null | grep -qx "$WINDOW"; then
    TARGET="$SESSION:$WINDOW"
fi

if [ "${1:-}" = "--check" ] || [ "${1:-}" = "--print" ]; then
    if [ "${1:-}" = "--print" ]; then
        printf 'tmux -L %q attach -t %q\n' "$SOCKET" "$TARGET"
    else
        printf 'socket=%s target=%s\n' "$SOCKET" "$TARGET"
    fi
    exit 0
fi

# Apply mouse/truecolor/focus settings to this private server.  Some options
# (notably default-terminal) only take effect for newly created panes.
if [ -f "$CONF" ]; then
    tmux -L "$SOCKET" source-file "$CONF" 2>/dev/null || true
fi

if [ -n "${TMUX:-}" ]; then
    # A tmux client cannot attach to a different server from inside another
    # client; print the command to run in a separate terminal.
    echo "当前已在一个 tmux 客户端里；请另开终端执行：" >&2
    echo "  tmux -L $SOCKET attach -t $TARGET" >&2
    exit 0
fi

exec tmux -L "$SOCKET" attach -t "$TARGET"
