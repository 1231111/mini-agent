# -*- coding: utf-8 -*-
"""Java 侧探针的裁判：拿 probe-golden.json 判定 probe-java.json 是否达标。

要判三件事，任一不过就不该把 Java 内联接进项目：
  1. token ids 逐 token 一致（不一致说明 DJL 与 Python 的 tokenizers 在
     normalizer / pre_tokenizer / post_processor / padding 哪一层分叉了）
  2. normalized_embedding 的 cosine >= 0.9999
  3. sentence_embedding（未归一化）的绝对差 —— 归一化会把绝对差除小，
     只比归一化过的东西会把偏差看漏，所以这条单独查

用法：
  python check_java_probe.py
"""
from __future__ import annotations

import json
import os

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
THRESHOLD = 0.9999


def cosine(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    a = a / np.maximum(np.linalg.norm(a, axis=1, keepdims=True), 1e-12)
    b = b / np.maximum(np.linalg.norm(b, axis=1, keepdims=True), 1e-12)
    return np.sum(a * b, axis=1)


def main() -> int:
    import sys
    gp = os.path.join(HERE, "probe-golden.json")
    jp = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "probe-java.json")
    if not os.path.isabs(jp):
        jp = os.path.abspath(jp)
    for p in (gp, jp):
        if not os.path.exists(p):
            print("缺文件: %s" % p)
            return 2
    print("被测: %s" % os.path.basename(jp))
    golden = json.load(open(gp, encoding="utf-8"))
    java = json.load(open(jp, encoding="utf-8"))

    g = {s["id"]: s for s in golden["samples"]}
    g_ids = np.array([g[s["id"]]["ids_batch"] for s in golden["samples"]], dtype=object)
    g_norm = np.array([s["ref_norm"] for s in golden["samples"]], dtype=np.float64)
    g_raw = np.array([s["ref_raw"] for s in golden["samples"]], dtype=np.float64)

    print("=" * 74)
    print("金标准: %d 条 维度=%d  threads=%s" % (len(g), g_norm.shape[1], golden["threads"]))
    print("=" * 74)

    verdicts = {}
    for variant in ("plain", "explicit"):
        rows = java.get(variant)
        if not rows:
            continue
        print("\n---------- tokenizer 配置: %s ----------" % variant)

        # --- 1. 长度与 ids ---
        len_mismatch, token_mismatch, mask_mismatch = [], [], []
        first_bad = None
        for i, row in enumerate(rows):
            gs = g[row["id"]]
            if row["len"] != len(gs["ids_batch"]):
                len_mismatch.append((row["id"], row["len"], len(gs["ids_batch"])))
                continue
            if list(row["ids"]) != list(gs["ids_batch"]):
                bad = [k for k, (a, b) in enumerate(zip(row["ids"], gs["ids_batch"])) if a != b]
                token_mismatch.append((row["id"], len(bad)))
                if first_bad is None:
                    k = bad[0]
                    first_bad = (row["id"], k, row["ids"][k], gs["ids_batch"][k])
            if list(row["mask"]) != list(gs["mask_batch"]):
                mask_mismatch.append(row["id"])

        lens = sorted({r["len"] for r in rows})
        print("  长度: %s  与金标准长度不一致的样本数=%d" % (lens, len(len_mismatch)))
        for x in len_mismatch[:6]:
            print("      %s java=%d golden=%d" % x)
        print("  ids  逐 token 一致: %s  (不一致样本 %d 条)"
              % ("是" if not token_mismatch and not len_mismatch else "否", len(token_mismatch)))
        if first_bad:
            print("      首个差异 %s 位置 %d: java=%s golden=%s" % first_bad)
        for x in token_mismatch[:6]:
            print("      %s 有 %d 个 token 不同" % x)
        print("  mask 一致: %s  (不一致 %d 条)"
              % ("是" if not mask_mismatch else "否", len(mask_mismatch)))

        # --- 2/3. 向量 ---
        v_norm = np.array([r["vec"] for r in rows], dtype=np.float64)
        v_raw = np.array([r["vecraw"] for r in rows], dtype=np.float64)
        cos = cosine(v_norm, g_norm)
        raw_abs = np.abs(v_raw - g_raw)
        raw_max = raw_abs.max()
        raw_argmax = np.unravel_index(np.argmax(raw_abs), raw_abs.shape)
        norm_java = np.linalg.norm(v_norm, axis=1)
        print("  cosine(normalized) : min=%.10f  mean=%.10f  最差样本=%s"
              % (cos.min(), cos.mean(), rows[int(np.argmin(cos))]["id"]))
        print("  未归一化 max|diff| : %.3e  (样本 %s 第 %d 维)"
              % (raw_max, rows[raw_argmax[0]]["id"], raw_argmax[1]))
        print("  Java 侧 L2 范数    : min=%.9f max=%.9f" % (norm_java.min(), norm_java.max()))

        ok = (not len_mismatch and not token_mismatch and not mask_mismatch
              and cos.min() >= THRESHOLD)
        verdicts[variant] = ok
        print("  ==> %s" % ("PASS" if ok else "FAIL"))

    print("\n" + "=" * 74)
    print("门槛: ids 逐 token 一致 + mask 一致 + cosine >= %g" % THRESHOLD)
    for k, v in verdicts.items():
        print("  %-9s %s" % (k, "PASS" if v else "FAIL"))
    if any(verdicts.values()):
        print("\n结论: PASS —— Java 侧接入可行（用通过的那套 tokenizer 配置）")
    else:
        print("\n结论: FAIL —— 不要把内联 embedding 接进项目，先解决上面对不上的那层")
    print("=" * 74)
    return 0 if any(verdicts.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
