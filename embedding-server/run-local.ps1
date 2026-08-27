# 本机 venv 启动（需 Python 3.11+；3.14 可能装不上 torch）
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path .venv)) {
  py -3.13 -m venv .venv
  if ($LASTEXITCODE -ne 0) { py -3 -m venv .venv }
}
.\.venv\Scripts\Activate.ps1
$env:HF_ENDPOINT = if ($env:HF_ENDPOINT) { $env:HF_ENDPOINT } else { "https://hf-mirror.com" }
$env:MODEL_ID = "IEITYuan/Yuan-embedding-2.0-zh"
$env:MODEL_NAME = "Yuan-embedding-2.0-zh"
$env:DEVICE = "cpu"
pip install -r requirements.txt
python download_model.py
uvicorn app:app --host 0.0.0.0 --port 8008
