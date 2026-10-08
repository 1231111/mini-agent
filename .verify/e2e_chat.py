"""桌面档一次真实对话的端到端验证。

这是数据库选型的终审：注册/登录只碰了 users / auth_sessions 两张表，
而 chat_tasks / chat_messages / agent_trace_steps 上集中了 @Lob + columnDefinition="LONGTEXT"
和两处 ON DUPLICATE KEY UPDATE —— 只有真跑一次对话才能确认它们在 H2 上写得进去。

用法：python e2e_chat.py <base_url> <username> <password> [message]
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18083"
USER = sys.argv[2] if len(sys.argv) > 2 else "bin"
PWD = sys.argv[3] if len(sys.argv) > 3 else "BinAgent#2026!"
MSG = sys.argv[4] if len(sys.argv) > 4 else "用一句话介绍你自己，不要用任何工具。"


def call(method, path, body=None, token=None, timeout=30):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or "{}")
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:400]}
    except Exception as e:
        return -1, {"_error": f"{type(e).__name__}: {e}"}


# ---------- 0. 等后端 ----------
print("== 0. 等待后端就绪 ==")
for _ in range(90):
    st, body = call("GET", "/actuator/health", timeout=5)
    if st == 200 and body.get("status") == "UP":
        break
    time.sleep(1)
else:
    print("  后端没起来")
    sys.exit(1)
print("  [OK] /actuator/health = UP")

# ---------- 1. 登录 ----------
print("\n== 1. 登录 ==")
st, body = call("POST", "/api/tokens", {"username": USER, "password": PWD})
token = (body.get("data") or {}).get("token")
if not token:
    st, body = call("POST", "/api/users",
                    {"username": USER, "password": PWD, "displayName": "斌哥"})
    token = (body.get("data") or {}).get("token")
if not token:
    print("  [FAIL] 拿不到 token: %s" % json.dumps(body, ensure_ascii=False)[:300])
    sys.exit(1)
print("  [OK] token 长度 %d" % len(token))

# ---------- 2. 新建会话 ----------
print("\n== 2. 新建会话 ==")
st, body = call("POST", "/api/conversations", token=token)
sid = (body.get("data") or {}).get("sessionId")
if not sid:
    print("  [FAIL] %s" % json.dumps(body, ensure_ascii=False)[:300])
    sys.exit(1)
print("  [OK] sessionId = %s" % sid)

# ---------- 3. 发一轮真实对话（SSE）----------
print("\n== 3. 发消息并读 SSE 流 ==")
print("    消息: %s" % MSG)
req = urllib.request.Request(
    BASE + "/api/conversations/%s/messages/stream" % sid, method="POST")
req.add_header("Content-Type", "application/json")
req.add_header("Accept", "text/event-stream")
req.add_header("Authorization", "Bearer " + token)
payload = json.dumps({"message": MSG}).encode()

events = {}
text_chunks = []
errors = []
deadline = time.time() + 300
finished = False

try:
    with urllib.request.urlopen(req, data=payload, timeout=300) as r:
        print("  HTTP %s, content-type=%s" % (r.status, r.headers.get("content-type")))
        cur_event = "message"
        for raw in r:
            if time.time() > deadline:
                print("  (到达 300s 上限，主动断开)")
                break
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if line == "":
                continue
            if line.startswith("event:"):
                cur_event = line[6:].strip()
                continue
            if line.startswith(":"):
                continue  # SSE 心跳
            if line.startswith("data:"):
                data = line[5:].strip()
                events[cur_event] = events.get(cur_event, 0) + 1
                if cur_event in ("error", "failed"):
                    errors.append(data[:300])
                if cur_event in ("message", "content", "delta", "chunk", "token"):
                    text_chunks.append(data)
                if data in ("[DONE]",) or cur_event == "done":
                    finished = True
                    break
except urllib.error.HTTPError as e:
    print("  [FAIL] HTTP %s: %s" % (e.code, e.read().decode()[:400]))
    sys.exit(1)
except Exception as e:
    print("  (流结束: %s: %s)" % (type(e).__name__, e))

print("  收到的 SSE 事件类型统计: %s" % json.dumps(events, ensure_ascii=False))
print("  文本片段数: %d" % len(text_chunks))
if errors:
    print("  [FAIL] 流内错误: %s" % errors[:3])
else:
    print("  [OK] 流内无错误事件")

# ---------- 4. 取回落库的消息 ----------
print("\n== 4. 从数据库读回落库结果 ==")
time.sleep(3)
st, body = call("GET", "/api/conversations/%s/messages" % sid, token=token)
data = body.get("data")
msgs = data if isinstance(data, list) else (data or {}).get("messages") or []
print("  消息条数: %d" % len(msgs))
answer = ""
for m in msgs:
    role = m.get("role")
    content = (m.get("content") or "")
    print("    [%s] %d 字符: %s" % (role, len(content),
                                   content[:120].replace("\n", " ") + ("..." if len(content) > 120 else "")))
    if role in ("assistant", "ai"):
        answer = content

# ---------- 5. 用量表 ----------
st, body = call("GET", "/api/token-usage", token=token)
print("\n  /api/token-usage: %s" % json.dumps(body, ensure_ascii=False)[:300])

print("\n===== 结果 =====")
print("  SESSION_ID=%s" % sid)
ok = bool(answer.strip()) and not errors
print("  助手回复: %s" % ("有" if answer.strip() else "无"))
print("  判定: %s" % ("通过" if ok else "失败"))
sys.exit(0 if ok else 1)
