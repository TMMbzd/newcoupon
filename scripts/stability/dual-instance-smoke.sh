#!/usr/bin/env bash
#
# 双实例滚动重启冒烟（V6 阶段 5B 刀 2 ④）——两个真实应用实例共享同一 MySQL/Redis，
# 持续打领券流量，依次 SIGTERM/重启 A、B，断言"零 5xx、零在途 reset、业务零失败"。
#
# 验的是什么：
#   ① 两实例同时服务（各自的 worker-id / cache instance-id 不同，共享库与缓存）；
#   ② 优雅停机：SIGTERM 后进程在 30 秒停机窗口内退出，在途请求完成而不是被 reset；
#   ③ 滚动重启期间客户端把流量切到存活实例（failover），不产生 5xx/业务失败；
#   ④ 重启后两个实例都恢复健康，库存守恒（Σused == 券行数）。
#
# 为什么用真实 JVM 而不是 mock/单测：多实例语义（worker id 租约、缓存广播、优雅停机）
# 是进程级行为，单 JVM 里两个 Spring 上下文共享静态状态，证明不了。这与
# scripts/cluster/verify-cluster.sh 的形态一致。
#
# 资源纪律：两个实例各 -Xmx256m；退出时（含 Ctrl-C）一定停掉两个实例和压测客户端。
#
# 用法：
#   make up
#   CONFIRM=yes bash scripts/stability/dual-instance-smoke.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"

PORT_A="${PORT_A:-18091}"
PORT_B="${PORT_B:-18092}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-newcoupon-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-newcoupon-redis}"
ACTUATOR_AUTH="${ACTUATOR_AUTH:-actuator:it-actuator-pass}"
ACTUATOR_AUTH_PASS="${ACTUATOR_AUTH#*:}"
LOGDIR="$REPO_DIR/target/stability"
mkdir -p "$LOGDIR"
RUN_ID="$(date +%s)"
STOP_FILE="$LOGDIR/dual-smoke-$RUN_ID.stop"
CLIENT_JSON="$LOGDIR/dual-smoke-$RUN_ID.json"
CLIENT_LOG="$LOGDIR/dual-smoke-$RUN_ID-client.log"

PASS=0
PID_A=""
PID_B=""
CLIENT_PID=""
WID_A=""
WID_B=""

assert_true() {   # $1=描述 $2=1/0
  PASS=$((PASS + 1))
  if [[ "$2" == "1" ]]; then ok "$1"; else fail "断言失败：$1"; fi
}

stop_pid() {   # $1=pid $2=名称（优雅 TERM，最多 35s，再 KILL）
  local pid="$1" name="$2" i
  [[ -n "$pid" ]] || return 0
  kill -0 "$pid" 2>/dev/null || return 0
  warn "停止 $name（SIGTERM，优雅停机窗口 30s）"
  kill -TERM "$pid" 2>/dev/null || true
  for i in $(seq 1 350); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$pid" 2>/dev/null; then
    warn "$name 35 秒内未退出，强制 KILL"
    kill -9 "$pid" 2>/dev/null || true
    return 1
  fi
  return 0
}

cleanup() {
  local rc=$?
  warn "清理：停掉压测客户端与两个应用实例"
  [[ -n "$CLIENT_PID" ]] && kill -TERM "$CLIENT_PID" 2>/dev/null || true
  stop_pid "$PID_A" "实例 A" || true
  stop_pid "$PID_B" "实例 B" || true
  rm -f "$STOP_FILE"
  for p in "$PORT_A" "$PORT_B"; do
    ss -ltn 2>/dev/null | grep -q ":$p " && warn "端口 $p 仍有监听，请手动检查"
  done
  ok "清理完成"
  return $rc
}
trap cleanup EXIT INT TERM

start_app() {   # $1=端口 $2=instanceId $3=日志
  local port="$1" inst="$2" log="$3"
  # 不显式传 coupon.id.worker-id：由 WorkerIdLease 在 Redis 里抢一个 0-1023 的空闲 ID。
  # 为什么不用固定 ID：2026-11-06 首跑实测，SIGTERM 退出后 @PreDestroy 没有释放
  # c:id:worker:{id} 租约，210ms 后用同一个显式 ID 重启会直接启动失败
  # ("WORKER_ID=101 已被其他实例占用")，要等 60s TTL。自动分配会换一个空闲 ID，
  # 滚动重启不被这个已登记的缺陷卡住（缺陷见 evidence 报告，未在本刀修）。
  ACTUATOR_AUTH_PASSWORD="$ACTUATOR_AUTH_PASS" \
  java -Xmx256m -cp "$CP" com.newcoupon.NewCouponApplication \
    "--server.port=$port" \
    "--coupon.cache.invalidation.instance-id=$inst" \
    --coupon.risk.ip-rate.enabled=false \
    --coupon.issue.worker.initial-delay-ms=600000 \
    > "$log" 2>&1 &
  echo $!
}

