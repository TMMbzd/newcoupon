#!/usr/bin/env bash
#
# gate 修复跨进程验收 —— 单 JVM 的 GateRepairStormIntegrationTest 证明不了的两件事：
#   ① 两个真实应用进程共享一个坏 gate 时，全集群只发生一次修复，且 DB 不超发；
#   ② Redis 重启 / 主从切换后，gate 丢失或损坏仍只修复一次，且 DB 不超发。
#
# 与 verify-cluster.sh 同一套纪律：
#   · 应用进程按 PID 停，**绝不用 pkill -f**；
#   · 专用 MySQL schema + 专用 Redis 端口，不碰 dev 库与 dev Redis；
#   · 端口先查后起，EXIT/INT/TERM 统一清理并打印 PASS/FAIL 行。
#
# 跨进程证据（不用日志猜）：
#   两个实例各自 /actuator/prometheus 上
#   issue_gate_result_total{result="repair_gate_corrupt"} 的增量之和必须 == 1。
#   该指标只由 ReceiveAsyncAppService.acquireWithRepair 发出；后台修复任务
#   （本脚本用 initial-delay=1h 停掉）不会污染它。
#
# 用法见 usage()；实现只写脚本，不改生产代码。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"
# shellcheck source=lib-gate-repair.sh
source "$SCRIPT_DIR/lib-gate-repair.sh"

PIDS=()
MODE=""
GR_SCHEMA=""
GR_RUN_DIR=""
GR_CP=""
GR_TEMPLATE_ID=""
RUN_ID=""
GR_USER_BASE=0
MVN="${MVN:-mvn}"

usage() {
  cat <<'EOF'
用法：
  CONFIRM=yes bash scripts/cluster/verify-gate-repair.sh <mode>

模式：
  two-process  两个真实应用进程（:18091/:18092）共享专用 Redis(:6380)；
               破坏 gate 后 20 并发只允许 1 次修复；第二波继续打到库存边界，
               验证 DB 不超发。
  restart      专用 Redis 重启（AOF 强制落盘后重启）；gate 丢失/损坏后
               仍只修复 1 次，并验证 DB 不超发。
  failover     Redis 主从切换（脚本化提升 replica，不依赖 Sentinel）；
               切换后仍只修复 1 次，并验证 DB 不超发。

必填：
  CONFIRM=yes   本脚本会启动 2 个 JVM、专用 Redis/MySQL schema 并改 Redis 数据。

可选环境变量：
  MVN=...                 Maven 可执行文件（默认 mvn）
  SKIP_BUILD=1            跳过编译（要求 target/classes 与 target/gate-repair-cp.txt 已存在）
  GR_QUOTA=30             模板库存（预热2 + 修复测量20 + 填充20；必须 >=24）
  GR_PORT_A=18091         应用 A 端口
  GR_PORT_B=18092         应用 B 端口
  GR_REDIS_PORT=6380      专用 Redis master 主机端口
  GR_REDIS_REPLICA_PORT=6381  failover 模式 replica 主机端口
  ADMIN_TOKEN=...         管理面令牌（默认 dev-admin-token）
  MYSQL_CONTAINER=newcoupon-mysql
  MYSQL_PASSWORD=root123456

前置：
  docker compose up -d mysql        # 只起 MySQL；dev Redis(:6379) 不被本脚本使用
  # 如未编译过，或刚改过生产代码：
  mvn -q -o -DskipTests compile
  mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/gate-repair-cp.txt

示例：
  CONFIRM=yes bash scripts/cluster/verify-gate-repair.sh two-process
  CONFIRM=yes bash scripts/cluster/verify-gate-repair.sh restart
  CONFIRM=yes bash scripts/cluster/verify-gate-repair.sh failover

通过输出：
  GATE REPAIR VERIFY PASS mode=<mode> assertions=<n> evidence=target/gate-repair-verify/<run>
失败输出：
  GATE REPAIR VERIFY FAIL mode=<mode> exit=<rc> evidence=target/gate-repair-verify/<run>
EOF
}

case "${1:-}" in
  --help|-h|help|"")
    usage
    exit 0
    ;;
  two-process|restart|failover)
    MODE="$1"
    ;;
  *)
    usage >&2
    printf '未知模式：%s\n' "$1" >&2
    exit 2
    ;;
