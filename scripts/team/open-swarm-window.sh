#!/usr/bin/env bash
# Open Windows Terminal in WSL and attach to Claude Code's claude-swarm
# session.  Use --print to inspect the exact command without opening a window.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DISTRO="${WSL_DISTRO_NAME:-Ubuntu-24.04}"
ATTACH="$ROOT/scripts/team/attach-swarm.sh"
WT="${WT_EXE:-wt.exe}"

if [ ! -f "$ATTACH" ]; then
    echo "找不到 $ATTACH" >&2
    exit 1
fi

# -w -1 asks Windows Terminal for a new window.  If your Windows Terminal
# version rejects it, remove the "-w -1" pair.
CMD=("$WT" -w -1 new-tab --title "newcoupon teammates" \
    wsl.exe -d "$DISTRO" --cd "$ROOT" -- \
    bash -lc "$ATTACH || { echo; read -r -p '按 Enter 关闭…' _; }")

if [ "${1:-}" = "--print" ]; then
    printf '%q ' "${CMD[@]}"
    printf '\n'
    exit 0
fi

if ! command -v "$WT" >/dev/null 2>&1; then
    echo "找不到 Windows Terminal（$WT）。可以在 WSL 终端里直接执行：" >&2
    echo "  bash $ATTACH" >&2
    exit 1
fi

exec "${CMD[@]}"
