#!/usr/bin/env bash
#
# 经 LB 的全链路滚动重启演练（V6 阶段 5B 生产化模拟刀 A3）——升级版双实例冒烟：
# 流量不再两端口直连，而是全打 nginx:8080（ops/nginx/，compose profile lbe2e）；
# 滚动序列升级为生产形态：**drain 摘流量 → 确认 LB 出池 → SIGTERM 优雅停机
# → 重启 → 回池**，对 A、B 各来一轮。
#
# 验的是什么（与 dual-instance-smoke.sh 的分工：那把验"客户端侧 failover +
# graceful shutdown 本身"，这把验"LB 侧生产形态"）：
#   ① drain 生效：实例 health 变 503 且 LB 不再路由新请求给它（被动摘除）；
#   ② 摘除后的 SIGTERM：graceful 30s 内退出，在途请求完成而不是被 reset；
#   ③ 重启回池：health UP 后 LB 重新路由流量给它；
#   ④ 全程经 LB 的业务流量：零 5xx、零在途 reset、零业务失败、两实例都有成功分布；
#   ⑤ LB 视角健康探测（脚本每秒经 nginx 打 /actuator/health，模拟主动探测）
#      全程 200——探测打到被摘实例会被 nginx 重试到存活实例，客户端视角无感。
#
# LB 摘除探测方式（选型说明，定死为可断言的方法）：
#   开源 nginx 无主动健康检查，被动摘除（max_fails=1 fail_timeout=30s）要靠
#   "打到失败实例的请求"触发。本脚本用 GET /actuator/health（经 nginx）当探针：
#   被摘实例回它 503 → nginx 计一次失败 → 出池 30s，并把探针重试到存活实例
#   （探针自身看到 200，LB 视角无感）。"已出池"的判据 = 探针响应头 X-Served-By
#   出现**重试列表**（"A, B" 形状且含被摘实例）——这是"被咨询且失败"的实锤；
#   max_fails=1 保证这一次失败即出池。出池窗口内紧接着的 20 个业务 GET
#   全部 200 且无一路由到被摘实例（窗口内轮询指针不咨询它，判定是确定性的）。
#   标记阶段后台探针暂停（PROBE_PAUSE_FILE），把"失败标记权"交给判定函数自己的
#   探针——否则后台探针抢先完成标记，判定函数永远观察不到重试列表（首跑实测假红）。
#
# 资源纪律：两个实例各 -Xmx256m + nginx（~10m）；退出时（含 Ctrl-C）停掉
# 两个实例、压测客户端、探测循环，并把演练 profile 起的 nginx 停掉还原。
#
# 用法：
#   make up
#   CONFIRM=yes bash scripts/stability/lb-rolling-drill.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"

PORT_A="${PORT_A:-18091}"
PORT_B="${PORT_B:-18092}"
NGINX_PORT="${NGINX_PORT:-8080}"
NGINX_PROFILE="${NGINX_PROFILE:-lbe2e}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-newcoupon-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-newcoupon-redis}"
ACTUATOR_AUTH="${ACTUATOR_AUTH:-actuator:it-actuator-pass}"
ACTUATOR_AUTH_PASS="${ACTUATOR_AUTH#*:}"
LB="http://127.0.0.1:$NGINX_PORT"
LOGDIR="$REPO_DIR/target/stability"
mkdir -p "$LOGDIR"
RUN_ID="$(date +%s)"
STOP_FILE="$LOGDIR/lb-drill-$RUN_ID.stop"
PROBE_STOP_FILE="$LOGDIR/lb-drill-$RUN_ID.probe-stop"
PROBE_PAUSE_FILE="$LOGDIR/lb-drill-$RUN_ID.probe-pause"
PROBE_ACK_FILE="$LOGDIR/lb-drill-$RUN_ID.probe-ack"
CLIENT_JSON="$LOGDIR/lb-drill-$RUN_ID.json"
CLIENT_LOG="$LOGDIR/lb-drill-$RUN_ID-client.log"
PROBE_LOG="$LOGDIR/lb-drill-$RUN_ID-probes.log"