esac

trap 'exit 130' INT
trap 'exit 143' TERM
trap cleanup EXIT

cleanup() {
  local rc=$?
  set +e
  printf '\n' >&2
  warn "清理：停止应用、专用容器、专用 schema（退出码 $rc）"
  gr_stop_apps
  gr_remove_containers
  docker network rm "$GR_NET" >/dev/null 2>&1 || true
  if [ -n "$GR_SCHEMA" ]; then
    gr_drop_schema "$GR_SCHEMA"
  fi
  local d
  for d in "${GR_TMP_DIRS[@]:-}"; do
    if [ -n "$d" ]; then gr_remove_temp_dir "$d"; fi
  done
  local p
  for p in "$GR_PORT_A" "$GR_PORT_B" "$GR_REDIS_PORT" "$GR_REDIS_REPLICA_PORT"; do
    if ss -ltn 2>/dev/null | awk -v p=":$p" 'NR > 1 && $4 ~ p "$" { found = 1 } END { exit(found ? 0 : 1) }'; then
      warn "端口 $p 仍有监听，请手动检查"
    fi
  done
  if [ "$rc" -eq 0 ]; then
    printf 'GATE REPAIR VERIFY PASS mode=%s assertions=%s evidence=%s\n' \
      "${MODE:-?}" "$ASSERTIONS" "${GR_RUN_DIR:-?}"
  else
    printf 'GATE REPAIR VERIFY FAIL mode=%s exit=%s evidence=%s\n' \
      "${MODE:-?}" "$rc" "${GR_RUN_DIR:-?}"
  fi
  exit "$rc"
}

preflight() {
  [ "${CONFIRM:-}" = "yes" ] \
    || fail "会启动 2 个 JVM、专用 Redis/MySQL schema 并改 Redis 数据。确认请加 CONFIRM=yes"
  local c
  for c in curl python3 ss java docker awk grep sed; do gr_require "$c"; done
  docker info >/dev/null 2>&1 || fail "Docker 不可用（docker info 失败）"
  [ "$(docker inspect -f '{{.State.Running}}' "$GR_MYSQL_CONTAINER" 2>/dev/null || true)" = "true" ] \
    || fail "MySQL 容器 $GR_MYSQL_CONTAINER 未运行，先执行：docker compose up -d mysql"

  gr_remove_stale
  local p
  for p in "$GR_PORT_A" "$GR_PORT_B" "$GR_REDIS_PORT"; do
    gr_check_port_free "$p"
  done
  if [ "$MODE" = "failover" ]; then
    gr_check_port_free "$GR_REDIS_REPLICA_PORT"
  fi
  [ -f "$REPO_DIR/docker/redis/redis.conf" ] || fail "找不到 docker/redis/redis.conf"
  [ "${GR_QUOTA:-0}" -ge 24 ] \
    || fail "GR_QUOTA 必须 >= 24（预热 2 + 修复测量 20 + 填充至少 2）"
}

setup_run() {
  RUN_ID="$(date +%Y%m%d_%H%M%S)_$$"
  GR_SCHEMA="newcoupon_verify_$RUN_ID"
  GR_RUN_DIR="$REPO_DIR/target/gate-repair-verify/$RUN_ID"
  mkdir -p "$GR_RUN_DIR"
  GR_USER_BASE=$(( 1000000000 + (RANDOM % 100000) * 1000 ))
  BASE="http://127.0.0.1:$GR_PORT_A"
  gr_note "mode=$MODE schema=$GR_SCHEMA ports=$GR_PORT_A/$GR_PORT_B redis=$GR_REDIS_PORT replica=$GR_REDIS_REPLICA_PORT quota=$GR_QUOTA"
  ok "证据目录：$GR_RUN_DIR"
}

