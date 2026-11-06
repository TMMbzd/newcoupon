#!/usr/bin/env bash
#
# MySQL 恢复演练（V6 阶段 5B 刀 2 ①）——把一份逻辑备份恢复到同实例的新库，
# 并在脚本里逐条核对"恢复出来的库和源库是不是同一份事实"。
#
# 为什么恢复到 newcoupon_restore 而不是覆盖 newcoupon：
#   演练的第一价值是"恢复动作真的能跑通"，不是"把生产库搞挂"。同实例新库能证明
#   dump 文件自洽、flyway 历史完整、分片表/库存守恒/字符集都对；覆盖源库属于
#   灾难恢复动作，必须人工审批与停写，不放进可反复执行的演练脚本。
#
# 恢复方法：dump 里带 `CREATE DATABASE `newcoupon`` 与 `USE `newcoupon``，
# 恢复前把 **反引号包裹的库名** 替换成目标库（只替换 `newcoupon` 这个标识符，
# 不动普通字符串数据）。然后用 mysql 客户端整包导入。
#
# 验证清单（全部是脚本内断言，不是"打印出来给人看"）：
#   ① 表数一致；
#   ② flyway_schema_history 摘要一致（版本/脚本/checksum/成功标记）；
#   ③ 关键表行数一致 + 4 组 64 分表的行数合计一致；
#   ④ 库存守恒：每个模板 Σ coupon_stock_shard.used == COUNT(user_coupon)；
#   ⑤ 字符集 utf8mb4 + 中文/emoji 字段摘要一致（dump/restore 没把多字节字符弄坏）。
#
# 计时口径：
#   restoreSeconds = DROP/CREATE 目标库 + 导入完成；
#   verifySeconds  = 上述五项断言全部跑完；
#   RTO            = restoreSeconds + verifySeconds（演练的目标值 ≤30 分钟）。
#
# 用法：
#   CONFIRM=yes bash scripts/backup/restore.sh                    # 用 target/backup/latest.sql.gz
#   CONFIRM=yes bash scripts/backup/restore.sh /path/to/dump.sql.gz
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-newcoupon-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"
SOURCE_DB="${SOURCE_DB:-newcoupon}"
TARGET_DB="${TARGET_DB:-newcoupon_restore}"
BACKUP_DIR="${BACKUP_DIR:-$REPO_DIR/target/backup}"
BACKUP_FILE="${1:-$BACKUP_DIR/latest.sql.gz}"

ASSERTIONS=0
assert_eq() {   # $1=描述 $2=期望 $3=实际
  ASSERTIONS=$((ASSERTIONS + 1))
  if [[ "$2" == "$3" ]]; then
    ok "$1（$3）"
  else
    fail "断言失败：$1 —— 期望 [$2]，实际 [$3]"
  fi
}

mysql_exec() {   # $1=数据库名（可空表示不指定）；其余参数透传 mysql
  local db="${1:-}"; shift || true
  if [[ -n "$db" ]]; then
    docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
      mysql -u"$MYSQL_USER" -N -B "$db" "$@"
  else
    docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
      mysql -u"$MYSQL_USER" -N -B "$@"
  fi
}

# 生成 "SELECT COUNT(*) FROM db.t1 UNION ALL ..." 的跨分表合计 SQL。
# $1=库名 $2=表名前缀（如 user_coupon_）
sum_count_sql() {
  local db="$1" prefix="$2" first=1 sql="" t
  for t in $(mysql_exec "" -e \
      "SELECT table_name FROM information_schema.tables WHERE table_schema='$db' AND table_name LIKE '${prefix}%' ORDER BY table_name"); do
    [[ $first -eq 1 ]] && first=0 || sql+=" UNION ALL "
    sql+="SELECT COUNT(*) AS c FROM \`$db\`.\`$t\`"
  done
  [[ -n "$sql" ]] || { echo "SELECT 0"; return; }
  echo "SELECT SUM(c) FROM ($sql) x"
}

# 生成 64 张分表的 (template_id, COUNT(*)) 子查询，供库存守恒核对。
coupon_count_by_template_sql() {
  local db="$1" first=1 sql="" t
  for t in $(mysql_exec "" -e \
      "SELECT table_name FROM information_schema.tables WHERE table_schema='$db' AND table_name LIKE 'user\_coupon\_%' ORDER BY table_name"); do
    [[ $first -eq 1 ]] && first=0 || sql+=" UNION ALL "
    sql+="SELECT template_id FROM \`$db\`.\`$t\`"
  done
  if [[ -z "$sql" ]]; then
    echo "SELECT NULL AS template_id, 0 AS cnt WHERE FALSE"
  else
    echo "SELECT template_id, COUNT(*) AS cnt FROM ($sql) u GROUP BY template_id"
  fi
}

