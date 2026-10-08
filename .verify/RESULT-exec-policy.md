# exec_command 三档策略 —— 实测报告

> 需求原话：「2 放开，但是要有切换策略的功能」。
> 也就是出厂放开执行能力（`exec-policy: allow`），同时用户能在运行期把它切掉。
>
> 本文所有数字都来自本轮实际执行输出，脚本与原始日志留在同目录，可逐条复核。
> 复现命令见文末。

---

## 1. 改动的形状

原来的形态是**启动时固定的一个 boolean**：

```
agent.tools.exec-enabled: true   →  注入 ToolPipeline 构造器的 final boolean execEnabled
```

这个形态与需求直接矛盾：`true` 能让出厂可用，但用户永远不能收紧；`false` 能收紧，但出厂不可用。
**不是配错值，是配置项的形状撑不住需求** —— 所以改成两层：

| 层 | 存哪 | 决定什么 | 怎么改 |
|---|---|---|---|
| 全局默认 | `agent.tools.exec-policy` | 新会话的初始档 | 改配置文件、重启 |
| 会话覆盖 | `SessionPermissionStore` | 这个会话现在的档 | `PUT /api/conversations/{sid}/permission` 的 `execPolicy`，**不用重启** |

覆盖为空 → 跟随全局。三档取值：`block`（禁止）/ `ask`（需批准）/ `allow`（放行）。

### 1.1 优先级：BLOCK 是不变量，必须排在 `PermissionMode.ACCEPT_EDITS` 之上

这是本次最容易写错的一处。`PermissionMode.ACCEPT_EDITS`（自动编辑）的语义是
**「别问我」**，不是**「我允许执行命令」**。两者容易混成一条判定链，混错的后果是
用户把策略设成 `block` 之后，只要同时开着自动编辑，命令照样执行 —— 而那正是用户想拦的场景。

```java
// PermissionPolicy.effectiveExecPolicy —— 顺序不能换
if (p == ExecPolicy.BLOCK) return ExecPolicy.BLOCK;     // 硬闸门先短路
if (mode == PermissionMode.ACCEPT_EDITS) return ExecPolicy.ALLOW;
return p;
```

### 1.2 闸门位置：BLOCK 必须排在 `needsSessionGrant` 之前

`needsSessionGrant` 对 `BLOCK` 返回 `false`（禁止档不该有「批准一下就放行」的路径）。
所以管线里必须先判 `isExecBlocked` 再判 `needsSessionGrant`：

```java
// ToolPipeline:145-158
ExecPolicy effectiveExec = execPolicyService.effective(sid, mode);
if (PermissionPolicy.isExecBlocked(name, effectiveExec)) { /* PERM_DENY，直接拒 */ }
if (PermissionPolicy.needsSessionGrant(mode, name, effectiveExec) && !granted) { /* 等批准 */ }
```

**排错的后果是「静默变档」**：闸门排后面的话，`isExecBlocked` 依然返回 `true`、
`PermissionPolicyTest` 依然全绿，但调用会掉进「等批准」分支 —— `block` 实际变成了 `ask`。
这个错位**只有管线级测试能抓到**，所以本轮专门补了一组（见 3.1）。

---

## 2. 改动的文件