build_artifacts() {
  if [ "${SKIP_BUILD:-0}" = "1" ]; then
    [ -d "$REPO_DIR/target/classes" ] || fail "SKIP_BUILD=1 但 target/classes 不存在"
    [ -f "$REPO_DIR/target/gate-repair-cp.txt" ] \
      || fail "SKIP_BUILD=1 但 target/gate-repair-cp.txt 不存在"
    warn "SKIP_BUILD=1：跳过编译，使用现有 target/classes"
  else
    step "编译（只编译主代码，不跑测试）"
    ( cd "$REPO_DIR" && "$MVN" -q -o -DskipTests compile ) || fail "编译失败"
    ( cd "$REPO_DIR" && "$MVN" -q -o dependency:build-classpath \
        -Dmdep.outputFile="$REPO_DIR/target/gate-repair-cp.txt" ) \
      || fail "生成 classpath 失败"
  fi
  GR_CP="$REPO_DIR/target/classes:$(cat "$REPO_DIR/target/gate-repair-cp.txt")"
}

start_apps() {
  step "启动两个真实应用实例（共享 schema $GR_SCHEMA / Redis :$GR_REDIS_PORT）"
  local log_a="$GR_RUN_DIR/app-$GR_PORT_A.log"
  local log_b="$GR_RUN_DIR/app-$GR_PORT_B.log"

  gr_start_app "$GR_PORT_A" "$log_a"
  if ! gr_wait_health "$GR_PORT_A" 180; then
    tail -n 60 "$log_a" >&2 || true
    fail "实例 A(:$GR_PORT_A) 未在 180s 内健康"
  fi
  ok "实例 A :$GR_PORT_A UP（PID $(cat "$GR_RUN_DIR/app-$GR_PORT_A.pid")）"

  gr_start_app "$GR_PORT_B" "$log_b"
  if ! gr_wait_health "$GR_PORT_B" 150; then
    tail -n 60 "$log_b" >&2 || true
    fail "实例 B(:$GR_PORT_B) 未在 150s 内健康"
  fi
  ok "实例 B :$GR_PORT_B UP（PID $(cat "$GR_RUN_DIR/app-$GR_PORT_B.pid")）"

  local pid_a pid_b
  pid_a=$(cat "$GR_RUN_DIR/app-$GR_PORT_A.pid")
  pid_b=$(cat "$GR_RUN_DIR/app-$GR_PORT_B.pid")
  gr_assert "两个实例是独立进程（PID $pid_a != $pid_b）" \
    "$([ "$pid_a" != "$pid_b" ] && echo 1 || echo 0)"

  local wid_a="" wid_b="" i
  for i in $(seq 1 15); do
    wid_a=$(grep -o 'worker lease acquired: workerId=[0-9]*' "$log_a" 2>/dev/null \
      | tail -1 | grep -o '[0-9]*$' || true)
    wid_b=$(grep -o 'worker lease acquired: workerId=[0-9]*' "$log_b" 2>/dev/null \
      | tail -1 | grep -o '[0-9]*$' || true)
    [ -n "$wid_a" ] && [ -n "$wid_b" ] && break
    sleep 1
  done
  if [ -n "$wid_a" ] && [ -n "$wid_b" ]; then
    gr_assert "两实例 snowflake workerId 不同（$wid_a vs $wid_b）" \
      "$([ "$wid_a" != "$wid_b" ] && echo 1 || echo 0)"
  else
    warn "日志中未解析到两实例的 workerId（跳过硬断言）"
  fi
}