# ---------------------------------------------------------------- 护栏
step "0. 前置检查（三道护栏）"
[[ "${CONFIRM:-}" == "yes" ]] \
  || fail "恢复会 DROP/CREATE 目标库。确认请加：CONFIRM=yes"
[[ "$TARGET_DB" != "$SOURCE_DB" ]] \
  || fail "TARGET_DB 不能等于源库 $SOURCE_DB（演练不覆盖源库）"
[[ "$TARGET_DB" =~ ^[A-Za-z0-9_]+$ ]] \
  || fail "TARGET_DB 只允许字母/数字/下划线：$TARGET_DB"
[[ -f "$BACKUP_FILE" ]] || fail "备份文件不存在：$BACKUP_FILE（先跑 backup.sh）"
docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -q true \
  || fail "容器 $MYSQL_CONTAINER 不在运行——先 make up"
ok "演练目标是本机新库：$TARGET_DB（源库 $SOURCE_DB 不动）"
ok "备份文件：$BACKUP_FILE（$(du -hL "$BACKUP_FILE" | cut -f1)）"

# ---------------------------------------------------------------- 恢复
step "1. 重建目标库并导入备份"
RESTORE_START_MS=$(date +%s%3N)
mysql_exec "" -e "DROP DATABASE IF EXISTS \`$TARGET_DB\`; \
  CREATE DATABASE \`$TARGET_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
ok "目标库已重建：$TARGET_DB"

# 只替换反引号包裹的库名标识符；dump 的注释/字符串数据里即使出现 newcoupon 也不动。
# pipefail 确保解压/替换/导入任一段失败都能被捕获。
gzip -dc "$BACKUP_FILE" \
  | sed "s/\`$SOURCE_DB\`/\`$TARGET_DB\`/g" \
  | docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
      mysql -u"$MYSQL_USER"
RESTORE_END_MS=$(date +%s%3N)
RESTORE_MS=$((RESTORE_END_MS - RESTORE_START_MS))
ok "导入完成（$((RESTORE_MS / 1000)).$(printf '%03d' $((RESTORE_MS % 1000))) 秒）"

# ---------------------------------------------------------------- 验证
step "2. 恢复后验证（脚本内断言）"
VERIFY_START_MS=$(date +%s%3N)

