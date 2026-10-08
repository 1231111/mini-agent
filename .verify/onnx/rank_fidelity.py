# -*- coding: utf-8 -*-
"""
int8 量化对「召回排序」的影响实测。

背景：consistency.json 只给了逐条 cosine（两个向量像不像）。
检索系统真正在乎的是 **排序** —— 交给 LLM 的是按相似度排出来的前 k 条。
cosine 0.98 的向量，排序未必稳；这是 int8 唯一需要实测而非推断的点。

口径：
  gold = probe-golden.json 的 ref_norm（sentence-transformers 产出，归一化）
  三个模型 = fp32 / fp16 / int8 的 normalized_embedding

三组实验：
  L1 样本互查    query = 样本原文，库 = 20 条（测"同义/自反"场景）
  L2 改写检索    query = 手写改写，库 = 20 条（测真实场景：query ≠ doc）
  L3 分布位移    全部 190 个文档对的 cosine 差值分布
"""
import json
import os
import time

import numpy as np
import onnxruntime as ort

HERE = os.path.dirname(os.path.abspath(__file__))
GOLD = os.path.join(HERE, "probe-golden.json")
MODELS = {
    "fp32": "yuan-embedding-2.0-zh.onnx",
    "fp16": "yuan-embedding-2.0-zh.fp16.onnx",
    "int8": "yuan-embedding-2.0-zh.int8.onnx",
}
THREADS = 8
DEGEN = {"s17", "s18"}  # 空串 / 单个空格：检索里不会出现的 query

# L2 改写 query —— 每条都是「人话提问」，期望命中括号里那条文档
REWRITES = [
    ("内存不够用了怎么办", "s02"),
    ("上下文窗口太长，应该先压缩哪部分对话", "s03"),
    ("工具调用会被闸门拦住吗", "s05"),
    ("那只敏捷的棕色狐狸跳过了懒狗", "s06"),
    ("记忆模块怎么把事件变成可检索的条目", "s04"),
    ("修改会话权限要调哪个接口", "s20"),
    ("向量是几维的，序列最长多少 token", "s11"),
    ("exec 命令在什么情况下被判定为阻止", "s14"),
    ("执行完了，接下来要修 bug", "s13"),
    ("hello world miniagent", "s07"),
]


def load_sessions():
    so = ort.SessionOptions()
    so.intra_op_num_threads = THREADS
    so.inter_op_num_threads = 1
    sess, load_s = {}, {}
    for name, fn in MODELS.items():
        p = os.path.join(HERE, fn)
        t0 = time.time()
        sess[name] = ort.InferenceSession(p, sess_options=so, providers=["CPUExecutionProvider"])
        load_s[name] = time.time() - t0
    return sess, load_s


def embed(sess, ids, mask):
    out = sess.run(["normalized_embedding"], {"input_ids": ids, "attention_mask": mask})[0]
    return out.astype(np.float64)


def cos_rows(A, B):
    """A:[n,d] B:[m,d] -> [n,m] cosine（输入已 L2 归一化，但仍做一次保险除法）。"""
    A = A / np.linalg.norm(A, axis=1, keepdims=True)
    B = B / np.linalg.norm(B, axis=1, keepdims=True)
    return A @ B.T


def kendall_tau(a, b):
    """无并列时的 (C-D)/T，范围 [-1,1]。"""
    n = len(a)
    iu = np.triu_indices(n, 1)
    sa = np.sign(a[iu[0]] - a[iu[1]])
    sb = np.sign(b[iu[0]] - b[iu[1]])
    return float(np.mean(sa * sb))


def tokenize_queries(texts, tok_path):
    from tokenizers import Tokenizer
    tok = Tokenizer.from_file(tok_path)
    encs = tok.encode_batch(list(texts))
    L = max(len(e.ids) for e in encs)
    ids, mask = [], []
    for e in encs:
        pad = L - len(e.ids)
        ids.append(e.ids + [0] * pad)
        mask.append(e.attention_mask + [0] * pad)
    return np.array(ids, dtype=np.int64), np.array(mask, dtype=np.int64)


