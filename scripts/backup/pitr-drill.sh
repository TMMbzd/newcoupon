#!/usr/bin/env bash
#
# PITR（Point-In-Time Recovery）演练（V6 5C 刀 B2）——证明"全量基线 + binlog 增量"
# 能把库恢复到**任意时间点**，而不只是 dump 时刻。
#
# 演练剧本（"误删发生在 T 之后"的最小化版本）：
#   1. backup.sh 全量备份（dump 里带备份时刻 binlog 位点 = 重放起点）；
#   2. restore.sh 把全量恢复到 newcoupon_restore 并过 20 条断言（基线可用）；
#   3. 在源库制造 **T 前增量**：1 张真实模板 + pitr_marker 表 3 行（这些该活下来）；
#   4. 记录时间点 T（SHOW MASTER STATUS 的文件+位点，比 --stop-datetime 精确）；
#   5. 制造 **T 后事故数据**：1 张毒模板 + 2 行 marker（这些不该回来）；
#   6. binlog-archive.sh 归档（顺带实测"写入→可归档"的即时窗口，RPO 口径见该脚本头）；
#   7. 起一次性 MySQL 沙箱容器（newcoupon-mysql-pitr），导入全量 dump；
#   8. mysqlbinlog 从 dump 位点重放到 T 位点（--database=newcoupon 过滤——ROW 格式的
#      重放按 Table_map 的库过滤，xxl_job/newcoupon_restore 等库的事件不会打进沙箱）；
#   9. 断言：T 前数据在、T 后数据不在、schema 历史未被重放破坏。
#
# 为什么重放到**独立容器**而不是源实例的 newcoupon_restore：ROW 格式的 binlog 事件
# 把库名编在 Table_map 二进制里，MySQL 版 mysqlbinlog 没有改写库名的选项（那是
# MariaDB 的 --rewrite-db）——同名库重放只能发生在"另一台服务器"上。这也正是生产
# PITR 的形态：恢复到沙箱、核对、再切流量，绝不直接在源库上回放。
#
# 三道纪律：只打本机容器；CONFIRM=yes；幂等重跑（沙箱容器每次重建，源库演练痕迹
# 收尾清理）。EXIT 码：断言全过 = 0 并打印 "PITR DRILL PASS assertions=N"。
#
# 用法：
#   CONFIRM=yes bash scripts/backup/pitr-drill.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-newcoupon-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"
DB_NAME="${DB_NAME:-newcoupon}"
PITR_CONTAINER="${PITR_CONTAINER:-newcoupon-mysql-pitr}"
# 沙箱镜像用 Debian 变体而不是默认 oracle 变体：**mysql:8.0（oracle 底）不带 mysqlbinlog**
# （/usr/bin 里只有 mysql/mysqldump/mysqladmin 等精简客户端，2026-11-10 实测踩到）；
# Debian 变体带全套客户端工具。重放也可以换任何"有 mysqlbinlog 的镜像"当 sidecar。
PITR_IMAGE="${PITR_IMAGE:-mysql:8.0-debian}"
BACKUP_DIR="${BACKUP_DIR:-$REPO_DIR/target/backup}"
WORK_DIR="$REPO_DIR/target/pitr-work"

ASSERTIONS=0
assert_eq() {   # $1=描述 $2=期望 $3=实际
  ASSERTIONS=$((ASSERTIONS + 1))
  if [[ "$2" == "$3" ]]; then
    ok "$1（$3）"
  else
    fail "断言失败：$1 —— 期望 [$2]，实际 [$3]"
  fi
}

mysql_exec() {   # $1=容器名 $2=数据库名（可空）；其余透传 mysql
  local c="$1" db="${2:-}"; shift 2 || true
  if [[ -n "$db" ]]; then
    docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$c" mysql -u"$MYSQL_USER" -N -B "$db" "$@"
  else
    docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$c" mysql -u"$MYSQL_USER" -N -B "$@"
  fi
}