# 从应用日志里读自动分配的 workerId（启动日志有一行 "snowflake worker lease acquired"）。
worker_id_of() {   # $1=日志
  local id
  for _ in $(seq 1 30); do
    id=$(grep -oP 'snowflake worker lease acquired: workerId=\K[0-9]+' "$1" 2>/dev/null | tail -1)
    [[ -n "$id" ]] && { echo "$id"; return 0; }
    sleep 0.5
  done
  fail "没能从日志读到 workerId：$1"
}

wait_health() {   # $1=端口 $2=名称 $3=pid
  local port="$1" name="$2" pid="$3"
  for _ in $(seq 1 120); do
    if curl -sf --max-time 2 "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    kill -0 "$pid" 2>/dev/null || fail "$name 启动失败（看 $LOGDIR/dual-$name.log）"
    sleep 1
  done
  fail "$name 120 秒内没有通过健康检查"
}

create_and_publish_template() {   # $1=port → stdout: templateId
  local port="$1" resp tpl
  resp=$(curl -sS -X POST "http://127.0.0.1:$port/api/templates" \
    -H 'Content-Type: application/json' -H "X-Admin-Token: $ADMIN_TOKEN" \
    -d "{\"name\":\"DUAL-SMOKE-$RUN_ID\",\"activityId\":900160,\"couponType\":1,
         \"faceValue\":100,\"threshold\":1000,\"totalCount\":10000,\"perUserLimit\":1,
         \"validType\":1,\"validStart\":\"2026-11-01T00:00:00\",
         \"validEnd\":\"2026-12-31T23:59:59\",\"dailyLimit\":0,\"perUserDailyLimit\":0}")
  tpl=$(printf '%s' "$resp" | json_get '["data"]["id"]' 2>/dev/null) \
    || fail "建模板失败：$resp"
  curl -sS -X POST "http://127.0.0.1:$port/api/templates/$tpl/publish" \
    -H "X-Admin-Token: $ADMIN_TOKEN" | json_get '["code"]' | grep -q '^0$' \
    || fail "发布模板失败"
  echo "$tpl"
}

json_field() {   # $1=文件 $2=表达式（python）
  python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print($2)" "$1"
}

# ---------------------------------------------------------------- 护栏
step "0. 前置检查（三道护栏 + 依赖）"
[[ "${CONFIRM:-}" == "yes" ]] \
  || fail "这会启动两个 JVM 并滚动重启它们。确认请加：CONFIRM=yes"
case "${BASE:-http://127.0.0.1:$PORT_A}" in
  http://localhost:*|http://127.0.0.1:*) ok "目标是本机（端口 $PORT_A/$PORT_B）" ;;
  *) fail "本脚本只在本机起实例；BASE 不是本机" ;;
esac
docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -q true \
  || fail "MySQL 容器不在运行——先 make up"
docker inspect -f '{{.State.Running}}' "$REDIS_CONTAINER" 2>/dev/null | grep -q true \
  || fail "Redis 容器不在运行——先 make up"
docker exec -e MYSQL_PWD=root123456 "$MYSQL_CONTAINER" \
  mysql -uroot -N -B newcoupon -e "SELECT 1" >/dev/null 2>&1 \
  || fail "MySQL 在运行但业务库不可查询"
ok "MySQL / Redis 可用"

CP_FILE="$REPO_DIR/target/stability-cp.txt"
if [[ ! -f "$CP_FILE" || ! -d "$REPO_DIR/target/classes" ]]; then
  ( cd "$REPO_DIR" && mvn -q -DskipTests compile dependency:build-classpath \
      -Dmdep.outputFile="$CP_FILE" )
fi
CP="$REPO_DIR/target/classes:$(cat "$CP_FILE")"

