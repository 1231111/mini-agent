#!/usr/bin/env python3
"""
一键跑 GAIA 并判分（HTTP 方式打真实的 agent 服务）。

为什么是 HTTP 而不是内置的 EvalRunner：
  - `EvalRunner` 是进程内直调，需要先起一个完整的 Spring 上下文（DB/Redis/模型 key 全就位），
    而且它用的是 `EVAL_USER_ID = 0L` —— 库里没有 id=0 的用户，每条用例都会以
    AUTH_SESSION_INVALID 失败（见审计报告 P1-18）。HTTP 方式则只要服务在跑就行，
    测的也正是真实链路（鉴权 → 会话 → SSE → 工具执行）。
  - 这里用的是和浏览器完全相同的三个接口：
      POST /api/tokens                                   → 拿 JWT
      POST /api/conversations                            → 建会话（服务端签发 id）
      POST /api/conversations/{sid}/messages/stream      → 发问，收 SSE

判分：实现了一份**近似 GAIA 官方**的归一化准匹配（小写、去冠词、去标点、千分位、
末尾句点、数字尾零）。官方 scorer 仍是对外报数的唯一权威；这里用于本地回归。

用法（三种模式）：
  1) 自测：不连 agent、不需要模型 key，验证脚本自身（登录/SSE 解析/判分/报告）都对
       python scripts/gaia/run-gaia.py --self-test
  2) 快速冒烟：连本机服务，每级 3 题
       python scripts/gaia/run-gaia.py --base-url http://127.0.0.1:8080 -u admin -p '***' --limit 3
  3) 全量 validation：
       python scripts/gaia/run-gaia.py --base-url http://127.0.0.1:8080 -u admin -p '***' --all
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import threading
import time
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

DEFAULT_CASES = Path("scripts/agent-eval/gaia/cases-gaia-validation.json")
DEFAULT_REPORT = Path("scripts/agent-eval/gaia/report")


# ─────────────────────────── 判分 ───────────────────────────

def normalize(text: str) -> str:
    """近似 GAIA 官方归一化。保守为主：只做有把握的等价变换，避免把错答案判成对。"""
    if text is None:
        return ""
    s = str(text).strip().lower()
    s = s.replace("\u2212", "-").replace("\u2013", "-").replace("\u2014", "-")
    s = s.replace("\u00a0", " ")
    s = re.sub(r"(?<=\d),(?=\d{3}(\D|$))", "", s)          # 千分位 1,000 → 1000
    s = re.sub(r"\b(\d+\.\d*?)0+\b", r"\1", s)              # 26.40 → 26.4
    s = re.sub(r"\b(\d+)\.0*\b", r"\1", s)                  # 42.0 / 42. → 42
    s = re.sub(r"\b(a|an|the)\b", " ", s)                   # 冠词
    s = re.sub(r"[^\w\s.%$/\-]", " ", s)                    # 标点（保留 . % $ / -）
    s = re.sub(r"[$€£¥]", " ", s)                            # 货币符号：不区分语义
    s = re.sub(r"\s+", " ", s).strip()
    s = s.strip(".")                                        # 末尾句点
    return s


# 判分自检表：(期望, 实际, 是否应判等, 是否应子串命中)
#
# 这张表第一次跑就纠了我三处想当然（都是**表**写错、不是实现错），留着当记录：
#   1) 'egalitarian' vs 'The answer is egalitarian.' → 我原以为该判等；
#      实际归一化只处理格式，不剥"the answer is"这类叙述框 —— 剥了就等于猜意图，
#      会连带把"The answer is not egalitarian"判成对。故 exact=False、contains=True。
#   2) '3' vs 'There are 13 apples' → 我原以为不该子串命中，其实 "3" 就在 "13" 里。
#      这正是子串判分的假阳性来源，也说明**通过标准必须是 exact**，contains 只能当诊断。
#   3) '41%' vs '41' → 方向反了：归一化保留 %，"41%" 不是 "41" 的子串。
SCORE_CASES = [
    ("41", "41", True, True),
    ("Fred", "fred", True, True),
    ("The Eiffel Tower.", "eiffel tower", True, True),      # 冠词 + 末尾句点
    ("1,000", "1000", True, True),                          # 千分位
    ("26.4", "26.40", True, True),                          # 小数尾零
    ("42.0", "42", True, True),
    ("$3.2 billion", "3.2 billion", True, True),            # 货币符号
    # 叙述框不剥：保守优先（宁可漏判等，不可把否定句判成对）
    ("egalitarian", "The answer is egalitarian.", False, True),
    ("egalitarian", "The answer is not egalitarian", False, True),
    # 子串命中的假阳性：通过标准用 exact，contains 仅作诊断
    ("41", "The year was 1941, not 41", False, True),
    ("3", "There are 13 apples", False, True),
    ("26.4", "26.5", False, False),
    ("41%", "41", False, False),                            # 单位不同：保守判错
    ("Claude Shannon", "Claude E. Shannon", False, False),  # 中间名差异：保守判错
]


def score_self_test() -> int:
    """验证判分逻辑本身。判分错了，跑分再顺也没意义。"""
    bad = 0
    print("=== 判分自检（近似 GAIA 归一化准匹配）===")
    for expected, actual, want_exact, want_contains in SCORE_CASES:
        s = score_one(expected, actual)
        mark = "ok " if (s["exact"] == want_exact and s["contains"] == want_contains) else "BAD"
        if mark == "BAD":
            bad += 1
        print(f"  [{mark}] {expected!r} vs {actual!r} → exact={s['exact']} contains={s['contains']}"
              f"（期望 exact={want_exact} contains={want_contains}）"
              + (f"\n        归一化后: {s['normalized_expected']!r} vs {s['normalized_actual']!r}"
                 if mark == "BAD" else ""))
    if bad:
        print(f"\n[FAIL] {bad}/{len(SCORE_CASES)} 条判分自检不符预期 —— 先修判分，再看分数")
        return 1
    print(f"\n[OK] {len(SCORE_CASES)} 条判分自检全部符合预期")
    return 0


def score_one(expected: str, actual: str) -> dict:
    """返回 {exact, contains, normalized_expected, normalized_actual}。pass 以 exact 为准。"""
    ne, na = normalize(expected), normalize(actual)
    exact = bool(ne) and ne == na
    # 子串命中只作为诊断信息：短答案（如 "41"、"Fred"）很容易被子串误判成对
    contains = bool(ne) and ne in na
    return {
        "exact": exact,
        "contains": contains,
        "normalized_expected": ne,
        "normalized_actual": na,
    }


# ─────────────────────── SSE 客户端 ───────────────────────

def iter_sse(lines):
    """把 SSE 行流解析成 (event, data)。支持多行 data 与心跳注释。"""
    name, data_lines = None, []
    for raw in lines:
        if raw is None:
            continue
        if raw == "":
            if name or data_lines:
                yield name, "\n".join(data_lines)
            name, data_lines = None, []
            continue
        if raw.startswith(":"):          # 心跳/注释
            continue
        field, _, value = raw.partition(":")
        if value.startswith(" "):
            value = value[1:]
        if field == "event":
            name = value
        elif field == "data":
            data_lines.append(value)
    if name or data_lines:
        yield name, "\n".join(data_lines)


class AgentClient:
    """与浏览器同一条链路的极简客户端。"""

    def __init__(self, base_url: str, timeout: int = 60):
        import requests  # 延迟导入：--self-test 也用得到，但先让报错信息更清楚
        self.requests = requests
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.token = None

    def login(self, username: str, password: str) -> None:
        r = self.requests.post(f"{self.base}/api/tokens",
                               json={"username": username, "password": password},
                               timeout=self.timeout)
        r.raise_for_status()
        body = r.json()
        if not body.get("success"):
            raise RuntimeError(f"登录失败: {body.get('code')} {body.get('message')}")
        self.token = (body.get("data") or {}).get("token")
        if not self.token:
            raise RuntimeError(f"登录响应里没有 token: {body}")

    def _headers(self):
        return {"Authorization": f"Bearer {self.token}"}

    def new_session(self) -> str:
        r = self.requests.post(f"{self.base}/api/conversations",
                               headers=self._headers(), timeout=self.timeout)
        r.raise_for_status()
        body = r.json()
        sid = (body.get("data") or {}).get("sessionId")
        if not sid:
            raise RuntimeError(f"建会话失败: {body}")
        return sid

    def permission_view(self, session_id: str) -> dict:
        """读取服务端生效的权限/执行策略（跑前预检用）。"""
        r = self.requests.get(f"{self.base}/api/conversations/{session_id}/permission",
                              headers=self._headers(), timeout=self.timeout)
        r.raise_for_status()
        return (r.json().get("data") or {})

    def ask(self, session_id: str, message: str, answer_timeout: int) -> dict:
        """发问并收流。返回 {answer, error, events, elapsed}。"""
        started = time.time()
        payload = {"message": message, "permissionMode": "accept_edits",
                   "confirmPolicy": "auto"}
        answer_parts: list[str] = []
        final = None
        error = None
        events: dict[str, int] = defaultdict(int)
        url = f"{self.base}/api/conversations/{session_id}/messages/stream"
        with self.requests.post(url, json=payload, headers=self._headers(),
                                stream=True, timeout=(self.timeout, answer_timeout)) as r:
            if r.status_code == 429:
                raise RateLimited("服务端限流（HTTP 429）。生产配置默认 30 请求/分钟，"
                                  "而每题要 3 个请求 —— 用 --delay 放慢，或调大限流阈值")
            if r.status_code != 200:
                raise RuntimeError(f"发问被拒 HTTP {r.status_code}: {r.text[:200]}")
            for name, data in iter_sse(r.iter_lines(decode_unicode=True)):
                events[name or "(none)"] += 1
                if name == "token":
                    answer_parts.append(data)
                elif name == "end":
                    final = data
                    break
                elif name == "error":
                    error = data
                    break
        answer = final if final is not None else "".join(answer_parts)
        return {"answer": answer or "", "error": error, "events": dict(events),
                "elapsed": round(time.time() - started, 1)}


class RateLimited(RuntimeError):
    """服务端限流。单独一个类型，便于跑批时退避重试而不是直接判错。"""


# ─────────────────── 自测用的桩服务 ───────────────────

class _StubHandler(BaseHTTPRequestHandler):
    cases: list[dict] = []
    correct_levels: set[int] = {1}
    log_lines: list[str] = []

    def log_message(self, *args):  # 静音
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        path = urlparse(self.path).path
        _StubHandler.log_lines.append(f"{path} {raw[:80]}")

        if path == "/api/tokens":
            return self._json({"success": True, "data": {"userId": 1, "username": "stub",
                                                         "token": "stub-token"}})
        if path == "/api/conversations":
            return self._json({"success": True, "data": {"sessionId": "stub-session"}})
        if path.endswith("/messages/stream"):
            try:
                msg = json.loads(raw).get("message", "")
            except Exception:
                msg = ""
            # 按题面找到这道题，根据级别决定答对还是答错（用来同时验证判分正负路径）
            hit = next((c for c in _StubHandler.cases if c["prompt"] == msg), None)
            if hit is None:
                answer = "unknown"
            elif hit["_gaia"]["level"] in _StubHandler.correct_levels:
                answer = hit["_gaia"]["official_answer"]
            else:
                answer = "I could not determine the answer"
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream;charset=UTF-8")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            for chunk in (f"event:token\ndata:{answer}\n\n",
                          f"event:end\ndata:{answer}\n\n"):
                self.wfile.write(chunk.encode("utf-8"))
                self.wfile.flush()
            return
        return self._json({"success": False, "message": "not found"}, 404)

    def do_GET(self):
        # 权限预检：桩服务返回 allow（自测也要走一遍这条分支，否则它永远没被测过）
        if urlparse(self.path).path.endswith("/permission"):
            return self._json({"success": True, "data": {
                "execPolicyGlobal": "allow", "execPolicyEffective": "allow",
                "execPolicyFollowsGlobal": True}})
        return self._json({"success": False, "message": "not found"}, 404)


def start_stub(cases: list[dict], correct_levels: set[int], port: int = 0):
    _StubHandler.cases = cases
    _StubHandler.correct_levels = correct_levels
    server = ThreadingHTTPServer(("127.0.0.1", port), _StubHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}"


# ─────────────────────────── 主流程 ───────────────────────────

def select_cases(cases: list[dict], levels: set[int], limit: int, only: list[str]) -> list[dict]:
    picked = []
    per_level: dict[int, int] = defaultdict(int)
    for c in cases:
        lvl = c["_gaia"]["level"]
        if levels and lvl not in levels:
            continue
        if only and not any(o in c["id"] or o in c["_gaia"]["task_id"] for o in only):
            continue
        if limit and per_level[lvl] >= limit:
            continue
        per_level[lvl] += 1
        picked.append(c)
    return picked


def run(cases: list[dict], client: AgentClient, args) -> dict:
    results = []
    for i, c in enumerate(cases, 1):
        gold = c["_gaia"]["official_answer"]
        lvl = c["_gaia"]["level"]
        if i > 1 and args.delay > 0:
            time.sleep(args.delay)
        print(f"[{i}/{len(cases)}] L{lvl} {c['id']} … ", end="", flush=True)
        entry = {"id": c["id"], "task_id": c["_gaia"]["task_id"], "level": lvl,
                 "expected": gold, "session": None, "answer": "", "error": None,
                 "elapsed": 0, "pass": False}
        for attempt in range(1, args.retry_on_429 + 2):
            try:
                sid = client.new_session()
                entry["session"] = sid
                out = client.ask(sid, c["prompt"], args.timeout)
                entry.update(answer=out["answer"], error=out["error"],
                             elapsed=out["elapsed"], events=out["events"])
                s = score_one(gold, out["answer"])
                entry["score"] = s
                entry["pass"] = s["exact"]
                flag = "PASS" if s["exact"] else ("SUBSTR" if s["contains"] else "FAIL")
                print(f"{flag} ({out['elapsed']}s) 期望={gold!r} 实际={out['answer'][:60]!r}")
                break
            except RateLimited as e:
                # 限流是可恢复的：退避重试，不要把"服务端限流"记成"模型答错"
                if attempt <= args.retry_on_429:
                    wait = args.delay if args.delay > 0 else 20 * attempt
                    print(f"[限流] {e}；{wait}s 后重试（第 {attempt} 次）")
                    time.sleep(wait)
                    continue
                entry["error"] = f"限流且重试耗尽: {e}"
                print(f"RATE-LIMITED {e}")
                break
            except Exception as e:  # noqa: BLE001 - 单题失败不该中断整轮
                entry["error"] = str(e)
                print(f"ERROR {e}")
                break
        results.append(entry)

    by_level: dict[str, dict[str, int]] = {}
    for lvl in sorted({r["level"] for r in results}):
        rows = [r for r in results if r["level"] == lvl]
        by_level[str(lvl)] = {
            "total": len(rows),
            "pass": sum(1 for r in rows if r["pass"]),
            "substring_only": sum(1 for r in rows
                                  if not r["pass"] and (r.get("score") or {}).get("contains")),
            "error": sum(1 for r in rows if r["error"]),
        }
    total = len(results)
    passed = sum(1 for r in results if r["pass"])
    return {
        "summary": {
            "total": total, "pass": passed,
            "pass_rate": round(passed / total, 4) if total else 0.0,
            "substring_only": sum(1 for r in results
                                  if not r["pass"] and (r.get("score") or {}).get("contains")),
            "errors": sum(1 for r in results if r["error"]),
            "by_level": by_level,
        },
        "config": {"base_url": args.base_url, "limit": args.limit,
                   "levels": sorted(args.level or []), "timeout": args.timeout,
                   "self_test": bool(args.self_test)},
        "scoring_note": "近似 GAIA 官方归一化准匹配；官方 scorer 仍是报数权威。"
                        "substring_only 表示只有子串命中、不算通过。",
        "results": results,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="一键跑 GAIA 并判分")
    ap.add_argument("--cases", default=str(DEFAULT_CASES), help="用例文件（cases-gaia-*.json）")
    ap.add_argument("--out", default=str(DEFAULT_REPORT), help="报告输出前缀（不加扩展名）")
    ap.add_argument("--base-url", default="http://127.0.0.1:8080", help="agent 服务地址")
    ap.add_argument("-u", "--user", default="", help="登录用户名")
    ap.add_argument("-p", "--password", default="", help="登录密码")
    ap.add_argument("--limit", type=int, default=3, help="每个级别取几题（0=不限）")
    ap.add_argument("--all", action="store_true", help="跑全部用例（等价 --limit 0）")
    ap.add_argument("--level", type=int, action="append", help="只跑指定级别，可重复：--level 1 --level 3")
    ap.add_argument("--only", action="append", help="只跑 id/task_id 含该子串的题，可重复")
    ap.add_argument("--timeout", type=int, default=300, help="单题作答超时（秒）")
    ap.add_argument("--delay", type=float, default=0.0,
                    help="每题之间的间隔秒数。每题要 3 个请求，而生产限流默认 30 请求/分钟 —— "
                         "跑全量（165 题）必须放慢，否则会撞 429")
    ap.add_argument("--retry-on-429", type=int, default=2,
                    help="遇到 429 的退避重试次数（默认 2；限流不等于答错，不该记成失败）")
    ap.add_argument("--require-exec-allow", action="store_true",
                    help="跑前预检：服务端执行策略不是 allow 就直接失败（无人值守时"
                         "审批没人点，需要跑命令的题必然失败，而失败会伪装成'模型答错'）")
    ap.add_argument("--min-pass-rate", type=float, default=0.0,
                    help="通过率低于该值时退出码非 0（CI 门禁用）")
    ap.add_argument("--self-test", action="store_true",
                    help="不连 agent：起本地桩服务验证脚本自身（登录/SSE/判分/报告）")
    ap.add_argument("--score-test", action="store_true",
                    help="只跑判分自检表（不连服务、不需要用例文件）")
    ap.add_argument("--stub-correct-levels", default="1",
                    help="自测时桩服务答对的级别（默认只答对 L1，用来同时验证正负路径）")
    args = ap.parse_args()

    if args.score_test:
        return score_self_test()

    try:
        import requests  # noqa: F401
    except ImportError:
        print("[FAIL] 缺少 requests。用带依赖的解释器，例如：\n"
              r"  C:\Users\abc\miniconda3\envs\bert_train\python.exe scripts/gaia/run-gaia.py ...",
              file=sys.stderr)
        return 2

    cases_path = Path(args.cases)
    if not cases_path.exists():
        print(f"[FAIL] 找不到用例文件 {cases_path}；先跑 fetch-gaia.py 生成", file=sys.stderr)
        return 2
    cases = json.loads(cases_path.read_text(encoding="utf-8"))
    if not cases:
        print(f"[FAIL] 用例文件为空: {cases_path}", file=sys.stderr)
        return 2

    if args.all:
        args.limit = 0
    picked = select_cases(cases, set(args.level or []), args.limit, args.only or [])
    if not picked:
        print("[FAIL] 没有选中任何用例（检查 --level/--limit/--only）", file=sys.stderr)
        return 2

    stub = None
    try:
        if args.self_test:
            levels = {int(x) for x in args.stub_correct_levels.split(",") if x.strip()}
            stub, url = start_stub(cases, levels, port=0)
            args.base_url = url
            args.user, args.password = "stub", "stub"
            print(f"[自测] 桩服务 {url}；预期答对的级别={sorted(levels)}，"
                  f"其余应全部判 FAIL —— 两者都对才说明脚本可信")
        elif not (args.user and args.password):
            print("[FAIL] 需要 -u/--user 与 -p/--password（或用 --self-test）", file=sys.stderr)
            return 2

        client = AgentClient(args.base_url, timeout=60)
        client.login(args.user, args.password)
        print(f"已登录 {args.base_url}；共 {len(picked)} 题"
              f"（级别 {sorted({c['_gaia']['level'] for c in picked})}）\n")

        # 跑前预检：无人值守跑批时，若执行策略是 ask/block，需要跑命令的题会因"没人点审批"而失败，
        # 结果看起来像"模型答错"。这个坑必须在跑之前说清楚，而不是跑完拿一堆 0 分去分析模型。
        # 自测模式也走这条分支（桩服务返回 allow）—— 否则这段代码永远没被验过。
        try:
            view = client.permission_view(client.new_session())
            effective = view.get("execPolicyEffective")
            print(f"服务端执行策略: {effective}（全局 {view.get('execPolicyGlobal')}）")
            if effective and effective != "allow":
                msg = (f"执行策略为 {effective}：需要 exec_command 的题会因无法自动获批而失败。"
                       "请把服务端 agent.tools.exec-policy 设为 allow（评测环境），"
                       "或在结果里把这些题单独归因，不要算作模型能力问题。")
                if args.require_exec_allow:
                    print(f"[FAIL] {msg}")
                    return 1
                print(f"[警告] {msg}\n")
        except Exception as e:  # noqa: BLE001 - 预检失败不该阻断跑批
            print(f"[警告] 权限预检未完成（{e}），继续跑\n")

        report = run(picked, client, args)
        report["cases_file"] = str(cases_path)
        report["started_at"] = time.strftime("%Y-%m-%d %H:%M:%S")

        out_prefix = Path(args.out)
        out_prefix.parent.mkdir(parents=True, exist_ok=True)
        json_path = out_prefix.with_suffix(".json")
        json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

        s = report["summary"]
        print(f"\n=== 结果 ===  通过 {s['pass']}/{s['total']}"
              f"（{s['pass_rate']*100:.1f}%）  仅子串命中 {s['substring_only']}  错误 {s['errors']}")
        for lvl, v in s["by_level"].items():
            print(f"  L{lvl}: {v['pass']}/{v['total']}"
                  + (f"（其中仅子串命中 {v['substring_only']}）" if v["substring_only"] else "")
                  + (f"（错误 {v['error']}）" if v["error"] else ""))
        print(f"报告: {json_path}")

        if args.self_test:
            expect_pass = sum(1 for c in picked if c["_gaia"]["level"] in
                              {int(x) for x in args.stub_correct_levels.split(",") if x.strip()})
            ok = (s["pass"] == expect_pass and s["errors"] == 0
                  and s["pass"] + s["substring_only"] + (s["total"] - s["pass"] - s["substring_only"]) == s["total"])
            print(f"[自测] 预期 {expect_pass} 题通过，实际 {s['pass']} 题"
                  + ("  -> [OK] 脚本自身可信" if ok else "  -> [FAIL] 脚本有问题，别拿它的分数当结论"))
            return 0 if ok else 1

        if s["pass_rate"] < args.min_pass_rate:
            print(f"[FAIL] 通过率 {s['pass_rate']*100:.1f}% 低于门槛 {args.min_pass_rate*100:.1f}%")
            return 1
        return 0
    finally:
        if stub is not None:
            stub.shutdown()


if __name__ == "__main__":
    sys.exit(main())
