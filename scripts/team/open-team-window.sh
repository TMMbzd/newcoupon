#!/usr/bin/env bash
# Open a Windows Terminal window running the stable terminal-first team:
# the lead is inside tmux, so teammate panes split in the same window.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DISTRO="${WSL_DISTRO_NAME:-Ubuntu-24.04}"
LEAD="$ROOT/scripts/team/team-session.sh"
WT="${WT_EXE:-wt.exe}"

if [ ! -f "$LEAD" ]; then
    echo "找不到 $LEAD" >&2
    exit 1
fi

CMD=("$WT" -w -1 new-tab --title "newcoupon team" \
    wsl.exe -d "$DISTRO" --cd "$ROOT" -- \
    bash -lc "$LEAD || { echo; read -r -p '按 Enter 关闭…' _; }")

if [ "${1:-}" = "--print" ]; then
    printf '%q ' "${CMD[@]}"
    printf '\n'
    exit 0
fi

if ! command -v "$WT" >/dev/null 2>&1; then
    echo "找不到 Windows Terminal（$WT）。可以在 WSL 终端里直接执行：" >&2
    echo "  bash $LEAD" >&2
    exit 1
fi

exec "${CMD[@]}"
