#!/usr/bin/env python3
"""
把 GAIA 各分片的 jsonl 合并成**一个 JSON 文件**（题目 + 答案 + 附件引用）。

为什么还要这一步：
  - `gaia-<split>.jsonl` 是"一个分片一个文件"的规范格式，适合喂 scorer 与流式处理；
  - 但做人工审阅、导入别的工具、或只是想"一个文件看全 466 道题"时，
    一个带元信息的 JSON 数组更方便。

产物结构（自描述，拿到文件的人不需要再看别的文档）：
    {
      "dataset": "gaia-benchmark/GAIA",
      "exported_at": "2026-10-08",
      "counts": {"total": 466, "by_split": {...}, "by_level": {...},
                 "with_attachment": ..., "with_public_answer": ...},
      "notes": ["...口径提醒..."],
      "questions": [
        {"id", "task_id", "split", "level", "question", "answer",
         "has_public_answer", "attachment": {...}, "file_path", "runnable_locally"},
        ...
      ]
    }

用法：
    C:\\Users\\abc\\miniconda3\\envs\\bert_train\\python.exe scripts/gaia/export-json.py
    ... export-json.py --out C:\\Users\\abc\\Desktop\\gaia-testcases\\gaia-all.json
    ... export-json.py --splits validation          # 只要 validation
    ... export-json.py --absolute-paths             # 附件路径写成绝对路径（自包含副本用）
"""

from __future__ import annotations

import argparse
import datetime
import json
import sys
from collections import Counter
from pathlib import Path

DEFAULT_DIR = Path("scripts/agent-eval/gaia")

NOTES = [
    "GAIA 是 gated 数据集：来源 https://huggingface.co/datasets/gaia-benchmark/GAIA，再分发前请确认许可。",
    "test 分片**没有公开答案**（其 parquet 的 'Final answer' 列全是占位符 '?'）。"
    "本文件里 test 题目的 answer 一律为 null、has_public_answer 为 false —— "
    "不要把它当成'答案是问号'。",
    "validation 分片答案公开。官方判分是归一化后的准匹配（去冠词/标点/大小写、数字与单位归一），"
    "用子串匹配（response_contains）会偏乐观，尤其对 <=3 字符的短答案。",
    "part 附件路径是**相对该 JSON 文件自身所在目录**的（形如 data/2023/...）；"
    "用 --absolute-paths 可导出绝对路径版本（副本目录中附件仍需位于 <json目录>/data/2023 下）。",
]


