#!/usr/bin/env bash
#
# MySQL 硬故障演练（V6 阶段 5B 刀 2 ②）——停掉 MySQL 容器，验证"故障期行为可预期、
# 恢复后能自愈、库存不超发"，并输出 DRILL PASS assertions=N。
#
# 演练形态：**同一台本机**起应用 + MySQL + Redis，`docker stop newcoupon-mysql` 制造硬故障，
# 不是网络延迟/丢包（ToxiProxy 那类）。理由：硬故障是最坏情况，且不需要额外代理进程，
# 在 WSL 内存预算内可重复跑。网络类故障登记为裁剪项（见 evidence 报告）。
#
# 断言依据的是**现有代码的实际行为**，不是期望：
#   · 同步领券 /api/coupons/receive：落库路径包在 mysql 依赖级熔断里（ReceiveAppService
#     .withBreakers），熔断打开后抛 BizException(SYSTEM_ERROR=5000)。所以故障期看到的
#     业务码是 **5000（可重试的系统繁忙）**，不是 5001（5001 是 SwitchGuard/REDIS 降级码）。
#   · 异步领券 /api/coupons/receive-async：先抢 Redis gate 预扣，再插任务表；MySQL 挂时
#     任务插入失败 → 补偿脚本退回预扣 → 返回 5000。**受理失败不落券、不泄漏库存**。
#   · 核销 /api/coupons/confirm：占用单/券状态全在 MySQL，故障期返回 5000；恢复后同一
#     usageNo 仍可正常核销（幂等/状态机不被故障改坏）。
#   · mysql 熔断器：故障期由失败/慢调用推 OPEN，恢复后 HALF_OPEN → CLOSED。
#     证据用 Prometheus `state="open"=1` + `kind="failed"` 调用数 ≥ min-calls=5 +
#     应用日志 `CLOSED → OPEN` 三份独立观测；**不断言 notPermitted>0**——实测它可能
#     是 0（缓存过期后请求在进入熔断段之前就因读 DB 失败而 5000，没被 breaker 拦下）。
#
# 三道护栏（照 scripts/open-api/drill.sh）：
#   · BASE 必须是本机；确要远程请 ALLOW_REMOTE=1（不建议）。
#   · 必须 CONFIRM=yes。
#   · 退出时（含 Ctrl-C）一定把 MySQL 容器拉起来、把脚本自己起的应用停掉；
#     残留状态文件会在下次启动时提示。
#
# 资源纪律：应用 -Xmx384m；worker 初始延迟调到 10 分钟，避免后台任务在故障期搅局。
#
# 用法：
#   make up
#   CONFIRM=yes bash scripts/stability/mysql-outage-drill.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
# PORT/BASE 必须在 source lib.sh **之前**决定：lib.sh 里 BASE 默认值写死 8080，
# source 之后再写 `${BASE:-...}` 永远拿不到 PORT 覆盖（2026-11-06 审查修正）。
PORT="${PORT:-8080}"
BASE="${BASE:-http://localhost:$PORT}"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-newcoupon-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-newcoupon-redis}"
STATE_FILE="${MYSQL_DRILL_STATE_FILE:-/tmp/newcoupon-mysql-drill.state}"
LOGDIR="$REPO_DIR/target/stability"
APP_LOG="$LOGDIR/mysql-drill-app.log"
mkdir -p "$LOGDIR"
# actuator Basic 凭据：故障期 /api 管理面要查 RBAC 权限表（LegacyAdminTokenAuthenticationFilter
# 的 authenticateTokenHolder 会 SELECT 权限码），MySQL 一挂它也不可用了；因此熔断器状态
# 一律从 /actuator/prometheus 读（独立安全链，不碰业务库）。
ACTUATOR_AUTH="${ACTUATOR_AUTH:-actuator:it-actuator-pass}"
ACTUATOR_AUTH_PASS="${ACTUATOR_AUTH#*:}"

PASS=0
APP_PID=""
APP_STARTED_BY_US=0
TMPDIR_DRILL=$(mktemp -d)

assert_true() {   # $1=描述 $2=1/0
  PASS=$((PASS + 1))
  if [[ "$2" == "1" ]]; then ok "$1"; else fail "断言失败：$1"; fi
}