cleanup() {
  local rc=$?
  warn "清理：删沙箱容器/工作目录，并清源库演练痕迹（幂等——正常收尾已清过也无害）"
  docker rm -f "$PITR_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
  # 半途失败时源库可能留着演练对象（首跑实测踩到：marker 表残留让重跑 CREATE 撞 1050）。
  # 只删本演练的命名对象：PITR-% 模板码是本脚本生成的独占前缀，不会碰真实数据。
  docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
    mysql -u"$MYSQL_USER" -N -B "$DB_NAME" -e \
    "DROP TABLE IF EXISTS pitr_marker; DELETE FROM coupon_template WHERE template_code LIKE 'PITR-%';" \
    >/dev/null 2>&1 || true
  docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
    mysql -u"$MYSQL_USER" -N -B -e "DROP DATABASE IF EXISTS newcoupon_restore;" \
    >/dev/null 2>&1 || true
  return $rc
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------- 护栏
step "0. 前置检查"
[[ "${CONFIRM:-}" == "yes" ]] \
  || fail "演练会写源库测试数据、起一次性沙箱 MySQL 容器。确认请加：CONFIRM=yes"
docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -q true \
  || fail "容器 $MYSQL_CONTAINER 不在运行——先 make up"
docker inspect "$PITR_CONTAINER" >/dev/null 2>&1 \
  && fail "容器 $PITR_CONTAINER 已存在——先清理：docker rm -f $PITR_CONTAINER"
LOG_BIN=$(mysql_exec "$MYSQL_CONTAINER" "" -e "SHOW VARIABLES LIKE 'log_bin'" | awk '{print $2}')
[[ "$LOG_BIN" == "ON" ]] || fail "log_bin=OFF——PITR 无从谈起（compose 需带 --log-bin）"
ok "binlog 开启、源库 $MYSQL_CONTAINER 在运行、沙箱名 $PITR_CONTAINER 空闲"

# ---------------------------------------------------------------- 1. 全量备份
step "1. 全量备份（backup.sh，dump 带重放起点位点）"
mkdir -p "$WORK_DIR"
CONFIRM=yes BACKUP_DIR="$BACKUP_DIR" bash "$SCRIPT_DIR/backup.sh" 2>&1 | tee "$WORK_DIR/backup.log" | tail -3
DUMP="$BACKUP_DIR/latest.sql.gz"
DUMP_REAL=$(readlink -f "$DUMP")
META="$DUMP_REAL.meta"
[[ -f "$META" ]] || fail "备份元信息缺失：$META"
# 从 .meta 的 sourceDataLine 解析重放起点：-- CHANGE MASTER TO MASTER_LOG_FILE='mysql-bin.000004', MASTER_LOG_POS=157;
START_FILE=$(python3 -c "import json,sys,re; s=json.load(open(sys.argv[1]))['sourceDataLine']; m=re.search(r\"MASTER_LOG_FILE='([^']+)'\", s); print(m.group(1) if m else '')" "$META")
START_POS=$(python3 -c "import json,sys,re; s=json.load(open(sys.argv[1]))['sourceDataLine']; m=re.search(r'MASTER_LOG_POS=(\d+)', s); print(m.group(1) if m else '')" "$META")
[[ -n "$START_FILE" && -n "$START_POS" ]] \
  || fail "无法从 .meta 解析 binlog 起点（sourceDataLine 异常？）"
META_TABLES=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['tableCount'])" "$META")
META_TEMPLATES=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['couponTemplates'])" "$META")
META_FLYWAY=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['flywayRows'])" "$META")
ok "重放起点：$START_FILE:$START_POS（dump 时刻位点）；meta 表数=$META_TABLES 模板=$META_TEMPLATES flyway=$META_FLYWAY"

# ---------------------------------------------------------------- 2. 全量恢复基线
step "2. 全量恢复演练（restore.sh → newcoupon_restore，20 条断言）"
CONFIRM=yes bash "$SCRIPT_DIR/restore.sh" 2>&1 | tee "$WORK_DIR/restore.log" | grep -E "RESTORE PASS|断言失败" || {
  tail -20 "$WORK_DIR/restore.log"; fail "restore.sh 未通过（上面是它的输出尾部）"; }
ok "全量恢复 PASS（基线可用）"

# ---------------------------------------------------------------- 3. T 前增量
TS_TAG=$(date +%Y%m%d%H%M%S)
INC_CODE="PITR-INC-$TS_TAG"; ACC_CODE="PITR-ACC-$TS_TAG"
step "3. 制造 T 前增量数据（真实模板 + marker 表 3 行）"
# 先清上一轮半途失败可能留下的演练对象（幂等重跑；正常轮次这里是空操作）
mysql_exec "$MYSQL_CONTAINER" "$DB_NAME" -e \
  "DROP TABLE IF EXISTS pitr_marker; DELETE FROM coupon_template WHERE template_code LIKE 'PITR-%';"