PASS=0
PID_A=""
PID_B=""
CLIENT_PID=""
PROBE_PID=""
NGINX_STARTED=0

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
  warn "清理：停压测客户端、探测循环、两个应用实例、演练 nginx"
  [[ -n "$CLIENT_PID" ]] && kill -TERM "$CLIENT_PID" 2>/dev/null || true
  # 兜底再按脚本名清一次：万一 $CLIENT_PID 失手（2026-11-10 run2 实测有客户端
  # 残留并自旋），不许让演练的压测客户端活过 cleanup
  pkill -f "dual_instance_client.py.*lb-drill-$RUN_ID" 2>/dev/null || true
  [[ -n "$PROBE_PID" ]] && kill -TERM "$PROBE_PID" 2>/dev/null || true
  stop_pid "$PID_A" "实例 A" || true
  stop_pid "$PID_B" "实例 B" || true
  rm -f "$STOP_FILE" "$PROBE_STOP_FILE" "$PROBE_PAUSE_FILE" "$PROBE_ACK_FILE"
  if [[ "$NGINX_STARTED" == "1" ]]; then
    ( cd "$REPO_DIR" && docker compose --profile "$NGINX_PROFILE" stop nginx ) >/dev/null 2>&1 || true
  fi
  for p in "$PORT_A" "$PORT_B"; do
    ss -ltn 2>/dev/null | grep -q ":$p " && warn "端口 $p 仍有监听，请手动检查"
  done
  ok "清理完成"
  return $rc
}
trap cleanup EXIT INT TERM

start_app() {   # $1=端口 $2=instanceId $3=日志
  local port="$1" inst="$2" log="$3"
  # worker-id 仍由 Redis 租约自动分配：SIGTERM 后 @PreDestroy 不释放旧租约的
  # 已登记缺陷（见 dual-instance-smoke.sh 注释）在滚动重启里会卡固定 ID，自动分配绕开。
  ACTUATOR_AUTH_PASSWORD="$ACTUATOR_AUTH_PASS" \
  java -Xmx256m -cp "$CP" com.newcoupon.NewCouponApplication \
    "--server.port=$port" \
    "--coupon.cache.invalidation.instance-id=$inst" \
    --coupon.risk.ip-rate.enabled=false \
    --coupon.issue.worker.initial-delay-ms=600000 \
    > "$log" 2>&1 &
  echo $!
}

worker_id_of() {   # $1=日志
  local id
  for _ in $(seq 1 30); do
    id=$(grep -oP 'snowflake worker lease acquired: workerId=\K[0-9]+' "$1" 2>/dev/null | tail -1)
    [[ -n "$id" ]] && { echo "$id"; return 0; }
    sleep 0.5
  done
  fail "没能从日志读到 workerId：$1"
}

wait_health() {   # $1=端口 $2=名称 $3=pid —— 直到 health 200（实例可服务）
  local port="$1" name="$2" pid="$3"
  for _ in $(seq 1 120); do
    if curl -sf --max-time 2 "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    kill -0 "$pid" 2>/dev/null || fail "$name 启动失败（看 $LOGDIR/lb-$name.log）"
    sleep 1
  done
  fail "$name 120 秒内没有通过健康检查"
}

wait_drained() {   # $1=端口 —— 直到 health 503（drain 已生效）
  local port="$1" code
  for _ in $(seq 1 10); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$port/actuator/health" || echo 000)
    [[ "$code" == "503" ]] && return 0
    sleep 0.5
  done
  return 1
}

