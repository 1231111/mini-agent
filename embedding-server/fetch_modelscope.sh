#!/bin/bash
set -e
pip install -q modelscope -i https://pypi.tuna.tsinghua.edu.cn/simple
python <<'PY'
from modelscope import snapshot_download
p = snapshot_download(
    "IEITYuan/Yuan-embedding-2.0-zh",
    cache_dir="/models",
)
print("OK", p)
PY
