#!/usr/bin/env bash
# 重新编译并重启后端：先停旧进程（等端口释放，超时强杀），再编译，避免覆盖运行中的 jar
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MVN=${MVN:-$(command -v mvn || echo ~/tools/apache-maven-3.9.16/bin/mvn)}
pkill -f cloudai-backend-1.0.0.jar
for i in $(seq 1 10); do lsof -nP -iTCP:8580 -sTCP:LISTEN >/dev/null || break; sleep 1; done
pkill -9 -f cloudai-backend-1.0.0.jar 2>/dev/null; sleep 1
cd "$ROOT/backend"
if ! $MVN -s .mvn-settings.xml -q -DskipTests package > "$ROOT/.logs/build.log" 2>&1; then
  grep -E 'ERROR|error:' "$ROOT/.logs/build.log" | head -30
  echo "编译失败，未启动后端（完整日志：.logs/build.log）"; exit 1
fi
CLOUDAI_DATA_DIR="$ROOT/data" nohup java -jar target/cloudai-backend-1.0.0.jar > "$ROOT/.logs/backend.log" 2>&1 &
for i in $(seq 1 40); do grep -qE 'Started CloudAiApplication|APPLICATION FAILED' "$ROOT/.logs/backend.log" && break; sleep 2; done
grep -E 'Started CloudAiApplication|APPLICATION FAILED|Caused by' "$ROOT/.logs/backend.log" | head -5