mysql_exec "$MYSQL_CONTAINER" "$DB_NAME" -e "
INSERT INTO coupon_template (template_code, activity_id, name, coupon_type, face_value, threshold, total_count, per_user_limit, valid_type, valid_days)
VALUES ('$INC_CODE', 900181, 'PITR演练-恢复点前模板', 3, 500, 0, 100, 1, 2, 7);
CREATE TABLE pitr_marker (
  id INT AUTO_INCREMENT PRIMARY KEY,
  tag VARCHAR(32) NOT NULL,
  note VARCHAR(255),
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PITR 演练时间点标记（演练后清理）';
INSERT INTO pitr_marker (tag, note) VALUES ('BEFORE_T', 'T 前-1'), ('BEFORE_T', 'T 前-2'), ('BEFORE_T', 'T 前-3');"
LAST_WRITE_AT=$(mysql_exec "$MYSQL_CONTAINER" "" -N -B -e "SELECT NOW(3)")
ok "T 前数据已写（模板 $INC_CODE + marker 3 行），最后一笔写入时刻 $LAST_WRITE_AT"

# ---------------------------------------------------------------- 4. 记录时间点 T
step "4. 记录恢复目标时间点 T（binlog 位点比 datetime 精确）"
T_FILE=$(mysql_exec "$MYSQL_CONTAINER" "" -e "SHOW MASTER STATUS" | awk '{print $1}')
T_POS=$(mysql_exec "$MYSQL_CONTAINER" "" -e "SHOW MASTER STATUS" | awk '{print $2}')
T_DT=$(mysql_exec "$MYSQL_CONTAINER" "" -N -B -e "SELECT NOW(3)")
[[ -n "$T_FILE" && -n "$T_POS" ]] || fail "SHOW MASTER STATUS 拿不到位点"
ok "T = $T_FILE:$T_POS（$T_DT）——重放到这里为止"

# ---------------------------------------------------------------- 5. T 后事故数据
step "5. 制造 T 后'事故数据'（毒模板 + marker 2 行）"
mysql_exec "$MYSQL_CONTAINER" "$DB_NAME" -e "
INSERT INTO coupon_template (template_code, activity_id, name, coupon_type, face_value, threshold, total_count, per_user_limit, valid_type, valid_days)
VALUES ('$ACC_CODE', 900181, 'PITR演练-事故毒模板', 3, 99999, 0, 100, 1, 2, 7);
INSERT INTO pitr_marker (tag, note) VALUES ('AFTER_T', 'T 后-误操作-1'), ('AFTER_T', 'T 后-误操作-2');"
ok "事故数据已写（模板 $ACC_CODE + marker 2 行）——这些不该被恢复回来"

# ---------------------------------------------------------------- 6. 归档 + RPO 即时窗口
step "6. binlog 归档（顺带实测 写入→已归档 的即时窗口）"
ARCH_START=$(date +%s%3N)
CONFIRM=yes bash "$SCRIPT_DIR/binlog-archive.sh" 2>&1 | tee "$WORK_DIR/archive.log" | grep -E "本轮归档|manifest：" | tail -2
ARCH_END=$(date +%s%3N)
ARCH_MS=$((ARCH_END - ARCH_START))
# 即时窗口 = 最后一笔 T 前写入（DB 时钟 NOW(3)）→ 归档完成（宿主时钟）。两钟同机同步，
# 毫秒级误差可接受；报告里按"秒级"口径表述。
ARCH_END_STR=$(date '+%Y-%m-%d %H:%M:%S.%3N')
RPO_INSTANT=$(python3 -c "
from datetime import datetime
w='$LAST_WRITE_AT'.split('.')
a=datetime.strptime(w[0]+'.'+w[1][:3].ljust(3,'0'), '%Y-%m-%d %H:%M:%S.%f')
b=datetime.strptime('$ARCH_END_STR', '%Y-%m-%d %H:%M:%S.%f')
print(f'{(b-a).total_seconds():.3f}')")
ok "归档完成：单轮耗时 ${ARCH_MS}ms；『最后一笔 T 前写入 → 该事件已进归档目录』即时窗口 = ${RPO_INSTANT}s"

# ---------------------------------------------------------------- 7. 沙箱实例
step "7. 起一次性沙箱 MySQL（$PITR_CONTAINER，$PITR_IMAGE，无 binlog、不发布端口）"
docker run -d --name "$PITR_CONTAINER" \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_PASSWORD" \
  "$PITR_IMAGE" --character-set-server=utf8mb4 --disable-log-bin --server-id=99 >/dev/null \
  || fail "沙箱容器起不来（镜像 $PITR_IMAGE 本地没有？先 docker pull）"
ok "容器已起，等初始化（首次建库 ~20-40s）"
# 就绪判定用**带认证的 SELECT 1**，不能用 mysqladmin ping：ping 只问"服务器活着吗"，
# 在官方镜像首次初始化的临时 bootstrap 阶段（root 还没设密码）也返回 0——首跑实测
# 就是被它骗过、导入时撞上 Access denied。
for _ in $(seq 1 90); do
  docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$PITR_CONTAINER" \
    mysql -u"$MYSQL_USER" -N -B -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 1
done
docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$PITR_CONTAINER" \
  mysql -u"$MYSQL_USER" -N -B -e "SELECT 1" >/dev/null 2>&1 \
  || fail "沙箱 MySQL 90 秒内没有就绪（带认证 SELECT 1 不通）"
ok "沙箱就绪"

# ---------------------------------------------------------------- 8. 全量导入
step "8. 沙箱导入全量 dump（原始库名，不做 sed 改名——ROW 重放要求同名库）"
IMPORT_START=$(date +%s%3N)
gzip -dc "$DUMP_REAL" | docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" "$PITR_CONTAINER" \
  mysql -u"$MYSQL_USER" \
  || fail "dump 导入沙箱失败"
IMPORT_MS=$(( $(date +%s%3N) - IMPORT_START ))
PITR_TABLES_AFTER_IMPORT=$(mysql_exec "$PITR_CONTAINER" "" -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME'")
assert_eq "导入后沙箱表数 == dump 时刻 meta.tableCount" "$META_TABLES" "$PITR_TABLES_AFTER_IMPORT"
ok "导入完成（$((IMPORT_MS / 1000)).$((IMPORT_MS % 1000))s）"

# ---------------------------------------------------------------- 9. binlog 重放到 T
step "9. mysqlbinlog 从 dump 位点重放到 T（ROW + --database 过滤）"
mkdir -p "$WORK_DIR/replay"
# 选档案里编号在 [START_FILE, T_FILE] 区间内的文件（含两端），按编号排序
start_num="${START_FILE##*.}"; t_num="${T_FILE##*.}"
replay_files=()
for f in $(ls "$REPO_DIR/target/binlog-archive" | grep -E "^mysql-bin\.[0-9]+\.gz$" | sort); do
  n="${f%.gz}"; n="${n##*.}"
  if (( 10#$n >= 10#$start_num && 10#$n <= 10#$t_num )); then
    replay_files+=("${f%.gz}")
    gzip -dc "$REPO_DIR/target/binlog-archive/$f" > "$WORK_DIR/replay/${f%.gz}"
  fi
done
[[ ${#replay_files[@]} -gt 0 ]] || fail "归档目录里找不到 [$START_FILE .. $T_FILE] 区间的 binlog 文件"
ok "重放文件（${#replay_files[@]} 个）：${replay_files[*]}（起点 $START_FILE:$START_POS，终点 $T_FILE:$T_POS）"

docker exec "$PITR_CONTAINER" mkdir -p /tmp/binlog-replay
docker cp "$WORK_DIR/replay/." "$PITR_CONTAINER:/tmp/binlog-replay/" >/dev/null \
  || fail "binlog 文件拷进沙箱失败"
# 重放前先确认工具在（默认 mysql:8.0 oracle 镜像没有 mysqlbinlog——见 PITR_IMAGE 注释）；
# 且管道要 pipefail：首跑实测 mysqlbinlog 缺失时 mysql 吃空输入退出 0，假成功。
docker exec "$PITR_CONTAINER" sh -c "command -v mysqlbinlog" >/dev/null 2>&1 \
  || fail "沙箱里没有 mysqlbinlog（换 PITR_IMAGE=mysql:8.0-debian 或任一含全套客户端的镜像）"
# --start-position 只作用于第一个文件、--stop-position 只作用于最后一个（mysqlbinlog 语义），
# 中间文件整文件重放——这正是"从 dump 位点到 T 位点"的区间。
REPLAY_ARGS=(--database="$DB_NAME" --start-position="$START_POS" --stop-position="$T_POS")
REPLAY_START=$(date +%s%3N)
docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$PITR_CONTAINER" \
  bash -c "set -o pipefail; mysqlbinlog --database=$DB_NAME --start-position=$START_POS --stop-position=$T_POS \
    /tmp/binlog-replay/${replay_files[*]} | mysql -u$MYSQL_USER" \
  || fail "binlog 重放失败（mysqlbinlog/mysql 报错见上）"
REPLAY_MS=$(( $(date +%s%3N) - REPLAY_START ))
ok "重放完成（$((REPLAY_MS / 1000)).$((REPLAY_MS % 1000))s，命令：mysqlbinlog ${REPLAY_ARGS[*]} ${replay_files[*]}）"

# ---------------------------------------------------------------- 10. 断言
step "10. PITR 断言：T 前在、T 后不在"
PITR_TABLES=$(mysql_exec "$PITR_CONTAINER" "" -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME'")
assert_eq "沙箱表数 == meta.tableCount + 1（重放只多了 pitr_marker）" "$((META_TABLES + 1))" "$PITR_TABLES"

MARKER_ROWS=$(mysql_exec "$PITR_CONTAINER" "$DB_NAME" -e "SELECT COUNT(*) FROM pitr_marker")
assert_eq "T 前 marker 行全部恢复（3 行）" "3" "$MARKER_ROWS"
AFTER_ROWS=$(mysql_exec "$PITR_CONTAINER" "$DB_NAME" -e "SELECT COUNT(*) FROM pitr_marker WHERE tag='AFTER_T'")
assert_eq "T 后 marker 行没有被恢复（0 行）" "0" "$AFTER_ROWS"

INC_COUNT=$(mysql_exec "$PITR_CONTAINER" "$DB_NAME" -e \
  "SELECT COUNT(*) FROM coupon_template WHERE template_code='$INC_CODE'")
assert_eq "T 前写入的真实模板在（$INC_CODE）" "1" "$INC_COUNT"
ACC_COUNT=$(mysql_exec "$PITR_CONTAINER" "$DB_NAME" -e \
  "SELECT COUNT(*) FROM coupon_template WHERE template_code='$ACC_CODE'")
assert_eq "T 后的事故毒模板不在（$ACC_CODE）" "0" "$ACC_COUNT"
PITR_TEMPLATES=$(mysql_exec "$PITR_CONTAINER" "$DB_NAME" -e "SELECT COUNT(*) FROM coupon_template")
assert_eq "沙箱模板数 == meta.couponTemplates + 1（只多了 T 前那张）" "$((META_TEMPLATES + 1))" "$PITR_TEMPLATES"
PITR_FLYWAY=$(mysql_exec "$PITR_CONTAINER" "$DB_NAME" -e "SELECT COUNT(*) FROM flyway_schema_history")
assert_eq "flyway 历史行数未被重放破坏（== meta.flywayRows）" "$META_FLYWAY" "$PITR_FLYWAY"

# ---------------------------------------------------------------- 11. 收尾
step "11. 清理（沙箱已由 trap 删；这里清源库演练痕迹）"
mysql_exec "$MYSQL_CONTAINER" "$DB_NAME" -e "DROP TABLE IF EXISTS pitr_marker;
DELETE FROM coupon_template WHERE template_code IN ('$INC_CODE', '$ACC_CODE');"
mysql_exec "$MYSQL_CONTAINER" "" -e "DROP DATABASE IF EXISTS newcoupon_restore;"
ok "源库演练痕迹已清（pitr_marker / 两张演练模板 / newcoupon_restore）"

# ---------------------------------------------------------------- 汇总
step "演练完成"
echo "PITR DRILL PASS assertions=$ASSERTIONS"
echo "  全量导入沙箱: $((IMPORT_MS / 1000)).$(printf '%03d' $((IMPORT_MS % 1000)))s"
echo "  binlog 重放($T_FILE 起点 $START_FILE:$START_POS → $T_FILE:$T_POS): $((REPLAY_MS / 1000)).$(printf '%03d' $((REPLAY_MS % 1000)))s"
echo "  RPO 即时窗口(最后一笔 T 前写入→已归档): ${RPO_INSTANT}s（含 binlog-archive 单轮 ${ARCH_MS}ms）"
echo "  RTO 构成: 全量 dump + 沙箱起 + 导入 + 重放 + 断言（数字见 docs/evidence/2026-11-10-PITR演练.md）"
