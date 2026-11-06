#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${MEMORY_SERVICE_JAR:-$SCRIPT_DIR/mini-agent-memory-service.jar}"
PORT="${MEMORY_SERVICE_PORT:-8091}"
ADDRESS="${MEMORY_SERVICE_ADDRESS:-0.0.0.0}"

: "${DB_URL:?Set DB_URL to the memory-service MySQL database URL}"
: "${DB_USERNAME:?Set DB_USERNAME}"
: "${DB_PASSWORD:?Set DB_PASSWORD}"
: "${ACCOUNT_BASE_URL:?Set ACCOUNT_BASE_URL reachable from this server}"
: "${ACCOUNT_INTERNAL_KEY:?Set ACCOUNT_INTERNAL_KEY to the same value used by Account}"
: "${ACCOUNT_MEMORY_TOKEN_KEY:?Set ACCOUNT_MEMORY_TOKEN_KEY to the same value used by Account}"

if [[ ! -r "$JAR" ]]; then
  echo "Memory-service JAR not found or unreadable: $JAR" >&2
  exit 1
fi

if [[ "${1:-}" == "--foreground" ]]; then
  exec java -jar "$JAR" --server.address="$ADDRESS" --server.port="$PORT" \
    --memory.service.role="${MEMORY_SERVICE_ROLE:-all}"
fi

LOG_FILE="${MEMORY_SERVICE_LOG_FILE:-$SCRIPT_DIR/memory-service.log}"
PID_FILE="${MEMORY_SERVICE_PID_FILE:-$SCRIPT_DIR/memory-service.pid}"
if [[ -s "$PID_FILE" ]] && kill -0 "$(<"$PID_FILE")" 2>/dev/null; then
  echo "Memory-service is already running (PID $(<"$PID_FILE"))." >&2
  exit 1
fi
nohup java -jar "$JAR" --server.address="$ADDRESS" --server.port="$PORT" \
  --memory.service.role="${MEMORY_SERVICE_ROLE:-all}" \
  >>"$LOG_FILE" 2>&1 </dev/null &
echo "$!" > "$PID_FILE"
echo "Memory-service started (PID $(<"$PID_FILE")). Log: $LOG_FILE"
