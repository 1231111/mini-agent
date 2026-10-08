# -*- coding: utf-8 -*-
"""ONNX 内联 vs Python 服务：延迟与吞吐基准。

为什么必须量：内联 embedding 的全部收益就是「去掉进程外一跳 + 去掉 1792 个 float 的
JSON 序列化」。这个收益到底值多少，不能靠估，要有 p50/p95。

三组对比（后两组按可用性自动跳过）：
  L1 ONNX fp32（以及 fp16/int8 若有）—— 进程内，onnxruntime
  L2 PyTorch sentence-transformers 同进程 —— 参考实现的进程内上限
  L3 HTTP（需先起 embedding-server）—— 现状：进程外 + HTTP + JSON

文本长度按 agent 里的真实用途取：
  - 记忆条目 / 用户问句 ~30 字
  - 上下文候选片段 ~200 字

用法：
  python bench.py                              # 只跑 L1/L2
  python bench.py --http http://127.0.0.1:8008 # 加上 L3
  python bench.py --iters 60
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.abspath(os.path.join(
    HERE, "..", "..", "embedding-server", "models", "models",
    "IEITYuan--Yuan-embedding-2.0-zh", "snapshots", "master"))

CANDIDATES = {
    "fp32": "yuan-embedding-2.0-zh.onnx",
    "fp16": "yuan-embedding-2.0-zh.fp16.onnx",
    "int8": "yuan-embedding-2.0-zh.int8.onnx",
}

SHORT = "上下文窗口溢出时应当优先压缩最旧的对话，并保留最近的任务状态。"
LONG = ("记忆模块把事件写进流水表，再由后台任务抽取成条目并建索引。检索时按语义相似度排序，"
        "因此本地 embedding 模型的输出必须稳定；一旦模型换了维度，已有索引就会维度不匹配。"
        "上下文管理还要考虑工具结果的预算，超长的工具输出必须先截断再进入窗口。") * 2
BATCH8 = [SHORT] * 8


def pct(xs: list[float], q: float) -> float:
    if not xs:
        return float("nan")
    s = sorted(xs)
    idx = min(len(s) - 1, int(round(q / 100.0 * (len(s) - 1))))
    return s[idx]


def summarize(name: str, unit: str, times: list[float]) -> dict:
    d = {
        "label": name,
        "unit": unit,
        "n": len(times),
        "mean_ms": round(statistics.mean(times) * 1000, 2),
        "p50_ms": round(pct(times, 50) * 1000, 2),
        "p95_ms": round(pct(times, 95) * 1000, 2),
        "min_ms": round(min(times) * 1000, 2),
        "max_ms": round(max(times) * 1000, 2),
    }
    return d


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--iters", type=int, default=40)
    ap.add_argument("--http", default=None, help="embedding-server 的 base url")
    ap.add_argument("--threads", type=int, default=8,
                    help="固定线程数。Windows 上 OMP 多线程偶发挂起，不固定会随机卡住")
    args = ap.parse_args()

    import onnxruntime as ort
    import torch

    # 必须固定：实测 Windows + 多线程 OMP 下 torch CPU 编码会偶发卡住十几分钟无产出，
    # 表现为「基准跑到一半不动了」。参考向量缓存与三方对比都要在同一线程数下才有可比性。
    torch.set_num_threads(args.threads)

    out = {"threads": {"torch": torch.get_num_threads(),
                       "ort_intra_op": args.threads,
                       "fixed_by": "--threads"},
           "iters": args.iters, "cases": {}}

    texts = {"short(~30字)": SHORT, "long(~400字)": LONG}
    batches = {"short(~30字)": [SHORT], "long(~400字)": [LONG], "batch8(短)": BATCH8}

    sessions = {}
    for name, fname in CANDIDATES.items():
        p = os.path.join(HERE, fname)
        if os.path.exists(p):
            so = ort.SessionOptions()
            so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
            so.intra_op_num_threads = args.threads
            sessions[name] = (ort.InferenceSession(p, so, providers=["CPUExecutionProvider"]), p)
    if not sessions:
        print("没有 ONNX 产物，先跑 export_onnx.py")
        return 2

    # ---------- L1 ONNX ----------
    for name, (sess, p) in sessions.items():
        in_names = [i.name for i in sess.get_inputs()]
        out_names = [o.name for o in sess.get_outputs()]
        assert in_names == ["input_ids", "attention_mask"], in_names
        for case, batch in batches.items():
            # 预热：不预热则首个样本把 session 首次分配/图优化的开销算进去，
            # 而 L2 有预热 —— 不补这一下，L1 是拿"冷启动"对"稳态"，加速比会虚高。
            enc = _tokenize(batch)
            sess.run(out_names, {"input_ids": np.asarray(enc["input_ids"], dtype=np.int64),
                                 "attention_mask": np.asarray(enc["attention_mask"], dtype=np.int64)})
            per_call = []
            for _ in range(args.iters):
                # 计时含 tokenize：Java 侧也是「文本进去、向量出来」，只测推理会虚高
                t0 = time.perf_counter()
                enc = _tokenize(batch)
                ids = np.asarray(enc["input_ids"], dtype=np.int64)
                am = np.asarray(enc["attention_mask"], dtype=np.int64)
                res = sess.run(out_names, {"input_ids": ids, "attention_mask": am})
                assert res[0].shape[1] == 1792
                per_call.append(time.perf_counter() - t0)
            key = "L1 %s / %s" % (name, case)
            out["cases"][key] = summarize(key, "ms/次(含tokenize)", per_call)

    # ---------- L2 PyTorch 同进程 ----------
    from sentence_transformers import SentenceTransformer
    st = SentenceTransformer(MODEL_DIR, device="cpu", local_files_only=True)
    tok = st.tokenizer
    for case, batch in batches.items():
        st.encode(batch, normalize_embeddings=True, show_progress_bar=False)  # 预热
        per_call = []
        for _ in range(args.iters):
            t0 = time.perf_counter()
            v = st.encode(batch, normalize_embeddings=True, convert_to_numpy=True,
                          show_progress_bar=False)
            assert v.shape[1] == 1792
            per_call.append(time.perf_counter() - t0)
        key = "L2 pytorch / %s" % case
        out["cases"][key] = summarize(key, "ms/次(含tokenize)", per_call)

    # ---------- L3 HTTP ----------
    if args.http:
        import urllib.request
        url = args.http.rstrip("/") + "/v1/embeddings"

        def call_http(payload):
            req = urllib.request.Request(url, method="POST",
                                         data=json.dumps(payload).encode(),
                                         headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.loads(r.read().decode())

        for case, batch in batches.items():
            call_http({"input": batch})  # 预热
            per_call = []
            for _ in range(args.iters):
                t0 = time.perf_counter()
                body = call_http({"input": batch})
                per_call.append(time.perf_counter() - t0)
            key = "L3 http / %s" % case
            out["cases"][key] = summarize(key, "ms/次(网络+模型)", per_call)
            out["cases"][key]["note"] = "返回向量长度=%d" % len(body["data"][0]["embedding"])

    print("\n=================== 结果 ===================")
    print("%-34s %8s %8s %8s %8s" % ("case", "mean", "p50", "p95", "n"))
    for k, v in out["cases"].items():
        print("%-34s %8.2f %8.2f %8.2f %8d"
              % (k, v["mean_ms"], v["p50_ms"], v["p95_ms"], v["n"]))

    fp32 = {k: v for k, v in out["cases"].items() if k.startswith("L1 fp32")}
    ref = {k: v for k, v in out["cases"].items() if k.startswith("L2 pytorch")}
    if fp32 and ref:
        print("\nL1(fp32) 相对 L2(pytorch) 的 p50 加速比：")
        for case in ("short(~30字)", "long(~400字)", "batch8(短)"):
            a = fp32.get("L1 fp32 / %s" % case)
            b = ref.get("L2 pytorch / %s" % case)
            if a and b:
                print("  %-16s %.1fx  (%.1fms -> %.1fms)"
                      % (case, b["p50_ms"] / a["p50_ms"], b["p50_ms"], a["p50_ms"]))
    if args.http:
        http = {k: v for k, v in out["cases"].items() if k.startswith("L3 http")}
        if http and fp32:
            print("\nL1(fp32) 相对 L3(HTTP) 的 p50 加速比：")
            for case in ("short(~30字)", "long(~400字)", "batch8(短)"):
                a = fp32.get("L1 fp32 / %s" % case)
                b = http.get("L3 http / %s" % case)
                if a and b:
                    print("  %-16s %.1fx  (%.1fms -> %.1fms)"
                          % (case, b["p50_ms"] / a["p50_ms"], b["p50_ms"], a["p50_ms"]))

    p = os.path.join(HERE, "bench.json")
    with open(p, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2)
    print("\n明细: %s" % p)
    return 0


_TOK = None


def _tokenize(batch: list[str]):
    """直读模型目录里的 tokenizer.json（Java 侧 HuggingFace tokenizers 也是读它）。

    padding/truncation 都写在 tokenizer.json 顶层（BatchLongest/Right、
    LongestFirst/max_length=512），所以 encode_batch 会自己应用，不用再传参。
    """
    global _TOK
    if _TOK is None:
        from tokenizers import Tokenizer
        _TOK = Tokenizer.from_file(os.path.join(MODEL_DIR, "tokenizer.json"))
    encs = _TOK.encode_batch(batch)
    return {"input_ids": [e.ids for e in encs],
            "attention_mask": [e.attention_mask for e in encs]}


if __name__ == "__main__":
    raise SystemExit(main())