| 文件 | 改动 |
|---|---|
| `mini-agent-common/.../permission/ExecPolicy.java` | 新增。三档枚举 + `parse`（无法识别返回 null，不兜底）+ 旧键兼容 `fromLegacyExecEnabled` |
| `mini-agent-loop/.../permission/ExecPolicyService.java` | 新增。策略唯一裁决点；`resolveGlobal` 对写错的值**抛异常** |
| `mini-agent-loop/.../permission/PermissionPolicy.java` | 加 `EXEC_TOOL` 常量、`effectiveExecPolicy`、`isExecBlocked`；`needsSessionGrant` 签名 `boolean→ExecPolicy` |
| `mini-agent-loop/.../permission/SessionPermissionStore.java` | `SessionPerm` 加第 5 字段 `execPolicyOverride`；`setExecPolicyOverride`（收紧时顺手撤掉 `exec_command` 的 grant）；`toView` 改 `LinkedHashMap` 并加字段 |
| `mini-agent-loop/.../execution/ToolPipeline.java` | `final boolean execEnabled` → `ExecPolicyService`；插入 BLOCK 硬闸门 |
| `mini-agent-tools/.../tool/BuiltinTools.java` | 删掉 `@Value("${agent.tools.exec-enabled:true}") private boolean execEnabled` |
| `mini-agent-app/.../security/ProductionReadinessValidator.java` | prod 断言改为「只有 `allow` 才报错」 |
| `mini-agent-app/.../web/MiniAgentChatPageController.java` | `permissionView(sessionId)`；`PUT /permission` 加 `execPolicy` 分支 + `touched` 记账 |
| `mini-agent-app/.../templates/chat.html` | `execSelector`/`execMenu` + 6 个 JS 函数；三个菜单互斥补齐 |
| `application.yml` / `application-prod.yml` / `application-desktop.yml` | `exec-policy: allow` / `ask` / `allow` |

### 2.1 `Map.of()` 不接受 null，所以视图必须用 `LinkedHashMap`

`permissionView` 要传「无覆盖」这个状态。如果用 `Map.of("execPolicyOverride", null)` 会直接抛
`NullPointerException`；而如果换成空串又和「策略值为空」混淆 —— 最后一处用 `LinkedHashMap`
把「无覆盖」序列化成空串，并在注释里写明空串的含义。

### 2.2 `touched` 记账修掉一个静默副作用

原来的写法是 `else if (body.get("confirmPolicy") == null) { setMode(default) }`，只看一项。
加了 `execPolicy` 之后，「只想切执行策略」的请求会掉进那个 `else` 被**顺手重置成 default 模式**。
改成显式记账：

```java
boolean touched = false;
if (body.get("confirmPolicy") != null) { ...; touched = true; }
if (body.get("execPolicy")    != null) { ...; touched = true; }
if (body.get("mode")          != null) { ...; touched = true; }
else if (!touched) { setMode(sessionId, PermissionMode.from("default")); }  // 空请求的旧行为保留
```

---

## 3. 验证

### 3.1 管线级（进程内，确定性）—— `mini-agent-loop`

```
./mvnw -B test -pl mini-agent-loop
→ Tests run: 131, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

改造前是 124 项，本轮新增 7 项，全部落在 `ToolPipelineTest`（6 → 13）：

| 用例 | 钉住的性质 |
|---|---|
`execBlockDeniesAndLeavesNoApprovalEntry` | 拒绝 + 轨迹有 `PERM_DENY`、**没有** `WAITING_FOR_HUMAN`（即不给批准入口） |
`execBlockIsNotBypassedByAcceptEditsMode` | `ACCEPT_EDITS` + `block` 仍然拒 —— 防 1.1 处的顺序被改回去 |
`execAskAsksOnceThenExecutesAfterGrant` | `ask` 首次走 `PERMISSION_ASK`，`grantAskTool` 后放行 |
`sessionOverrideTightensGlobalAllow` | 出厂 `allow` + 会话 `block` —— 需求的核心用例 |
`sessionOverrideIsScopedToItsOwnSession` | 覆盖只作用于本会话，别的会话仍跟随全局 |
`clearingSessionOverrideFallsBackToGlobal` | 撤销覆盖要回到全局档，不能卡在 `block` |
`execPolicyDoesNotLeakOntoNonExecTools` | `block` 档不影响 `write_file` —— 防「禁止档拒绝一切」 |

`PermissionPolicyTest` 11 项（改造前重写）：`parse` 拒绝未识别值、`false→ASK`（旧语义是「需批准」不是「禁止」）、`resolveGlobal` 写错抛异常、`needsSessionGrant` 对 `BLOCK` 返 `false` 的分工。

断言轨迹而不是只断言返回值，是因为 `PERM_DENY`（拒绝）和 `WAITING_FOR_HUMAN`（等批准）
返回值都表现为「没执行」—— 只有轨迹能区分，而「block 档到底给不给批准入口」正是要锁的性质。

### 3.2 HTTP 端到端 —— `.verify/e2e_exec_policy.py`

桌面档实例：`--spring.profiles.active=desktop --server.port=18083`，独立 `MINI_AGENT_HOME`。

```
python .verify/e2e_exec_policy.py http://127.0.0.1:18083 execpol 'Exec#2026!abc'
→ 小计: 29 通过 / 0 失败
```

| 编号 | 断言 | 实测 |
|---|---|---|
P1 | 初始视图四件套 | `global=allow` / `effective=allow` / `followsGlobal=true` / `override=''` |
P1 | `execPolicyOptions` 恰好三档 + 中文标签 | `['allow','ask','block']` + `{block:禁止, ask:需批准, allow:放行}` |
P2 | `PUT execPolicy=block` | `effective=block`、`followsGlobal=false`、`global` 仍是 `allow`（覆盖不改全局） |
P3 | 重新 GET | 仍是 `block` —— 覆盖落到了会话态，不是只在响应里修饰 |
P7 | 第二个会话 | `effective=allow` / `followsGlobal=true`（没被上一个会话污染） |
P5 | `PUT execPolicy=乱七八糟` | `success=false`、`code=CONFIG.02.01`，**且档位未被改动**（仍 `block`） |
P6 | 先 `mode=accept_edits`，再只传 `execPolicy=ask` | `mode` 仍是 `accept_edits`、`confirmPolicy` 仍是 `dangerous`（`touched` 记账回归） |
— | 空 body 回归 | `mode=default`（旧行为没被改坏） |

非法值的拒绝响应原文：

```json
{"success": false, "code": "CONFIG.02.01",
 "message": "execPolicy 取值无法识别: 乱七八糟（可选 block / ask / allow，或 default 表示跟随全局）"}
