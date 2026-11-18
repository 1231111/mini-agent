#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -r "$SCRIPT_DIR/../.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  . "$SCRIPT_DIR/../.env"
  set +a
fi
if [[ -n "${MEMORY_SERVICE_DB_URL:-}" ]]; then
  export DB_URL="$MEMORY_SERVICE_DB_URL"
fi
JAR="${MEMORY_SERVICE_JAR:-$SCRIPT_DIR/mini-agent-memory-service.jar}"
PORT="${MEMORY_SERVICE_PORT:-8091}"
ADDRESS="${MEMORY_SERVICE_ADDRESS:-127.0.0.1}"
SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-prod}"

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

MINI_AGENT_JAVA_OPTS="${MINI_AGENT_JAVA_OPTS:--XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=20 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError}"
read -r -a JVM_OPTS <<< "$MINI_AGENT_JAVA_OPTS"

if [[ "${1:-}" == "--foreground" ]]; then
  exec java "${JVM_OPTS[@]}" -jar "$JAR" --server.address="$ADDRESS" --server.port="$PORT" \
    --spring.profiles.active="$SPRING_PROFILE" \
    --memory.service.role="${MEMORY_SERVICE_ROLE:-all}"
fi

LOG_FILE="${MEMORY_SERVICE_LOG_FILE:-$SCRIPT_DIR/memory-service.log}"
PID_FILE="${MEMORY_SERVICE_PID_FILE:-$SCRIPT_DIR/memory-service.pid}"
if [[ -s "$PID_FILE" ]] && kill -0 "$(<"$PID_FILE")" 2>/dev/null; then
  echo "Memory-service is already running (PID $(<"$PID_FILE"))." >&2
  exit 1
fi
nohup java "${JVM_OPTS[@]}" -jar "$JAR" --server.address="$ADDRESS" --server.port="$PORT" \
  --spring.profiles.active="$SPRING_PROFILE" \
  --memory.service.role="${MEMORY_SERVICE_ROLE:-all}" \
  >>"$LOG_FILE" 2>&1 </dev/null &
echo "$!" > "$PID_FILE"
echo "Memory-service started (PID $(<"$PID_FILE")). Log: $LOG_FILE"
