#!/usr/bin/env python3
"""
下载 GAIA 基准（gaia-benchmark/GAIA）并转换成可跑/可评分的本地用例。

为什么需要脚本而不是一句 wget：
  1. GAIA 是 **gated** 数据集（`gated=auto`）—— 匿名请求一律 401，必须带 HF token，
     且该账号要先在数据集页面点过「Agree and access repository」；
  2. 题目数据在 parquet 里（`2023/<split>/metadata.parquet`），而题目引用的附件
     （PDF/xlsx/png/mp3/MOV/pdb…）是仓库里的独立文件，靠 `file_name` 关联 ——
     只下 parquet 会得到一堆"附件缺失"的题；
  3. 仓库里的评测运行器（`EvalRunner` / `run-prod-eval.ps1`）吃的是自己的 cases.json 格式，
     需要一次转换才能直接用。

用法（需要 pandas + pyarrow + huggingface_hub；本机可用 conda 环境 bert_train）：
    C:\\Users\\abc\\miniconda3\\envs\\bert_train\\python.exe scripts/gaia/fetch-gaia.py --check
    set HF_TOKEN=hf_xxx
    C:\\Users\\abc\\miniconda3\\envs\\bert_train\\python.exe scripts/gaia/fetch-gaia.py
    # 连 test 分片（95MB 附件，且 test 没有公开答案）一起下：
    ... fetch-gaia.py --splits validation,test

产物（默认在 scripts/agent-eval/gaia/）：
    data/2023/validation/...            原始 parquet + 附件（不进版本控制）
    gaia-validation.jsonl               规范格式：task_id/question/level/final_answer/file_name
    gaia-validation-summary.json        每级题量、附件覆盖率
    cases-gaia-validation.json          仓库评测格式（response_contains 代理判定，见下）
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from collections import Counter
from pathlib import Path

REPO_ID = "gaia-benchmark/GAIA"
REPO_TYPE = "dataset"
DATASET_URL = f"https://huggingface.co/datasets/{REPO_ID}"
DEFAULT_OUT = Path("scripts/agent-eval/gaia")

# 仓库评测器支持的断言只有有限几种（见 EvalChecker.KNOWN）。
# GAIA 官方判定是"归一化后准匹配"，本地没有那个 scorer，所以这里用 response_contains 做**代理**：
# 能跑通链路、能发现明显退化，但**不等于**官方分数。规范化后的 official 分数请用 --emit-jsonl 的产物接官方脚本。
PROXY_MATCH_NOTE = (
    "代理判定：回复包含标准答案（非官方归一化准匹配）。"
    "GAIA 官方 scorer 需要另跑，本文件只用于本地冒烟/回归。"
)

# GAIA 的 test 分片**不公开答案**，但 parquet 里保留了 "Final answer" 列，
# 全部 301 行都是占位符 "?"。如果把它当成真答案：
#   1) 汇总会谎报"有公开答案 301"；
#   2) 生成的用例断言变成 response_contains("?") —— 任何含问号的回复都算通过，
#      也就是一个"人人满分"的假评测。
# 这比缺数据危险得多，所以这里显式识别占位符，并把它归为"没有公开答案"。
PLACEHOLDER_ANSWERS = {
    "", "?", "??", "???", "n/a", "na", "none", "null", "nan", "tbd", "-", "--", "unknown", "待定",
}


def normalize_answer(raw) -> str | None:
    """把占位符/空值统一成 None（= 没有公开答案）。"""
    if raw is None:
        return None
    text = str(raw).strip()
    if text.lower() in PLACEHOLDER_ANSWERS:
        return None
    return text


def fail(msg: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"\n[错误] {msg}", file=sys.stderr)
    sys.exit(2)


def resolve_token(args) -> str | None:
    return (
        args.token
        or os.environ.get("HF_TOKEN")
        or os.environ.get("HUGGING_FACE_HUB_TOKEN")
        or None
    )


def explain_gated(err: Exception) -> str:
    text = str(err)
    if "401" in text or "403" in text or "gated" in text.lower() or "authenticated" in text.lower():
        return (
            "GAIA 是 gated 数据集，当前凭据不够。请按顺序确认：\n"
            f"  1) 用浏览器登录 HuggingFace，打开 {DATASET_URL}\n"
            "     点「Agree and access repository」接受条款（gated=auto，接受后立即生效）；\n"
            "  2) 生成一个 read 权限的 token：https://huggingface.co/settings/tokens\n"
            "  3) 设好环境变量后重跑：set HF_TOKEN=hf_xxx（或 --token hf_xxx）\n"
            "注意：token 属于凭据，不要写进仓库文件或提交。"
        )
    return f"下载失败：{err}"


def require_deps():
    missing = []
    for mod in ("pandas", "pyarrow", "huggingface_hub"):
        try:
            __import__(mod)
        except ImportError:
            missing.append(mod)
    if missing:
        fail(
            "缺少依赖: " + ", ".join(missing) + "\n"
            "推荐直接用带这些包的解释器（本机已装）：\n"
            r"  C:\Users\abc\miniconda3\envs\bert_train\python.exe scripts/gaia/fetch-gaia.py ..."
        )


def download(repo_id: str, out_dir: Path, patterns: list[str], token: str) -> Path:
    from huggingface_hub import snapshot_download
    from huggingface_hub.utils import GatedRepoError, HfHubHTTPError, RepositoryNotFoundError

    print(f"→ 下载 {repo_id}（pattern: {', '.join(patterns)}）")
    try:
        path = snapshot_download(
            repo_id=repo_id,
            repo_type=REPO_TYPE,
            token=token,
            allow_patterns=patterns,
            local_dir=str(out_dir),
            max_workers=4,
        )
    except (GatedRepoError, RepositoryNotFoundError) as e:
        fail(explain_gated(e))
    except HfHubHTTPError as e:
        fail(explain_gated(e))
    return Path(path)


def load_split(root: Path, split: str):
    import pandas as pd

    parquet = root / "2023" / split / "metadata.parquet"
    if not parquet.exists():
        # level 分片版本也可能存在；优先用全量 metadata
        cands = sorted((root / "2023" / split).glob("metadata*.parquet"))
        if not cands:
            fail(f"没找到 {split} 的 parquet：{parquet}")
        parquet = cands[0]
    print(f"→ 解析 {parquet.relative_to(root)}")
    return pd.read_parquet(parquet), parquet


def to_records(df, split: str, root: Path):
    """规范记录：字段名保留 GAIA 原样（Question/Final answer 带空格），另给 snake_case 别名。"""
    records = []
    placeholder_seen = False
    for _, row in df.iterrows():
        question = row.get("Question")
        raw_answer = row.get("Final answer", None)
        if raw_answer is not None and str(raw_answer).strip().lower() in PLACEHOLDER_ANSWERS \
                and str(raw_answer).strip() != "":
            placeholder_seen = True
        answer = normalize_answer(raw_answer)
        file_name = row.get("file_name", "") or ""
        file_name = "" if str(file_name).lower() == "nan" else str(file_name)
        attachment = root / "2023" / split / file_name if file_name else None
        records.append(
            {
                "task_id": str(row.get("task_id", "")),
                "question": str(question) if question is not None else "",
                "level": int(row.get("Level", 0) or 0),
                "final_answer": answer,
                "has_public_answer": answer is not None,
                "answer_was_placeholder": answer is None
                and raw_answer is not None and str(raw_answer).strip() != "",
                "file_name": file_name,
                "file_path": str(attachment) if attachment else "",
                "has_attachment": bool(attachment and attachment.exists()),
                "split": split,
                # 附件缺失是本地跑不动的题，单独标出来，避免把环境问题算成模型问题
                "runnable_locally": (not file_name) or bool(attachment and attachment.exists()),
            }
        )
    if placeholder_seen:
        print(f"  [注意] {split} 的 'Final answer' 列含占位符（GAIA test 分片不公开答案），"
              f"已按「无公开答案」处理")
    return records


def write_jsonl(path: Path, records) -> None:
    with path.open("w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def repo_relative(path: Path) -> str:
    """尽量给出仓库相对路径：用例文件要可移植，绝对路径换台机器就失效。
    同时它落在项目根内，agent 的 read_file 能直接按相对路径解析（PathGuard 允许项目根）。"""
    try:
        return path.resolve().relative_to(Path.cwd().resolve()).as_posix()
    except ValueError:
        return str(path)


def to_eval_cases(records, max_per_level: int | None):
    """转成仓库评测格式。只有带答案的分片能生成。"""
    cases = []
    per_level = Counter()
    for r in records:
        if not r["final_answer"]:
            continue  # test 分片没有公开答案，无法判定
        level = r["level"] or 0
        if max_per_level and per_level[level] >= max_per_level:
            continue
        per_level[level] += 1
        prompt = r["question"]
        if r["file_name"]:
            if r["has_attachment"]:
                prompt += f"\n\n[附件] {r['file_name']}（本地路径：{repo_relative(Path(r['file_path']))}）"
            else:
                prompt += f"\n\n[附件缺失] {r['file_name']} —— 本地未下载到该文件"
        cases.append(
            {
                "id": f"gaia_{r['split']}_L{level}_{r['task_id'][:8] or len(cases)}",
                "category": "gaia",
                "prompt": prompt,
                "timeoutMs": 300000,
                "checks": [
                    {"type": "response_contains", "value": r["final_answer"]},
                    {"type": "no_error"},
                ],
                "_gaia": {
                    "task_id": r["task_id"],
                    "level": level,
                    "official_answer": r["final_answer"],
                    "note": PROXY_MATCH_NOTE,
                },
            }
        )
    return cases


def main() -> int:
    ap = argparse.ArgumentParser(description="下载 GAIA 基准并转成本地用例")
    ap.add_argument("--token", help="HF token（也可用环境变量 HF_TOKEN）")
    ap.add_argument("--out", default=str(DEFAULT_OUT), help=f"输出目录（默认 {DEFAULT_OUT}）")
    ap.add_argument("--splits", default="validation",
                    help="要下载的分片，逗号分隔：validation / test（test 无公开答案，约 95MB）")
    ap.add_argument("--max-per-level", type=int, default=0,
                    help="每个 level 最多生成多少条评测用例（0=不限制）")
    ap.add_argument("--check", action="store_true", help="只校验凭据与连通性，不下载")
    ap.add_argument("--metadata-only", action="store_true",
                    help="只取题面/答案（parquet，几百 KB），不下载附件。"
                         "用于 test 分片：它没有公开答案、附件又有 95MB，"
                         "但题面本身对了解难度分布与做链路演示有用")
    ap.add_argument("--keep-data", action="store_true",
                    help="保留 parquet 与附件（默认保留；该开关仅用于显式声明意图）")
    args = ap.parse_args()

    require_deps()
    split_list = [s.strip() for s in args.splits.split(",") if s.strip()]
    for s in split_list:
        if s not in ("validation", "test"):
            fail(f"未知分片: {s}（只支持 validation / test）")

    out_dir = Path(args.out).resolve()
    token = resolve_token(args)

    print(f"仓库    : {REPO_ID}")
    print(f"输出    : {out_dir}")
    print(f"分片    : {', '.join(split_list)}")
    print(f"凭据    : {'已提供' if token else '未提供（gated 数据集会 401）'}")

    if args.check:
        if not token:
            fail("--check 需要 token：" + explain_gated(RuntimeError("401")))
        try:
            from huggingface_hub import HfApi
            info = HfApi().dataset_info(REPO_ID, token=token)
            print(f"\n[OK] 凭据可用：gated={info.gated} private={info.private} "
                  f"files={len(info.siblings or [])}")
            return 0
        except Exception as e:  # noqa: BLE001 - 需要把远端错误翻译成人话
            fail(explain_gated(e))

    if not token:
        fail("缺少 token。" + explain_gated(RuntimeError("401")))

    patterns = ["2023/*/metadata*.parquet"]
    if not args.metadata_only:
        for s in split_list:
            # parquet + 该分片的所有附件（保留目录结构，file_path 才能对上）
            patterns.append(f"2023/{s}/*")

    root = download(REPO_ID, out_dir / "data", patterns, token)

    summary = {"repo": REPO_ID, "splits": {}, "proxy_note": PROXY_MATCH_NOTE}
    for split in split_list:
        df, parquet = load_split(root, split)
        records = to_records(df, split, root)
        if not records:
            print(f"[警告] {split} 没有记录，跳过")
            continue

        jsonl = out_dir / f"gaia-{split}.jsonl"
        write_jsonl(jsonl, records)

        levels = Counter(r["level"] for r in records)
        # 三个数必须分开统计：只有"有 file_name 但文件没下下来"才叫缺失。
        # 早先把 with_attachment - runnable_locally 当缺失数，而 runnable 里还包含
        # "本来就没有附件"的纯检索题，于是算出负数（-127）—— 一个把两类题混在一起的错误。
        with_attachment = sum(1 for r in records if r["file_name"])
        attachment_present = sum(1 for r in records if r["file_name"] and r["has_attachment"])
        attachment_missing = with_attachment - attachment_present
        without_attachment = len(records) - with_attachment
        runnable = sum(1 for r in records if r["runnable_locally"])
        answered = sum(1 for r in records if r["final_answer"])
        split_summary = {
            "rows": len(records),
            "per_level": {str(k): v for k, v in sorted(levels.items())},
            "with_attachment": with_attachment,
            "attachment_present": attachment_present,
            "attachment_missing": attachment_missing,
            "without_attachment": without_attachment,
            "runnable_locally": runnable,
            "with_public_answer": answered,
            "placeholder_answers": sum(1 for r in records if r.get("answer_was_placeholder")),
            "parquet": str(parquet.relative_to(root)),
            "jsonl": jsonl.name,
        }

        if answered:
            cases = to_eval_cases(records, args.max_per_level or None)
            cases_path = out_dir / f"cases-gaia-{split}.json"
            cases_path.write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
            split_summary["eval_cases"] = len(cases)
            split_summary["cases_file"] = cases_path.name
        else:
            split_summary["eval_cases"] = 0
            split_summary["note"] = ("该分片没有公开答案（GAIA 的 test 答案只对官方评测开放，"
                                     "parquet 里是 '?' 占位符），只能用于跑通链路，不能本地判分")
            # 关键：把历史运行可能留下的用例文件删掉。
            # 占位符曾被当成真答案，生成过 response_contains("?") 的"人人满分"用例；
            # 留着它会让人以为 test 可以本地判分，比没有文件更糟。
            stale = out_dir / f"cases-gaia-{split}.json"
            if stale.exists():
                stale.unlink()
                print(f"  [清理] 删除了不含真答案的用例文件 {stale.name}")

        summary["splits"][split] = split_summary
        print(f"\n[{split}] {len(records)} 题  level分布={dict(sorted(levels.items()))}")
        print(f"  有附件 {with_attachment} 题（已下载 {attachment_present}，缺失 {attachment_missing}）"
              f"，纯文本题 {without_attachment} 题，可直接跑 {runnable} 题，有公开答案 {answered} 题")

    (out_dir / "gaia-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n✓ 完成。汇总：{out_dir / 'gaia-summary.json'}")
    print("  规范数据：gaia-validation.jsonl（可直接喂官方 scorer）")
    print("  本地用例：cases-gaia-validation.json（代理判定，非官方分数）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
