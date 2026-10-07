#!/usr/bin/env bash
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
pkill -f "uvicorn app.main:app"; sleep 2
cd "$ROOT/executor" && CLOUDAI_DATA_DIR="$ROOT/data" nohup .venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8590 > "$ROOT/.logs/executor.log" 2>&1 < /dev/null & disown
for i in $(seq 1 20); do curl -s http://127.0.0.1:8590/health >/dev/null && break; sleep 1; done; curl -s http://127.0.0.1:8590/health; echo