# 经 LB 的健康探针一次，stdout 输出 X-Served-By 的值（可能为空）。
# 这个请求同时就是"驱动被动摘除"的探针：打到被摘实例 → 503 → nginx 计失败出池
# → 探针被重试到存活实例（探针视角仍是 200）。
lb_probe_once() {
  # max-time 15s ≥ proxy_connect_timeout 10s：网关短暂停滞时探针等到连上，而不是
  # 5s 就放弃把一次可自愈的停滞记成探针失败
  curl -sD - -o /dev/null --max-time 15 "$LB/actuator/health" 2>/dev/null \
    | tr -d '\r' | awk 'tolower($1)=="x-served-by:"{$1=""; print substr($0,2)}'
}

# 暂停后台探针并**等在途探针静止**（审查 m2）：touch 暂停文件本身不追已派发的
# 在途探针——host-gateway 的 0.25% 停滞探针（curl 挂十几秒）可能在暂停生效后才
# 完成，它的 503 重试会抢走"失败标记权"，让 wait_lb_marked 25s 假红。
# 判定依据 ACK 文件：probe_loop 是单线程循环（一次只有一个 curl），它在暂停分支
# 里 touch ACK = 上一个探针已完整结束且循环已让路——此刻起 health 流量只剩
# 本函数自己的探针，标记权确定归它。
pause_probes_and_wait_quiescent() {
  local i
  rm -f "$PROBE_ACK_FILE"
  touch "$PROBE_PAUSE_FILE"
  for i in $(seq 1 100); do    # 20s 上界：盖住 max-time 15s 的在途探针 + 1s 间隔
    [[ -f "$PROBE_ACK_FILE" ]] && return 0
    sleep 0.2
  done
  return 1
}

# 等 LB 记下失败（出池证据）：本函数探针的 X-Served-By 出现"尝试列表"——即被摘
# 实例被咨询过、503 了、探针被重试到存活实例。max_fails=1：这一次失败就把它标记
# 出池 fail_timeout（120s）。
#
# **前置**：调用方必须已 pause_probes_and_wait_quiescent（drain 前就暂停，见
# roll_instance）——否则后台探针抢先打到被摘实例完成标记（2026-11-10 前两轮实测
# 假红），本函数的探针就永远只看到单地址响应。业务流量不受暂停影响照常跑
# （打到被摘实例的业务请求 200，不参与标记也不被标记）。
wait_lb_marked() {   # $1=端口 $2=名称
  local port="$1" name="$2" hdr
  for _ in $(seq 1 25); do
    hdr=$(lb_probe_once)
    if [[ "$hdr" == *", "* && "$hdr" == *":$port"* ]]; then
      ok "LB 已对 $name 计失败并出池 120s（探针尝试列表：$hdr）"
      return 0
    fi
    sleep 1
  done
  return 1
}

# 经 LB 打一个带令牌的业务 GET，stdout 输出 "code X-Served-By"。
lb_ping_once() {
  curl -s -D - -H "X-Admin-Token: $ADMIN_TOKEN" --max-time 15 "$LB/api/ping" 2>/dev/null \
    | tr -d '\r' \
    | awk 'tolower($1)=="x-served-by:"{s=$2} /^HTTP/{c=$2} END{print c" "s}'
}

# 确认分布窗口：紧跟 wait_lb_marked 之后（30s 出池窗口内，被摘实例不会被轮询指针
# 咨询，判定是确定性的不是概率性的）：20 个业务 GET 全部 200 且尝试列表里被摘
# 实例一次都不出现。
confirm_removed_distribution() {   # $1=端口 $2=名称
  local port="$1" name="$2" i line code served ok_count=0
  for i in $(seq 1 20); do
    line=$(lb_ping_once)
    code=$(awk '{print $1}' <<<"$line")
    served=$(awk '{print $2}' <<<"$line")
    if [[ "$code" == "200" && -n "$served" && "$served" != *":$port"* ]]; then
      ok_count=$((ok_count + 1))
    else
      warn "窗口内第 $i 个请求异常：$line"
    fi
  done
  assert_true "$name 出池窗口内 20 个业务请求全部 200 且无一路由到它（合格 $ok_count/20）" \
      "$([[ "$ok_count" == "20" ]] && echo 1 || echo 0)"
}

# 等 LB 回池：轮询业务 GET 直到**实际服务者**（X-Served-By 末项）是被等实例且 200。
# 不能用整列表判断：重启失败期间的请求列表是"A, B"（A 连不上被重试到 B），
# 末项才是真正服务的实例。240s 上界 = fail_timeout 120s（池内禁令全程）+ 重启
# 启动/健康 ~60s + 余量——禁令期内实例不会被路由，回池最早发生在标记+120s。
wait_lb_rejoined() {   # $1=端口 $2=名称
  local port="$1" name="$2" line code served last
  for _ in $(seq 1 240); do
    line=$(lb_ping_once)
    code=$(awk '{print $1}' <<<"$line")
    served=$(awk '{print $2}' <<<"$line")
    last="${served##*, }"
    if [[ "$code" == "200" && "$last" == *":$port" ]]; then
      ok "$name 已回池（实际服务者：$last）"
      return 0
    fi
    sleep 1
  done
  return 1
}

drain_instance() {   # $1=端口 $2=名称
  local port="$1" name="$2" resp
  resp=$(curl -sS -X POST "http://127.0.0.1:$port/internal/admin/drain" \
    -H "X-Admin-Token: $ADMIN_TOKEN" --max-time 5)
  printf '%s' "$resp" | json_get '["code"]' | grep -q '^0$' \
    || fail "drain $name 失败：$resp"
}

create_and_publish_template() {   # 经 LB 建并发布（顺带验证 LB 的 POST 通路）→ stdout: templateId
  local resp tpl
  resp=$(curl -sS -X POST "$LB/api/templates" \
    -H 'Content-Type: application/json' -H "X-Admin-Token: $ADMIN_TOKEN" \
    -d "{\"name\":\"LB-DRILL-$RUN_ID\",\"activityId\":900161,\"couponType\":1,
         \"faceValue\":100,\"threshold\":1000,\"totalCount\":10000,\"perUserLimit\":1,
         \"validType\":2,\"validDays\":7,\"dailyLimit\":0,\"perUserDailyLimit\":0}")
  tpl=$(printf '%s' "$resp" | json_get '["data"]["id"]' 2>/dev/null) \
    || fail "建模板失败：$resp"
  curl -sS -X POST "$LB/api/templates/$tpl/publish" \
    -H "X-Admin-Token: $ADMIN_TOKEN" | json_get '["code"]' | grep -q '^0$' \
    || fail "发布模板失败"
  echo "$tpl"
}

json_field() {   # $1=文件 $2=表达式（python）
  python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print($2)" "$1"
}

# ---------------------------------------------------------------- 护栏
step "0. 前置检查（CONFIRM 护栏 + 依赖 + 端口）"
[[ "${CONFIRM:-}" == "yes" ]] \
  || fail "这会启动两个 JVM、一个 nginx 容器并滚动重启它们。确认请加：CONFIRM=yes"
case "${BASE:-http://127.0.0.1:$PORT_A}" in
  http://localhost:*|http://127.0.0.1:*) ok "目标是本机（端口 $PORT_A/$PORT_B，LB $NGINX_PORT）" ;;
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
ss -ltn 2>/dev/null | grep -q ":$NGINX_PORT " \
  && fail "端口 $NGINX_PORT 已被占用（应用 dev 默认端口也是 8080——演练要把它给 nginx）" \
  || ok "端口 $NGINX_PORT 空闲"
for p in "$PORT_A" "$PORT_B"; do
  ss -ltn 2>/dev/null | grep -q ":$p " && fail "端口 $p 已被占用（上一轮实例没停干净？）"
done

CP_FILE="$REPO_DIR/target/stability-cp.txt"
if [[ ! -f "$CP_FILE" || ! -d "$REPO_DIR/target/classes" ]]; then
  ( cd "$REPO_DIR" && mvn -q -DskipTests compile dependency:build-classpath \
      -Dmdep.outputFile="$CP_FILE" )
