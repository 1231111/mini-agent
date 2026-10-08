# -*- coding: utf-8 -*-
"""把 Yuan-embedding-2.0-zh 的整条管道导成 ONNX。

管道来自模型目录里的 modules.json（不是猜的）：
  idx 0  sentence_transformers.models.Transformer   BERT, last_hidden_state  (B, L, 1024)
  idx 1  sentence_transformers.models.Pooling       pooling_mode_mean_tokens=true,
                                                    include_prompt=true
  idx 2  sentence_transformers.models.Dense         Linear(1024 -> 1792) + bias, Identity
最后 encode(normalize_embeddings=True) 再 L2 归一化（util.normalize_embeddings =
F.normalize(p=2, dim=1)）。

导出两个输出：
  sentence_embedding          未归一化 (B, 1792)
  normalized_embedding        L2 归一化 (B, 1792)
两个都给是为了将来排查数值差异时不用重新导出（重新导出要加载 1.3G 权重 + 重新 trace）。

用法：
  python export_onnx.py                 # 只导 fp32（一致性闸门用这个）
  python export_onnx.py --variants      # 额外导 fp16 / int8 供体积-精度权衡
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import time

import numpy as np
import torch
import torch.nn as nn

MODEL_DIR = os.path.abspath(os.path.join(
    os.path.dirname(__file__), "..", "..",
    "embedding-server", "models", "models", "IEITYuan--Yuan-embedding-2.0-zh",
    "snapshots", "master"))
OUT_DIR = os.path.dirname(os.path.abspath(__file__))

MAX_SEQ_LENGTH = 512


class YuanPipeline(nn.Module):
    """BERT -> mean pooling -> Dense -> (可选) L2 归一化。严格复刻 sentence-transformers 的算子顺序。"""

    def __init__(self, bert: nn.Module, linear: nn.Linear, normalize: bool = True) -> None:
        super().__init__()
        self.bert = bert
        # 只取 Dense 内部的 nn.Linear，不要整个 Dense 模块：
        # Dense.forward(features: dict) 做的是 features.update({...})，喂 Tensor 会
        # AttributeError: 'Tensor' object has no attribute 'update'。
        # 又因为 activation_function 是 Identity，用 nn.Linear 与整个 Dense 完全等价。
        #
        # 权重在这里**转置存**（PyTorch 布局 [out,in] -> ONNX MatMul 布局 [in,out]），
        # 并且刻意用 torch.matmul + add 而不是 nn.Linear。原因：
        #   1) nn.Linear 会被导出成 Gemm(transB=1)，权重是 [1792,1024]。
        #      onnxruntime 的动态量化器在形状推断前会 replace_gemm_with_matmul()，
        #      它**丢掉 transB 语义**，于是推断出 [batch,1024] 与已标注的 [batch,1792] 冲突：
        #        [ShapeInferenceError] Inferred shape and existing shape differ in dimension 0
        #      int8 量化因此直接失败（fp32 推理不受影响，所以这个坑只在量化时才现形）。
        #   2) 转置后的 [1024,1792] 是 ONNX 规范布局，别的工具读这个模型也不用再猜。
        with torch.no_grad():
            self.weight = nn.Parameter(linear.weight.detach().t().contiguous())
            self.bias = nn.Parameter(linear.bias.detach().clone())
        self.normalize = normalize

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor):
        # 1) BERT。token_type_ids 不暴露：单序列全 0，BERT 不传就是 zeros。
        last_hidden = self.bert(
            input_ids=input_ids, attention_mask=attention_mask
        ).last_hidden_state                                   # (B, L, 1024)

        # 2) mean pooling —— 逐字复刻 Pooling.forward 里 pooling_mode_mean_tokens 分支
        #    （见 .venv 里 sentence_transformers/models/Pooling.py）。
        #    include_prompt=true 且无 WordWeights，所以不裁 prompt、也没有 token_weights_sum。
        mask = attention_mask.unsqueeze(-1).expand(last_hidden.size()).to(last_hidden.dtype)
        summed = torch.sum(last_hidden * mask, 1)
        counts = torch.clamp(mask.sum(1), min=1e-9)           # clamp 的 1e-9 必须保留
        pooled = summed / counts                              # (B, 1024)

        # 3) Dense: Identity(linear(x))  —— 显式 matmul，避免 Gemm(transB=1)
        emb = torch.matmul(pooled, self.weight) + self.bias    # (B, 1792)

        # 4) L2 归一化 = F.normalize(p=2, dim=1)
        normed = torch.nn.functional.normalize(emb, p=2, dim=1)
        return emb, normed


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def human(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "%.1f %s" % (n, unit) if unit != "B" else "%d B" % n
        n /= 1024.0
    return str(n)


def build_pipeline(sentence_model):
    """从已加载的 SentenceTransformer 里把 BERT 与 Dense 取出来，并校验配置与假设一致。"""
    from sentence_transformers.models import Transformer, Pooling, Dense

    transformer = sentence_model[0]
    pooling = sentence_model[1]
    dense_mod = sentence_model[2]
    assert isinstance(transformer, Transformer), type(transformer)
    assert isinstance(pooling, Pooling), type(pooling)
    assert isinstance(dense_mod, Dense), type(dense_mod)

    checks = {
        "pooling_mode_mean_tokens": bool(pooling.pooling_mode_mean_tokens),
        "pooling_mode_cls_token": bool(pooling.pooling_mode_cls_token),
        "pooling_mode_max_tokens": bool(pooling.pooling_mode_max_tokens),
        "pooling_mode_mean_sqrt_len_tokens": bool(pooling.pooling_mode_mean_sqrt_len_tokens),
        "pooling_mode_weightedmean_tokens": bool(pooling.pooling_mode_weightedmean_tokens),
        "pooling_mode_lasttoken": bool(pooling.pooling_mode_lasttoken),
        "include_prompt": bool(pooling.include_prompt),
    }
    # 只要这里不成立，说明管道不是"mean pooling"这一种，导出脚本必须重写而不是硬跑
    if not (checks["pooling_mode_mean_tokens"] and checks["include_prompt"]):
        raise SystemExit("假设不成立，池化配置 = %s" % checks)
    for k in ("pooling_mode_cls_token", "pooling_mode_max_tokens",
              "pooling_mode_mean_sqrt_len_tokens", "pooling_mode_weightedmean_tokens",
              "pooling_mode_lasttoken"):
        if checks[k]:
            raise SystemExit("存在多种池化叠加，脚本需扩展: %s" % checks)

    linear = dense_mod.linear
    assert isinstance(linear, nn.Linear), type(linear)
    d = {
        "in_features": linear.in_features,
        "out_features": linear.out_features,
        "bias": linear.bias is not None,
        "activation": type(dense_mod.activation_function).__name__,
        "max_seq_length": int(transformer.max_seq_length),
        "word_embedding_dimension": int(pooling.word_embedding_dimension),
        "bert_hidden_size": int(transformer.auto_model.config.hidden_size),
        "bert_layers": int(transformer.auto_model.config.num_hidden_layers),
        "bert_vocab_size": int(transformer.auto_model.config.vocab_size),
    }
    if d["activation"] != "Identity":
        raise SystemExit("Dense 激活不是 Identity，脚本需扩展: %s" % d["activation"])
    if d["in_features"] != d["word_embedding_dimension"]:
        raise SystemExit("Dense 输入维度与池化输出维度不一致: %s" % d)

    bert = transformer.auto_model
    bert.eval()
    for p in bert.parameters():
        p.requires_grad_(False)
    dense_mod.eval()
    for p in dense_mod.parameters():
        p.requires_grad_(False)

    return bert, linear, d, checks


SANITY_TEXTS = [
    "你好",
    "上下文窗口溢出时应当优先压缩最旧的对话。",
    "MiniAgent 的 ToolPipeline 会先过 BLOCK 闸门",
    "The quick brown fox jumps over the lazy dog.",
]


def sanity_check(pipe: nn.Module, st) -> float:
    """导出前先用 PyTorch 前向跑一遍，和 sentence-transformers 对一下。

    不这么做的话，包装写错（比如把整个 Dense 模块当成 nn.Linear 用）要等到
    ONNX 导出 trace 时才炸，而那时报的是 FakeTensor 的怪错，跟真实原因隔了三层。
    这一步花不到一秒，能把问题钉在写错的同一行。
    """
    tok = st.tokenizer
    enc = tok(SANITY_TEXTS, padding=True, truncation="longest_first",
              max_length=MAX_SEQ_LENGTH, return_tensors="pt")
    with torch.no_grad():
        _, normed = pipe(enc["input_ids"], enc["attention_mask"])
    ref = st.encode(SANITY_TEXTS, normalize_embeddings=True,
                    convert_to_numpy=True, show_progress_bar=False)
    got = normed.numpy()
    cos = np.sum(got * ref, axis=1) / (
        np.maximum(np.linalg.norm(got, axis=1), 1e-12) * np.maximum(np.linalg.norm(ref, axis=1), 1e-12))
    worst = float(cos.min())
    print("前向自检（PyTorch 包装 vs sentence-transformers）: cosine_min=%.12f" % worst)
    if worst < 0.999999:
        raise SystemExit("包装实现与参考不一致（cosine_min=%.8f），先修包装再导出" % worst)
    return worst


def actual_opset(path: str) -> dict:
    """读产物里真实的 opset，而不是照抄我请求的值。

    踩过：dynamo 导出器最低只实现到 opset 18，请求 17 会触发一次「自动降级转换」，
    那次转换是**失败**的（`Failed to convert the model to the target version 17`），
    最后模型保持 18。如果 manifest 写 17，就是一份对不上的记录。
    """
    import onnx
    m = onnx.load(path, load_external_data=False)
    return {imp.domain or "ai.onnx": imp.version for imp in m.opset_import}


def export_fp32(pipe: nn.Module, out_path: str) -> dict:
    ids = torch.tensor([[101, 872, 1962, 102]], dtype=torch.long)
    am = torch.tensor([[1, 1, 1, 1]], dtype=torch.long)
    dynamic = {"input_ids": {0: "batch", 1: "sequence"},
               "attention_mask": {0: "batch", 1: "sequence"},
               "sentence_embedding": {0: "batch"},
               "normalized_embedding": {0: "batch"}}

    # 18 是 dynamo 导出器的最低实现版本。给 17 只会多一次注定失败的降级转换尝试。
    attempts = []
    for kwargs in ({"dynamo": True}, {"dynamo": False}):
        t0 = time.time()
        try:
            torch.onnx.export(
                pipe, (ids, am), out_path,
                input_names=["input_ids", "attention_mask"],
                output_names=["sentence_embedding", "normalized_embedding"],
                dynamic_axes=dynamic, opset_version=18,
                do_constant_folding=True, export_params=True,
                **kwargs)
            return {"exporter": kwargs, "export_seconds": round(time.time() - t0, 1)}
        except Exception as exc:                                # noqa: BLE001
            import traceback
            tb = traceback.format_exc()
            attempts.append("==== %s -> %s: %s\n%s" % (kwargs, type(exc).__name__, exc, tb))
    raise SystemExit("两种导出器都失败:\n" + "\n".join(attempts))


def to_fp16(src: str, dst: str) -> None:
    """fp32 -> fp16。用 onnxruntime 自带的转换器，不额外装 onnxconverter_common。

    keep_io_types=True：输入输出仍是 fp32，这样 Java 侧不用为 fp16 变体改绑定代码。
    模型带 external data（1.3G 权重在同目录 .data 文件里），所以保存时也要 external。
    """
    import onnx
    from onnxruntime.transformers import float16

    m = onnx.load(src, load_external_data=True)
    m16 = float16.convert_float_to_float16(m, keep_io_types=True)
    data_name = os.path.basename(dst) + ".data"
    onnx.save_model(m16, dst, save_as_external_data=True,
                    all_tensors_to_one_file=True, location=data_name,
                    size_threshold=1024)
    print("       fp16 external data -> %s (%s)"
          % (data_name, human(os.path.getsize(os.path.join(os.path.dirname(dst), data_name)))))


def to_int8(src: str, dst: str) -> None:
    """动态 int8 量化（权重 int8、激活运行时算 scale）。只作体积-精度数据点。"""
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(src, dst, weight_type=QuantType.QInt8)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--variants", action="store_true", help="额外导 fp16 / int8")
    args = ap.parse_args()

    from sentence_transformers import SentenceTransformer

    print("模型目录: %s" % MODEL_DIR)
    t0 = time.time()
    st = SentenceTransformer(MODEL_DIR, device="cpu", local_files_only=True)
    print("加载 sentence-transformers 完成 %.1fs, dim=%d"
          % (time.time() - t0, st.get_sentence_embedding_dimension()))

    bert, linear, meta, checks = build_pipeline(st)
    pipe = YuanPipeline(bert, linear, normalize=True).eval()
    print("管道元信息: %s" % json.dumps(meta, ensure_ascii=False))
    print("池化配置:   %s" % json.dumps(checks, ensure_ascii=False))

    sanity_worst = sanity_check(pipe, st)

    fp32_path = os.path.join(OUT_DIR, "yuan-embedding-2.0-zh.onnx")
    info = export_fp32(pipe, fp32_path)
    print("导出完成: %s（%s, %.1fs）"
          % (fp32_path, info["exporter"], info["export_seconds"]))

    manifest = {
        "model_dir": MODEL_DIR,
        "pipeline": ["Transformer(BERT)", "Pooling(mean, include_prompt=true)",
                     "Dense(1024->1792, bias, Identity)", "L2 normalize"],
        "meta": meta,
        "checks": checks,
        "opset_requested": 18,
        "opset_actual_fp32": actual_opset(fp32_path),
        "exporter": {k: str(v) for k, v in info["exporter"].items()},
        "export_seconds": info["export_seconds"],
        "wrapper_sanity_cosine_min": sanity_worst,
        "inputs": {"input_ids": "int64[B,L]", "attention_mask": "int64[B,L]"},
        "outputs": {"sentence_embedding": "float32[B,1792]",
                    "normalized_embedding": "float32[B,1792]"},
        "files": {},
    }

    variants = {"fp32": fp32_path}
    if args.variants:
        for name, fn in (("fp16", to_fp16), ("int8", to_int8)):
            dst = os.path.join(OUT_DIR, "yuan-embedding-2.0-zh.%s.onnx" % name)
            t1 = time.time()
            try:
                fn(fp32_path, dst)
                variants[name] = dst
                partners = [p for p in (dst + ".data",) if os.path.exists(p)]
                total = os.path.getsize(dst) + sum(os.path.getsize(p) for p in partners)
                print("导 %s 完成 %.1fs -> %s (%s)"
                      % (name, time.time() - t1, os.path.basename(dst), human(total)))
            except Exception as exc:                            # noqa: BLE001
                import traceback
                print("导 %s 失败: %s: %s\n%s"
                      % (name, type(exc).__name__, exc, traceback.format_exc()))

    base = 0
    for name, path in variants.items():
        # external data 必须算进去：只看 .onnx 主文件会得到「2.6MB」这种假数字。
        companions = [p for p in
                      (path + ".data", os.path.splitext(path)[0] + ".onnx.data",
                       os.path.splitext(path)[0] + ".data")
                      if os.path.exists(p) and p != path]
        companions = sorted(set(companions))
        size = os.path.getsize(path) + sum(os.path.getsize(p) for p in companions)
        if name == "fp32":
            base = size
        manifest["files"][name] = {
            "path": os.path.basename(path),
            "companions": [os.path.basename(p) for p in companions],
            "main_bytes": os.path.getsize(path),
            "bytes": size,
            "human": human(size),
            "ratio_of_fp32": round(size / base, 4) if base else None,
            "sha256": sha256(path),
        }
        if companions:
            manifest["files"][name]["companion_bytes"] = {
                os.path.basename(p): os.path.getsize(p) for p in companions}

    mp = os.path.join(OUT_DIR, "manifest.json")
    with open(mp, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
    print("\n=== 产物 ===")
    for name, d in manifest["files"].items():
        print("  %-5s %-12s %s" % (name, d["human"], d["path"]))
    print("manifest: %s" % mp)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
