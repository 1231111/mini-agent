"""桌面档 custom-base-url-enabled 闸门的端到端验证（第一阶段：save 闸门）。

验的是「配置里写了 custom-base-url-enabled=false，运行时到底有没有代码执行它」。
背景：这个键此前全项目只有 ProductionReadinessValidator 读，而那是 prod 专用
启动断言，客户端档一次都跑不到 —— 等于配置形同注释。

本脚本覆盖：
  A. GET  /api/model      -> 必须回 customBaseUrlAllowed=false（前端据此禁掉输入框）
  B. GET  /api/model      -> 记录当前生效 baseUrl（改动后必须与它一致）
  C. PUT  /api/model 带恶意 baseUrl + 空 apiKey
                          -> 必须 customBaseUrlRejected=true
                          -> current.baseUrl 必须仍是改动前那个（没打出去）
                          -> current.customBaseUrl 必须为空串（没落库）
                          -> current.hasApiKey 必须仍为 true（证明 apiKey 与 baseUrl
                             确实是分别回退的，也就是这次攻击路径真实存在）

存量行（resolve 闸门）由 e2e_model_gate_legacy 部分覆盖，见 ModelGateRow.java。

用法：python e2e_model_gate.py [base_url] [username] [password]
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18083"
USER = sys.argv[2] if len(sys.argv) > 2 else "gate"
PWD = sys.argv[3] if len(sys.argv) > 3 else "Gate#2026!abc"

EVIL = "https://attacker.example.com/v1"

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


# ---------- 0. 等后端就绪 ----------
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

# ---------- 1. 注册 / 登录 ----------
print("\n== 1. 注册 / 登录拿到 token ==")
st, body = call("POST", "/api/users", {"username": USER, "password": PWD, "displayName": "闸门测试"})
token = (body.get("data") or {}).get("token")
if not token:
    st, body = call("POST", "/api/tokens", {"username": USER, "password": PWD})
    token = (body.get("data") or {}).get("token")
check("拿到 token", bool(token), "status=%s len=%s" % (st, len(token or "")))
if not token:
    print("  无 token，终止")
    sys.exit(1)

# ---------- 2. A: customBaseUrlAllowed ----------
print("\n== 2. GET /api/model 应声明自定义 baseUrl 不被允许 ==")
st, body = call("GET", "/api/model", token=token)
allowed = body.get("customBaseUrlAllowed")
check("customBaseUrlAllowed == false", allowed is False,
      "实际 = %r" % (allowed,))
check("响应里确实带了这个字段（不是缺省）", "customBaseUrlAllowed" in body,
      "top-level keys = %s" % sorted(body.keys())[:8])

cur = body.get("current") or {}
base_before = cur.get("baseUrl")
model_before = cur.get("modelName")
key_before = cur.get("hasApiKey")
print("  改动前生效配置: baseUrl=%s model=%s hasApiKey=%s customBaseUrl=%r"
      % (base_before, model_before, key_before, cur.get("customBaseUrl")))
check("改动前 baseUrl 是全局出厂值（非空）", bool(base_before), str(base_before))

# ---------- 3. C: PUT 恶意 baseUrl 必须被拒 ----------
print("\n== 3. PUT /api/model 指向攻击者地址 + apiKey 留空 ==")
print("  提交: baseUrl=%s apiKey=''（空 = 不覆盖自定义 key，于是回退到出厂 key）" % EVIL)
st, body = call("PUT", "/api/model", {"baseUrl": EVIL, "apiKey": ""}, token=token)
rejected = body.get("customBaseUrlRejected")
cur2 = body.get("current") or {}
check("customBaseUrlRejected == true", rejected is True, "实际 = %r" % (rejected,))
check("生效 baseUrl 未被改动（仍为 %s）" % base_before,
      cur2.get("baseUrl") == base_before,
      "实际 = %r" % (cur2.get("baseUrl"),))
check("customBaseUrl 未落库（回显为空串）",
      (cur2.get("customBaseUrl") or "") == "",
      "实际 = %r" % (cur2.get("customBaseUrl"),))
mask_before = cur.get("apiKeyMasked")
check("密钥状态改动前后完全一致（没被换走、没被清空）",
      cur2.get("hasApiKey") == key_before and cur2.get("apiKeyMasked") == mask_before,
      "前 hasApiKey=%r mask=%r / 后 hasApiKey=%r mask=%r"
      % (key_before, mask_before, cur2.get("hasApiKey"), cur2.get("apiKeyMasked")))

# ---------- 4. 复核：再 GET 一次，确认拒绝是持久的而非只在响应里 ----------
print("\n== 4. 重新 GET /api/model 复核（确认拒绝真的落到了库，不是响应修饰）==")
st, body = call("GET", "/api/model", token=token)
cur3 = body.get("current") or {}
check("再次 GET，customBaseUrl 仍为空串",
      (cur3.get("customBaseUrl") or "") == "",
      "实际 = %r" % (cur3.get("customBaseUrl"),))
check("再次 GET，baseUrl 仍是 %s" % base_before,
      cur3.get("baseUrl") == base_before,
      "实际 = %r" % (cur3.get("baseUrl"),))

# ---------- 5. 反证：不带 baseUrl 的保存不应被误判为拒绝 ----------
print("\n== 5. 反证：只改 modelName、不传 baseUrl，不应报 rejected ==")
st, body = call("PUT", "/api/model", {"modelName": model_before or ""}, token=token)
check("customBaseUrlRejected == false（没传 baseUrl 就不该拒绝）",
      body.get("customBaseUrlRejected") is False,
      "实际 = %r" % (body.get("customBaseUrlRejected"),))

ok_n = sum(1 for _, ok, _ in results if ok)
bad_n = sum(1 for _, ok, _ in results if not ok)
print("\n小计: %d 通过 / %d 失败" % (ok_n, bad_n))
sys.exit(0 if bad_n == 0 else 1)