fi
CP="$REPO_DIR/target/classes:$(cat "$CP_FILE")"

# ---------------------------------------------------------------- 起 LB 与两实例
step "1. 起 nginx（profile $NGINX_PROFILE）与两个实例"
( cd "$REPO_DIR" && docker compose --profile "$NGINX_PROFILE" up -d nginx ) \
  || fail "nginx 起不来（看 docker compose 输出；配置 ops/nginx/nginx.conf）"
NGINX_STARTED=1
# nginx 本身秒起；等它开始应答（后端没起时回 502，只证明监听已就绪）
for _ in $(seq 1 20); do
  curl -s -o /dev/null --max-time 2 "$LB/actuator/health" && break
  sleep 0.5
done
curl -s -o /dev/null --max-time 2 "$LB/actuator/health" || fail "nginx 没有在 $LB 监听"

PID_A=$(start_app "$PORT_A" "lbdrill-a" "$LOGDIR/lb-A.log")
wait_health "$PORT_A" "A" "$PID_A"
WID_A=$(worker_id_of "$LOGDIR/lb-A.log")
ok "实例 A 已就绪（:$PORT_A, worker-id=$WID_A）"
PID_B=$(start_app "$PORT_B" "lbdrill-b" "$LOGDIR/lb-B.log")
wait_health "$PORT_B" "B" "$PID_B"
WID_B=$(worker_id_of "$LOGDIR/lb-B.log")
ok "实例 B 已就绪（:$PORT_B, worker-id=$WID_B）"
assert_true "两个实例都通过健康检查" 1
assert_true "两个实例的 Snowflake workerId 不同（A=$WID_A, B=$WID_B）" \
    "$([[ "$WID_A" != "$WID_B" ]] && echo 1 || echo 0)"
assert_true "LB 视角两实例都可路由（探针 200）" \
    "$( [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$LB/actuator/health")" == "200" ]] && echo 1 || echo 0)"

# ---------------------------------------------------------------- 准备模板（经 LB）
step "2. 经 LB 建并发布模板（顺带验证 LB 的 POST 通路）"
TPL_ID=$(create_and_publish_template)
ok "模板已发布：$TPL_ID（经 $LB）"

# ---------------------------------------------------------------- 流量与探针
step "3. 开始持续领券流量（经 LB）+ 每秒健康探针（模拟 LB 主动探测）"
rm -f "$STOP_FILE" "$PROBE_STOP_FILE" "$PROBE_PAUSE_FILE" "$PROBE_ACK_FILE" "$PROBE_LOG"
probe_loop() {
  local code
  while [[ ! -f "$PROBE_STOP_FILE" ]]; do
    # 暂停闸打开时让路并 touch ACK：probe_loop 单线程（一次只有一个 curl），
    # 走到这里 = 上一个探针已完整结束——pause_probes 用 ACK 判定"在途探针已静止"
    if [[ -f "$PROBE_PAUSE_FILE" ]]; then
      : > "$PROBE_ACK_FILE"
      sleep 0.2
      continue
    fi
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$LB/actuator/health" || echo 000)
    printf '%s %s\n' "$(date +%s%3N)" "$code" >> "$PROBE_LOG"
    sleep 1
  done
}
probe_loop &
PROBE_PID=$!

python3 -I "$SCRIPT_DIR/dual_instance_client.py" \
  --endpoints "$LB" \
  --template-id "$TPL_ID" \
  --stop-file "$STOP_FILE" \
  --user-base "$((990000000000 + RUN_ID % 100000 * 1000))" \
  --request-prefix "lb-drill-$RUN_ID" \
  --timeout 30 \
  --max-seconds 900 \
  --output "$CLIENT_JSON" \
  > "$CLIENT_LOG" 2>&1 &
CLIENT_PID=$!
sleep 8
kill -0 "$CLIENT_PID" 2>/dev/null || fail "压测客户端没起来（看 $CLIENT_LOG）"
ok "流量与探针已开始（client PID=$CLIENT_PID, probe PID=$PROBE_PID）"

