"""下载 Yuan-embedding-2.0-zh 到本地缓存，并打印向量维度。"""
from __future__ import annotations

import os
import sys

from sentence_transformers import SentenceTransformer

MODEL_ID = os.getenv("MODEL_ID", "IEITYuan/Yuan-embedding-2.0-zh")
MODEL_DIR = os.getenv("MODEL_DIR", "").strip()
DEVICE = os.getenv("DEVICE", "cpu")


def main() -> int:
    path = MODEL_DIR if MODEL_DIR else MODEL_ID
    print(f"downloading/loading: {path}", flush=True)
    if os.getenv("HF_ENDPOINT"):
        print(f"HF_ENDPOINT={os.environ['HF_ENDPOINT']}", flush=True)
    model = SentenceTransformer(path, device=DEVICE)
    dim = int(model.get_sentence_embedding_dimension())
    vec = model.encode(
        ["维度探测"],
        normalize_embeddings=True,
        convert_to_numpy=True,
    )
    actual = int(vec.shape[-1])
    print(f"MODEL_READY dim_reported={dim} dim_actual={actual}", flush=True)
    out = os.getenv("DIM_FILE", "").strip()
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write(str(actual))
    return 0


if __name__ == "__main__":
    sys.exit(main())
