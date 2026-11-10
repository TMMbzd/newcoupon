#!/usr/bin/env bash
#
# host-gateway 新建连接停滞率探测（V6 5B 刀 A 审查 m4②）——把 2026-11-10 的隔离
# 实验固化成可复跑脚本，参数与判定阈值写死在脚本里。
#
# 验的是什么：Docker Desktop（WSL2）下容器→宿主唯一通道 host.docker.internal
# （host-gateway 用户态代理）对**新建连接**有随机停滞率。本刀演练的前几轮假红
# 根因就是它（每请求新建连接=每请求抽签，抽中=connect 超时→好实例被被动摘除
# 误伤出池）。阈值基线：2026-11-10 实测 ~0.25%（2500 次新建连接 6 次停滞 >12s，
# 与 Java/nginx 无关，隔离环境即容器 wget 打宿主 python http.server）。
#
# 判定（写死）：
#   - 请求总数 REQUESTS（默认 2500），间隔 0.04s（≈25 rps，对齐演练客户端量级）
#   - 单请求超时 12s（wget -T 12；超过即计一次"停滞"）
#   - 停滞率 > 1%（=基线 0.25% 的 4 倍余量）→ 退出码 1（通道劣化，演练不可信）
#   - 其余情况退出码 0，并打印实测停滞率供登记
#
# 用法：
#   CONFIRM=yes bash scripts/stability/host-gateway-stall-probe.sh
#   （REQUESTS=500 CONFIRM=yes ... 可缩短；自起 python http.server 临时靶机，用后即清）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../open-api/lib.sh"

PORT="${PROBE_PORT:-18091}"
REQUESTS="${REQUESTS:-2500}"
INTERVAL="${INTERVAL:-0.04}"
TIMEOUT_S="${TIMEOUT_S:-12}"
MAX_FAIL_RATE="${MAX_FAIL_RATE:-1}"   # 百分比上限（基线 0.25% 的 4 倍）

STANDIN_PID=""
cleanup() {
  [[ -n "$STANDIN_PID" ]] && kill "$STANDIN_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

step "0. 护栏与靶机"
[[ "${CONFIRM:-}" == "yes" ]] || fail "会起临时 http.server 与一次性容器。确认请加：CONFIRM=yes"
ss -ltn 2>/dev/null | grep -q ":$PORT " && fail "端口 $PORT 被占用（换个 PROBE_PORT）"
docker info >/dev/null 2>&1 || fail "docker 不可用"
nohup python3 -m http.server "$PORT" --bind 0.0.0.0 > /tmp/host-gateway-standin.log 2>&1 &
STANDIN_PID=$!
sleep 1
curl -sf -o /dev/null "http://127.0.0.1:$PORT/" || fail "靶机没起来"

step "1. 容器内新建 $REQUESTS 个连接（间隔 ${INTERVAL}s，单请求超时 ${TIMEOUT_S}s）"
FAILS=$(docker run --rm --add-host host.docker.internal:host-gateway nginx:1.27-alpine sh -c "
  i=0; fails=0
  while [ \$i -lt $REQUESTS ]; do
    i=\$((i+1))
    wget -q -O /dev/null -T $TIMEOUT_S -t 1 http://host.docker.internal:$PORT/ 2>/dev/null || fails=\$((fails+1))
    sleep $INTERVAL
  done
  echo \$fails")
ok "完成：$REQUESTS 次新建连接，停滞 $FAILS 次"

RATE=$(python3 -c "print(round($FAILS * 100.0 / $REQUESTS, 3))")
ok "停滞率 ${RATE}%（基线 2026-11-10 实测 ~0.25%；阈值 ${MAX_FAIL_RATE}%）"

STEP_OK=$(python3 -c "print(1 if $FAILS * 100.0 / $REQUESTS <= $MAX_FAIL_RATE else 0)")
if [[ "$STEP_OK" == "1" ]]; then
  ok "GATEWAY PROBE PASS requests=$REQUESTS fails=$FAILS rate=${RATE}%"
else
  fail "停滞率超阈值：${RATE}% > ${MAX_FAIL_RATE}%——通道劣化，经 LB 的演练结果不可信"
fi
