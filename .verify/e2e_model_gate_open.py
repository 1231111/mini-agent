"""回归验证：把闸门显式打开时，原有开发行为必须完全恢复。

为什么必须测这个：
  闸门是「配置驱动」而不是「硬编码」，否则将来做企业版（允许用户指向自有网关）
  就得改代码。这个脚本证明 custom-base-url-enabled=true 时 PUT 的自定义地址
  能真的生效 —— 也就是改动没有把默认行为改坏。

用法：python e2e_model_gate_open.py [base_url] [username] [password]
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18083"
USER = sys.argv[2] if len(sys.argv) > 2 else "gate"
PWD = sys.argv[3] if len(sys.argv) > 3 else "Gate#2026!abc"

MY_URL = "https://my-own-gateway.internal/v1"

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

print("\n== 1. 登录 ==")
st, body = call("POST", "/api/tokens", {"username": USER, "password": PWD})
token = (body.get("data") or {}).get("token")
check("拿到 token", bool(token), "status=%s" % st)
if not token:
    sys.exit(1)

print("\n== 2. 开关打开时，响应应声明允许 ==")
st, body = call("GET", "/api/model", token=token)
check("customBaseUrlAllowed == true", body.get("customBaseUrlAllowed") is True,
      "实际 = %r" % (body.get("customBaseUrlAllowed"),))

print("\n== 3. PUT 自定义 baseUrl 必须真的生效（原有行为不被破坏）==")
st, body = call("PUT", "/api/model", {"baseUrl": MY_URL}, token=token)
cur = body.get("current") or {}
check("customBaseUrlRejected == false", body.get("customBaseUrlRejected") is False,
      "实际 = %r" % (body.get("customBaseUrlRejected"),))
check("生效 baseUrl 就是自定义值", cur.get("baseUrl") == MY_URL,
      "实际 = %r" % (cur.get("baseUrl"),))
check("customBaseUrl 已落库并回显", (cur.get("customBaseUrl") or "") == MY_URL,
      "实际 = %r" % (cur.get("customBaseUrl"),))

print("\n== 4. 收尾：重置回默认，避免污染后续测试数据 ==")
st, body = call("PUT", "/api/model", {"reset": True}, token=token)
cur2 = body.get("current") or {}
check("reset 后 baseUrl 回到全局出厂值",
      (cur2.get("baseUrl") or "").startswith("https://token-plan-cn"),
      "实际 = %r" % (cur2.get("baseUrl"),))

ok_n = sum(1 for _, ok, _ in results if ok)
bad_n = sum(1 for _, ok, _ in results if not ok)
print("\n小计: %d 通过 / %d 失败" % (ok_n, bad_n))
sys.exit(0 if bad_n == 0 else 1)
