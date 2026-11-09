#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${ACCOUNT_JAR:-$SCRIPT_DIR/mini-agent-account.jar}"
PORT="${ACCOUNT_SERVER_PORT:-8081}"

: "${DB_URL:?Set DB_URL to the account MySQL database URL}"
: "${DB_USERNAME:?Set DB_USERNAME}"
: "${DB_PASSWORD:?Set DB_PASSWORD}"
: "${ACCOUNT_INTERNAL_KEY:?Set ACCOUNT_INTERNAL_KEY}"
: "${ACCOUNT_MEMORY_TOKEN_KEY:?Set ACCOUNT_MEMORY_TOKEN_KEY}"

if [[ ! -r "$JAR" ]]; then
  echo "Account JAR not found or unreadable: $JAR" >&2
  exit 1
fi

if [[ "${1:-}" == "--foreground" ]]; then
  exec java -jar "$JAR" --server.port="$PORT"
fi

LOG_FILE="${ACCOUNT_LOG_FILE:-$SCRIPT_DIR/account.log}"
PID_FILE="${ACCOUNT_PID_FILE:-$SCRIPT_DIR/account.pid}"
if [[ -s "$PID_FILE" ]] && kill -0 "$(<"$PID_FILE")" 2>/dev/null; then
  echo "Account is already running (PID $(<"$PID_FILE"))." >&2
  exit 1
fi
nohup java -jar "$JAR" --server.port="$PORT" \
  >>"$LOG_FILE" 2>&1 </dev/null &
echo "$!" > "$PID_FILE"
echo "Account started (PID $(<"$PID_FILE")). Log: $LOG_FILE"
