#!/usr/bin/env bash
#
# 三级预案演练（S2 第 10 步，RQ-9）——一条命令跑完：
#     注入 Redis 故障 → 观察 fail-closed/fail-open → 恢复 → 套用黄预案 → 行为验证 → 恢复预案
#
# 2026-11-06 更新（V6 阶段 5B 复跑取证）：C1 之后 Redis 不再是库存权威——同步领券的
# 库存/落库都在 MySQL，Redis 只做售罄提示（try/catch 降级）。旧版脚本"暂停 Redis 后
# 同步领券应 5000→5001、redis 熔断器 OPEN"的断言已经不成立（redis 熔断器也没有接线点）。
# 现在验证的是真实强依赖：异步准入 gate（receive-async）在 Redis 故障时 fail-closed
# 返回 5001（reject 策略），以及风控闸 fail-open 指标必须可见。
#
# **为什么演练脚本要当成交付物**（与 demo.sh / negative.sh 同样的理由）：
# 治理能力的价值不在"配好了"，而在**出事时真的管用**。能一条命令跑完
# "故障 → 降级 → 恢复"并逐步断言的演练，才敢说这套东西是活的。
# 只打印不断言的脚本，在交接时分不清"看过"与"验过"。
#
# ⚠️ 这个脚本会：① 暂停本机 Redis 容器  ② 改真实租户的开关配置。
#    所以有三道护栏：
#      · BASE 必须是本机（除非显式 ALLOW_REMOTE=1）
#      · 必须显式 `CONFIRM=yes`
#      · 退出时（含 Ctrl-C 与**断电后的下次启动**）自动恢复 Redis 与预案
#        —— 本项目真的遇到过会话被断电中止，所以状态要落文件、下次启动能发现并拒绝
#
# 用法：
#   make up && make run            # 另一个终端
#   CONFIRM=yes bash scripts/open-api/drill.sh
#
# 关于 IP 频控：领券入口默认限 60 秒 30 次。本脚本把总请求数控制在 25 次以内、
# 并拉长轮询间隔来规避；万一仍撞上 7001，脚本会明确报出来（而不是把它误判成
# 故障现象），此时用 -Dcoupon.risk.ip-rate.enabled=false 重启应用再跑。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

REDIS_CONTAINER="${REDIS_CONTAINER:-newcoupon-redis}"
STATE_FILE="${DRILL_STATE_FILE:-/tmp/newcoupon-drill.state}"
USER_A=96001
PASS=0

expect() { expect_http_and_code "$@"; PASS=$((PASS + 1)); }

# ---------------------------------------------------------------- 清理
# 幂等：重复执行无害。**先恢复依赖，再恢复配置**——反过来会在依赖不可用时改配置。
cleanup() {
  local rc=$?
  if [[ -f "$STATE_FILE" ]]; then
    warn "清理：恢复 Redis 与预案（脚本退出码 $rc）"
    docker unpause "$REDIS_CONTAINER" >/dev/null 2>&1 || true
    # shellcheck disable=SC1090
    source "$STATE_FILE" 2>/dev/null || true
    if [[ -n "${DRILL_APP:-}" ]]; then
      admin_post "/api/apps/$DRILL_APP/plans/revert" '{"reason":"drill-cleanup"}' >/dev/null 2>&1 || true
    fi
    rm -f "$STATE_FILE"
    ok "已恢复：Redis 继续运行、预案已回滚"
  fi
  return $rc
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------- 前置检查
# 切换当前签名凭据。**每次发签名请求前都显式切一次**，不要依赖"上一步结束时是谁"——
# 第一版脚本就是靠隐式状态：给 B 加了两句准备动作后，步骤 2 悄悄变成用 B 的凭据发请求，
# 报出来的是"A 领券 403 越权"，而真正的原因是凭据没切回来。
use_app() {
  case "$1" in
    A) APP_ID="$APP_A"; APP_SECRET="$SECRET_A" ;;
    B) APP_ID="$APP_B"; APP_SECRET="$SECRET_B" ;;
    *) fail "未知的演练租户：$1" ;;
  esac
}

step "0. 前置检查（三道护栏）"
[[ "${CONFIRM:-}" == "yes" ]] \
  || fail "演练会暂停 Redis 并修改真实租户配置。确认请加：CONFIRM=yes"

case "$BASE" in
  http://localhost:*|http://127.0.0.1:*) ok "目标是本机：$BASE" ;;
  *)
    [[ "${ALLOW_REMOTE:-0}" == "1" ]] \
      || fail "BASE=$BASE 不是本机。演练只应打本机（Redis 容器也是本机的）；确要远程请加 ALLOW_REMOTE=1"
    warn "目标是远程环境（ALLOW_REMOTE=1 已显式放开）"
    ;;
