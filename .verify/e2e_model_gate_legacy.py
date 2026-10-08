"""第二阶段：存量行（legacy row）闸门验证。

背景：custom-base-url-enabled 是后加的开关。老版本写进 user_model_config 的
custom_base_url 不会自己消失。只堵 save() 不堵 resolve()，等于给存量行留后门。

本脚本假设库里已经被 ModelGateRow.java 塞了一行
custom_base_url = <EVIL>，然后只做两件事：
  1. GET /api/model -> current.baseUrl 必须是全局出厂值，不能是那个恶意地址
  2. 确认 current.customBaseUrl 仍回显恶意值（说明它确实在库里，是被 resolve 忽略的，
     而不是压根没写进去 —— 否则这个测试是假通过）

日志侧对应断言（grep gate-run2.log）：
  "忽略已存的自定义 baseUrl（agent.models.custom-base-url-enabled=false）"

用法：python e2e_model_gate_legacy.py [base_url] [username] [password]
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18083"
USER = sys.argv[2] if len(sys.argv) > 2 else "gate"
PWD = sys.argv[3] if len(sys.argv) > 3 else "Gate#2026!abc"

EVIL_MARK = "legacy-attacker.example.com"

results = []


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=20) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or "{}")
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:
        return -1, {"_error": f"{type(e).__name__}: {e}"}


def check(label, ok, detail=""):
    results.append((label, ok, detail))
    print(("  [OK]   " if ok else "  [FAIL] ") + label + (("  -> " + detail) if detail else ""))


print("== 0. 等待后端就绪 ==")
for _ in range(90):
    st, body = call("GET", "/actuator/health")
    if st == 200 and body.get("status") == "UP":
        break
    time.sleep(1)
else:
    print("  后端没起来，终止")
    sys.exit(2)
check("GET /actuator/health = UP", True)

print("\n== 1. 登录（用上一阶段已注册的账号）==")
st, body = call("POST", "/api/tokens", {"username": USER, "password": PWD})
token = (body.get("data") or {}).get("token")
check("POST /api/tokens 拿到 token", bool(token), "status=%s" % st)
if not token:
    sys.exit(1)

print("\n== 2. 存量行 read 路径：resolve() 必须忽略库里的恶意 baseUrl ==")
st, body = call("GET", "/api/model", token=token)
cur = body.get("current") or {}
base = cur.get("baseUrl") or ""
stored = cur.get("customBaseUrl") or ""

check("库里的 custom_base_url 确实是那个恶意值（测试前提成立）",
      EVIL_MARK in stored,
      "customBaseUrl = %r" % (stored,))
check("生效 baseUrl 已回退到全局出场值，不含攻击者域名",
      EVIL_MARK not in base and bool(base),
      "baseUrl = %r" % (base,))
check("customBaseUrlAllowed 仍为 false",
      body.get("customBaseUrlAllowed") is False,
      "实际 = %r" % (body.get("customBaseUrlAllowed"),))

print("\n== 3. 对话路径同源：getEffective() 也走 resolve()，这里用同一响应旁证 ==")
# getEffective(userId) 与 getView(userId) 共用 resolve()，两步都在本类里，
# 所以上一步的 GET 已经覆盖。若要更直接，可在对话 SSE 里观察建连的 baseUrl，
# 但那需要真实调用外部 LLM，本脚本不引入该依赖。

ok_n = sum(1 for _, ok, _ in results if ok)
bad_n = sum(1 for _, ok, _ in results if not ok)
print("\n小计: %d 通过 / %d 失败" % (ok_n, bad_n))
sys.exit(0 if bad_n == 0 else 1)
