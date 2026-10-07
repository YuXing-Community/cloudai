#!/usr/bin/env bash
# 测试辅助：api METHOD PATH [JSON]，自动带 token 和空间头；结果打印 JSON
B=${B:-http://127.0.0.1:8580/api}
api() {
  local m=$1 p=$2 d=${3:-}
  local args=(-s -X "$m" "$B$p" -H 'Content-Type: application/json')
  [ -n "${TOKEN:-}" ] && args+=(-H "Authorization: Bearer $TOKEN")
  [ -n "${SPACE:-}" ] && args+=(-H "X-Space-Id: $SPACE")
  [ -n "$d" ] && args+=(-d "$d")
  curl "${args[@]}"
}
j() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }
