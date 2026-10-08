"""桌面档（H2 + 无 Redis）会话链路端到端验证。

验的是 SessionStore 的数据库实现是否真的顶替了 Redis：
  注册 -> 拿 token -> 带 token 访问鉴权接口 -> 登出 -> 同一个 token 立即失效

用法：python e2e_session.py <base_url> <username> <password>
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18082"
USER = sys.argv[2] if len(sys.argv) > 2 else "bin"
PWD = sys.argv[3] if len(sys.argv) > 3 else "BinAgent#2026!"

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
for _ in range(60):
    st, body = call("GET", "/actuator/health")
    if st == 200 and body.get("status") == "UP":
        break
    time.sleep(1)
else:
    print("  后端没起来，终止")
    sys.exit(1)
check("GET /actuator/health = UP", True)

# ---------- 1. 注册 ----------
print("\n== 1. 注册（会写 users + auth_sessions 两张表）==")
st, body = call("POST", "/api/users", {"username": USER, "password": PWD, "displayName": "斌哥"})
data = body.get("data") or {}
token = data.get("token")

if st == 200 and token:
    check("POST /api/users 拿到 token", True, "token 长度 %d" % len(token))
else:
    # 已注册过的情况：改用登录
    print("  注册未成功（可能用户已存在），改走登录：%s" % json.dumps(body, ensure_ascii=False)[:200])
    st, body = call("POST", "/api/tokens", {"username": USER, "password": PWD})
    data = body.get("data") or {}
    token = data.get("token")
    check("POST /api/tokens 拿到 token", bool(token), "status=%s" % st)

if not token:
    print("  拿不到 token，后续步骤无法进行")
    print("\n小计: %d 通过 / %d 失败" % (sum(1 for _, ok, _ in results if ok), sum(1 for _, ok, _ in results if not ok)))
    sys.exit(1)

# ---------- 2. 带 token 访问鉴权接口 ----------
print("\n== 2. 带 token 访问鉴权接口（走 sessionStore.validateAndTouch）==")
st, body = call("GET", "/api/tokens/current", token=token)
d = body.get("data") or {}
check("GET /api/tokens/current 已认证", d.get("authenticated") is True or bool(d.get("username")),
      json.dumps(d, ensure_ascii=False)[:160])

# ---------- 3. 无 token 应为匿名 ----------
print("\n== 3. 不带 token 应为匿名 ==")
st, body = call("GET", "/api/tokens/current")
d = body.get("data") or {}
check("无 token 时 anonymous", d.get("authenticated") is not True,
      json.dumps(d, ensure_ascii=False)[:160])

# ---------- 4. 登出 ----------
print("\n== 4. 登出（sessionStore.revoke）==")
st, body = call("DELETE", "/api/tokens", token=token)
check("DELETE /api/tokens 成功", st == 200 and body.get("code") in (0, None),
      "status=%s body=%s" % (st, json.dumps(body, ensure_ascii=False)[:160]))

# ---------- 5. 同一 token 立即失效 ----------
print("\n== 5. 同一 token 登出后应立即失效 ==")
st, body = call("GET", "/api/tokens/current", token=token)
d = body.get("data") or {}
check("登出后 token 失效（匿名）", d.get("authenticated") is not True,
      json.dumps(d, ensure_ascii=False)[:200])

ok_n = sum(1 for _, ok, _ in results if ok)
bad_n = sum(1 for _, ok, _ in results if not ok)
print("\n小计: %d 通过 / %d 失败" % (ok_n, bad_n))
sys.exit(0 if bad_n == 0 else 1)