def main():
    gold = json.load(open(GOLD, encoding="utf-8"))
    S = gold["samples"]
    ids = np.array([s["ids_batch"] for s in S], dtype=np.int64)
    mask = np.array([s["mask_batch"] for s in S], dtype=np.int64)
    ref = np.array([s["ref_norm"] for s in S], dtype=np.float64)
    N = len(S)
    keep = [i for i, s in enumerate(S) if s["id"] not in DEGEN]

    print(f"样本 {N} 条，batch 宽 {ids.shape[1]}，维度 {ref.shape[1]}")
    print("载入模型：")
    sess, load_s = load_sessions()
    for k, v in load_s.items():
        print(f"  {k:5s} {v:5.2f}s")

    # 库向量（用固定的 ids/mask 跑一次）
    lib = {n: embed(s, ids, mask) for n, s in sess.items()}
    gold_lib = ref

    # ---------------- A 逐条 cosine（复核 consistency.json）----------------
    print("\n=================== A 逐条 cosine ===================")
    for n in MODELS:
        c = np.sum(lib[n] * gold_lib, axis=1)
        ck = c[keep]
        w = keep[int(np.argmin(ck))]
        print(f"  {n:5s} 全 20 条 min={c.min():.10f} | "
              f"剔退化后 min={ck.min():.10f} worst={S[w]['id']}")
    print("  （consistency.json 的口径阈值 0.9999 是「ONNX 导出 vs PyTorch 等价性」闸门，"
          "不是检索质量阈值）")

    # ---------------- B 排序保真（样本互查）----------------
    print("\n=================== B 样本互查排序保真 ===================")
    print(f"  {'模型':6s} {'top1':>7s} {'top3':>7s} {'top5':>7s} {'top10':>7s} {'全序τ':>8s}")
    print("  " + "-" * 46)
    ranks = {}
    for n in MODELS:
        sc = cos_rows(lib[n], lib[n])
        np.fill_diagonal(sc, -np.inf)
        gs = cos_rows(gold_lib, gold_lib)
        np.fill_diagonal(gs, -np.inf)
        rg, rm = np.argsort(-gs, 1), np.argsort(-sc, 1)
        t1 = float(np.mean(rg[:, 0] == rm[:, 0]))
        tks = {k: float(np.mean([len(set(rg[i, :k]) & set(rm[i, :k])) / k for i in range(N)]))
               for k in (3, 5, 10)}
        taus = []
        for i in range(N):
            idx = [j for j in range(N) if j != i]
            taus.append(kendall_tau(gs[i][idx], sc[i][idx]))
        ranks[n] = (rg, rm)
        print(f"  {n:6s} {t1*100:6.1f}% {tks[3]*100:6.1f}% {tks[5]*100:6.1f}% "
              f"{tks[10]*100:6.1f}% {np.mean(taus)*100:7.1f}%")

    # 换位案例
    print("\n  --- 换位案例（gold top-1 被挤到第几）---")
    for n in MODELS:
        rg, rm = ranks[n]
        mv = []
        for i in range(N):
            want = rg[i, 0]
            got = int(np.where(rm[i] == want)[0][0])
            if got != 0:
                mv.append((S[i]["id"], S[i]["note"], S[want]["id"], S[got]["id"], got + 1))
        if not mv:
            print(f"    {n:5s} 无换位 ✓")
        else:
            print(f"    {n:5s} {len(mv)} 处：")
            for q, qn, w, g, r in mv:
                print(f"          q={q}({qn}) 期望{w} 实际{g} 期望项掉到第{r}")

    # ---------------- L2 改写检索 ----------------
    print("\n=================== L2 改写检索（query ≠ doc，最贴近线上）===================")
    qtexts = [q for q, _ in REWRITES]
    qwant = [w for _, w in REWRITES]
    qids, qmask = tokenize_queries(qtexts, gold["tokenizer_json"])
    print(f"  {len(qtexts)} 条改写 query，库 {N} 条文档")
    print(f"  {'模型':6s} {'top1命中':>9s} {'top3命中':>9s} {'MRR':>8s}   命中明细")
    print("  " + "-" * 78)
    for n in MODELS:
        qv = embed(sess[n], qids, qmask)
        gl = cos_rows(gold_lib, gold_lib)  # 库自身（用于取 gold 排序）
        sc = cos_rows(qv, lib[n])
        gsc = cos_rows(qv, gold_lib)
        grank = np.argsort(-gsc, 1)
        rank = np.argsort(-sc, 1)
        want_idx = [next(i for i, s in enumerate(S) if s["id"] == w) for w in qwant]
        hit1 = hit3 = 0
        rr = 0.0
        marks = []
        for r_i, wi in enumerate(want_idx):
            pos = int(np.where(rank[r_i] == wi)[0][0]) + 1
            if pos == 1:
                hit1 += 1
            if pos <= 3:
                hit3 += 1
            rr += 1.0 / pos
            marks.append(f"{qtexts[r_i][:8]}→{pos}")
        # 与 gold 的 top-1 是否一致（更能说明"排序有没有被改变"）
        same_gold_top1 = sum(1 for r_i in range(len(qtexts))
                            if rank[r_i, 0] == grank[r_i, 0])
        print(f"  {n:6s} {hit1:>6d}/{len(qtexts):<3d} {hit3:>6d}/{len(qtexts):<3d} "
              f"{rr/len(qtexts):7.3f}   与gold top1 一致 {same_gold_top1}/{len(qtexts)}")
        print(f"         排名明细: {' '.join(marks)}")

    # ---------------- C 分布位移 ----------------
    print("\n=================== C 相似度分布位移（190 个文档对）===================")
    iu = np.triu_indices(N, 1)
    g = (gold_lib @ gold_lib.T)[iu]
    for n in MODELS:
        d = (lib[n] @ lib[n].T)[iu] - g
        print(f"  {n:5s} Δcosine min={d.min():+.6f} mean={d.mean():+.6f} max={d.max():+.6f} "
              f"|Δ|>0.01 的对={int((np.abs(d) > 0.01).sum())}/190")

    json.dump({
        "note": "int8 召回保真实测",
        "models": list(MODELS),
        "degen_excluded": sorted(DEGEN),
        "rewrites": REWRITES,
    }, open(os.path.join(HERE, "rank-fidelity.json"), "w", encoding="utf-8"),
        ensure_ascii=False, indent=2)
    print(f"\n明细: {os.path.join(HERE, 'rank-fidelity.json')}")


if __name__ == "__main__":
    main()