def load(path: Path) -> list[dict]:
    if not path.exists():
        return []
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            out.append(json.loads(line))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="把 GAIA 分片合并成一个 JSON")
    ap.add_argument("--dir", default=str(DEFAULT_DIR), help="含 gaia-<split>.jsonl 的目录")
    ap.add_argument("--out", default="", help="输出文件（默认 <dir>/gaia-all.json）")
    ap.add_argument("--splits", default="validation,test",
                    help="要合并的分片，逗号分隔（不存在的会被跳过并提示）")
    ap.add_argument("--absolute-paths", action="store_true",
                    help="附件路径写成绝对路径（用于做成脱离仓库也能读的自包含副本）")
    ap.add_argument("--indent", type=int, default=2, help="缩进（0=紧凑单行，体积更小）")
    args = ap.parse_args()

    base = Path(args.dir).resolve()
    out_path = Path(args.out).resolve() if args.out else base / "gaia-all.json"
    wanted = [s.strip() for s in args.splits.split(",") if s.strip()]

    # 附件路径的基准是**输出文件所在目录**（不是 jsonl 所在目录）。
    # 踩过一次：写桌面副本时用 --dir 的仓库路径去拼绝对路径，结果桌面那份 JSON 里
    # 的路径全指向仓库，"自包含"名不副实；相对路径也少了一层（写成 ../data/...）。
    # 现在两种模式都以 out_path.parent 为基准，并用真实文件系统判定 exists。
    attach_root = out_path.parent
    data_root = attach_root / "data" / "2023"

    questions: list[dict] = []
    by_split: Counter = Counter()
    skipped: list[str] = []
    seen_ids: dict[str, int] = {}
    for split in wanted:
        records = load(base / f"gaia-{split}.jsonl")
        if not records:
            skipped.append(split)
            continue
        for r in records:
            answer = r.get("final_answer")
            has_answer = bool(r.get("has_public_answer", answer is not None))
            file_name = r.get("file_name") or ""

            # 相对路径：相对 JSON 自身所在目录，形如 data/2023/<split>/<file>
            rel = f"data/2023/{split}/{file_name}" if file_name else ""
            if file_name:
                absolute = (data_root / split / file_name).resolve()
                file_path = str(absolute) if args.absolute_paths else rel
                exists = absolute.exists()
            else:
                file_path, exists = "", False

            # id 必须唯一且可读：GAIA 的 test 分片里有个非 UUID 的 task_id（'0-0-0-0-0'，
            # 就是那道"训练一个能解 GAIA 的助手"的占位题），截断后会撞车，所以加去重后缀。
            suffix = r["task_id"] if len(r["task_id"]) <= 12 else r["task_id"][:8]
            candidate = f"gaia-{split}-{suffix}"
            if candidate in seen_ids:
                seen_ids[candidate] += 1
                candidate = f"{candidate}-{seen_ids[candidate]}"
            else:
                seen_ids[candidate] = 1

            questions.append({
                "id": candidate,
                "task_id": r["task_id"],
                "split": split,
                "level": r["level"],
                "question": r["question"],
                "answer": answer if has_answer else None,
                "has_public_answer": has_answer,
                "attachment": ({"file_name": file_name,
                                "path": file_path,
                                "exists": exists} if file_name else None),
                "file_path": file_path,
                "runnable_locally": (not file_name) or exists,
            })
            by_split[split] += 1

    if not questions:
        print(f"[FAIL] 在 {base} 没找到任何 gaia-<split>.jsonl，先跑 fetch-gaia.py", file=sys.stderr)
        return 2

    counts = {
        "total": len(questions),
        "by_split": dict(sorted(by_split.items())),
        "by_level": {str(k): v for k, v in sorted(Counter(q["level"] for q in questions).items())},
        "with_attachment": sum(1 for q in questions if q["attachment"]),
        "with_public_answer": sum(1 for q in questions if q["has_public_answer"]),
        "runnable_locally": sum(1 for q in questions if q["runnable_locally"]),
    }
    payload = {
        "dataset": "gaia-benchmark/GAIA",
        "source_url": "https://huggingface.co/datasets/gaia-benchmark/GAIA",
        "exported_at": datetime.date.today().isoformat(),
        "counts": counts,
        "notes": NOTES,
        "questions": questions,
    }

    out_path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, ensure_ascii=False,
                      indent=args.indent if args.indent > 0 else None)
    out_path.write_text(text, encoding="utf-8")

    print(f"→ {out_path}")
    print(f"  题目总数 {counts['total']}  by_split={counts['by_split']}  by_level={counts['by_level']}")
    print(f"  带附件 {counts['with_attachment']}  有公开答案 {counts['with_public_answer']}"
          f"  本地可跑 {counts['runnable_locally']}")
    print(f"  体积 {out_path.stat().st_size/1024:.1f} KB")
    if skipped:
        print(f"  [跳过] 没有 jsonl 的分片: {', '.join(skipped)}（先跑 fetch-gaia.py --splits {','.join(skipped)}）")

    # 自校验：把刚写的文件读回来验一遍。导出器不验自己，等于把"文件对不对"留给使用者去发现 ——
    # 上一版就是这样把"路径全指向仓库"的桌面副本交出去的。
    problems = self_check(out_path)
    if problems:
        print(f"\n[FAIL] 自校验发现 {len(problems)} 个问题：")
        for p in problems:
            print("  - " + p)
        return 1
    print("  [OK] 自校验通过：id 唯一、test 无答案、声称存在的附件都真实存在")
    return 0


def self_check(path: Path) -> list[str]:
    """读回产物做一致性检查。返回问题列表（空=通过）。"""
    problems: list[str] = []
    data = json.loads(path.read_text(encoding="utf-8"))
    questions = data.get("questions") or []
    if not questions:
        return ["questions 为空"]

    counts = data.get("counts") or {}
    if counts.get("total") != len(questions):
        problems.append(f"counts.total={counts.get('total')} 与实际条数 {len(questions)} 不一致")

    ids = [q["id"] for q in questions]
    dupes = {k for k in ids if ids.count(k) > 1}
    if dupes:
        problems.append(f"id 重复: {sorted(dupes)[:5]}")

    # test 分片绝不能带答案（它的 parquet 里是 '?' 占位符，混进来就是"人人满分"）
    bad = [q["id"] for q in questions
           if q["split"] == "test" and (q["answer"] is not None or q["has_public_answer"])]
    if bad:
        problems.append(f"test 分片有 {len(bad)} 条带答案（应为 0）: {bad[:3]}")

    checked = broken = 0
    for q in questions:
        att = q.get("attachment")
        if not att:
            continue
        if att.get("exists"):
            checked += 1
            p = Path(att["path"])
            if not p.is_absolute():
                p = (path.parent / att["path"]).resolve()
            if not p.exists():
                broken += 1
                if broken <= 5:
                    problems.append(f"附件声称存在但实际缺失: {att['path']}")
    if checked == 0 and any(q.get("attachment") for q in questions):
        problems.append("所有附件都标记为不存在 —— 若刚导出副本，请确认 data/ 已被复制到 JSON 同级目录")
    return problems


if __name__ == "__main__":
    sys.exit(main())