# ---------------------------------------------------------------- 滚动重启
roll_instance() {   # $1=端口 $2=名称 $3=pid变量名（nameref）
  local -n pid_ref="$3"
  local port="$1" name="$2" t0 t1 stop_ms

  warn "── 滚动 $name：drain → 确认出池 → SIGTERM → 重启 → 回池 ──"
  # 暂停后台探针要**早于 drain**（且等在途探针静止，见 pause_probes 注释）：
  # 否则后台探针在空档里抢先打到 503，"失败标记"归了它，wait_lb_marked 自己的
  # 探针永远观察不到重试列表（2026-11-10 前两轮实跑 roll B 假红的根因）。
  assert_true "后台探针已暂停且在途探针静止（ACK 收到）" \
      "$(pause_probes_and_wait_quiescent && echo 1 || echo 0)"
  drain_instance "$port" "$name"
  assert_true "$name drain 后实例侧 health=503" \
      "$(wait_drained "$port" && echo 1 || echo 0)"
  assert_true "LB 对 $name 计失败出池（观察到 503 重试列表）" \
      "$(wait_lb_marked "$port" "$name" && echo 1 || echo 0)"
  rm -f "$PROBE_PAUSE_FILE"   # 标记完成，探针恢复（出池 120s 窗口内不会打扰判定）
  confirm_removed_distribution "$port" "$name"

  t0=$(date +%s%3N)
  if stop_pid "$pid_ref" "$name"; then local graceful=1; else local graceful=0; fi
  t1=$(date +%s%3N); stop_ms=$((t1 - t0))
  assert_true "$name SIGTERM 后在 35s 内优雅退出（实际 ${stop_ms}ms）" "$graceful"
  # 等待点核实（审查 M2②）：stop_pid 上面已经**等进程完全消失**才返回（kill -0
  # 轮询至进程退出，非只发信号）；这里再钉一道端口级断言——进程退出=监听socket
  # 关闭，"端口还开着但 Tomcat paused"的 graceful 尾窗从此刻起不可能出现在路径上。
  assert_true "$name 进程退出后端口 :$port 已关闭（重启前不存在 paused 尾窗）" \
      "$(ss -ltn 2>/dev/null | grep -q ":$port " && echo 0 || echo 1)"

  pid_ref=$(start_app "$port" "lbdrill-$(tr 'A-Z' 'a-z' <<<"$name")" "$LOGDIR/lb-$name.log")
  wait_health "$port" "$name" "$pid_ref"
  ok "$name 已重启（:$port, worker-id=$(worker_id_of "$LOGDIR/lb-$name.log")）"
  assert_true "$name 重启后实例侧 health=UP" 1
  assert_true "$name 重启后被 LB 回池（X-Served-By 重新出现 :$port）" \
      "$(wait_lb_rejoined "$port" "$name" && echo 1 || echo 0)"
}

step "4. 滚动重启：先 A 后 B（drain → 出池 → SIGTERM → 重启 → 回池）"
roll_instance "$PORT_A" "A" PID_A
sleep 3
roll_instance "$PORT_B" "B" PID_B
sleep 3

# ---------------------------------------------------------------- 停止流量并核对
step "5. 停止流量与探针，核对统计"
touch "$STOP_FILE"
for _ in $(seq 1 60); do
  kill -0 "$CLIENT_PID" 2>/dev/null || break
  sleep 1
done
kill -0 "$CLIENT_PID" 2>/dev/null && fail "压测客户端 60 秒内没有退出"
wait "$CLIENT_PID" 2>/dev/null || true
CLIENT_PID=""

touch "$PROBE_STOP_FILE"
for _ in $(seq 1 10); do
  kill -0 "$PROBE_PID" 2>/dev/null || break
  sleep 0.5
done
kill -0 "$PROBE_PID" 2>/dev/null && { kill -9 "$PROBE_PID" 2>/dev/null || true; }
PROBE_PID=""