esac

docker inspect -f '{{.State.Running}}' "$REDIS_CONTAINER" 2>/dev/null | grep -q true \
  || fail "容器 $REDIS_CONTAINER 不在运行——先 make up"
ok "Redis 容器在运行"

curl -sf "$BASE/actuator/health" >/dev/null || fail "服务不可达：$BASE（先在另一个终端 make run）"
ok "服务可达"

[[ ! -f "$STATE_FILE" ]] || fail "发现残留的演练状态文件 $STATE_FILE——
上次演练可能被中断（Ctrl-C 或断电）。请先确认 Redis 未被暂停、开关已恢复，再删除该文件重跑。
  docker ps --filter name=$REDIS_CONTAINER    # 状态应为 Up 而非 Paused
  curl -sS $BASE/api/switches/breakers -H 'X-Admin-Token: <你的管理令牌>'   # 2A-2 起 GET 也要令牌"

# ---------------------------------------------------------------- 准备
step "1. 准备两个演练租户（drill- 前缀，便于与真实租户区分）"
register_app "drill-a" 0 0
APP_A="$APP_ID"; SECRET_A="$APP_SECRET"
register_app "drill-b" 0 0
APP_B="$APP_ID"; SECRET_B="$APP_SECRET"
# 落文件：脚本被中断时，cleanup 靠它知道该回滚哪个租户的预案
printf 'DRILL_APP=%q\n' "$APP_A" > "$STATE_FILE"
ok "演练租户 A=$APP_A"
ok "演练租户 B=$APP_B"

use_app A
resp=$(signed_call_ok "A 建券" POST /open/v1/templates \
    '{"name":"演练券","activityId":1001,"couponType":1,"faceValue":1000,"threshold":10000,"totalCount":1000,"perUserLimit":5,"validType":2,"validDays":7}' \
    "drill-create-$(date +%s%3N)")
TPL_A=$(printf '%s' "$resp" | json_get '["data"]["id"]')
signed_call_ok "A 发布" POST "/open/v1/templates/$TPL_A/publish" "" "drill-pub-$(date +%s%3N)" >/dev/null

# B 也要有自己的模板，但**留到第 6 步才发布**：这既是"对照组"，也是"隔离组"。
# 踩过两次坑，都记在这：
#   ① 第一版让 B 去发布 A 的模板 → 拿到越权码 6011，测的是越权不是隔离；
#   ② 第二版让 B 在准备阶段就发布 → 第 6 步再发布时撞上"已发布不可再发布"（2003），
#      因为"发布"这个动作本身不是幂等的。
# 结论：**对照动作要选可重复的、且与被对照的动作完全同构**。
use_app B
resp=$(signed_call_ok "B 建券（暂不发布）" POST /open/v1/templates \
    '{"name":"演练券B","activityId":1001,"couponType":1,"faceValue":1000,"threshold":10000,"totalCount":1000,"perUserLimit":5,"validType":2,"validDays":7}' \
    "drill-create-b-$(date +%s%3N)")
TPL_B=$(printf '%s' "$resp" | json_get '["data"]["id"]')

# ---------------------------------------------------------------- 基线
step "2. 基线：故障之前，领券正常（没有这一步，后面的失败就无法归因）"
use_app A
expect "A 领券成功" 200 0 POST /open/v1/coupons/receive \
    "{\"requestId\":\"drill-base-$(date +%s%3N)\",\"userId\":$USER_A,\"templateId\":$TPL_A}" \
    "drill-base-tok-$(date +%s%3N)"

# ---------------------------------------------------------------- 注入故障
step "3. 注入故障：暂停 Redis 容器"
docker pause "$REDIS_CONTAINER" >/dev/null
ok "Redis 已暂停（TCP 仍可连、命令不再响应——这正是熔断该保护的形态）"

# ---------------------------------------------------------------- 观察故障期行为
step "4. 观察故障期行为：异步准入 gate 是 Redis 的唯一强依赖路径"
warn "C1 之后 Redis 不再是库存权威：同步领券的库存/落库都在 MySQL，Redis 只做售罄提示"
warn "（try/catch 降级），所以它会继续成功——旧版脚本在这里等 5000/5001 已经过时。"
warn "Redis 的强依赖点是异步准入：预扣失败按 reject 策略返回 5001（可重试、fail-closed）。"
saw_degraded=0; saw_success=0
for i in $(seq 1 3); do
  body=$(curl -sS -X POST "$BASE/api/coupons/receive-async" -H "Content-Type: application/json" \
      -d "{\"requestId\":\"drill-async-fault-$i-$(date +%s%3N)\",\"userId\":$USER_A,\"templateId\":$TPL_A}" 2>/dev/null || echo '{}')
  code=$(printf '%s' "$body" | json_get '["code"]' 2>/dev/null || echo "?")
  if [[ "$code" == "7001" ]]; then
    fail "被 IP 频控拦下（7001）——用 -Dcoupon.risk.ip-rate.enabled=false 重启应用再跑。