mysql_sql() {   # 在容器里查业务库（只在 MySQL 运行时调用）
  docker exec -e MYSQL_PWD=root123456 "$MYSQL_CONTAINER" \
    mysql -uroot -N -B newcoupon -e "$1" 2>/dev/null
}

mysql_running() {
  docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -q true
}

redis_running() {
  docker inspect -f '{{.State.Running}}' "$REDIS_CONTAINER" 2>/dev/null | grep -q true
}

stop_app() {
  if [[ "$APP_STARTED_BY_US" == "1" && -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill -TERM "$APP_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$APP_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$APP_PID" 2>/dev/null || true
  fi
}

# ---------------------------------------------------------------- 清理
# 顺序：先恢复依赖（MySQL），再停应用——反过来会让应用在依赖不可用时收尾。
cleanup() {
  local rc=$? start_failed=0
  warn "清理（脚本退出码 $rc）：确保 MySQL 已启动、脚本启动的应用已停止"
  if ! mysql_running; then
    if ! docker start "$MYSQL_CONTAINER" >/dev/null 2>&1; then
      start_failed=1
    else
      for _ in $(seq 1 60); do
        mysql_running && break
        sleep 1
      done
      mysql_running || start_failed=1
    fi
  fi
  stop_app
  rm -rf "$TMPDIR_DRILL"
  if [[ "$start_failed" == "0" ]]; then
    rm -f "$STATE_FILE"
    ok "清理完成"
  else
    # 保留状态文件：下次运行会提示"上次可能被中断"，要求人工确认 MySQL 状态。
    warn "清理未完成：MySQL 容器未能恢复运行；保留状态文件 $STATE_FILE，请手动 docker start 后删除"
  fi
  if [[ "$rc" == "0" && "$start_failed" == "1" ]]; then
    rc=1
  fi
  return "$rc"
}
trap cleanup EXIT INT TERM

start_app() {
  local cp_file="$REPO_DIR/target/stability-cp.txt"
  if [[ ! -f "$cp_file" || ! -d "$REPO_DIR/target/classes" ]]; then
    warn "缺 target/classes 或依赖 classpath，先编译（mvn -q -DskipTests compile dependency:build-classpath）"
    ( cd "$REPO_DIR" && mvn -q -DskipTests compile dependency:build-classpath \
        -Dmdep.outputFile="$cp_file" )
  fi
  local cp="$REPO_DIR/target/classes:$(cat "$cp_file")"
  # ACTUATOR_AUTH_PASSWORD：配置里默认空 → 启动时随机生成，演练脚本就拿不到 Prometheus；
  # 显式传固定值只用于本机演练（生产用环境变量覆盖，语义不变）。
  #
  # allowPublicKeyRetrieval=true（演练环境覆盖，不改仓库配置）：2026-11-06 实测发现，
  # dev 默认 JDBC URL 没有这个参数时，MySQL 容器重启后 caching_sha2_password 缓存清空、
  # 驱动要走全量认证 → "Public Key Retrieval is not allowed" → Hikari 一条新连接都建不起来
  # → 应用**永远无法自愈**（熔断恢复断言必然失败）。这是真实的配置缺口，登记在
  # docs/evidence/2026-11-06-MySQL故障演练.md §5；本脚本用启动参数覆盖，让演练验证的是
  # "熔断/业务恢复"，而不是被这个 URL 参数挡住。生产应改为 SSL 或显式加该参数。
  ACTUATOR_AUTH_PASSWORD="$ACTUATOR_AUTH_PASS" \
  java -Xmx384m -cp "$cp" com.newcoupon.NewCouponApplication \
    "--server.port=$PORT" \
    --coupon.risk.ip-rate.enabled=false \
    --coupon.issue.worker.initial-delay-ms=600000 \
    "--spring.datasource.url=jdbc:mysql://localhost:3306/newcoupon?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true" \
    "--coupon.sharding.url=jdbc:mysql://127.0.0.1:3306/newcoupon?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
    > "$APP_LOG" 2>&1 &
  APP_PID=$!
  APP_STARTED_BY_US=1
  for _ in $(seq 1 90); do
    # --max-time 3：Tomcat 已监听但 Spring 上下文未就绪时，无超时的 curl 会一直挂着，
    # 整个健康检查循环就永远不进入下一轮（2026-11-06 实测踩到过）。
    if curl -sf --max-time 3 "$BASE/actuator/health" >/dev/null 2>&1; then return 0; fi
    kill -0 "$APP_PID" 2>/dev/null || fail "应用启动失败（看 $APP_LOG）"
    sleep 1
  done
  fail "应用 90 秒内没有通过健康检查（看 $APP_LOG）"
}

# 发一次同步领券，把业务码打到 stdout；HTTP 状态打到 stderr 文件，便于区分"网络没通"与"业务拒绝"。
receive_code() {   # $1=userId $2=requestId
  curl -sS --max-time 30 -X POST "$BASE/api/coupons/receive" \
    -H 'Content-Type: application/json' \
    -d "{\"requestId\":\"$2\",\"userId\":$1,\"templateId\":$TPL_ID}" 2>/dev/null \
    | json_get '["code"]' 2>/dev/null || echo "NET_ERROR"
}

async_receive_raw() {   # $1=userId $2=requestId → stdout: "<http> <body>"
  local body http
  body=$(curl -sS --max-time 30 -o "$TMPDIR_DRILL/async-body.$1" -w '%{http_code}' \
    -X POST "$BASE/api/coupons/receive-async" -H 'Content-Type: application/json' \
    -d "{\"requestId\":\"$2\",\"userId\":$1,\"templateId\":$TPL_ID}" 2>/dev/null || echo "000")
  http="$body"
  printf '%s %s\n' "$http" "$(cat "$TMPDIR_DRILL/async-body.$1" 2>/dev/null || echo '{}')"
}

confirm_code() {   # $1=usageNo
  curl -sS --max-time 30 -X POST "$BASE/api/coupons/confirm" \
    -H 'Content-Type: application/json' \
    -d "{\"usageNo\":\"$1\",\"discountAmt\":50}" 2>/dev/null \
    | json_get '["code"]' 2>/dev/null || echo "NET_ERROR"
}

scrape_prometheus() {
  # 20s 而不是 5s：MySQL 故障时部分 Micrometer gauge 会尝试访问依赖，抓取可能变慢；
  # 5s 截断会拿到半截文本，熔断器序列可能还没出现（演练里实测过）。
  curl -sS --max-time 20 -u "$ACTUATOR_AUTH" "$BASE/actuator/prometheus" 2>/dev/null || true
}

# mysql 熔断器某个状态是否为 1（Prometheus 里 resilience4j_circuitbreaker_state 是
# "每个状态一条序列，当前状态为 1"。$1=open/closed/half_open）
breaker_metric_state() {
  scrape_prometheus | awk -v want="$1" '
    index($0, "resilience4j_circuitbreaker_state{") == 1 && index($0, "name=\"mysql\"") > 0 && index($0, "state=\"" want "\"") > 0 {
        if ($2 + 0 == 1) { print "1"; found=1; exit }
    }
    END { if (!found) print "0" }'
}

# 依赖失败调用数（resilience4j 的 calls_seconds_count 按 kind 分：failed/successful/ignored）。
# 它证明熔断器确实看到了失败，而不是被别的东西顺手打开。
breaker_failed_calls() {
  scrape_prometheus | awk '
    index($0, "resilience4j_circuitbreaker_calls_seconds_count{") == 1 && index($0, "kind=\"failed\"") > 0 && index($0, "name=\"mysql\"") > 0 { printf "%.0f\n", $2; exit }'
}

create_and_publish_template() {
  local name="MYSQL-DRILL-$(date +%s)"
  local resp tpl
  resp=$(curl -sS -X POST "$BASE/api/templates" \
    -H 'Content-Type: application/json' -H "X-Admin-Token: $ADMIN_TOKEN" \
    -d "{\"name\":\"$name\",\"activityId\":900150,\"couponType\":1,\"faceValue\":100,
         \"threshold\":1000,\"totalCount\":1000,\"perUserLimit\":2,\"validType\":2,
         \"validDays\":7,\"dailyLimit\":0,\"perUserDailyLimit\":0}")
  tpl=$(printf '%s' "$resp" | json_get '["data"]["id"]' 2>/dev/null) \
    || fail "建模板失败：$resp"
  curl -sS -X POST "$BASE/api/templates/$tpl/publish" \
    -H "X-Admin-Token: $ADMIN_TOKEN" | json_get '["code"]' | grep -q '^0$' \
    || fail "发布模板失败"
  echo "$tpl"
}

# ---------------------------------------------------------------- 护栏
step "0. 前置检查（三道护栏 + 依赖）"
[[ "${CONFIRM:-}" == "yes" ]] \
  || fail "演练会停 MySQL 容器。确认请加：CONFIRM=yes"
case "$BASE" in
  http://localhost:*|http://127.0.0.1:*) ok "目标是本机：$BASE" ;;
  *)
    [[ "${ALLOW_REMOTE:-0}" == "1" ]] \
      || fail "BASE=$BASE 不是本机。演练只应打本机；确要远程请加 ALLOW_REMOTE=1"
    warn "目标是远程环境（ALLOW_REMOTE=1 已显式放开）"
    ;;
esac

[[ ! -f "$STATE_FILE" ]] || fail "发现残留的演练状态文件 $STATE_FILE——
上次演练可能被中断（Ctrl-C/断电）。先确认 MySQL 容器已启动（docker start $MYSQL_CONTAINER），
再删掉该文件重跑。"

# 上次演练如果崩在"MySQL 停止"状态，先把它拉起来（本机演练可以自愈，不阻塞重跑）
if docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1 && ! mysql_running; then
  warn "MySQL 容器当前未运行，先启动它（上次演练可能没走完 cleanup）"
  docker start "$MYSQL_CONTAINER" >/dev/null
fi
mysql_running || fail "MySQL 容器 $MYSQL_CONTAINER 不可用——先 make up"
redis_running || fail "Redis 容器 $REDIS_CONTAINER 不可用——先 make up"
# 容器 "Up" 不等于 mysqld 已接受连接（刚重启时会有几秒初始化）。应用在这里起不来
# 会表现为启动卡住/健康检查空转，所以先等 SELECT 1 真正成功。
for _ in $(seq 1 90); do
  mysql_sql "SELECT 1" >/dev/null 2>&1 && break
  sleep 1
done
mysql_sql "SELECT 1" >/dev/null 2>&1 || fail "MySQL 容器在运行但 90 秒内不接受连接"
ok "MySQL / Redis 容器都在运行，MySQL 已可查询"

printf 'DRILL=mysql\n' > "$STATE_FILE"

# ---------------------------------------------------------------- 起应用
step "1. 起应用（演练必须由脚本自启，日志断言才可信）"
# 2026-11-06 审查修正：删除"复用已有服务"分支。复用时不重写 APP_LOG，上一次运行
# 残留的 "CLOSED → OPEN" 会让本轮"日志双证据"假通过；而干净环境又必然失败。
# 脚本自启后 APP_LOG 是本轮专属，断言才有意义。
if curl -sf --max-time 3 "$BASE/actuator/health" >/dev/null 2>&1; then
  fail "已有服务占用 $BASE：请先停掉它。演练必须由脚本自启应用（复用会让日志断言读到历史运行的假证据）。"
fi
start_app
ok "应用已启动（PID=$APP_PID，日志 $APP_LOG）"

# 故障期熔断器状态从 Prometheus 读；先把"能抓、且 mysql 熔断器序列存在"验掉，
# 否则等 MySQL 停了才发现抓不到，演练已经进行到一半。
scrape_prometheus | grep -q 'resilience4j_circuitbreaker_state{.*name="mysql"' \
  || fail "抓不到 Prometheus 的 mysql 熔断器指标——检查 ACTUATOR_AUTH（当前 $ACTUATOR_AUTH）"
assert_true "Prometheus 可抓取且 mysql 熔断器指标可见" 1

# ---------------------------------------------------------------- 准备
step "2. 建模板 + 基线：故障前领券/锁券必须正常"
TPL_ID=$(create_and_publish_template)
ok "模板已发布：$TPL_ID"

# 用户区间：每个场景独立，便于事后按 user_id 精确核对"故障期有没有落券"。
U_CONFIRM=963001          # 故障前领券并锁定；故障期核销，恢复后再核销
U_OUT_SYNC_BASE=961000    # 故障期同步领券：961001..961008
U_OUT_ASYNC_BASE=962000   # 故障期异步领券：962001..962003
U_RECOVER=964001          # 恢复后新领券

# requestId 必须**每次演练唯一**：任务/券的幂等唯一键是 (app_id, user_id, requestId)，
# 不含 templateId。2026-11-06 实测：第二次跑同样脚本时，预热 requestId 命中了上一次
# 留下的任务 → 1001，演练在准备阶段就失败了。把 RUN_ID 拼进所有 requestId。
RUN_ID="$(date +%s%3N)"
SYNC_FAULT_PREFIX="mysql-drill-$RUN_ID-fault-sync"
ASYNC_FAULT_PREFIX="mysql-drill-$RUN_ID-fault-async"
ASYNC_WARM_PREFIX="mysql-drill-$RUN_ID-warm"
RECOVER_PREFIX="mysql-drill-$RUN_ID-recover"

base_code=$(receive_code "$U_CONFIRM" "mysql-drill-$RUN_ID-base")
assert_true "基线领券成功（code=0）" "$([[ "$base_code" == "0" ]] && echo 1 || echo 0)"

# 再领一张用于锁定/核销（同上用户每人限领 1，所以用另一个用户）
U_LOCK=963002
lock_coupon_json=$(curl -sS -X POST "$BASE/api/coupons/receive" \
  -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"mysql-drill-$RUN_ID-lock\",\"userId\":$U_LOCK,\"templateId\":$TPL_ID}")
COUPON_ID=$(printf '%s' "$lock_coupon_json" | json_get '["data"]["couponId"]' 2>/dev/null || true)
[[ -n "$COUPON_ID" && "$COUPON_ID" != "None" ]] || fail "基线领券没有返回 couponId：$lock_coupon_json"
LOCK_RESP=$(curl -sS -X POST "$BASE/api/coupons/lock" -H 'Content-Type: application/json' \
  -d "{\"orderNo\":\"mysql-drill-order-$(date +%s%3N)\",\"userId\":$U_LOCK,\"couponId\":$COUPON_ID}")
USAGE_NO=$(printf '%s' "$LOCK_RESP" | json_get '["data"]["usageNo"]' 2>/dev/null || true)
[[ -n "$USAGE_NO" && "$USAGE_NO" != "None" ]] || fail "基线锁券失败：$LOCK_RESP"
ok "基线锁券成功：usageNo=$USAGE_NO（故障期用来验证核销 fail-closed）"

# 异步入口预热：先让每个故障期用户成功受理一次，把两样东西建出来：
#   ① c:issue:gate:{tpl} —— 否则故障期会先走"从 DB 重建 gate"，失败点是
#      acquireWithRepair 的 DataAccessException → 5001（DEGRADED，见
#      ReceiveAsyncAppService 第 180 行）；
#   ② 每个用户的 c:issue:ul:{tpl}:{user} —— 否则该用户首个请求命中 COUNTER_MISSING(-12)，
#      同样要读 DB 修复，还是 5001。
# 两样都在时，故障期才会走到我们要验的线：Redis 预扣成功 → 任务插入失败 → compensate
# → 5000（SYSTEM_ERROR）。模板 perUserLimit=2 让"预热 1 张 + 故障期新 requestId 再要 1 张"
# 能通过限领判断；两条线都是 fail-closed，但只有后者能证明"预扣被补偿、库存没漏"。
for i in $(seq 1 3); do
  uid=$((U_OUT_ASYNC_BASE + i))
  read -r warm_http warm_body < <(async_receive_raw "$uid" "$ASYNC_WARM_PREFIX-$i")
  warm_code=$(printf '%s' "$warm_body" | json_get '["code"]' 2>/dev/null || echo "?")
  assert_true "异步入口预热受理成功 user=$uid（HTTP $warm_http / code=$warm_code）" \
      "$([[ "$warm_http" == "202" && "$warm_code" == "0" ]] && echo 1 || echo 0)"
done
gate_exists=$(docker exec "$REDIS_CONTAINER" redis-cli EXISTS "c:issue:gate:{$TPL_ID}" 2>/dev/null | tr -d '\r')
assert_true "Redis gate 键已建立（EXISTS=$gate_exists），故障期可验证预扣补偿路径" \
    "$([[ "$gate_exists" == "1" ]] && echo 1 || echo 0)"

# ---------------------------------------------------------------- 注入故障
step "3. 注入硬故障：docker stop $MYSQL_CONTAINER"
docker stop -t 10 "$MYSQL_CONTAINER" >/dev/null
mysql_running && fail "MySQL 容器仍在运行，故障注入失败"
ok "MySQL 已停止（容器级硬故障：连接被拒/池拿不到连接）"

# ---------------------------------------------------------------- 故障期断言
step "4. 故障期：同步领券应快速失败为 5000；异步领券应补偿后 5000；核销 5000"
# 并发打 8 发：既填满熔断滑动窗口（min-calls=5），也不至于把 Hikari 3s 超时叠太久。
# 只等这 8 个子 shell：裸 `wait` 会连脚本自己起的 java 应用一起等（应用不会退出，
# 演练会永久挂住——2026-11-06 首跑实测踩到，cleanup 收到 TERM 后才把 MySQL 拉回）。
sync_pids=()
for i in $(seq 1 8); do
  ( receive_code "$((U_OUT_SYNC_BASE + i))" "$SYNC_FAULT_PREFIX-$i" \
      > "$TMPDIR_DRILL/sync-$i.code" ) &
  sync_pids+=($!)
done
for p in "${sync_pids[@]}"; do wait "$p"; done
SYNC_OK=0
for i in $(seq 1 8); do
  c=$(cat "$TMPDIR_DRILL/sync-$i.code" 2>/dev/null || echo "?")
  [[ "$c" == "5000" ]] && SYNC_OK=$((SYNC_OK + 1))
done
assert_true "故障期同步领券 8/8 返回 5000（可重试、无成功）——实际 $SYNC_OK/8" \
    "$([[ "$SYNC_OK" == "8" ]] && echo 1 || echo 0)"

ASYNC_BAD=0
async_retry_user=""
async_retry_request=""
for i in $(seq 1 3); do
  uid=$((U_OUT_ASYNC_BASE + i))
  rid="$ASYNC_FAULT_PREFIX-$i"
  read -r http body < <(async_receive_raw "$uid" "$rid")
  code=$(printf '%s' "$body" | json_get '["code"]' 2>/dev/null || echo "?")
  [[ "$code" == "5000" && "$http" != "202" ]] || ASYNC_BAD=$((ASYNC_BAD + 1))
  if [[ $i -eq 1 ]]; then async_retry_user=$uid; async_retry_request=$rid; fi
done
assert_true "故障期异步领券 3/3 返回 5000 且非 202（Redis 预扣已补偿，没有'受理了却没人执行'）" \
    "$([[ "$ASYNC_BAD" == "0" ]] && echo 1 || echo 0)"
# 注：这里断言 5000 而不是 5001，前提是上面预热过 gate；没有 gate 时失败点是
# 重建 gate 的 DB 读，会按 ReceiveAsyncAppService 第 180 行的设计返回 5001。

confirm_fault_code=$(confirm_code "$USAGE_NO")
assert_true "故障期核销返回 5000（fail-closed，不静默成功）——实际 $confirm_fault_code" \
    "$([[ "$confirm_fault_code" == "5000" ]] && echo 1 || echo 0)"

# 熔断器状态：故障期必须 OPEN。注意从 Prometheus 读，不走 /api/switches/breakers——
# 后者要经过管理面过滤器，过滤器会用旧令牌查 RBAC 权限表（MySQL），故障期同样不可用。
PROM_OUTAGE="$LOGDIR/mysql-drill-prometheus-outage.txt"
scrape_prometheus > "$PROM_OUTAGE"
open_val=$(breaker_metric_state open)
assert_true "mysql 依赖级熔断器处于 OPEN（Prometheus state=open=$open_val）" \
    "$([[ "$open_val" == "1" ]] && echo 1 || echo 0)"

# 为什么不断言 notPermitted>0 / 单发时延 <1.5s：模板/租户缓存过期后，请求可能在
# **进入熔断段之前**就因读 DB 失败（例如 CachedOpenAppReader 回源）而 5000——这条
# 路径不经过 breaker，所以"打开之后的第一发"不一定快速失败。实测 9th 轮：
# state=open=1，但 fast-fail 请求耗时 3028ms（卡在熔断段之外的 DB 读）。
# 能钉死的证据是：breaker 记录了足够多的依赖失败（≥ min-calls=5）→ 触发 OPEN。
mysql_failed_calls=$(breaker_failed_calls)
assert_true "breaker 记录的 mysql 依赖失败数 ≥5（实际 $mysql_failed_calls，min-calls=5）" \
    "$([[ "${mysql_failed_calls:-0}" -ge 5 ]] && echo 1 || echo 0)"

# 双证据：Prometheus 是"现在是什么状态"，应用日志是"确实发生过状态转移"。
# 两者独立；即使抓取因依赖慢而截断，日志行仍然能证明熔断真的打开过。
assert_true "应用日志记录了 mysql 熔断 CLOSED → OPEN" \
    "$(grep -q '熔断器打开：mysql CLOSED → OPEN' "$APP_LOG" && echo 1 || echo 0)"

# ---------------------------------------------------------------- 恢复
step "5. 恢复 MySQL：容器启动 + 健康等待"
docker start "$MYSQL_CONTAINER" >/dev/null
for _ in $(seq 1 90); do
  mysql_sql "SELECT 1" >/dev/null 2>&1 && break
  sleep 1
done
mysql_sql "SELECT 1" >/dev/null 2>&1 || fail "MySQL 重启后 90 秒内不可用"
ok "MySQL 已恢复"

# 等熔断器 OPEN → HALF_OPEN → CLOSED：用新用户持续打，最多 120 秒。
# 每次换一个 userId，避免"同一用户重复领券"的业务码混进恢复判据。
recovered_code=""
recover_seq=0
for _ in $(seq 1 24); do
  recover_seq=$((recover_seq + 1))
  recovered_code=$(receive_code "$((U_RECOVER + recover_seq))" "$RECOVER_PREFIX-$recover_seq")
  if [[ "$recovered_code" == "0" ]]; then break; fi
  sleep 5
done
assert_true "恢复后同步领券成功（code=0）——实际 $recovered_code" \
    "$([[ "$recovered_code" == "0" ]] && echo 1 || echo 0)"

# 半开态需要 3 次成功试探才闭合（permittedNumberOfCallsInHalfOpenState=3）；
# 第一次成功只说明"放行了试探"，不等于已经 CLOSED，所以继续打到 closed=1。
closed_val="0"
for _ in $(seq 1 20); do
  closed_val=$(breaker_metric_state closed)
  [[ "$closed_val" == "1" ]] && break
  recover_seq=$((recover_seq + 1))
  receive_code "$((U_RECOVER + recover_seq))" "$RECOVER_PREFIX-halfopen-$recover_seq" >/dev/null || true
  sleep 2
done
assert_true "mysql 熔断器恢复闭合（Prometheus state=closed=$closed_val）" \
    "$([[ "$closed_val" == "1" ]] && echo 1 || echo 0)"

# 异步失败请求用**同一个 requestId** 重试：补偿脚本删掉了 PENDING 幂等键，重试应重新受理。
read -r async_http async_body < <(async_receive_raw "$async_retry_user" "$async_retry_request")
async_code=$(printf '%s' "$async_body" | json_get '["code"]' 2>/dev/null || echo "?")
assert_true "故障期失败的异步请求可用同 requestId 重试并受理（HTTP+code 实际 $async_http/$async_code）" \
    "$([[ "$async_http" == "202" && "$async_code" == "0" ]] && echo 1 || echo 0)"

confirm_recovered_code=$(confirm_code "$USAGE_NO")
assert_true "恢复后同一 usageNo 核销成功（故障没有改坏状态机）——实际 $confirm_recovered_code" \
    "$([[ "$confirm_recovered_code" == "0" ]] && echo 1 || echo 0)"

# ---------------------------------------------------------------- 数据核对
step "6. 数据核对：故障期请求零落券、零任务；库存守恒"
# 用**请求 id** 精确核对而不是按用户：故障期共 11 个 requestId 失败
# （8 个同步 + 3 个异步），其中 async-1 恢复后重试成功；其余 10 个必须查无痕迹。
# （user_coupon.idempotent_key = 受理时的 requestId，可直接按请求核对。）
out_req_ids=""
for i in $(seq 1 8); do out_req_ids+="'$SYNC_FAULT_PREFIX-$i',"; done
out_req_ids+="'$ASYNC_FAULT_PREFIX-2','$ASYNC_FAULT_PREFIX-3'"

out_coupons=$(mysql_sql "
  SELECT COALESCE(SUM(c), 0) FROM (
    $(for sh in $(seq 0 63); do
        printf "SELECT COUNT(*) AS c FROM user_coupon_%d WHERE template_id=%d AND idempotent_key IN (%s)" "$sh" "$TPL_ID" "$out_req_ids"
        [[ $sh -lt 63 ]] && echo ' UNION ALL'
      done)
  ) x")
assert_true "故障期 10 个未重试请求没有落任何券（实际 $out_coupons 张）" \
    "$([[ "${out_coupons:-0}" == "0" ]] && echo 1 || echo 0)"

out_tasks=$(mysql_sql "SELECT COUNT(*) FROM coupon_issue_task WHERE request_id IN ($out_req_ids)")
assert_true "故障期未重试请求没有留下任务行（实际 $out_tasks 行；插入失败已回滚）" \
    "$([[ "${out_tasks:-0}" == "0" ]] && echo 1 || echo 0)"

# 库存守恒：模板 Σused == 64 分表里该模板的券行总数（C1 等式②，SQL 复算）。
# LEFT JOIN + WHERE 差异 = 不守恒行数；0 行才 PASS。
CONSERVATION_BROKEN=$(mysql_sql "SELECT COUNT(*) FROM (
  SELECT SUM(used) AS used FROM coupon_stock_shard WHERE template_id=$TPL_ID
) s LEFT JOIN (
  SELECT COUNT(*) AS cnt FROM (
    $(for sh in $(seq 0 63); do
        printf 'SELECT 1 FROM user_coupon_%d WHERE template_id=%d' "$sh" "$TPL_ID"
        [[ $sh -lt 63 ]] && echo ' UNION ALL'
      done)
  ) x
) c ON s.used = c.cnt WHERE c.cnt IS NULL OR s.used <> c.cnt")
assert_true "模板 $TPL_ID 库存守恒（Σused == 券行数；不守恒行数 $CONSERVATION_BROKEN）" \
    "$([[ "${CONSERVATION_BROKEN:-1}" == "0" ]] && echo 1 || echo 0)"

# 预热本身应留下 3 条任务（这 3 条在故障期之前就已落库，不属于故障期泄漏）
warm_tasks=$(mysql_sql "SELECT COUNT(*) FROM coupon_issue_task WHERE request_id LIKE '$ASYNC_WARM_PREFIX-%'")
assert_true "预热产生 3 条任务（实际 $warm_tasks 条）" \
    "$([[ "${warm_tasks:-0}" == "3" ]] && echo 1 || echo 0)"

# 异步重试后应恰好有 1 条任务（证明补偿后重试只受理一次）
retry_tasks=$(mysql_sql "SELECT COUNT(*) FROM coupon_issue_task WHERE user_id=$async_retry_user AND request_id='$async_retry_request'")
assert_true "异步重试只产生 1 条任务（实际 $retry_tasks 条）" \
    "$([[ "${retry_tasks:-0}" == "1" ]] && echo 1 || echo 0)"

# ---------------------------------------------------------------- 收尾
step "演练完成"
echo "DRILL PASS assertions=$PASS"
