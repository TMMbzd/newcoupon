#!/usr/bin/env bash
#
# 三级预案演练（S2 第 10 步，RQ-9）——一条命令跑完：
#     注入故障 → 观察熔断打开 → 恢复故障 → 套用黄预案 → 行为验证 → 恢复预案
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
# 关于 IP 频控：POST /api/coupons/receive 默认限 60 秒 30 次。本脚本把总请求数
# 控制在 25 次以内、并拉长轮询间隔来规避；万一仍撞上 429/7001，脚本会明确报出来
# （而不是把它误判成熔断现象），此时用 -Dcoupon.risk.ip-rate.enabled=false 重启应用再跑。
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
  curl -sS $BASE/api/switches/breakers"

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

# ---------------------------------------------------------------- 观察熔断
step "4. 观察熔断：**走内部面**"
warn "开放面此时读不到——Redis 一挂，nonce 校验 fail-closed 会把请求先拒掉（5000/503），"
warn "这是**有意的安全行为**（防重放不能因为 Redis 挂就放行），不是缺陷。"
saw_timeout=0; saw_open=0
for i in $(seq 1 8); do
  body=$(curl -sS -X POST "$BASE/api/coupons/receive" -H "Content-Type: application/json" \
      -d "{\"requestId\":\"drill-fault-$i\",\"userId\":$USER_A,\"templateId\":$TPL_A}" 2>/dev/null || echo '{}')
  code=$(printf '%s' "$body" | json_get '["code"]' 2>/dev/null || echo "?")
  if [[ "$code" == "7001" ]]; then
    fail "被 IP 频控拦下（7001）——用 -Dcoupon.risk.ip-rate.enabled=false 重启应用再跑。
（脚本只在第 4 步就撞上限，说明频控阈值比预期低。）"
  fi
  [[ "$code" == "5000" ]] && saw_timeout=$((saw_timeout + 1))
  [[ "$code" == "5001" ]] && saw_open=$((saw_open + 1))
  [[ $saw_open -gt 0 ]] && break
  sleep 2
done
[[ $saw_timeout -gt 0 ]] || fail "没有观察到依赖超时（5000）——故障注入可能没生效"
ok "观察到依赖超时（5000）$saw_timeout 次"
[[ $saw_open -gt 0 ]] || fail "熔断始终没有打开（未见到 5001）"
ok "观察到熔断快速失败（5001）"

state=$(breaker_state redis || echo "?")
[[ "$state" == "OPEN" ]] || fail "熔断器状态期望 OPEN，实际 $state"
ok "熔断器状态 = OPEN（读 /api/switches/breakers 得到）"
PASS=$((PASS + 3))

# ---------------------------------------------------------------- 新增：防线失效的可见性
step "4'. 防线失效必须**看得见**（S3 第 6.5/7 步的承诺，在这里验）"
text=$(curl -sS "$BASE/actuator/prometheus" 2>/dev/null || echo "")
# ① 风控闸（Redis 频控）在故障期间是 fail-open 的——但它必须留下痕迹，
#    否则"风控闸已经失效"与"今天没人刷"在监控上完全一样。
printf '%s' "$text" | grep -q 'coupon_ratelimit_failopen_total{dimension="risk_ip"}' \
  || fail "Redis 故障期间没有出现 fail-open 指标（dimension=risk_ip）——防线失效变成静默的了"
ok "fail-open 指标已出现：coupon_ratelimit_failopen_total{dimension=\"risk_ip\"}"
# ② Sentinel 自身那两条 gauge（全局开关 / 处理链数量）——它们回答的是"整套限流还在不在"
printf '%s' "$text" | grep -q 'coupon_sentinel_enabled' \
  || fail "抓取里没有 coupon_sentinel_enabled（Sentinel 全局开关不可见）"
printf '%s' "$text" | grep -q 'coupon_sentinel_slot_chain_count' \
  || fail "抓取里没有 coupon_sentinel_slot_chain_count（处理链数量不可见）"
ok "Sentinel 自身状态可见（全局开关 + 处理链数量）"
PASS=$((PASS + 3))

# ---------------------------------------------------------------- 恢复故障
step "5. 恢复故障：熔断应当自己闭合（半开探测 → 闭合）"
docker unpause "$REDIS_CONTAINER" >/dev/null
ok "Redis 已恢复"
recovered=0
for i in $(seq 1 10); do
  body=$(curl -sS -X POST "$BASE/api/coupons/receive" -H "Content-Type: application/json" \
      -d "{\"requestId\":\"drill-heal-$i\",\"userId\":$USER_A,\"templateId\":$TPL_A}" 2>/dev/null || echo '{}')
  code=$(printf '%s' "$body" | json_get '["code"]' 2>/dev/null || echo "?")
  [[ "$code" == "7001" ]] && fail "被 IP 频控拦下（7001）——见第 4 步的说明"
  if [[ "$code" == "0" ]]; then recovered=$((recovered + 1)); fi
  if [[ $recovered -ge 1 && "$(breaker_state redis)" == "CLOSED" ]]; then break; fi
  sleep 5
done
[[ $recovered -gt 0 ]] || fail "故障恢复后请求仍未成功——检查 Redis 是否真的恢复了"
ok "业务已恢复（成功 $recovered 次）"
state=$(breaker_state redis || echo "?")
[[ "$state" == "CLOSED" ]] || warn "熔断器状态为 $state（半开探测需要几次成功才闭合，可稍后复查）"
[[ "$state" == "CLOSED" ]] && ok "熔断器状态 = CLOSED"
PASS=$((PASS + 2))

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