```

### 3.3 启动期两条性质（只有重启才能验）

| 场景 | 期望 | 实测（`.verify/exec-bogus.log` / `exec-legacy2.log`） |
|---|---|---|
| `--agent.tools.exec-policy=乱七八糟` | 启动即失败，**不静默兜底** | `java.lang.IllegalStateException: agent.tools.exec-policy 取值无法识别: '乱七八糟'（可选 block / ask / allow）`，context refresh 取消，进程退出 |
| `--agent.tools.exec-policy= --agent.tools.exec-enabled=false` | 旧键兼容 → `ask` | `exec_command 策略: 全局默认 = ask（需批准）  ← 来自已弃用的 agent.tools.exec-enabled，建议改用 agent.tools.exec-policy` |
| 桌面档默认 | `allow` | `exec_command 策略: 全局默认 = allow（放行）` |
| 两个键同时给 | 以 `exec-policy` 为准并告警 | `agent.tools.exec-policy 与已弃用的 agent.tools.exec-enabled 同时出现，以前者为准: exec-policy=allow exec-enabled=false` |

「非法值启动即崩」是刻意的：静默降级会让「我明明配了 block，怎么还在执行命令」极难查。

### 3.4 前端 —— `.verify/make_exec_dom_test.py`（无头 Edge）

```
python .verify/make_exec_dom_test.py
→ PASS: 26  FAIL: 0
```

分四组：

- **结构**：`execSelector`/`execMenu`/`execPolicyBtn`/`execPolicyLabel` 存在；菜单恰好 4 项、
  `data-exec` 覆盖 `block/ask/allow/default`；4 项都真的绑了 `selectExecPolicy(...)`；
  页面暴露 `toggleExecMenu`/`selectExecPolicy`/`applyExecPolicyView`/`resetExecPolicyView`。
- **互斥**：打开 exec 菜单会关掉另外三个（role/perm/confirm）；反向打开任一个也会关掉 exec。
- **用真实响应渲染**：喂给 `applyExecPolicyView` 的 JSON 直接取自 3.2 的真实响应
  （不是手搓形状，否则字段名写错也能「通过」）——
  `override=block` → 按钮「禁止」+ 高亮 + 只有 block 项 active + hint「全局默认：放行」；
  `override=''` → 按钮「**跟随·放行**」+ 不高亮 + default 项 active；
  `ask` → 「需批准」且不高亮（只有「禁止」才值得一直亮着）。
- **复位**：`resetExecPolicyView()` 把按钮退回「执行」、清掉 hint 与高亮。

截图 `.verify/execdom/sub/exec-menu.png`（light 主题）。

内联脚本语法：`python _pin_verify/check_js_syntax.py` → 12 块全过。

### 3.5 没做的那一条，和替代证据

原计划里有一条「发一次真实的 `exec_command`，断言被 `PERM_DENY` 拒绝」。
**本轮没做，因为本机没有任何可用的 LLM 密钥**（`LLM_API_KEY` / `MIMO_API_KEY` /
`CHATANYWHERE_API_KEY` 全为空），发不出真实对话。

替代证据是 3.1 那 7 条管线级用例：它们直接驱动真的 `ToolPipeline`（不是 mock），
覆盖了 3.2 覆盖不到的那一段 —— 策略生效后**确实拦住了**、并且**没有留批准入口**。
两侧合起来才完整：HTTP 层证「策略能改且改得对」，管线层证「改了之后确实拦住了」。

要补这条真实链路，只需把密钥塞进 `${MINI_AGENT_HOME}/config.yml` 或 `LLM_API_KEY` 环境变量，
然后发一条「执行 echo hi」的对话，再 `GET /api/conversations/{sid}/traces` 找 `PERM_DENY` 节点。

---

## 4. 遗留（本轮没动）

1. **`agent.tools.exec-enabled` 尚未删除**，只做了兼容与告警。等确认没有环境在用旧键再删。
2. `application-desktop.yml` 头部「尚未落地清单」第 2 条仍写着
   `codebase.embedding-enabled: false`（embedding 内联 ONNX 是下一件事，未开始）。
3. 前端不缓存 `localStorage`：一律以 `GET /permission` 视图为准。
   因为「全局默认」只有后端知道，前端自己记一份必然漂移。

---

## 5. 复现命令

```powershell
# 1. 构建
./mvnw -B install -DskipTests -pl mini-agent-common,mini-agent-tools,mini-agent-loop,mini-agent-app -am -o

