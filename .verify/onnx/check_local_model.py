# -*- coding: utf-8 -*-
"""裁判：LocalOnnxEmbeddingModel（生产类）的输出 vs Python 金标准。

与 check_java_probe.py 的区别：那份判的是"手写探针跑 DJL + ORT 行不行"，
这份判的是"真的接进项目的那份实现对不对"。两者不能互相替代 ——
实现里的分桶、手工 padding、占位语义都可能引入探针里没有的偏差。

比三件事：
  A batch  —— embedAll(20 条) vs 金标准 ref_norm 的 cosine
  B single —— 逐条 embed() vs 金标准 ref_norm 的 cosine
  C 一致性 —— batch 与 single 互相之间的 cosine（批次填充不变性）
               优化后的实现会按 token 数排序分桶，不同桶的 padding 长度不同，
               若手工补齐或 mask 写错，这条会先炸。

用法：
  python check_local_model.py [local-model-out.json]
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
THRESHOLD = 0.9999


def cosine(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    a = a / np.maximum(np.linalg.norm(a, axis=1, keepdims=True), 1e-12)
    b = b / np.maximum(np.linalg.norm(b, axis=1, keepdims=True), 1e-12)
    return np.sum(a * b, axis=1)


def main() -> int:
    golden = json.load(open(os.path.join(HERE, "probe-golden.json"), encoding="utf-8"))
    jp = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "local-model-out.json")
    if not os.path.isabs(jp):
        jp = os.path.abspath(jp)
    out = json.load(open(jp, encoding="utf-8"))

    ref = np.array([s["ref_norm"] for s in golden["samples"]], dtype=np.float64)
    ids = [s["id"] for s in golden["samples"]]
    print("被测: %s   dim=%s" % (os.path.basename(jp), out.get("dim")))
    print("=" * 74)

    mats = {}
    for key in ("batch", "single"):
        rows = out.get(key) or []
        if len(rows) != len(ids):
            print("%-7s 条数不符：%d 条，金标准 %d 条" % (key, len(rows), len(ids)))
            return 2
        if [r["id"] for r in rows] != ids:
            print("%-7s id 顺序与金标准不一致 —— 说明结果没按入参下标回填" % key)
            return 2
        mats[key] = np.array([r["vec"] for r in rows], dtype=np.float64)

    verdicts = {}
    for key in ("batch", "single"):
        m = mats[key]
        if m.shape[1] != ref.shape[1]:
            print("%-7s 维度不符：%d vs %d" % (key, m.shape[1], ref.shape[1]))
            return 2
        cos = cosine(m, ref)
        norms = np.linalg.norm(m, axis=1)
        empty = int(np.sum(norms == 0))
        worst = int(np.argmin(cos))
        ok = cos.min() >= THRESHOLD and empty == 0
        verdicts[key] = ok
        print("%-7s cosine min=%.10f mean=%.10f  最差=%s  零向量=%d  范数[%.6f, %.6f]  ==> %s"
              % (key, cos.min(), cos.mean(), ids[worst], empty,
                 norms.min(), norms.max(), "PASS" if ok else "FAIL"))
        if not ok:
            bad = [(ids[i], round(float(cos[i]), 8)) for i in np.argsort(cos)[:5]]
            print("        最差 5 条: %s" % bad)

    # C 批次填充不变性
    cb = cosine(mats["batch"], mats["single"])
    ok_c = cb.min() >= THRESHOLD
    print("%-7s cosine min=%.10f  最差=%s  ==> %s"
          % ("A/B 一致", cb.min(), ids[int(np.argmin(cb))], "PASS" if ok_c else "FAIL"))

    print("=" * 74)
    allok = all(verdicts.values()) and ok_c
    print("门槛: cosine >= %g（batch / single / 两者互相一致）" % THRESHOLD)
    print("结论: %s" % ("PASS —— LocalOnnxEmbeddingModel 与 Python 参考实现一致" if allok
                      else "FAIL —— 不要把这个实现接出去"))
    print("=" * 74)
    return 0 if allok else 1


if __name__ == "__main__":
    raise SystemExit(main())