（脚本只在第 4 步就撞上限，说明频控阈值比预期低。）"
  fi
  [[ "$code" == "5001" ]] && saw_degraded=$((saw_degraded + 1))
  [[ "$code" == "0" ]] && saw_success=$((saw_success + 1))
  sleep 1
done
[[ $saw_degraded -gt 0 ]] || fail "Redis 暂停期间异步受理没有返回 5001——故障注入没生效或 reject 策略被绕过"
[[ $saw_success -eq 0 ]] || fail "Redis 暂停期间异步受理仍成功（预扣路径不该 fail-open）"
ok "故障期异步受理 fail-closed：5001 共 $saw_degraded 次，成功 0 次"
PASS=$((PASS + 1))

# ---------------------------------------------------------------- 恢复故障
step "5. 恢复故障：Redis 恢复后异步 gate 重新可用"
docker unpause "$REDIS_CONTAINER" >/dev/null
sleep 1
[[ "$(docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | tr -d '\r')" == "PONG" ]] \
  || fail "Redis 恢复后 PING 不是 PONG"
ok "Redis 已恢复（PING=PONG）"
PASS=$((PASS + 1))

# 用新用户，避免"基线已经领过"的限领码混进恢复判据。
RECOVER_USER=97001
body=$(curl -sS -X POST "$BASE/api/coupons/receive-async" -H "Content-Type: application/json" \
    -d "{\"requestId\":\"drill-async-heal-$(date +%s%3N)\",\"userId\":$RECOVER_USER,\"templateId\":$TPL_A}" 2>/dev/null || echo '{}')
code=$(printf '%s' "$body" | json_get '["code"]' 2>/dev/null || echo "?")
[[ "$code" == "0" ]] || fail "Redis 恢复后异步受理仍失败：$body"
ok "异步受理恢复（code=0）"
PASS=$((PASS + 1))

RECOVER_SYNC_USER=97002
body=$(curl -sS -X POST "$BASE/api/coupons/receive" -H "Content-Type: application/json" \
    -d "{\"requestId\":\"drill-sync-heal-$(date +%s%3N)\",\"userId\":$RECOVER_SYNC_USER,\"templateId\":$TPL_A}" 2>/dev/null || echo '{}')
code=$(printf '%s' "$body" | json_get '["code"]' 2>/dev/null || echo "?")
[[ "$code" == "0" ]] || fail "Redis 恢复后同步领券失败：$body"
ok "同步领券正常（code=0）"
PASS=$((PASS + 1))

# ---------------------------------------------------------------- 防线失效的可见性
step "5'. 防线失效必须**看得见**（S3 第 6.5/7 步的承诺，在这里验）"
# 为什么放在恢复之后读：这些是**累计计数器**（故障期间已 +N），恢复后读到的仍是同一份事实；
# 计数器不会因为恢复而清零，证据不丢。抓取结果落盘，断言全部 grep 文件而不是管道
# （2026-11-06 实测踩坑：`printf '%s' "$text" | grep -q` 在响应约 99KB > 管道缓冲时，
#  grep 提前退出会让 printf 收到 SIGPIPE，pipefail 把整条管道判成失败——指标明明在文本里，
#  断言却报"没有出现"）。grep 文件没有这个信号问题。
# 2A-2 起 prometheus 需要 Basic 凭据（与 SecurityConfig 的 actuator 链一致）；
# ACTUATOR_AUTH 默认值=SecurityRbacIntegrationTest 同款 dev 凭据，生产用环境变量覆盖。
ACTUATOR_AUTH="${ACTUATOR_AUTH:-actuator:it-actuator-pass}"
SCRAPE_FILE="/tmp/newcoupon-drill-prometheus.txt"
text=$(curl -sS --max-time 15 -u "$ACTUATOR_AUTH" "$BASE/actuator/prometheus" 2>/dev/null || echo "")
printf '%s' "$text" > "$SCRAPE_FILE"
warn "Prometheus 抓取 ${#text} 字符（落盘 $SCRAPE_FILE，凭据 $ACTUATOR_AUTH）"
# ① 风控闸（Redis 频控）在故障期间是 fail-open 的——但它必须留下痕迹，
#    否则"风控闸已经失效"与"今天没人刷"在监控上完全一样。
grep -q 'coupon_ratelimit_failopen_total{dimension="risk_ip"}' "$SCRAPE_FILE" \
  || fail "Redis 故障期间没有出现 fail-open 指标（dimension=risk_ip）——防线失效变成静默的了"
