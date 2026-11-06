#!/usr/bin/env bash
#
# MySQL 恢复演练（V6 阶段 5B 刀 2 ①）——把一份逻辑备份恢复到同实例的新库，
# 并用**备份时刻的元信息**（.meta）核对恢复出来的库。
#
# 为什么恢复到 newcoupon_restore 而不是覆盖 newcoupon：
#   演练的第一价值是"恢复动作真的能跑通"，不是"把生产库搞挂"。同实例新库能证明
#   dump 文件自洽、flyway 历史完整、分片表/库存守恒/字符集都对；覆盖源库属于
#   灾难恢复动作，必须人工审批与停写，不放进可反复执行的演练脚本。
#
# 两道防"误伤源库"的保险（2026-11-06 审查修正）：
#   ① 导入前从 dump 首部解析内嵌库名，必须等于 DB_NAME，否则立即中止——
#      如果 DB_NAME 配错、或 dump 内嵌库名不是预期，sed 替换会空操作，dump 自带的
#      `CREATE DATABASE` / `USE` / `DROP TABLE` 会直接命中真实源库；
#   ② mysql 客户端加 `--one-database $TARGET_DB`：即使 replace 失效，也只执行
#      目标库的语句。
#
# 校验基准（2026-11-06 审查修正）：
#   **不再拿"当前源库"当基准**——备份后的写入会让对比必然假失败。基准一律用
#   backup.sh 同时写出的 `.meta`（dump 时刻的表数/行数/摘要），恢复后逐项对齐。
#   除了对齐 meta，还做恢复库内部一致性断言（库存守恒、有券无库存行）。
#
# 验证清单（全部是脚本内断言）：
#   ① .meta 存在且 database == DB_NAME；
#   ② 表数、flyway 行数与摘要、关键表行数 == meta；
#   ③ 4 组 64 分表的表数 >0（枚举失败不许退化成 0==0）且行数合计 == meta；
#   ④ 库存守恒：每个模板 Σ coupon_stock_shard.used == COUNT(user_coupon)，
#      且不存在"有券行但没有库存行"的模板；
#   ⑤ utf8mb4 + 模板名/编码摘要、多字节字符数 == meta（中文原样恢复；emoji 无样本，未覆盖）。
#
# 计时口径：
#   restoreSeconds = DROP/CREATE 目标库 + 导入完成；
#   verifySeconds  = 上述断言全部跑完；
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
# 与 backup.sh 使用同一变量名（DB_NAME），避免两个脚本各配一份库名配错。
DB_NAME="${DB_NAME:-newcoupon}"
TARGET_DB="${TARGET_DB:-newcoupon_restore}"
BACKUP_DIR="${BACKUP_DIR:-$REPO_DIR/target/backup}"
BACKUP_FILE="${1:-$BACKUP_DIR/latest.sql.gz}"
# latest.sql.gz 是软链，meta 跟着**真实文件名**（newcoupon-<ts>.sql.gz.meta），
# 不是 latest.sql.gz.meta。先解析软链再找 meta。
if [[ -z "${META_FILE:-}" ]]; then
  META_FILE="$(readlink -f "$BACKUP_FILE" 2>/dev/null || echo "$BACKUP_FILE").meta"
  [[ -f "$META_FILE" ]] || META_FILE="$BACKUP_FILE.meta"
fi

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

meta_field() {   # $1=字段名 → stdout
  python3 -c 'import json,sys; v=json.load(open(sys.argv[1]))[sys.argv[2]]; print(v)' \
    "$META_FILE" "$1"
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
  if [[ -z "$sql" ]]; then
    echo "SELECT 0 WHERE FALSE"
  else
    echo "SELECT SUM(c) FROM ($sql) x"
  fi
}

