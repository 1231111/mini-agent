# -*- coding: utf-8 -*-
"""桌面档 exec_command 三档策略的端到端验证（HTTP 层）。

验的是「配置里写了 exec-policy，运行期到底能不能按会话改、改完是不是真的生效」。
背景：这个键此前是 `agent.tools.exec-enabled` 一个 boolean，启动时固定注入
ToolPipeline 构造器 —— 出厂要放开、用户又要能随时收紧，两者不可能同时满足。

本脚本覆盖（HTTP 层能验的部分）：
  P1  GET  /api/conversations/{sid}/permission
          -> execPolicyGlobal=allow / execPolicyEffective=allow
          -> execPolicyFollowsGlobal=true / execPolicyOverride=""（空串=跟随全局）
          -> execPolicyOptions 恰好三档 block/ask/allow
  P2  PUT  execPolicy=block  -> effective=block，followsGlobal=false
  P3  重新 GET              -> 仍是 block（拒绝/覆盖要落到会话态，不是只在响应里修饰）
  P4  PUT  execPolicy=default -> 回到跟随全局，effective=allow
  P5  PUT  execPolicy=乱七八糟 -> success=false 且 code=CONFIG.02.01，且档位未被改动
  P6  只传 execPolicy 的请求不得把会话模式重置成 default（touched 记账回归）
  P7  覆盖按会话隔离：新会话仍跟随全局

管线的真实拒绝行为（PERM_DENY / 不给批准入口 / 不被 ACCEPT_EDITS 绕过）
由 mini-agent-loop 的 ToolPipelineTest 在进程内确定性验证 —— 这里不重复造 LLM 调用。
两侧合起来才是完整证据：本脚本证"策略能改且改得对"，JUnit 证"改了之后确实拦住了"。

用法：python e2e_exec_policy.py [base_url] [username] [password]
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18083"
USER = sys.argv[2] if len(sys.argv) > 2 else "execpol"
PWD = sys.argv[3] if len(sys.argv) > 3 else "Exec#2026!abc"

results = []


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or "{}")
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:
        return -1, {"_error": "%s: %s" % (type(e).__name__, e)}


def check(label, ok, detail=""):
    results.append((label, ok, detail))
    print(("  [OK]   " if ok else "  [FAIL] ") + label + (("  -> " + detail) if detail else ""))


def view(token, sid):
    st, body = call("GET", "/api/conversations/%s/permission" % sid, token=token)
    return body.get("data") or {}


def put(token, sid, payload):
    return call("PUT", "/api/conversations/%s/permission" % sid, payload, token=token)


# ---------- 0. 等后端就绪 ----------
print("== 0. 等待后端就绪 ==")
for _ in range(120):
    st, body = call("GET", "/actuator/health")
    if st == 200 and body.get("status") == "UP":
        break
    time.sleep(1)
else:
    print("  后端没起来，终止")
    sys.exit(2)
check("GET /actuator/health = UP", True)

# ---------- 1. 注册 / 登录 ----------
print("\n== 1. 注册 / 登录拿到 token ==")
st, body = call("POST", "/api/users", {"username": USER, "password": PWD, "displayName": "执行策略测试"})
token = (body.get("data") or {}).get("token")
if not token:
    st, body = call("POST", "/api/tokens", {"username": USER, "password": PWD})
    token = (body.get("data") or {}).get("token")
check("拿到 token", bool(token), "status=%s" % st)
if not token:
    print("  无 token，终止：%s" % json.dumps(body, ensure_ascii=False)[:300])
    sys.exit(1)

st, body = call("POST", "/api/conversations", {}, token=token)
SID = (body.get("data") or {}).get("sessionId")
check("建会话拿到 sessionId", bool(SID), "status=%s sid=%s" % (st, SID))
if not SID:
    print("  无 sessionId，终止：%s" % json.dumps(body, ensure_ascii=False)[:300])
    sys.exit(1)

# ---------- 2. P1: 初始视图 ----------
print("\n== 2. P1 初始权限视图（出厂 allow + 新会话无覆盖）==")
v = view(token, SID)
print("  视图: global=%r effective=%r override=%r followsGlobal=%r"
      % (v.get("execPolicyGlobal"), v.get("execPolicyEffective"),
         v.get("execPolicyOverride"), v.get("execPolicyFollowsGlobal")))
check("execPolicyGlobal == 'allow'", v.get("execPolicyGlobal") == "allow",
      "实际 = %r" % (v.get("execPolicyGlobal"),))
check("execPolicyEffective == 'allow'", v.get("execPolicyEffective") == "allow",
      "实际 = %r" % (v.get("execPolicyEffective"),))
check("execPolicyFollowsGlobal == true", v.get("execPolicyFollowsGlobal") is True,
      "实际 = %r" % (v.get("execPolicyFollowsGlobal"),))
check("execPolicyOverride == ''（空串=跟随全局，不是'策略为空'）",
      (v.get("execPolicyOverride") or "") == "",
      "实际 = %r" % (v.get("execPolicyOverride"),))
opts = v.get("execPolicyOptions") or []
vals = sorted(o.get("value") for o in opts if isinstance(o, dict))
check("execPolicyOptions 恰好三档 block/ask/allow", vals == ["allow", "ask", "block"],
      "实际 = %r" % (vals,))
labels = {o.get("value"): o.get("label") for o in opts if isinstance(o, dict)}
check("三档中文标签齐全", labels.get("block") and labels.get("ask") and labels.get("allow"),
      "实际 = %s" % json.dumps(labels, ensure_ascii=False))
check("execPolicyGlobalLabel / execPolicyEffectiveLabel 一并返回",
      bool(v.get("execPolicyGlobalLabel")) and bool(v.get("execPolicyEffectiveLabel")),
      "global=%r effective=%r" % (v.get("execPolicyGlobalLabel"), v.get("execPolicyEffectiveLabel")))

# ---------- 3. P2/P3: 收紧到 block 并复核持久性 ----------
print("\n== 3. P2/P3 会话内切到 block，并重新 GET 复核 ==")
st, body = put(token, SID, {"execPolicy": "block"})
d = body.get("data") or {}
check("PUT execPolicy=block 返回 success", body.get("success") is True,
      "status=%s body=%s" % (st, json.dumps(body, ensure_ascii=False)[:200]))
check("响应里 execPolicyEffective == 'block'", d.get("execPolicyEffective") == "block",
      "实际 = %r" % (d.get("execPolicyEffective"),))
check("响应里 execPolicyFollowsGlobal == false", d.get("execPolicyFollowsGlobal") is False,
      "实际 = %r" % (d.get("execPolicyFollowsGlobal"),))
check("响应里 execPolicyGlobal 仍是 allow（覆盖不改变全局）",
      d.get("execPolicyGlobal") == "allow", "实际 = %r" % (d.get("execPolicyGlobal"),))

v2 = view(token, SID)
check("重新 GET 仍是 block（覆盖落到了会话态，不是只在响应里修饰）",
      v2.get("execPolicyEffective") == "block",
      "实际 = %r" % (v2.get("execPolicyEffective"),))
check("重新 GET 的 execPolicyOverride == 'block'",
      v2.get("execPolicyOverride") == "block",
      "实际 = %r" % (v2.get("execPolicyOverride"),))

# ---------- 4. P7: 覆盖按会话隔离 ----------
print("\n== 4. P7 覆盖只作用于本会话 ==")
st, body = call("POST", "/api/conversations", {}, token=token)
SID2 = (body.get("data") or {}).get("sessionId")
check("建第二个会话", bool(SID2), "sid=%s" % SID2)
v_other = view(token, SID2)
check("新会话仍跟随全局 allow（没被上一个会话的 block 污染）",
      v_other.get("execPolicyEffective") == "allow" and v_other.get("execPolicyFollowsGlobal") is True,
      "effective=%r followsGlobal=%r"
      % (v_other.get("execPolicyEffective"), v_other.get("execPolicyFollowsGlobal")))

# ---------- 5. P5: 非法值必须报错且不改档 ----------
print("\n== 5. P5 非法档位必须报错，且不静默兜底 ==")
st, body = put(token, SID, {"execPolicy": "乱七八糟"})
check("success == false", body.get("success") is False,
      "status=%s body=%s" % (st, json.dumps(body, ensure_ascii=False)[:200]))
check("code == 'CONFIG.02.01'", body.get("code") == "CONFIG.02.01",
      "实际 = %r" % (body.get("code"),))
v3 = view(token, SID)
check("非法请求不改动档位（仍为 block）", v3.get("execPolicyEffective") == "block",
      "实际 = %r" % (v3.get("execPolicyEffective"),))

# ---------- 6. P4: 回到跟随全局 ----------
print("\n== 6. P4 default = 清除覆盖、跟随全局 ==")
st, body = put(token, SID, {"execPolicy": "default"})
d = body.get("data") or {}
check("execPolicyEffective == 'allow'", d.get("execPolicyEffective") == "allow",
      "实际 = %r" % (d.get("execPolicyEffective"),))
check("execPolicyFollowsGlobal == true", d.get("execPolicyFollowsGlobal") is True,
      "实际 = %r" % (d.get("execPolicyFollowsGlobal"),))
check("execPolicyOverride == ''", (d.get("execPolicyOverride") or "") == "",
      "实际 = %r" % (d.get("execPolicyOverride"),))

# ---------- 7. P6: touched 记账回归 ----------
print("\n== 7. P6 只传 execPolicy 不得重置会话模式（touched 记账）==")
st, body = put(token, SID, {"mode": "accept_edits"})
check("先把模式设为 accept_edits", (body.get("data") or {}).get("mode") == "accept_edits",
      "实际 = %r" % ((body.get("data") or {}).get("mode"),))
st, body = put(token, SID, {"execPolicy": "ask"})
d = body.get("data") or {}
check("只传 execPolicy 后 mode 仍是 accept_edits（未被静默重置）",
      d.get("mode") == "accept_edits", "实际 = %r" % (d.get("mode"),))
check("execPolicyOverride == 'ask'", d.get("execPolicyOverride") == "ask",
      "实际 = %r" % (d.get("execPolicyOverride"),))
check("confirmPolicy 也没被顺手改掉", bool(d.get("confirmPolicy")),
      "实际 = %r" % (d.get("confirmPolicy"),))
print("  注：ACCEPT_EDITS 会把生效档提升为 allow —— 这是刻意的（'自动编辑'是别问我），")
print("      但它绕不过 block：见 ToolPipelineTest#execBlockIsNotBypassedByAcceptEditsMode")

# ---------- 8. 空 body / 无字段请求的既有行为 ----------
print("\n== 8. 回归：不传任何字段仍回到 default 模式（旧行为不能被改坏）==")
st, body = put(token, SID, {})
d = body.get("data") or {}
check("空 body -> mode == 'default'", d.get("mode") == "default",
      "实际 = %r" % (d.get("mode"),))

ok_n = sum(1 for _, ok, _ in results if ok)
bad_n = sum(1 for _, ok, _ in results if not ok)
print("\n小计: %d 通过 / %d 失败" % (ok_n, bad_n))
if bad_n:
    print("\n失败项：")
    for label, ok, detail in results:
        if not ok:
            print("  - %s  %s" % (label, detail))
sys.exit(0 if bad_n == 0 else 1)