ok "fail-open 指标已出现：coupon_ratelimit_failopen_total{dimension=\"risk_ip\"}"
# ② Sentinel 自身那两条 gauge（全局开关 / 处理链数量）——它们回答的是"整套限流还在不在"
grep -q 'coupon_sentinel_enabled' "$SCRAPE_FILE" \
  || fail "抓取里没有 coupon_sentinel_enabled（Sentinel 全局开关不可见）"
grep -q 'coupon_sentinel_slot_chain_count' "$SCRAPE_FILE" \
  || fail "抓取里没有 coupon_sentinel_slot_chain_count（处理链数量不可见）"
ok "Sentinel 自身状态可见（全局开关 + 处理链数量）"
# ③ Redis 故障策略路径也必须是"看得见"的：reject 策略命中会打这个计数。
#    没有它，我们只知道"返回了 5001"，不知道是 gate 的 reject 策略还是别的降级码。
grep -q 'issue_gate_result_total{result="redis_down_reject"' "$SCRAPE_FILE" \
  || fail "抓取里没有 issue_gate_result_total{result=\"redis_down_reject\"}——无法证明走的是 Redis-down reject 策略"
ok "Redis-down 策略路径可见：issue_gate_result_total{result=\"redis_down_reject\"}"
PASS=$((PASS + 4))

# ---------------------------------------------------------------- 套用预案
step "6. 套用黄预案（租户 A）并验证"
curl -sS -X POST "$BASE/api/apps/$APP_A/plans/apply/YELLOW" \
    -H "Content-Type: application/json" -H "X-Admin-Token: $ADMIN_TOKEN" \
    -d '{"reason":"drill"}' | json_get '["code"]' | grep -q '^0$' \
  || fail "套用黄预案失败"
ok "黄预案已套用"

use_app A
raw=$(signed_call_raw POST "/open/v1/templates/$TPL_A/publish" "" "drill-pub-after-$(date +%s%3N)")
split_response "$raw"
code=$(printf '%s' "$RESP_BODY" | json_get '["code"]' 2>/dev/null || echo "?")
[[ "$code" == "5001" ]] || fail "黄预案没生效：非核心接口 publish 期望 5001，实际 $code —— $RESP_BODY"
printf '%s' "$RESP_BODY" | grep -q "券模板发布开关" \
  || fail "降级信息没说清是哪个开关被关了：$RESP_BODY"
ok "非核心接口已被关（publish → 5001，且指明了开关）"
PASS=$((PASS + 1))

expect "核心链路仍可用（领券）" 200 0 POST /open/v1/coupons/receive \
    "{\"requestId\":\"drill-after-$(date +%s%3N)\",\"userId\":$USER_A,\"templateId\":$TPL_A}" \
    "drill-after-tok-$(date +%s%3N)"

use_app B
expect "租户隔离：B 的发布不受影响" 200 0 POST "/open/v1/templates/$TPL_B/publish" "" \
    "drill-iso-$(date +%s%3N)"

# ---------------------------------------------------------------- 恢复预案
step "7. 恢复预案：A 的开关回到套用前"
admin_post "/api/apps/$APP_A/plans/revert" '{"reason":"drill"}' | json_get '["code"]' | grep -q '^0$' \
  || fail "恢复预案失败"
rm -f "$STATE_FILE"   # 已恢复完毕：删掉状态文件，避免退出时重复回滚
ok "预案已恢复"

use_app A
# 判据是"**不再被开关拦下**"而不是"业务码 = 0"：A 的模板在准备阶段就发布过了，
# 再发布会撞上"已发布不可再发布"（2003）——那是正常的业务校验，与开关无关。
raw=$(signed_call_raw POST "/open/v1/templates/$TPL_A/publish" "" "drill-restored-$(date +%s%3N)")
split_response "$raw"
code=$(printf '%s' "$RESP_BODY" | json_get '["code"]' 2>/dev/null || echo "?")
[[ "$code" != "5001" ]] || fail "恢复预案之后开关仍然是关的：$RESP_BODY"
ok "恢复后 A 的发布不再被开关拦截（业务码 $code —— 2003=模板已发布，属正常业务校验）"
PASS=$((PASS + 1))

# ---------------------------------------------------------------- 收尾
step "演练完成"
ok "全部断言通过"
echo "DRILL PASS assertions=$PASS"