# ---------------------------------------------------------------- 起两个实例
step "1. 起两个实例（不同 worker-id / cache instance-id，共享 MySQL+Redis）"
PID_A=$(start_app "$PORT_A" "smoke-a" "$LOGDIR/dual-A.log")
wait_health "$PORT_A" "A" "$PID_A"
WID_A=$(worker_id_of "$LOGDIR/dual-A.log")
ok "实例 A 已就绪（:$PORT_A, worker-id=$WID_A）"
PID_B=$(start_app "$PORT_B" "smoke-b" "$LOGDIR/dual-B.log")
wait_health "$PORT_B" "B" "$PID_B"
WID_B=$(worker_id_of "$LOGDIR/dual-B.log")
ok "实例 B 已就绪（:$PORT_B, worker-id=$WID_B）"
assert_true "两个实例都通过健康检查" 1
assert_true "两个实例的 Snowflake workerId 不同（A=$WID_A, B=$WID_B）" \
    "$([[ "$WID_A" != "$WID_B" ]] && echo 1 || echo 0)"

# ---------------------------------------------------------------- 准备模板
step "2. 建并发布一个模板（两实例共享库，B 从 DB 读）"
TPL_ID=$(create_and_publish_template "$PORT_A")
ok "模板已发布：$TPL_ID"

# ---------------------------------------------------------------- 开始流量
step "3. 开始持续领券流量（客户端按实例轮转，唯一用户/requestId）"
rm -f "$STOP_FILE"
python3 -I "$SCRIPT_DIR/dual_instance_client.py" \
  --endpoints "http://127.0.0.1:$PORT_A,http://127.0.0.1:$PORT_B" \
  --template-id "$TPL_ID" \
  --stop-file "$STOP_FILE" \
  --user-base "$((990000000000 + RUN_ID % 100000 * 1000))" \
  --request-prefix "dual-smoke-$RUN_ID" \
  --output "$CLIENT_JSON" \
  > "$CLIENT_LOG" 2>&1 &
CLIENT_PID=$!
sleep 3
kill -0 "$CLIENT_PID" 2>/dev/null || fail "压测客户端没起来（看 $CLIENT_LOG）"
ok "流量已开始（PID=$CLIENT_PID）"

# ---------------------------------------------------------------- 滚动重启
step "4. 滚动重启：停 A → 重启 A → 停 B → 重启 B"
STOP_A_START=$(date +%s%3N)
if stop_pid "$PID_A" "实例 A"; then A_GRACEFUL=1; else A_GRACEFUL=0; fi
STOP_A_MS=$(( $(date +%s%3N) - STOP_A_START ))
assert_true "实例 A SIGTERM 后在 35s 内退出（实际 ${STOP_A_MS}ms）" "$A_GRACEFUL"

PID_A=$(start_app "$PORT_A" "smoke-a" "$LOGDIR/dual-A.log")
wait_health "$PORT_A" "A" "$PID_A"
WID_A=$(worker_id_of "$LOGDIR/dual-A.log")
ok "实例 A 已重启（:$PORT_A, worker-id=$WID_A）"
assert_true "实例 A 重启后健康" 1
assert_true "重启后 A/B 的 workerId 仍不同（A=$WID_A, B=$WID_B）" \
    "$([[ "$WID_A" != "$WID_B" ]] && echo 1 || echo 0)"
sleep 3

STOP_B_START=$(date +%s%3N)
if stop_pid "$PID_B" "实例 B"; then B_GRACEFUL=1; else B_GRACEFUL=0; fi
STOP_B_MS=$(( $(date +%s%3N) - STOP_B_START ))
assert_true "实例 B SIGTERM 后在 35s 内退出（实际 ${STOP_B_MS}ms）" "$B_GRACEFUL"

PID_B=$(start_app "$PORT_B" "smoke-b" "$LOGDIR/dual-B.log")
wait_health "$PORT_B" "B" "$PID_B"
WID_B=$(worker_id_of "$LOGDIR/dual-B.log")
ok "实例 B 已重启（:$PORT_B, worker-id=$WID_B）"
assert_true "实例 B 重启后健康" 1
assert_true "重启后 A/B 的 workerId 仍不同（A=$WID_A, B=$WID_B）" \
    "$([[ "$WID_A" != "$WID_B" ]] && echo 1 || echo 0)"
sleep 3

# ---------------------------------------------------------------- 停止流量并核对
step "5. 停止流量，核对客户端统计"
touch "$STOP_FILE"
for _ in $(seq 1 60); do
  kill -0 "$CLIENT_PID" 2>/dev/null || break
  sleep 1