# 分表枚举结果数：>0 才允许做合计断言，否则 0==0 会把"枚举失败"伪装成通过。
count_tables_like() {
  mysql_exec "" -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$1' AND table_name LIKE '${2}%'"
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
[[ "$TARGET_DB" != "$DB_NAME" ]] \
  || fail "TARGET_DB 不能等于源库 $DB_NAME（演练不覆盖源库）"
[[ "$TARGET_DB" =~ ^[A-Za-z0-9_]+$ ]] \
  || fail "TARGET_DB 只允许字母/数字/下划线：$TARGET_DB"
[[ -f "$BACKUP_FILE" ]] || fail "备份文件不存在：$BACKUP_FILE（先跑 backup.sh）"
[[ -f "$META_FILE" ]] || fail "缺少备份元信息：$META_FILE —— 没有 dump 时刻基准就无法正确校验（先跑 backup.sh）"
docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -q true \
  || fail "容器 $MYSQL_CONTAINER 不在运行——先 make up"
META_DB=$(meta_field database)
[[ "$META_DB" == "$DB_NAME" ]] \
  || fail "备份元信息里的库是 [$META_DB]，与 DB_NAME=[$DB_NAME] 不一致——拒绝恢复（防配错源库）"
ok "演练目标是本机新库：$TARGET_DB（源库 $DB_NAME 不动）"
ok "备份文件：$BACKUP_FILE（$(du -hL "$BACKUP_FILE" | cut -f1)），基准：$META_FILE"

# 解析 dump 内嵌库名：--databases 会写 `CREATE DATABASE ... \`name\`` 与 `USE \`name\``。
EMBEDDED_DB=$(zgrep -m1 -E '^CREATE DATABASE' "$BACKUP_FILE" \
  | sed -E 's/.*`([^`]+)`.*/\1/' || true)
if [[ -z "$EMBEDDED_DB" ]]; then
  EMBEDDED_DB=$(zgrep -m1 -E '^USE `' "$BACKUP_FILE" \
    | sed -E 's/^USE `([^`]+)`.*/\1/' || true)
fi
[[ -n "$EMBEDDED_DB" ]] \
  || fail "无法从 dump 解析内嵌库名——拒绝在未知 dump 上做替换导入"
[[ "$EMBEDDED_DB" == "$DB_NAME" ]] \
  || fail "dump 内嵌库名是 [$EMBEDDED_DB]，与 DB_NAME=[$DB_NAME] 不一致；
继续导入会把 dump 里的 CREATE DATABASE/USE/DROP TABLE 指向 [$EMBEDDED_DB]。已中止。"
ok "dump 内嵌库名 = $EMBEDDED_DB，与 DB_NAME 一致"

# ---------------------------------------------------------------- 恢复
step "1. 重建目标库并导入备份"
RESTORE_START_MS=$(date +%s%3N)
mysql_exec "" -e "DROP DATABASE IF EXISTS \`$TARGET_DB\`; \
  CREATE DATABASE \`$TARGET_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
ok "目标库已重建：$TARGET_DB"

# 只替换反引号包裹的库名标识符；dump 的注释/字符串数据里即使出现库名也不动。
# 再叠一层 --one-database：即使替换失效，mysql 也只执行目标库的语句。
# pipefail 确保解压/替换/导入任一段失败都能被捕获。
gzip -dc "$BACKUP_FILE" \
  | sed "s/\`$DB_NAME\`/\`$TARGET_DB\`/g" \
  | docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
      mysql -u"$MYSQL_USER" --one-database "$TARGET_DB"
RESTORE_END_MS=$(date +%s%3N)
RESTORE_MS=$((RESTORE_END_MS - RESTORE_START_MS))
ok "导入完成（$((RESTORE_MS / 1000)).$(printf '%03d' $((RESTORE_MS % 1000))) 秒）"

# ---------------------------------------------------------------- 验证
step "2. 恢复后验证（基准 = dump 时刻 .meta）"
VERIFY_START_MS=$(date +%s%3N)
GC_PREFIX="SET SESSION group_concat_max_len=1000000; "

# ① 表数 == meta（不是与"当前源库"比——备份后的写入会让对比假失败）
DST_TABLES=$(mysql_exec "" -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TARGET_DB'")
assert_eq "表数 == meta.tableCount" "$(meta_field tableCount)" "$DST_TABLES"

# ② flyway 行数 + 历史摘要 == meta。
#    不要求 installed_on 时间一致——时间列会随导入时的 NOW() 改写；版本/脚本/checksum/
#    成功标记必须逐行一致，这是 flyway validate 的核心字段摘要。
DST_FLYWAY_ROWS=$(mysql_exec "$TARGET_DB" -e "SELECT COUNT(*) FROM flyway_schema_history")
assert_eq "flyway_schema_history 行数 == meta.flywayRows" "$(meta_field flywayRows)" "$DST_FLYWAY_ROWS"
FLYWAY_DIGEST_SQL="${GC_PREFIX}SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', installed_rank, version, description, type, script, checksum, success) ORDER BY installed_rank SEPARATOR '|')) FROM flyway_schema_history"
DST_FLYWAY=$(mysql_exec "$TARGET_DB" -e "$FLYWAY_DIGEST_SQL")
assert_eq "flyway 历史摘要 == meta（版本/脚本/checksum/成功标记，等价 validate）" \
  "$(meta_field flywayDigest)" "$DST_FLYWAY"

# ③ 关键表行数 == meta（stock_shard 用下面的 Σused 单独核对，行数不等于已发数）
for pair in "coupon_template:couponTemplates" \
            "audit_log:auditLogRows" "coupon_issue_task:issueTaskRows"; do
  table="${pair%%:*}"; field="${pair##*:}"
  actual=$(mysql_exec "$TARGET_DB" -e "SELECT COUNT(*) FROM \`$table\`")
  assert_eq "$table 行数 == meta.$field" "$(meta_field "$field")" "$actual"
done
DST_USED=$(mysql_exec "$TARGET_DB" -e "SELECT COALESCE(SUM(used),0) FROM coupon_stock_shard")
assert_eq "Σ coupon_stock_shard.used == meta.stockUsedSum" "$(meta_field stockUsedSum)" "$DST_USED"

# 4 组 64 分表：先断言枚举到的表数 >0，再对合计。
# 2026-11-06 审查修正：枚举失败时旧的 `SELECT 0` 会让 0==0 假通过。
for pair in "user_coupon_:userCouponRows" "coupon_usage_:couponUsageRows" \
            "coupon_expire_task_:couponExpireTaskRows" "coupon_release_task_:couponReleaseTaskRows"; do
  prefix="${pair%%:*}"; field="${pair##*:}"
  table_count=$(count_tables_like "$TARGET_DB" "$prefix")
  ASSERTIONS=$((ASSERTIONS + 1))
  if [[ "$table_count" -gt 0 ]]; then
    ok "枚举到 ${prefix}* 表数（$table_count）"
  else
    fail "枚举不到 ${prefix}* 表（information_schema 异常？）——拒绝用 0==0 假通过"
  fi
  actual=$(mysql_exec "" -e "$(sum_count_sql "$TARGET_DB" "$prefix")")
  assert_eq "分表行数合计 == meta.$field（${prefix}*）" "$(meta_field "$field")" "$actual"
done

# ④ 库存守恒：逐模板 Σused == COUNT(user_coupon)。
# 口径与 C1StockReconcileService 等式②一致。
CONSERVATION_SQL="SELECT COUNT(*) FROM ( \
    SELECT s.template_id, SUM(s.used) AS used \
      FROM \`$TARGET_DB\`.coupon_stock_shard s GROUP BY s.template_id \
  ) s LEFT JOIN ( \
    $(coupon_count_by_template_sql "$TARGET_DB") \
  ) c ON s.template_id = c.template_id \
  WHERE s.used <> COALESCE(c.cnt, 0)"
BROKEN=$(mysql_exec "" -e "$CONSERVATION_SQL")
assert_eq "库存守恒：所有模板 Σused == 券行数" "0" "$BROKEN"

# 反向覆盖：有券行但没有库存行的模板（旧注释说"不会被静默忽略"，现在真的检查）。
NO_SHARD_SQL="SELECT COUNT(*) FROM ( \
    SELECT u.template_id FROM ( \
      $(coupon_count_by_template_sql "$TARGET_DB") \
    ) u LEFT JOIN ( \
      SELECT DISTINCT template_id FROM \`$TARGET_DB\`.coupon_stock_shard \
    ) s ON s.template_id = u.template_id \
    WHERE s.template_id IS NULL AND u.cnt > 0 GROUP BY u.template_id \
  ) x"
NO_SHARD=$(mysql_exec "" -e "$NO_SHARD_SQL")
assert_eq "不存在'有券行但无库存行'的模板" "0" "$NO_SHARD"

# ⑤ 字符集 + 多字节字段摘要 == meta。
DST_CHARSET=$(mysql_exec "" -e \
  "SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.schemata WHERE schema_name='$TARGET_DB'")
assert_eq "目标库默认字符集" "utf8mb4" "$DST_CHARSET"

DST_NONASCII=$(mysql_exec "$TARGET_DB" -e \
  "SELECT COUNT(*) FROM coupon_template WHERE HEX(name) REGEXP '(8|9|A|B|C|D|E|F)[0-9A-F]'")
assert_eq "含多字节字符的模板名数量 == meta" "$(meta_field templateNonAsciiCount)" "$DST_NONASCII"

NAME_DIGEST_SQL="${GC_PREFIX}SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', id, template_code, name, HEX(name)) ORDER BY id SEPARATOR '|')) FROM coupon_template"
DST_NAMES=$(mysql_exec "$TARGET_DB" -e "$NAME_DIGEST_SQL")
assert_eq "模板名/编码内容摘要 == meta（中文/多字节字符原样恢复；emoji 无样本未覆盖）" \
  "$(meta_field templateNameDigest)" "$DST_NAMES"

VERIFY_END_MS=$(date +%s%3N)
VERIFY_MS=$((VERIFY_END_MS - VERIFY_START_MS))
RTO_MS=$((RESTORE_MS + VERIFY_MS))

# ---------------------------------------------------------------- 收尾
step "恢复演练完成"
RESTORE_S=$(awk "BEGIN{printf \"%.3f\", $RESTORE_MS/1000}")
VERIFY_S=$(awk "BEGIN{printf \"%.3f\", $VERIFY_MS/1000}")
RTO_S=$(awk "BEGIN{printf \"%.3f\", $RTO_MS/1000}")
echo "RESTORE PASS assertions=$ASSERTIONS restoreSeconds=$RESTORE_S verifySeconds=$VERIFY_S rtoSeconds=$RTO_S"
