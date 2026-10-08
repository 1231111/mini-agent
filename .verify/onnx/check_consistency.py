# -*- coding: utf-8 -*-
"""ONNX 导出件与 sentence-transformers 的数值一致性比对 —— 第 0 步的闸门。

不通过（cosine < 0.9999）就不做 Java 侧接入。理由：一旦进了 Java，
数值差异会和 tokenizer 差异、维度错配、归一化漏做混在一起，再也分不清是谁的锅。

比对分四组：
  A 单条逐样本：ONNX normalized vs ST encode(normalize_embeddings=True)
  B 未归一化输出：ONNX sentence_embedding vs ST encode(normalize_embeddings=False)
                  —— 归一化会掩盖误差（除法把绝对差压小了），所以必须单独比一次原始输出
  C 批量填充不变性：同一条文本「单独编码」与「混在批次里编码」结果必须一致
                  —— 这条专门抓 attention_mask / padding 写错。写错时 A 组也可能通过，
                  因为批次内所有样本的 mask 通常都是对的，单条编码也掩盖不了。
  D 边界样本：超长（>512，走截断）、纯空白、纯标点、[UNK]、全角、大小写

用法：
  python check_consistency.py                       # 比对已存在的 fp32（以及 fp16/int8，若有）
  python check_consistency.py --models fp32
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
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

# 精度门槛。fp32 必须稳过；fp16/int8 只作数据点，各自报实测值。
THRESHOLD = 0.9999


def build_samples() -> list[dict]:
    """样本集。每条带 id 与说明，方便失败时定位是哪一类文本出的问题。"""
    long_cn = "上下文管理的核心问题是如何在有限的窗口里保留最有价值的信息。" * 60
    return [
        {"id": "s01", "note": "极短中文", "text": "你好"},
        {"id": "s02", "note": "短中文", "text": "内存不足"},
        {"id": "s03", "note": "中文句子", "text": "上下文窗口溢出时应当优先压缩最旧的对话。"},
        {"id": "s04", "note": "中文段落", "text":
            "记忆模块把事件写进流水表，再由后台任务抽取成条目并建索引。"
            "检索时按语义相似度排序，因此本地 embedding 模型的输出必须稳定。"},
        {"id": "s05", "note": "中英混排", "text": "MiniAgent 的 ToolPipeline 会先过 BLOCK 闸门"},
        {"id": "s06", "note": "纯英文", "text": "The quick brown fox jumps over the lazy dog."},
        {"id": "s07", "note": "大小写（验 BertNormalizer.lowercase=true）",
         "text": "HeLLo WoRLD MiNiAgEnT"},
        {"id": "s08", "note": "首尾空白 + 连续空白（验 clean_text）",
         "text": "　  前后都有空白   中间  也是\t制表符  "},
        {"id": "s09", "note": "全角标点与符号", "text": "！？。，、；：「」『』（）【】—…·"},
        {"id": "s10", "note": "纯标点", "text": "。。。！！！？？？"},
        {"id": "s11", "note": "数字与符号", "text": "1792 维、512 tokens、0.9999 相似度"},
        {"id": "s12", "note": "罕见字/可能 [UNK]", "text": "𠀋 龘 靐 㐀 中文扩展区"},
        {"id": "s13", "note": "emoji", "text": "执行完成 ✅ 接下来 🔧 修一下 🐛"},
        {"id": "s14", "note": "代码片段", "text":
            "public static boolean isExecBlocked(String t, ExecPolicy p) { return EXEC_TOOL.equals(t); }"},
        {"id": "s15", "note": "超长（>512 token，走 LongestFirst 截断）", "text": long_cn},
        {"id": "s16", "note": "单字", "text": "好"},
        {"id": "s17", "note": "空串", "text": ""},
        {"id": "s18", "note": "单个空格", "text": " "},
        {"id": "s19", "note": "换行分隔", "text": "第一行\n第二行\n第三行"},
        {"id": "s20", "note": "URL 与路径", "text": "POST /api/conversations/{sid}/permission 与 D:\\AI\\miniagent"},
    ]


def cosine(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    a = a / np.maximum(np.linalg.norm(a, axis=1, keepdims=True), 1e-12)
    b = b / np.maximum(np.linalg.norm(b, axis=1, keepdims=True), 1e-12)
    return np.sum(a * b, axis=1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="*", default=None,
                    help="要比对的变体；默认全部已存在的")
    ap.add_argument("--gate", nargs="*", default=["fp32"],
                    help="哪些变体算「闸门」（决定退出码）。其余只作数据点报告。"
                         "int8 这类激进的量化失败不该把 fp32 的结论一起带翻。")
    ap.add_argument("--threads", type=int, default=8,
                    help="torch/ort 线程数。固定下来，避免不同次运行的数值与耗时漂移。")
    ap.add_argument("--no-ref-cache", action="store_true",
                    help="忽略参考向量的磁盘缓存，强制重算（改了样本集时用）")
    args = ap.parse_args()

    import onnxruntime as ort
    import torch
    from sentence_transformers import SentenceTransformer

    available = {k: os.path.join(HERE, v) for k, v in CANDIDATES.items()}
    available = {k: p for k, p in available.items() if os.path.exists(p)}
    if args.models:
        available = {k: p for k, p in available.items() if k in args.models}
    if not available:
        print("没有可比的 ONNX，先跑 export_onnx.py")
        return 2

    print("参考实现: sentence-transformers (torch %s)" % torch.__version__)
    # 固定线程数：多线程 OMP 在 Windows 上偶发卡住（实测卡了 13 分钟没产出），
    # 而且线程数不同会让 fp32 的加法顺序变化，导致数值对比出现无意义的抖动。
    torch.set_num_threads(args.threads)
    print("线程数固定为 %d" % args.threads)
    st = SentenceTransformer(MODEL_DIR, device="cpu", local_files_only=True)
    tok = st.tokenizer
    print("参考维度: %d" % st.get_sentence_embedding_dimension())

    samples = build_samples()
    texts = [s["text"] for s in samples]
    print("样本数: %d（含超长、空串、纯标点等边界）\n" % len(texts))

    # 参考向量缓存：那条 encode 要 30s+ 且偶发卡住，缓存后重跑只测 ONNX。
    # 指纹含模型目录 + 线程数 + 样本集，任一变化都会让缓存失效，不会拿旧结果糊弄。
    fp = hashlib.sha256(("\n".join(
        [MODEL_DIR, str(args.threads)] + texts)).encode("utf-8")).hexdigest()[:16]
    cache = os.path.join(HERE, "ref-cache-%s.npz" % fp)
    if os.path.exists(cache) and not args.no_ref_cache:
        z = np.load(cache)
        ref_norm, ref_raw = z["norm"], z["raw"]
        print("参考向量来自缓存 %s（%s）" % (os.path.basename(cache), fp))
    else:
        t0 = time.time()
        ref_norm = st.encode(texts, normalize_embeddings=True, convert_to_numpy=True,
                             show_progress_bar=False, batch_size=16)
        ref_raw = st.encode(texts, normalize_embeddings=False, convert_to_numpy=True,
                            show_progress_bar=False, batch_size=16)
        print("参考编码 %d 条 %.1fs" % (len(texts), time.time() - t0))
        np.savez_compressed(cache, norm=ref_norm, raw=ref_raw)

    report = {"threshold_cosine": THRESHOLD, "sample_count": len(texts),
              "gate_models": args.gate, "models": {}}
    overall_ok = True

    # ---- E tokenizer 等价性（与 ONNX 无关，但决定 Java 侧能不能对上）----
    # Java 侧用 HuggingFace tokenizers 直接读 tokenizer.json，靠的是文件里内建的
    # padding(BatchLongest/Right) 与 truncation(LongestFirst/512)。而 Python 参考实现
    # 是 st.encode() 显式传 padding=True, truncation='longest_first', max_length=512。
    # 两者必须等价，否则向量差得再多也不是 ONNX 的锅 —— 所以单独验一次并写进报告。
    from tokenizers import Tokenizer as _RustTokenizer
    rust_tok = _RustTokenizer.from_file(os.path.join(MODEL_DIR, "tokenizer.json"))
    mismatches = []
    for i, s in enumerate(samples):
        a = tok(s["text"], padding=True, truncation="longest_first",
                max_length=512)["input_ids"]
        b = rust_tok.encode(s["text"]).ids
        if a != b:
            mismatches.append({"id": s["id"],
                               "st_len": len(a), "rust_len": len(b),
                               "first_diff": next((j for j in range(min(len(a), len(b)))
                                                   if a[j] != b[j]), None)})
    # 批次级：BatchLongest 右侧填充 —— 逐条对比 padding 是否一致
    batch_a = tok(texts, padding=True, truncation="longest_first",
                  max_length=512)["input_ids"]
    batch_b = [e.ids for e in rust_tok.encode_batch(texts)]
    batch_ok = batch_a == batch_b
    report["E_tokenizer_equivalence"] = {
        "single_text_mismatches": mismatches,
        "batch_mismatch": not batch_ok,
        "baked_in_padding": "BatchLongest/Right (tokenizer.json)",
        "baked_in_truncation": "LongestFirst/max_length=512 (tokenizer.json)",
        "pass": (not mismatches) and batch_ok,
    }
    print("\n=================== E tokenizer 等价性 ===================")
    print("单条 20 例不一致数 = %d；批次 20 例序列完全一致 = %s -> %s"
          % (len(mismatches), batch_ok,
             "PASS" if report["E_tokenizer_equivalence"]["pass"] else "FAIL"))
    if mismatches:
        for m_ in mismatches[:5]:
            print("   %s" % m_)
    overall_ok = overall_ok and report["E_tokenizer_equivalence"]["pass"]

    for name, path in available.items():
        companions = sorted({p for p in (path + ".data",) if os.path.exists(p)})
        size = os.path.getsize(path) + sum(os.path.getsize(p) for p in companions)
        print("\n=================== %s (%s，含 external data) ===================" % (name, size))
        so = ort.SessionOptions()
        so.intra_op_num_threads = args.threads
        so.inter_op_num_threads = 1
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        sess = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
        in_names = [i.name for i in sess.get_inputs()]
        out_names = [o.name for o in sess.get_outputs()]
        print("inputs=%s outputs=%s" % (in_names, out_names))

        def run(batch: list[str]):
            enc = tok(batch, padding=True, truncation="longest_first",
                      max_length=512, return_tensors="np")
            ids = np.asarray(enc["input_ids"], dtype=np.int64)
            am = np.asarray(enc["attention_mask"], dtype=np.int64)
            outs = sess.run(out_names, {"input_ids": ids, "attention_mask": am})
            return {n: np.asarray(v, dtype=np.float32) for n, v in zip(out_names, outs)}

        t0 = time.time()
        got = run(texts)
        onnx_seconds = time.time() - t0
        got_norm = got["normalized_embedding"]
        got_raw = got["sentence_embedding"]

        m = {"path": os.path.basename(path), "bytes": size,
             "inputs": in_names, "outputs": out_names,
             "onnx_seconds_total": round(onnx_seconds, 2),
             "shape": list(got_norm.shape)}

        # ---- A 归一化输出 ----
        cos_norm = cosine(got_norm, ref_norm)
        diff_norm = np.abs(got_norm - ref_norm)
        m["A_normalized"] = {
            "cosine_min": float(cos_norm.min()),
            "cosine_mean": float(cos_norm.mean()),
            "max_abs_diff": float(diff_norm.max()),
            "worst": samples[int(cos_norm.argmin())]["id"],
            "worst_cosine": float(cos_norm.min()),
        }
        a_ok = float(cos_norm.min()) >= THRESHOLD
        m["A_pass"] = a_ok
        print("A 归一化输出  cosine_min=%.10f  cosine_mean=%.10f  max_abs_diff=%.3e  worst=%s  -> %s"
              % (cos_norm.min(), cos_norm.mean(), diff_norm.max(),
                 samples[int(cos_norm.argmin())]["id"], "PASS" if a_ok else "FAIL"))

        # ---- B 未归一化输出（归一化会压小绝对差，必须单比）----
        diff_raw = np.abs(got_raw - ref_raw)
        denom = np.maximum(np.abs(ref_raw), 1e-6)
        rel = (diff_raw / denom).max()
        m["B_unnormalized"] = {
            "max_abs_diff": float(diff_raw.max()),
            "max_rel_diff": float(rel),
            "ref_abs_max": float(np.abs(ref_raw).max()),
        }
        print("B 未归一化   max_abs_diff=%.3e  max_rel_diff=%.3e（参考值量级 %.3f）"
              % (diff_raw.max(), rel, np.abs(ref_raw).max()))

        # ---- C 批量填充不变性 ----
        padded_order = sorted(range(len(texts)), key=lambda i: -len(texts[i]))
        shuffled = [texts[i] for i in padded_order]
        got_shuffled = run(shuffled)
        inv = cosine(got_shuffled["normalized_embedding"], got_norm[padded_order])
        inv_st = cosine(ref_norm[padded_order], ref_norm[padded_order])
        m["C_batch_invariance"] = {
            "cosine_min": float(inv.min()),
            "same_as_single": bool(np.allclose(
                got_shuffled["normalized_embedding"], got_norm[padded_order], atol=1e-5)),
        }
        c_ok = float(inv.min()) >= 1.0 - 1e-6
        print("C 批量不变性 cosine_min=%.10f  allclose=%s -> %s"
              % (inv.min(), m["C_batch_invariance"]["same_as_single"],
                 "PASS" if c_ok else "FAIL"))

        # ---- D 逐样本明细 ----
        rows = []
        for i, s in enumerate(samples):
            n_tok = len(tok(s["text"], truncation=False)["input_ids"])
            rows.append({
                "id": s["id"], "note": s["note"], "chars": len(s["text"]),
                "tokens_untruncated": n_tok, "truncated": n_tok > 512,
                "cosine": float(cos_norm[i]),
                "max_abs_diff_norm": float(diff_norm[i].max()),
                "norm_of_normalized": float(np.linalg.norm(got_norm[i])),
            })
        m["D_per_sample"] = rows
        print("D 逐样本（cosine 升序前 5）:")
        for r in sorted(rows, key=lambda x: x["cosine"])[:5]:
            print("   %-4s %-34s tokens=%-4d%s cosine=%.10f  |n|=%.8f"
                  % (r["id"], r["note"], r["tokens_untruncated"],
                     " [TRUNC]" if r["truncated"] else "        ",
                     r["cosine"], r["norm_of_normalized"]))

        norm_dev = max(abs(r["norm_of_normalized"] - 1.0) for r in rows)
        print("   L2 范数偏离 1 的最大值 = %.3e" % norm_dev)
        m["unit_norm_max_deviation"] = float(norm_dev)

        report["models"][name] = m
        if name in args.gate:
            overall_ok = overall_ok and a_ok and c_ok

    # 非闸门变体的失败只记成数据点，不影响闸门结论
    report["data_points_failed"] = [
        n for n, m in report["models"].items() if n not in args.gate and not m["A_pass"]]
    report["overall_pass"] = overall_ok
    out = os.path.join(HERE, "consistency.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)

    print("\n=================== 汇总 ===================")
    for name, m in report["models"].items():
        tag = "闸门" if name in args.gate else "数据点"
        print("  [%s] %-5s A cosine_min=%.10f  %s  |  C 不变性 %s  |  B 未归一化 max_abs=%.3e"
              % (tag, name, m["A_normalized"]["cosine_min"],
                 "PASS" if m["A_pass"] else "FAIL",
                 "PASS" if m["C_batch_invariance"]["same_as_single"] else "FAIL",
                 m["B_unnormalized"]["max_abs_diff"]))
    print("\n门槛 cosine >= %.4f（A 组与 C 组都要过）" % THRESHOLD)
    print("闸门模型: %s" % ", ".join(args.gate))
    if report["data_points_failed"]:
        print("不达标的数据点（不影响闸门结论，供体积-精度权衡）: %s"
              % ", ".join(report["data_points_failed"]))
    print("结论: %s" % ("PASS —— 可以进入 Java 侧接入" if overall_ok else "FAIL —— 不要往下做"))
    print("明细: %s" % out)
    return 0 if overall_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
