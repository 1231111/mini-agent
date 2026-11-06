#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="${MEMORY_SERVICE_PID_FILE:-$SCRIPT_DIR/memory-service.pid}"
if [[ ! -s "$PID_FILE" ]]; then
  echo "Memory-service PID file not found: $PID_FILE" >&2
  exit 1
fi
PID="$(<"$PID_FILE")"
if ! kill -0 "$PID" 2>/dev/null; then
  rm -f "$PID_FILE"
  echo "Memory-service process is not running."
  exit 0
fi
kill "$PID"
rm -f "$PID_FILE"
echo "Memory-service stopped (PID $PID)."
