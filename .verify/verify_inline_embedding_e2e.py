# -*- coding: utf-8 -*-
"""端到端验证：内联 ONNX embedding 在真实 Spring 上下文里能出向量、能做语义召回。

为什么要跑这个：数值一致性（cosine 0.9999）只能证明「模型算得对」，
证明不了「接线接对了」。收口改动动了 VectorMemoryStore 的 embed 调用方式，
必须跑一次真实业务路径 —— 编译通过不代表运行期正确。

判据用「零关键词重叠的语义查询」：
  查询「服务突然整体卡顿，怀疑是后端资源不够了」
  目标条目「数据库连接池被占满之后，新的请求会一直在队列里等待，表现为整体响应变慢。」
  两者没有任何共同实词（响应/卡顿不算同一个词）。词法匹配不可能命中，
  只有向量召回能做到 —— 这才能证明 embedding 真的在算。

用法（先起好 desktop 档应用）：
  python verify_inline_embedding_e2e.py [base_url]
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18081"
USER = "onnxverify"
PWD = "Verify12345"

MEMORIES = [
    ("m-db", "数据库连接池被占满之后，新的请求会一直在队列里等待，表现为整体响应变慢。"),
    ("m-ui", "前端按钮的圆角在主色板里统一声明，换主题时会跟着一起变。"),
    ("m-k8s", "Kubernetes 的滚动更新会先起新 Pod 再摘掉旧的，期间流量会打到两批上。"),
]
QUERY = "服务突然整体卡顿，怀疑是后端资源不够了"
EXPECT_HIT = "m-db"


def call(method: str, path: str, body=None, token: str | None = None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=180) as r:
            raw = r.read().decode()
            try:
                return r.status, json.loads(raw)
            except json.JSONDecodeError:
                return r.status, raw[:400]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400]


def main() -> int:
    print("目标: %s" % BASE)

    # ---- 1. 注册（已存在则登录）----
    st, reg = call("POST", "/api/users",
                   {"username": USER, "password": PWD, "displayName": "ONNX Verify"})
    token = None
    if st == 200 and isinstance(reg, dict) and reg.get("code") == 0:
        token = reg["data"]["token"]
        print("[1] 注册成功 userId=%s" % reg["data"]["userId"])
    else:
        st, lg = call("POST", "/api/tokens", {"username": USER, "password": PWD})
        if st == 200 and isinstance(lg, dict) and lg.get("code") == 0:
            token = lg["data"]["token"]
            print("[1] 已存在，登录成功")
        else:
            print("[1] 注册与登录都失败: %s %s" % (st, reg))
            return 2

    # ---- 2. 写入记忆（会触发 VectorMemoryStore.reindex → 本地 embedding）----
    ids = {}
    for key, content in MEMORIES:
        st, resp = call("POST", "/v1/memory/memories",
                        {"content": content, "memoryType": "SEMANTIC",
                         "importance": 0.8, "confidence": 0.9}, token)
        ok = st == 200 and isinstance(resp, dict) and resp.get("code") == 0
        ids[key] = (resp.get("data") or {}).get("id") if ok else None
        print("[2] 写入 %-6s -> %s %s" % (key, "OK" if ok else "FAIL", "" if ok else str(resp)[:200]))

    ok_write = all(v is not None for v in ids.values())
    if not ok_write:
        print("    有记忆没写进去，后续召回结论不可信")

    # ---- 3. 语义召回 ----
    st, resp = call("POST", "/v1/memory/memories/search",
                    {"query": QUERY, "topK": 5, "minScore": 0.2}, token)
    if st != 200 or not isinstance(resp, dict) or resp.get("code") != 0:
        print("[3] 检索失败: %s %s" % (st, str(resp)[:300]))
        return 1

    hits = resp.get("data") or []
    print("[3] 查询: %s" % QUERY)
    print("    命中 %d 条:" % len(hits))
    order = []
    for h in hits:
        # ScoredMemory 的字段名按实际返回取，兼容几种可能
        content = h.get("content") or (h.get("memory") or {}).get("content") or ""
        score = h.get("score", h.get("similarity", h.get("finalScore")))
        mid = h.get("id") or (h.get("memory") or {}).get("id")
        for key, text in MEMORIES:
            if text in content:
                order.append(key)
        print("      score=%s  %s" % (score, content[:44].replace("\n", " ")))

    top = order[0] if order else None
    print("=" * 70)
    print("期望首条命中: %s   实际首条: %s" % (EXPECT_HIT, top))
    ok = bool(order) and top == EXPECT_HIT
    print("结论: %s" % ("PASS —— 内联 embedding 真实参与语义召回"
                      if ok else "FAIL —— 向量召回没生效或排序不对（查上面写入是否都成功）"))
    print("=" * 70)
    return 0 if ok else 1


if __name__ == "__main__":
    os.environ.setdefault("no_proxy", "127.0.0.1,localhost")
    os.environ.setdefault("NO_PROXY", "127.0.0.1,localhost")
    raise SystemExit(main())