done
kill -0 "$CLIENT_PID" 2>/dev/null && fail "压测客户端 60 秒内没有退出"
wait "$CLIENT_PID" 2>/dev/null || true
CLIENT_PID=""
[[ -f "$CLIENT_JSON" ]] || fail "客户端没有输出统计文件（看 $CLIENT_LOG）"

REQUESTS=$(json_field "$CLIENT_JSON" 'd["requests"]')
SUCCESS=$(json_field "$CLIENT_JSON" 'd["success"]')
FAILOVERS=$(json_field "$CLIENT_JSON" 'd["failovers"]')
MIDFLIGHT=$(json_field "$CLIENT_JSON" 'd["midflightResets"]')
HTTP5XX=$(json_field "$CLIENT_JSON" 'd["http5xx"]')
BIZERR=$(json_field "$CLIENT_JSON" 'd["bizErrors"]')
BOTHDOWN=$(json_field "$CLIENT_JSON" 'd["bothEndpointsDown"]')
A_OK=$(json_field "$CLIENT_JSON" "d['perEndpointSuccess'].get('http://127.0.0.1:$PORT_A', 0)")
B_OK=$(json_field "$CLIENT_JSON" "d['perEndpointSuccess'].get('http://127.0.0.1:$PORT_B', 0)")

ok "请求=$REQUESTS 成功=$SUCCESS failover=$FAILOVERS 在途reset=$MIDFLIGHT 5xx=$HTTP5XX 业务失败=$BIZERR 双实例不可用=$BOTHDOWN"
ok "按实例成功数：A=$A_OK B=$B_OK"

assert_true "流量覆盖足够（请求数 $REQUESTS >= 100）" \
    "$([[ "$REQUESTS" -ge 100 ]] && echo 1 || echo 0)"
assert_true "两个实例都实际服务过请求（A=$A_OK, B=$B_OK）" \
    "$([[ "$A_OK" -gt 0 && "$B_OK" -gt 0 ]] && echo 1 || echo 0)"
assert_true "滚动重启期间发生过至少一次流量迁移（failover=$FAILOVERS > 0）" \
    "$([[ "$FAILOVERS" -gt 0 ]] && echo 1 || echo 0)"
assert_true "全程零 HTTP 5xx（实际 $HTTP5XX）" \
    "$([[ "$HTTP5XX" == "0" ]] && echo 1 || echo 0)"
assert_true "全程零在途请求 reset（实际 $MIDFLIGHT）" \
    "$([[ "$MIDFLIGHT" == "0" ]] && echo 1 || echo 0)"
assert_true "全程零业务失败（实际 $BIZERR）" \
    "$([[ "$BIZERR" == "0" ]] && echo 1 || echo 0)"
assert_true "全程没有'两台都连不上'（实际 $BOTHDOWN）" \
    "$([[ "$BOTHDOWN" == "0" ]] && echo 1 || echo 0)"
assert_true "成功数 == 请求数（所有请求都拿到业务码 0；实际 $SUCCESS/$REQUESTS）" \
    "$([[ "$SUCCESS" == "$REQUESTS" ]] && echo 1 || echo 0)"

# ---------------------------------------------------------------- 数据核对
step "6. 数据核对：库存守恒"
CONSERVATION_BROKEN=$(docker exec -e MYSQL_PWD=root123456 "$MYSQL_CONTAINER" \
  mysql -uroot -N -B newcoupon -e "SELECT COUNT(*) FROM (
    SELECT SUM(used) AS used FROM coupon_stock_shard WHERE template_id=$TPL_ID
  ) s LEFT JOIN (
    SELECT COUNT(*) AS cnt FROM (
      $(for sh in $(seq 0 63); do
          printf 'SELECT 1 FROM user_coupon_%d WHERE template_id=%d' "$sh" "$TPL_ID"
          [[ $sh -lt 63 ]] && echo ' UNION ALL'
        done)
    ) x
  ) c ON s.used = c.cnt WHERE c.cnt IS NULL OR s.used <> c.cnt" 2>/dev/null)
assert_true "模板 $TPL_ID 库存守恒（Σused == 券行数；不守恒行数 ${CONSERVATION_BROKEN:-?}）" \
    "$([[ "${CONSERVATION_BROKEN:-1}" == "0" ]] && echo 1 || echo 0)"

# ---------------------------------------------------------------- 收尾
step "演练完成"
echo "DRILL PASS assertions=$PASS"
