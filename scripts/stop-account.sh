#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="${ACCOUNT_PID_FILE:-$SCRIPT_DIR/account.pid}"
if [[ ! -s "$PID_FILE" ]]; then
  echo "Account PID file not found: $PID_FILE" >&2
  exit 1
fi
PID="$(<"$PID_FILE")"
if ! kill -0 "$PID" 2>/dev/null; then
  rm -f "$PID_FILE"
  echo "Account process is not running."
  exit 0
fi
kill "$PID"
rm -f "$PID_FILE"
echo "Account stopped (PID $PID)."
