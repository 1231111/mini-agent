# -*- coding: utf-8 -*-
"""为 Java 侧探针生成「金标准」：token ids + 参考向量。

为什么要有这一步：第 0 步已经证明「ONNX 图 == sentence-transformers」（cosine 0.9999999）。
但那是在 Python 里。Java 侧接进来之后，多出两个 Python 没有的变量：
  1. DJL 的 HuggingFaceTokenizer（Rust tokenizers 的 JNI 绑定，与 Python 的 tokenizers
     是同一个 Rust 库，但封装层不同 —— padding/truncation 是否自动应用必须实测）；
  2. onnxruntime 1.20.0 的 Java 绑定（读 external data 的方式与 Python 不同）。

所以 Java 侧必须再走一次同样的闸门：ids 逐 token 一致 + 向量 cosine >= 0.9999。
本脚本产出的 golden 文件就是那个闸门的参照物。

产出：probe-golden.json
  samples[i] = {id, note, text, ids_batch, mask_batch, ref_norm, ref_raw}
    ids_batch  —— 用 tokenizers 直读 tokenizer.json 做 encode_batch 的结果（含内建 padding）
    ref_norm   —— st.encode(normalize_embeddings=True)，Java 侧就该对齐这个
    ref_raw    —— st.encode(normalize_embeddings=False)，未归一化，用来单独抓绝对误差

用法：
  python make_probe_golden.py [--threads 8]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.abspath(os.path.join(
    HERE, "..", "..", "embedding-server", "models", "models",
    "IEITYuan--Yuan-embedding-2.0-zh", "snapshots", "master"))

# 与 check_consistency.py 的 build_samples() 保持同一套样本 —— 两边共用一份样本，
# 失败时才能拿第 0 步的结论直接对表定位。
sys.path.insert(0, HERE)
from check_consistency import build_samples  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--threads", type=int, default=8,
                    help="固定线程数，Windows 上多线程 OMP 会偶发挂起")
    args = ap.parse_args()

    import torch
    torch.set_num_threads(args.threads)

    from tokenizers import Tokenizer
    from sentence_transformers import SentenceTransformer

    samples = build_samples()
    texts = [s["text"] for s in samples]

    print("[1/3] tokenizers 直读 tokenizer.json，encode_batch ...", flush=True)
    tok = Tokenizer.from_file(os.path.join(MODEL_DIR, "tokenizer.json"))
    t0 = time.time()
    encs = tok.encode_batch(texts)
    print("      %d 条，%.1fs，长度=%s"
          % (len(encs), time.time() - t0, [len(e.ids) for e in encs]), flush=True)

    print("[2/3] sentence-transformers 参考向量 ...", flush=True)
    st = SentenceTransformer(MODEL_DIR, device="cpu", local_files_only=True)
    t0 = time.time()
    ref_norm = st.encode(texts, normalize_embeddings=True, convert_to_numpy=True,
                         show_progress_bar=False)
    ref_raw = st.encode(texts, normalize_embeddings=False, convert_to_numpy=True,
                        show_progress_bar=False)
    print("      %s  %.1fs" % (ref_norm.shape, time.time() - t0), flush=True)

    print("[3/3] 落盘 ...", flush=True)
    out = {
        "model_dir": MODEL_DIR,
        "threads": args.threads,
        "tokenizer_json": os.path.join(MODEL_DIR, "tokenizer.json"),
        "samples": [
            {
                "id": s["id"],
                "note": s["note"],
                "text": s["text"],
                "ids_batch": [int(x) for x in e.ids],
                "mask_batch": [int(x) for x in e.attention_mask],
                "ref_norm": [round(float(x), 9) for x in ref_norm[i]],
                "ref_raw": [round(float(x), 9) for x in ref_raw[i]],
            }
            for i, (s, e) in enumerate(zip(samples, encs))
        ],
    }
    p = os.path.join(HERE, "probe-golden.json")
    with open(p, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False)
    print("      %s  (%.1f KB)" % (p, os.path.getsize(p) / 1024), flush=True)
    print("\n长度分布（Java 侧 batchEncode 应当一致）:")
    for s, e in zip(samples, encs):
        print("  %-4s len=%4d  %s" % (s["id"], len(e.ids), s["note"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
