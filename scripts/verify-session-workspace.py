#!/usr/bin/env python
"""
会话级工作目录的端到端验证。

为什么要真起服务：单元测试覆盖不到三件事 ——
1. 新增列在 H2 的 ddl-auto=update 下能否真的建出来（列名/长度写错会在启动或首次写入时报）；
2. 接口的鉴权与归属判定（单元测试里身份是 mock 的）；
3. /api/workspace-media?sid= 的隔离效果（文件在不在、跨用户能不能读）。

用法：python scripts/verify-session-workspace.py <jar> [port]
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

JAR = sys.argv[1] if len(sys.argv) > 1 else None
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 18091
BASE = f"http://127.0.0.1:{PORT}"


def req(method, path, token=None, body=None, raw=False):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=30) as resp:
            payload = resp.read()
            return resp.status, (payload if raw else json.loads(payload or b"{}"))
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return e.code, json.loads(payload or b"{}")
        except Exception:
            return e.code, payload.decode("utf-8", "replace")


def wait_health(deadline):
    while time.time() < deadline:
        try:
            status, _ = req("GET", "/actuator/health")
            if status == 200:
                return True
        except Exception:
            pass
        time.sleep(0.5)
    return False


def login(username, password):
    """注册（若不存在）并返回 token。注册端点是 /api/users，登录是 /api/tokens。"""
    status, body = req("POST", "/api/users", body={
        "username": username, "password": password, "displayName": username})
    if status >= 400:
        status, body = req("POST", "/api/tokens", body={"username": username, "password": password})
    data = (body or {}).get("data") or {}
    return status, data.get("token"), body


def run():
    results = []

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))

    if not JAR:
        print("用法: python scripts/verify-session-workspace.py <jar> [port]")
        return 2

    home = tempfile.mkdtemp(prefix="ma-ws-verify-")
    project_dir = tempfile.mkdtemp(prefix="ma-ws-project-")
    log_path = os.path.join(home, "server.log")
    env = dict(os.environ)
    env.update({
        "MINI_AGENT_HOME": home,
        "JWT_SECRET": "verify-session-workspace-secret-" + uuid.uuid4().hex,
        "SPRING_PROFILES_ACTIVE": "desktop",
    })
    log = open(log_path, "wb")
    proc = subprocess.Popen(
        ["java", "-jar", JAR, "--spring.profiles.active=desktop",
         f"--server.port={PORT}", "--logging.level.com.miniagent=INFO"],
        env=env, stdout=log, stderr=subprocess.STDOUT, cwd=os.getcwd())
    try:
        if not wait_health(time.time() + 150):
            log.flush()
            with open(log_path, "rb") as f:
                print("服务未就绪，日志尾部：\n" + f.read()[-3000:].decode("utf-8", "replace"))
            check("服务启动", False)
            return 1

        user = "wsuser" + uuid.uuid4().hex[:8]
        password = "Passw0rd!2345"
        status, token, body = login(user, password)
        check("登录拿到 token", bool(token), f"status={status} body={json.dumps(body, ensure_ascii=False)[:200]}")
        if not token:
            return 1

        status, body = req("POST", "/api/conversations", token, body={})
        data = body.get("data") or {}
        session_id = data.get("sessionId") or data.get("id")
        check("新建会话", bool(session_id), f"status={status} body={json.dumps(body, ensure_ascii=False)[:200]}")
        if not session_id:
            return 1

        status, body = req("GET", f"/api/conversations/{session_id}/workspace", token)
        data = body.get("data") or {}
        check("未绑定时返回默认工作区",
              status == 200 and not data.get("path")
              and str(data.get("effectivePath", "")).replace("\\", "/").endswith("workspace"),
              json.dumps(data, ensure_ascii=False))

        status, body = req("PUT", f"/api/conversations/{session_id}/workspace", token, {"path": project_dir})
        check("绑定项目目录成功", status == 200 and bool((body.get("data") or {}).get("path")),
              f"status={status} body={json.dumps(body, ensure_ascii=False)[:300]}")

        # 落库值必须是规范路径：符号链接/junction 都要折成真实路径
        _, body = req("GET", f"/api/conversations/{session_id}/workspace", token)
        stored = (body.get("data") or {}).get("path") or ""
        check("重新读取仍是绑定目录", os.path.normcase(stored) == os.path.normcase(os.path.realpath(project_dir)),
              f"stored={stored} real={os.path.realpath(project_dir)}")

        # 本项目的失败约定是 HTTP 200 + success=false + code（前端按 success 判），
        # 所以断言看 success 与文案，不看状态码。
        status, body = req("PUT", f"/api/conversations/{session_id}/workspace", token,
                           {"path": os.path.join(home, "definitely-missing")})
        check("不存在的目录被拒", body.get("success") is False
              and body.get("code") == "CONFIG.02.01"
              and "不存在" in str(body.get("message")),
              f"status={status} {json.dumps(body, ensure_ascii=False)[:200]}")

        status, body = req("PUT", f"/api/conversations/{session_id}/workspace", token, {"path": "relative/path"})
        check("相对路径被拒", body.get("success") is False and "绝对路径" in str(body.get("message")),
              f"status={status} {json.dumps(body, ensure_ascii=False)[:200]}")

        # 媒体端点：文件在绑定的项目目录里，带 sid 能读、不带 sid 读不到
        svg_path = os.path.join(project_dir, "diagram.svg")
        with open(svg_path, "w", encoding="utf-8") as f:
            f.write('<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10">'
                    '<rect width="10" height="10" fill="#123456"/></svg>')
        status, _ = req("GET", f"/api/workspace-media/diagram.svg?sid={session_id}", token, raw=True)
        check("带 sid 能读到项目目录里的图", status == 200, status)
        status, _ = req("GET", "/api/workspace-media/diagram.svg", token, raw=True)
        check("不带 sid 不混用默认工作区（应为 404）", status == 404, status)

        # 跨用户：另一个账号拿这个 sid 必须读不到
        user2 = "wsuser2" + uuid.uuid4().hex[:6]
        _, token2, _ = login(user2, password)
        status, _ = req("GET", f"/api/workspace-media/diagram.svg?sid={session_id}", token2 or "", raw=True)
        check("别人的会话 id 读不到我的工作区图片", status in (401, 404), status)

        status, body = req("DELETE", f"/api/conversations/{session_id}/workspace", token)
        check("恢复默认成功", status == 200 and not (body.get("data") or {}).get("path"),
              f"status={status} body={json.dumps(body, ensure_ascii=False)[:200]}")
        status, _ = req("GET", f"/api/workspace-media/diagram.svg?sid={session_id}", token, raw=True)
        check("恢复默认后不再从项目目录取图", status == 404, status)
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except Exception:
            proc.kill()
        log.close()
        failed = 0
        for name, ok, detail in results:
            print(("PASS " if ok else "FAIL ") + name + (f"  :: {detail}" if detail and not ok else ""))
            if not ok:
                failed += 1
        if results:
            print(f"\n{len(results) - failed}/{len(results)} 通过")
        shutil.rmtree(home, ignore_errors=True)
        shutil.rmtree(project_dir, ignore_errors=True)
        run.__dict__["_failed"] = failed or (0 if results else 1)


code = run()
sys.exit(code if code else run.__dict__.get("_failed", 0))
