#!/bin/bash
set -e
ROOT="/models/models/IEITYuan--Yuan-embedding-2.0-zh/snapshots/master"
pip install -q modelscope -i https://pypi.tuna.tsinghua.edu.cn/simple
python <<'PY'
from modelscope.hub.file_download import model_file_download
import os, shutil

files = [
    "1_Pooling/config.json",
    "2_Dense/config.json",
    "2_Dense/pytorch_model.bin",
]
dest_root = "/models/models/IEITYuan--Yuan-embedding-2.0-zh/snapshots/master"
for rel in files:
    path = model_file_download(
        model_id="IEITYuan/Yuan-embedding-2.0-zh",
        file_path=rel,
        revision="master",
        cache_dir="/models",
    )
    print("got", rel, "->", path)
    target = os.path.join(dest_root, rel)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    if os.path.abspath(path) != os.path.abspath(target):
        shutil.copy2(path, target)
    print("copied", target, os.path.getsize(target))
print("OK modules")
PY
ls -la "$ROOT/1_Pooling" "$ROOT/2_Dense"
