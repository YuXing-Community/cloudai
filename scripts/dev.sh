#!/usr/bin/env bash
# CloudAI 本地开发一键启动：MySQL + Milvus（Docker，独立实例）→ Python 执行器 → Java 后端 → Vue 前端
# 用法：./scripts/dev.sh [infra|executor|backend|frontend|all|stop|status]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/.logs"; VOL="$ROOT/deploy/volumes"
mkdir -p "$LOG" "$ROOT/data" "$VOL"/{etcd,minio,milvus,mysql}
MVN=${MVN:-$(command -v mvn || echo "$HOME/tools/apache-maven-3.9.16/bin/mvn")}
PIP_INDEX=${PIP_INDEX:-https://mirrors.aliyun.com/pypi/simple/}

run_if_absent() { # name, docker run args...
  local name=$1; shift
  if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then docker start "$name" >/dev/null; else docker run -d --name "$name" "$@" >/dev/null; fi
}

infra() {
  echo "▶ Docker 基础设施（colima）"
  docker info >/dev/null 2>&1 || colima start --cpu 4 --memory 8 --disk 60
  echo "▶ MySQL 8 :3306（root 无密码）"
  run_if_absent cloudai-mysql --restart unless-stopped -p 3306:3306 -e MYSQL_ALLOW_EMPTY_PASSWORD=yes -e MYSQL_ROOT_HOST=% \
    -v "$VOL/mysql:/var/lib/mysql" mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci --default-time-zone=+08:00
  echo "▶ CloudAI 专用 Milvus :19540（与 datademand 隔离）"
  docker network create cloudai-milvus >/dev/null 2>&1 || true
  run_if_absent cloudai-milvus-etcd --network cloudai-milvus --network-alias etcd --restart unless-stopped \
    -e ETCD_AUTO_COMPACTION_MODE=revision -e ETCD_AUTO_COMPACTION_RETENTION=1000 -e ETCD_QUOTA_BACKEND_BYTES=4294967296 -e ETCD_SNAPSHOT_COUNT=50000 \
    -v "$VOL/etcd:/etcd" quay.io/coreos/etcd:v3.5.5 etcd -advertise-client-urls=http://127.0.0.1:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd
  run_if_absent cloudai-milvus-minio --network cloudai-milvus --network-alias minio --restart unless-stopped \
    -e MINIO_ACCESS_KEY=minioadmin -e MINIO_SECRET_KEY=minioadmin -v "$VOL/minio:/minio_data" \
    minio/minio:RELEASE.2023-03-20T20-16-18Z server /minio_data --console-address ":9001"
  run_if_absent cloudai-milvus-standalone --network cloudai-milvus --restart unless-stopped --security-opt seccomp:unconfined \
    -e ETCD_ENDPOINTS=etcd:2379 -e MINIO_ADDRESS=minio:9000 -v "$VOL/milvus:/var/lib/milvus" \
    -p 19540:19530 -p 9592:9091 milvusdb/milvus:v2.4.15 milvus run standalone
  for i in $(seq 1 30); do curl -sf http://127.0.0.1:9592/healthz >/dev/null && break; sleep 3; done
  curl -sf http://127.0.0.1:9592/healthz >/dev/null && echo "  Milvus OK" || echo "  ⚠ Milvus 未就绪"
}

executor() {
  echo "▶ Python 执行器 :8590"
  cd "$ROOT/executor"
  if [ ! -x .venv/bin/python ]; then
    uv venv --python 3.12 .venv && UV_INDEX_URL=$PIP_INDEX uv pip install --python .venv/bin/python -r requirements.txt
  fi
  if [ ! -d "$ROOT/data/models/bge-small-zh-v1.5" ]; then
    echo "  下载 BGE 向量模型（ModelScope）"
    .venv/bin/python -c "from modelscope import snapshot_download; snapshot_download('BAAI/bge-small-zh-v1.5', local_dir='$ROOT/data/models/bge-small-zh-v1.5')" >/dev/null 2>&1
  fi
  pkill -f "uvicorn app.main:app" 2>/dev/null; sleep 1
  CLOUDAI_DATA_DIR="$ROOT/data" nohup .venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8590 > "$LOG/executor.log" 2>&1 &
  for i in $(seq 1 20); do curl -sf http://127.0.0.1:8590/health >/dev/null && break; sleep 1; done
  echo "  日志：$LOG/executor.log"
}

backend() {
  echo "▶ Java 后端 :8580"
  "$ROOT/scripts/restart-backend.sh"
}

frontend() {
  echo "▶ 前端 :5573"
  cd "$ROOT/frontend"
  [ -d node_modules ] || npm install --no-audit --no-fund
  pkill -f "vite" 2>/dev/null; sleep 1
  nohup npm run dev > "$LOG/frontend.log" 2>&1 &
  echo "  日志：$LOG/frontend.log"
}

stop() {
  pkill -f cloudai-backend-1.0.0.jar; pkill -f "uvicorn app.main:app"; pkill -f vite
  echo "已停止应用进程（Docker 中的 MySQL / Milvus 保留运行）"
}

status() {
  for p in 5573:前端 8580:后端 8590:执行器 3306:MySQL 19540:Milvus; do
    lsof -nP -iTCP:${p%%:*} -sTCP:LISTEN >/dev/null 2>&1 && echo "✅ ${p#*:} :${p%%:*}" || echo "❌ ${p#*:} :${p%%:*}"
  done
}

case "${1:-all}" in
  infra) infra ;; executor) executor ;; backend) backend ;; frontend) frontend ;;
  stop) stop ;; status) status ;;
  all) infra; executor; backend; frontend; sleep 2; status; echo "打开 http://localhost:5573" ;;
  *) echo "用法：$0 [infra|executor|backend|frontend|all|stop|status]"; exit 1 ;;
esac