# 2. 管线级
./mvnw -B test -pl mini-agent-loop

# 3. 起桌面档（独立 home；jwt-secret 必须 ≥256 位）
$env:MINI_AGENT_HOME = "D:\AI\miniagent\.verify\home-exec"
java -jar mini-agent-app\target\mini-agent-app-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=desktop --server.port=18083 `
  "--agent.auth.jwt-secret=execpolicy-verify-only-secret-0123456789abcdef0123456789"

# 4. HTTP 端到端
python .verify/e2e_exec_policy.py http://127.0.0.1:18083 execpol 'Exec#2026!abc' | Tee-Object .verify/exec-e2e.txt

# 5. 启动期性质（各自独立 home，否则 H2 文件锁会先炸）
java -jar mini-agent-app\target\mini-agent-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=desktop `
  --server.port=18084 "--agent.auth.jwt-secret=..." "--agent.tools.exec-policy=乱七八糟"

# 6. 前端
python .verify/make_exec_dom_test.py
python _pin_verify/check_js_syntax.py
```

原始产物（都在 `.verify/` 下，可逐条复核）：

| 文件 | 内容 |
|---|---|
`exec-e2e.txt` | 3.2 的完整输出 —— 29/29 |
`exec-desktop.log` | 桌面档启动日志（含 `exec_command 策略: 全局默认 = allow（放行）`） |
`exec-bogus.log` | 非法值启动失败的全栈（`IllegalStateException` 在 115 行） |
`exec-legacy.log` | 两键同时给、以 `exec-policy` 为准的告警 |
`exec-legacy2.log` | 隔离旧键后的 `ask（需批准）` + 弃用提示 |
`execdom/sub/dump.html` | 前端断言 DOM（26 个 PASS） |
`execdom/sub/exec-menu.png` | 前端截图（light 主题，菜单展开、block 档） |

脚本：`e2e_exec_policy.py`、`make_exec_dom_test.py`（均在 `.verify/`）。
