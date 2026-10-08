#!/usr/bin/env python3
"""
校验 GAIA 本地数据是否完整可用。

为什么要单独有这个：评测结果不可信通常不是模型的问题，而是数据的问题 ——
附件没下全、parquet 与附件版本错配、题目引用了本地不存在的文件。
这些都会表现为"模型答错了"，排查成本很高。跑一遍这个脚本可以在跑评测之前
把这些环境问题挡掉。

用法：
    C:\\Users\\abc\\miniconda3\\envs\\bert_train\\python.exe scripts/gaia/verify-gaia.py
    ... verify-gaia.py --split validation --require-answers
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

DEFAULT_DIR = Path("scripts/agent-eval/gaia")


def main() -> int:
    ap = argparse.ArgumentParser(description="校验 GAIA 本地数据完整性")
    ap.add_argument("--dir", default=str(DEFAULT_DIR), help="数据目录")
    ap.add_argument("--split", default="validation", help="分片名")
    ap.add_argument("--require-answers", action="store_true",
                    help="要求该分片必须有公开答案（test 分片没有，加了会失败）")
    ap.add_argument("--allow-missing-attachments", action="store_true",
                    help="允许附件缺失（用 --metadata-only 只取题面时是预期的，"
                         "否则会把『本来就是这么下的』报成数据损坏）")
    args = ap.parse_args()

    base = Path(args.dir).resolve()
    split = args.split
    jsonl = base / f"gaia-{split}.jsonl"
    cases = base / f"cases-gaia-{split}.json"
    problems: list[str] = []

    if not jsonl.exists():
        print(f"[FAIL] 缺少 {jsonl}（先跑 fetch-gaia.py）", file=sys.stderr)
        return 2

    records = [json.loads(line) for line in jsonl.read_text(encoding="utf-8").splitlines() if line.strip()]
    print(f"=== gaia-{split}: {len(records)} 条 ===")

    levels = Counter(r["level"] for r in records)
    print(f"level 分布: {dict(sorted(levels.items()))}")

    # 1) 附件必须真实存在
    with_att = [r for r in records if r["file_name"]]
    missing = [r for r in with_att if not Path(r["file_path"]).exists()]
    print(f"附件: {len(with_att)} 条引用，缺失 {len(missing)} 条"
          + ("（已按 --allow-missing-attachments 放行）" if args.allow_missing_attachments and missing else ""))
    if missing and not args.allow_missing_attachments:
        for r in missing[:10]:
            problems.append(f"附件缺失: {r['file_name']} (task {r['task_id'][:8]})")

    # 2) 附件不应该为空文件（下载中断会留下 0 字节）
    empty = [Path(r["file_path"]) for r in with_att
             if Path(r["file_path"]).exists() and Path(r["file_path"]).stat().st_size == 0]
    if empty:
        print(f"空文件: {len(empty)} 个")
        for p in empty[:10]:
            problems.append(f"附件是 0 字节（下载不完整）: {p.name}")
    print(f"扩展名: {dict(Counter(Path(r['file_name']).suffix.lower() for r in with_att))}")

    # 3) 题面不能为空、task_id 不能重复
    blank = [r for r in records if not r["question"].strip()]
    dupes = [k for k, v in Counter(r["task_id"] for r in records).items() if v > 1]
    if blank:
        problems.append(f"{len(blank)} 条题面为空")
    if dupes:
        problems.append(f"task_id 重复: {dupes[:5]}")

    # 4) 答案（可选强制）
    answered = [r for r in records if r["final_answer"]]
    placeholders = [r for r in records if r.get("answer_was_placeholder")]
    print(f"有公开答案: {len(answered)}/{len(records)}"
          + (f"（另有 {len(placeholders)} 条是占位符，已按无答案处理）" if placeholders else ""))

    # 4b) 假答案检测：非空但唯一值极少 = 占位符漏网，会让"人人满分"
    non_null = [r["final_answer"] for r in records if r["final_answer"]]
    if non_null and len(set(non_null)) == 1 and len(non_null) > 5:
        problems.append(
            f"{len(non_null)} 条答案全是同一个值 {non_null[0]!r} —— 极可能是未识别的占位符，"
            "用它做 response_contains 会产出『人人满分』的假评测")
    if placeholders and args.require_answers:
        problems.append(f"{len(placeholders)} 条答案仍是占位符")

    if args.require_answers and len(answered) != len(records):
        problems.append(f"{len(records) - len(answered)} 条没有公开答案（GAIA test 分片本就如此）")

    # 5) 本地用例文件与 jsonl 是否一致
    if cases.exists():
        eval_cases = json.loads(cases.read_text(encoding="utf-8"))
        print(f"本地用例: {len(eval_cases)} 条 ({cases.name})")
        bad = [c for c in eval_cases
               if not c.get("checks") or c["checks"][0].get("type") != "response_contains"]
        if bad:
            problems.append(f"{len(bad)} 条用例缺少 response_contains 断言")
    else:
        print(f"本地用例: 未生成（{cases.name} 不存在）")

    total_bytes = sum(f.stat().st_size for f in (base / "data").rglob("*") if f.is_file())
    print(f"数据体积: {total_bytes / 1024 / 1024:.1f} MB")

    if problems:
        print(f"\n[FAIL] 发现 {len(problems)} 个问题：")
        for p in problems:
            print("  - " + p)
        return 1
    if missing and args.allow_missing_attachments:
        print(f"\n[OK] 题面与答案完整（附件未下载：{len(missing)} 条，按 --allow-missing-attachments 放行）")
    else:
        print("\n[OK] 数据完整：附件齐全、题面非空、task_id 唯一")
    return 0


if __name__ == "__main__":
    sys.exit(main())