create_and_warm() {
  step "建券、发布，并让两个实例都读到已发布模板"
  gr_create_template "http://127.0.0.1:$GR_PORT_A"
  gr_publish_template "http://127.0.0.1:$GR_PORT_A"
  ok "templateId=$GR_TEMPLATE_ID quota=$GR_QUOTA"

  # 两个实例各预热一发：既确认缓存不是旧的未发布版本，也让 gate/计数键先建立。
  gr_receive "http://127.0.0.1:$GR_PORT_A" "$((GR_USER_BASE + 1))" \
    "warm-a-$RUN_ID" "$GR_RUN_DIR/warm-a.json"
  gr_assert "实例 A 预热受理 HTTP 202 / code 0（实际 $GR_LAST_HTTP/$GR_LAST_CODE）" \
    "$([ "$GR_LAST_HTTP" = "202" ] && [ "$GR_LAST_CODE" = "0" ] && echo 1 || echo 0)"

  gr_receive "http://127.0.0.1:$GR_PORT_B" "$((GR_USER_BASE + 2))" \
    "warm-b-$RUN_ID" "$GR_RUN_DIR/warm-b.json"
  gr_assert "实例 B 预热受理 HTTP 202 / code 0（实际 $GR_LAST_HTTP/$GR_LAST_CODE）" \
    "$([ "$GR_LAST_HTTP" = "202" ] && [ "$GR_LAST_CODE" = "0" ] && echo 1 || echo 0)"

  gr_wait_tasks_terminal 60 || fail "预热任务未在 60s 内终态"
  local success used gate
  success=$(gr_task_success)
  used=$(gr_stock_used)
  gate=$(gr_gate_get "$GR_TEMPLATE_ID")
  gr_assert "预热后 SUCCESS=2（实际 $success）" \
    "$([ "$success" -eq 2 ] && echo 1 || echo 0)"
  gr_assert "预热后 C1 used=2（实际 $used）" \
    "$([ "$used" -eq 2 ] && echo 1 || echo 0)"
  gr_assert "预热后 gate=$((GR_QUOTA - 2))（实际 ${gate:-<missing>}）" \
    "$([ "$gate" = "$((GR_QUOTA - 2))" ] && echo 1 || echo 0)"
  gr_note "warmup success=$success used=$used gate=$gate"
}

mode_two_process() {
  step "模式 two-process：损坏 gate，两个真实进程并发 20 发"
  gr_corrupt_gate "$GR_TEMPLATE_ID"
  gr_assert "gate 已写成非数字（GET=not-a-number）" 1

  local before_corrupt before_busy after_corrupt after_busy delta_corrupt delta_busy queue_rows
  before_corrupt=$(gr_sum_counter repair_gate_corrupt)
  before_busy=$(gr_sum_counter repair_busy)
  gr_save_metrics "two-process-before"

  gr_storm 20 0 measure
  gr_save_metrics "two-process-after"

  after_corrupt=$(gr_sum_counter repair_gate_corrupt)
  after_busy=$(gr_sum_counter repair_busy)
  delta_corrupt=$(( after_corrupt - before_corrupt ))
  delta_busy=$(( after_busy - before_busy ))
  queue_rows=$(gr_num "$(gr_mysql "$GR_SCHEMA" \
    "SELECT COUNT(*) FROM coupon_issue_gate_repair WHERE template_id=$GR_TEMPLATE_ID")")

  gr_assert "跨进程 gate 修复计数 = $delta_corrupt（期望 1；A+B 指标增量之和）" \
    "$([ "$delta_corrupt" -eq 1 ] && echo 1 || echo 0)"
  gr_assert_storm_codes
  warn "诊断（非硬断言）：repair_busy 增量=$delta_busy，修复队列行=$queue_rows（修复过快时可能为 0）"

  step "第二波：继续打到库存边界，验证不超发"
  gr_storm 20 1000 fill
  gr_assert_storm_codes
  gr_save_metrics "two-process-final"
  gr_assert_no_oversell
}

mode_restart() {
  step "模式 restart：强制 AOF 落盘后重启专用 Redis"
  gr_redis CONFIG SET appendfsync always >/dev/null
  [ "$(gr_redis CONFIG GET appendfsync | tail -1 | tr -d '\r')" = "always" ] \
    || fail "无法把 appendfsync 设为 always，重启演练无法保证坏 gate 落盘"
  gr_corrupt_gate "$GR_TEMPLATE_ID"
  gr_assert "重启前 gate 已损坏并强制 AOF 落盘" 1
  gr_redis CONFIG SET appendfsync everysec >/dev/null

  local before_uptime after_uptime aof
  before_uptime=$(gr_redis_uptime)
  gr_save_metrics "restart-before"
  docker restart "$GR_REDIS_MASTER" >/dev/null
  gr_redis_wait_ping "$GR_REDIS_MASTER" 30 || fail "Redis 重启后 30s 内未恢复 PING"
  after_uptime=$(gr_redis_uptime)
  aof=$(gr_redis_aof_enabled)
  gr_assert "Redis 已重启（uptime ${before_uptime}s -> ${after_uptime}s）" \
    "$([ "$after_uptime" -lt "$before_uptime" ] && echo 1 || echo 0)"
  gr_assert "AOF 仍开启（aof_enabled=$aof）" \
    "$([ "$aof" = "1" ] && echo 1 || echo 0)"
  ok "Redis PING=PONG（:$GR_REDIS_PORT）"

  if ! gr_wait_health "$GR_PORT_A" 120; then fail "实例 A 在 Redis 重启后未恢复健康"; fi
  if ! gr_wait_health "$GR_PORT_B" 120; then fail "实例 B 在 Redis 重启后未恢复健康"; fi
  gr_assert "重启后两个实例健康（Lettuce 已重连）" 1

  local gate_after
  gate_after=$(gr_gate_get "$GR_TEMPLATE_ID")
  case "$gate_after" in
    ''|not-a-number) ok "重启后 gate 状态：${gate_after:-missing}" ;;
    *) fail "重启后 gate 既未丢失也非损坏：$gate_after（AOF 未按预期生效？）" ;;
  esac
  gr_assert "重启后 gate 仍不可用（missing 或 corrupt）" 1

  local before_scope before_corrupt before_missing after_scope after_corrupt after_missing delta_scope
  before_scope=$(gr_sum_gate_scope)
  before_corrupt=$(gr_sum_counter repair_gate_corrupt)
  before_missing=$(gr_sum_counter repair_gate_missing)
  gr_save_metrics "restart-after-restart"

  gr_storm 20 0 measure
  gr_save_metrics "restart-after-storm"

  after_scope=$(gr_sum_gate_scope)
  after_corrupt=$(gr_sum_counter repair_gate_corrupt)
  after_missing=$(gr_sum_counter repair_gate_missing)
  delta_scope=$(( after_scope - before_scope ))
  gr_assert "重启后跨进程 gate 修复计数 = $delta_scope（期望 1）" \
    "$([ "$delta_scope" -eq 1 ] && echo 1 || echo 0)"
  if [ "$gate_after" = "not-a-number" ]; then
    gr_assert "修复类型为 repair_gate_corrupt（增量 $((after_corrupt - before_corrupt))）" \
      "$([ $((after_corrupt - before_corrupt)) -eq 1 ] && echo 1 || echo 0)"
  else
    gr_assert "修复类型为 repair_gate_missing（增量 $((after_missing - before_missing))）" \
      "$([ $((after_missing - before_missing)) -eq 1 ] && echo 1 || echo 0)"
  fi
  gr_assert_storm_codes
  step "第二波：继续打到库存边界，验证不超发"
  gr_storm 20 1000 fill
  gr_assert_storm_codes
  gr_save_metrics "restart-final"
  gr_assert_no_oversell
}

