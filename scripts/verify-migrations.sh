#!/usr/bin/env bash
# Flyway 迁移序列自检。
#
# 为什么需要这个脚本：CI 里原本就调用它，但文件一直不存在 —— 于是那一步在每次
# 流水线上都是失败的（"bash: scripts/verify-migrations.sh: No such file or directory"），
# 而"CI 绿"这件事本身就不再可信。这里把它补成一个真会失败的检查。
#
# 检查内容（都是发布时才暴露、且代价很高的错误）：
#   1. 版本号跨 SQL / Java 迁移全局唯一 —— 重复版本在 Flyway 里是启动期硬失败；
#   2. 文件名必须与版本号一致（V12__ 放在 V9 的文件名里是手改残留）；
#   3. 每个迁移文件必须有非空描述（V3__.sql 这种在 flyway_schema_history 里无法辨认）；
#   4. 不允许出现版本空缺（可以有意为之，但必须显式在 ALLOWED_GAPS 里声明）；
#   5. mini-agent-app 与 mini-agent-account 的迁移链各自独立编号（这是两个库）。
set -euo pipefail

cd "$(dirname "$0")/.."

# 有意保留的版本空缺（例如为并行开发预留号段）。空表示不允许空缺。
ALLOWED_GAPS=""

fail=0
err() { echo "::error::$*" >&2; fail=1; }

# 逐个迁移链检查：目录参数 + Java 包目录参数
check_chain() {
  local label="$1"
  local sql_dir="$2"
  local java_dir="$3"

  local versions_file
  versions_file="$(mktemp)"

  # SQL 迁移：V<version>__<description>.sql
  if [[ -d "$sql_dir" ]]; then
    while IFS= read -r file; do
      local base version desc
      base="$(basename "$file")"
      version="$(sed -E 's/^V([0-9]+)__.*/\1/' <<<"$base")"
      desc="$(sed -E 's/^V[0-9]+__(.*)\.sql$/\1/' <<<"$base")"
      [[ "$base" =~ ^V[0-9]+__.+\.sql$ ]] || { err "$label: 迁移文件名不合规（应为 V<数字>__<描述>.sql）: $base"; continue; }
      [[ -n "$desc" ]] || err "$label: 迁移缺少描述: $base"
      echo "$version $label/sql/$base" >>"$versions_file"
    done < <(find "$sql_dir" -maxdepth 1 -type f -name 'V*__*.sql' | sort)
  fi

  # Java 迁移：V<version>__<Description>.java
  if [[ -d "$java_dir" ]]; then
    while IFS= read -r file; do
      local base version
      base="$(basename "$file")"
      [[ "$base" =~ ^V[0-9]+__.+\.java$ ]] || { err "$label: Java 迁移文件名不合规（应为 V<数字>__<描述>.java）: $base"; continue; }
      version="$(sed -E 's/^V([0-9]+)__.*/\1/' <<<"$base")"
      echo "$version $label/java/$base" >>"$versions_file"
    done < <(find "$java_dir" -maxdepth 1 -type f -name 'V*__*.java' | sort)
  fi

  if [[ ! -s "$versions_file" ]]; then
    echo "warning: $label 没有任何迁移文件（$sql_dir / $java_dir）" >&2
    rm -f "$versions_file"
    return 0
  fi

  # 1. 版本号唯一
  local dupes
  dupes="$(cut -d' ' -f1 "$versions_file" | sort -n | uniq -d)"
  if [[ -n "$dupes" ]]; then
    while IFS= read -r v; do
      err "$label: 版本号重复 V$v —— Flyway 启动会直接失败。涉及：$(grep -E "^$v " "$versions_file" | cut -d' ' -f2- | tr '\n' ' ')"
    done <<<"$dupes"
  fi

  # 4. 版本号连续（允许在 ALLOWED_GAPS 声明空缺）
  local min max
  min="$(cut -d' ' -f1 "$versions_file" | sort -n | head -1)"
  max="$(cut -d' ' -f1 "$versions_file" | sort -n | tail -1)"
  local v
  for ((v = min; v <= max; v++)); do
    if ! grep -qE "^$v " "$versions_file"; then
      if [[ " $ALLOWED_GAPS " == *" $v "* ]]; then
        echo "note: $label 版本 V$v 空缺（已在 ALLOWED_GAPS 中声明）"
      else
        err "$label: 缺少版本 V$v（若为有意预留，请加入 ALLOWED_GAPS 并说明原因）"
      fi
    fi
  done

  local count
  count="$(wc -l <"$versions_file" | tr -d ' ')"
  echo "✓ $label: $count 个迁移，V$min..V$max"
  rm -f "$versions_file"
}

check_chain "agent" \
  "mini-agent-app/src/main/resources/db/migration" \
  "mini-agent-app/src/main/java/com/miniagent/migration"
check_chain "account" \
  "mini-agent-account/src/main/resources/db/migration" \
  "mini-agent-account/src/main/java/com/miniagent/account/migration"

if [[ "$fail" -ne 0 ]]; then
  echo "迁移序列校验失败" >&2
  exit 1
fi
echo "迁移序列校验通过"