[[ -f "$CLIENT_JSON" ]] || fail "客户端没有输出统计文件（看 $CLIENT_LOG）"

REQUESTS=$(json_field "$CLIENT_JSON" 'd["requests"]')
SUCCESS=$(json_field "$CLIENT_JSON" 'd["success"]')
MIDFLIGHT=$(json_field "$CLIENT_JSON" 'd["midflightResets"]')
HTTP5XX=$(json_field "$CLIENT_JSON" 'd["http5xx"]')
BIZERR=$(json_field "$CLIENT_JSON" 'd["bizErrors"]')
BOTHDOWN=$(json_field "$CLIENT_JSON" 'd["bothEndpointsDown"]')
STOP_SEEN=$(json_field "$CLIENT_JSON" 'd["stopFileSeen"]')
# X-Served-By 里是解析后的网关 IP（实测 192.168.65.254:1809x）而非主机名——按端口后缀聚合
A_OK=$(python3 -c "import json; d=json.load(open('$CLIENT_JSON')); print(sum(v for k,v in d['perInstanceSuccess'].items() if k.endswith(':$PORT_A')))")
B_OK=$(python3 -c "import json; d=json.load(open('$CLIENT_JSON')); print(sum(v for k,v in d['perInstanceSuccess'].items() if k.endswith(':$PORT_B')))")
SERVED_TOTAL=$(python3 -c "import json; d=json.load(open('$CLIENT_JSON')); print(sum(d['perInstanceSuccess'].values()))")

ok "请求=$REQUESTS 成功=$SUCCESS 在途reset=$MIDFLIGHT 5xx=$HTTP5XX 业务失败=$BIZERR LB不可达=$BOTHDOWN"
ok "按实例成功数（X-Served-By 聚合）：A=$A_OK B=$B_OK（盖章总数 $SERVED_TOTAL）"

PROBE_COUNT=$(wc -l < "$PROBE_LOG")
PROBE_BAD=$(awk '$2 != 200' "$PROBE_LOG" | wc -l)
ok "LB 健康探针：$PROBE_COUNT 次，非 200 共 $PROBE_BAD 次"
assert_true "LB 健康探针全程 200（$PROBE_COUNT 次里非 200 有 $PROBE_BAD 次）" \
    "$([[ "$PROBE_BAD" == "0" && "$PROBE_COUNT" -ge 20 ]] && echo 1 || echo 0)"

assert_true "流量覆盖足够（请求数 $REQUESTS >= 100）" \
    "$([[ "$REQUESTS" -ge 100 ]] && echo 1 || echo 0)"
assert_true "客户端正常看到停机标记（stopFileSeen=$STOP_SEEN；不是 max-seconds 提前退出）" \
    "$([[ "$STOP_SEEN" == "True" || "$STOP_SEEN" == "true" ]] && echo 1 || echo 0)"
assert_true "两个实例都实际服务过请求（A=$A_OK, B=$B_OK）" \
    "$([[ "$A_OK" -gt 0 && "$B_OK" -gt 0 ]] && echo 1 || echo 0)"
assert_true "每个成功请求都被 LB 盖章（ΣperInstance=$SERVED_TOTAL == 成功 $SUCCESS）" \
    "$([[ "$SERVED_TOTAL" == "$SUCCESS" ]] && echo 1 || echo 0)"
assert_true "全程零 HTTP 5xx（实际 $HTTP5XX）" \
    "$([[ "$HTTP5XX" == "0" ]] && echo 1 || echo 0)"
assert_true "全程零在途请求 reset（实际 $MIDFLIGHT）" \
    "$([[ "$MIDFLIGHT" == "0" ]] && echo 1 || echo 0)"
assert_true "全程零业务失败（实际 $BIZERR）" \
    "$([[ "$BIZERR" == "0" ]] && echo 1 || echo 0)"
assert_true "全程没有'LB 不可达'（实际 $BOTHDOWN）" \
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