mode_failover() {
  step "模式 failover：启动 replica，复制坏 gate 后切换"
  gr_start_redis_replica
  gr_redis_wait_link_up 60 || fail "replica 60s 内未与 master 建立复制"
  gr_assert "replica master_link_status=up" 1

  gr_corrupt_gate "$GR_TEMPLATE_ID"
  local ack replica_gate
  ack=$(gr_redis WAIT 1 2000 | tail -1)
  gr_assert "WAIT 1 2000 = $ack（坏 gate 已被 replica 确认）" \
    "$([ "$ack" = "1" ] && echo 1 || echo 0)"
  replica_gate=$(gr_redis_container "$GR_REDIS_REPLICA" GET "$(gr_gate_key "$GR_TEMPLATE_ID")")
  gr_assert "replica 上也是坏值（实际 ${replica_gate:-<missing>}）" \
    "$([ "$replica_gate" = "not-a-number" ] && echo 1 || echo 0)"

  local before_down after_down probe_ok=0 i
  before_down=$(gr_sum_counter redis_down_reject)
  step "停掉 master：故障必须可见（5001）"
  docker stop "$GR_REDIS_MASTER" >/dev/null
  for i in $(seq 1 10); do
    gr_receive "http://127.0.0.1:$GR_PORT_A" "$((GR_USER_BASE + 900))" \
      "failover-probe-$RUN_ID" "$GR_RUN_DIR/failover-probe.json"
    if [ "$GR_LAST_CODE" = "5001" ]; then probe_ok=1; break; fi
    sleep 1
  done
  gr_assert "master 停机期间受理返回 5001（实际 HTTP=$GR_LAST_HTTP code=$GR_LAST_CODE）" "$probe_ok"
  after_down=$(gr_sum_counter redis_down_reject)
  gr_assert "redis_down_reject 增长（$before_down -> $after_down）" \
    "$([ "$after_down" -gt "$before_down" ] && echo 1 || echo 0)"

  step "提升 replica，并把数据重建到 :$GR_REDIS_PORT"
  gr_redis_container "$GR_REDIS_REPLICA" REPLICAOF NO ONE >/dev/null
  local role
  role=$(gr_redis_role "$GR_REDIS_REPLICA")
  gr_assert "replica 已提升为 master（role=$role）" \
    "$([ "$role" = "master" ] && echo 1 || echo 0)"

  gr_redis_container "$GR_REDIS_REPLICA" SAVE >/dev/null
  local rdb_dir tmp
  rdb_dir=$(gr_redis_container "$GR_REDIS_REPLICA" CONFIG GET dir | tail -1 | tr -d '\r')
  [ -n "$rdb_dir" ] || fail "无法读取 replica 的 dir"
  # RDB 目录放在仓库 target/ 下：Docker Desktop 的共享路径一定包含仓库，
  # 比 /tmp 更不容易在 WSL 文件共享边界上踩坑。
  tmp=$(mktemp -d "$GR_RUN_DIR/failover-rdb.XXXXXX")
  GR_TMP_DIRS+=("$tmp")
  docker cp "$GR_REDIS_REPLICA:${rdb_dir}/dump.rdb" "$tmp/dump.rdb" \
    || fail "复制 replica RDB 失败"
  docker rm -f "$GR_REDIS_MASTER" "$GR_REDIS_REPLICA" >/dev/null 2>&1 || true
  gr_start_failover_master "$tmp"
  gr_redis_wait_ping "$GR_REDIS_MASTER" 30 || fail "新 master 30s 内未恢复 PING"
  gr_assert "新 master :$GR_REDIS_PORT PING=PONG" 1

  local gate_after
  gate_after=$(gr_gate_get "$GR_TEMPLATE_ID")
  gr_assert "切换后坏 gate 仍在（实际 ${gate_after:-<missing>}）" \
    "$([ "$gate_after" = "not-a-number" ] && echo 1 || echo 0)"
  if ! gr_wait_health "$GR_PORT_A" 120; then fail "实例 A 在切换后未恢复健康"; fi
  if ! gr_wait_health "$GR_PORT_B" 120; then fail "实例 B 在切换后未恢复健康"; fi
  gr_assert "切换后两个实例健康（Lettuce 已重连新 master）" 1

  local before_corrupt after_corrupt delta_corrupt
  before_corrupt=$(gr_sum_counter repair_gate_corrupt)
  gr_save_metrics "failover-before"
  gr_storm 20 0 measure
  gr_save_metrics "failover-after"
  after_corrupt=$(gr_sum_counter repair_gate_corrupt)
  delta_corrupt=$(( after_corrupt - before_corrupt ))
  gr_assert "切换后跨进程 gate 修复计数 = $delta_corrupt（期望 1）" \
    "$([ "$delta_corrupt" -eq 1 ] && echo 1 || echo 0)"
  gr_assert_storm_codes
  step "第二波：继续打到库存边界，验证不超发"
  gr_storm 20 1000 fill
  gr_assert_storm_codes
  gr_save_metrics "failover-final"
  gr_assert_no_oversell
}

main() {
  preflight
  setup_run
  step "准备专用 MySQL schema 与 Redis master"
  gr_create_schema "$GR_SCHEMA"
  gr_start_redis_master
  gr_redis_wait_ping "$GR_REDIS_MASTER" 30 || fail "专用 Redis 30s 内未就绪"
  ok "专用 Redis master :$GR_REDIS_PORT PING=PONG（dev Redis :6379 未使用）"
  build_artifacts
  start_apps
  create_and_warm
  case "$MODE" in
    two-process) mode_two_process ;;
    restart)     mode_restart ;;
    failover)    mode_failover ;;
    *) fail "未知模式：$MODE" ;;
  esac
  step "完成"
  ok "mode=$MODE assertions=$ASSERTIONS"
}

main