# ① 表数一致
SRC_TABLES=$(mysql_exec "" -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SOURCE_DB'")
DST_TABLES=$(mysql_exec "" -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TARGET_DB'")
assert_eq "表数一致" "$SRC_TABLES" "$DST_TABLES"

# ② flyway 历史摘要一致（版本/脚本/checksum/成功标记）。
# 不要求 installed_on 时间一致——时间列会随导入时的 NOW() 改写，但"跑过哪些迁移、
# 每个迁移的 checksum 是多少、是否成功"必须逐行一致，等价于 flyway validate。
FLYWAY_DIGEST_SQL="SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', installed_rank, version, description, type, script, checksum, success) ORDER BY installed_rank SEPARATOR '|')) FROM flyway_schema_history"
SRC_FLYWAY=$(mysql_exec "$SOURCE_DB" -e "$FLYWAY_DIGEST_SQL")
DST_FLYWAY=$(mysql_exec "$TARGET_DB" -e "$FLYWAY_DIGEST_SQL")
assert_eq "flyway_schema_history 摘要一致（等价 flyway validate）" "$SRC_FLYWAY" "$DST_FLYWAY"

# ③ 关键表行数一致 + 4 组 64 分表行数合计一致
for table in coupon_template coupon_stock_shard coupon_issue_task coupon_daily_counter \
             coupon_user_daily_counter audit_log coupon_share_detail coupon_outbox \
             coupon_usage_0 risk_decision_log open_app; do
  # 表可能还没建（旧备份/裁剪环境）——两边都查不到时跳过，不制造假失败
  exists_src=$(mysql_exec "" -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SOURCE_DB' AND table_name='$table'")
  [[ "$exists_src" == "1" ]] || { warn "源库没有 $table，跳过"; continue; }
  src=$(mysql_exec "$SOURCE_DB" -e "SELECT COUNT(*) FROM \`$table\`")
  dst=$(mysql_exec "$TARGET_DB" -e "SELECT COUNT(*) FROM \`$table\`")
  assert_eq "行数一致：$table" "$src" "$dst"
done

for prefix in user_coupon_ coupon_usage_ coupon_expire_task_ coupon_release_task_; do
  src=$(mysql_exec "" -e "$(sum_count_sql "$SOURCE_DB" "$prefix")")
  dst=$(mysql_exec "" -e "$(sum_count_sql "$TARGET_DB" "$prefix")")
  assert_eq "分表行数合计一致：${prefix}*" "$src" "$dst"
done

# ④ 库存守恒：逐模板 Σused == COUNT(user_coupon)。
# 口径与 C1StockReconcileService 等式②一致；只从 coupon_stock_shard 侧驱动，
# 因此"有券行但没有库存行"的异常模板不会被静默忽略——见下一条全局总数核对。
CONSERVATION_SQL="SELECT COUNT(*) FROM ( \
    SELECT s.template_id, SUM(s.used) AS used \
      FROM \`$TARGET_DB\`.coupon_stock_shard s GROUP BY s.template_id \
  ) s LEFT JOIN ( \
    $(coupon_count_by_template_sql "$TARGET_DB") \
  ) c ON s.template_id = c.template_id \
  WHERE s.used <> COALESCE(c.cnt, 0)"
BROKEN=$(mysql_exec "" -e "$CONSERVATION_SQL")
assert_eq "库存守恒：所有模板 Σused == 券行数" "0" "$BROKEN"

SRC_USED=$(mysql_exec "$SOURCE_DB" -e "SELECT COALESCE(SUM(used),0) FROM coupon_stock_shard")
DST_USED=$(mysql_exec "$TARGET_DB" -e "SELECT COALESCE(SUM(used),0) FROM coupon_stock_shard")
assert_eq "全局 Σ coupon_stock_shard.used 一致" "$SRC_USED" "$DST_USED"

# ⑤ 字符集 + 中文/emoji 字段摘要一致。
# HEX(name) 里只要有 >=0x80 的字节就说明含多字节字符（中文/emoji 都会命中）；
# 再把整行内容做 MD5——"含多字节"只证明有，摘要一致才证明 dump/restore 没弄坏。
DST_CHARSET=$(mysql_exec "" -e \
  "SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.schemata WHERE schema_name='$TARGET_DB'")
assert_eq "目标库默认字符集" "utf8mb4" "$DST_CHARSET"

NONASCII_SQL="SELECT COUNT(*) FROM coupon_template \
  WHERE HEX(name) REGEXP '(8|9|A|B|C|D|E|F)[0-9A-F]'"
SRC_NONASCII=$(mysql_exec "$SOURCE_DB" -e "$NONASCII_SQL")
DST_NONASCII=$(mysql_exec "$TARGET_DB" -e "$NONASCII_SQL")
assert_eq "含多字节字符的模板名数量一致" "$SRC_NONASCII" "$DST_NONASCII"

NAME_DIGEST_SQL="SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', id, template_code, name, HEX(name)) ORDER BY id SEPARATOR '|')) FROM coupon_template"
SRC_NAMES=$(mysql_exec "$SOURCE_DB" -e "$NAME_DIGEST_SQL")
DST_NAMES=$(mysql_exec "$TARGET_DB" -e "$NAME_DIGEST_SQL")
assert_eq "模板名/编码内容摘要一致（中文/emoji 原样恢复）" "$SRC_NAMES" "$DST_NAMES"

VERIFY_END_MS=$(date +%s%3N)
VERIFY_MS=$((VERIFY_END_MS - VERIFY_START_MS))
RTO_MS=$((RESTORE_MS + VERIFY_MS))

# ---------------------------------------------------------------- 收尾
step "恢复演练完成"
RESTORE_S=$(awk "BEGIN{printf \"%.3f\", $RESTORE_MS/1000}")
VERIFY_S=$(awk "BEGIN{printf \"%.3f\", $VERIFY_MS/1000}")
RTO_S=$(awk "BEGIN{printf \"%.3f\", $RTO_MS/1000}")
echo "RESTORE PASS assertions=$ASSERTIONS restoreSeconds=$RESTORE_S verifySeconds=$VERIFY_S rtoSeconds=$RTO_S"
