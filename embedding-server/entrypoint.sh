#!/bin/sh
set -e
if [ -n "$MODEL_DIR" ] && [ -f "$MODEL_DIR/config.json" ]; then
  echo "[yuan-embed] using local MODEL_DIR=$MODEL_DIR"
else
  echo "[yuan-embed] ensuring model cache..."
  python download_model.py
fi
exec uvicorn app:app --host 0.0.0.0 --port 8008
