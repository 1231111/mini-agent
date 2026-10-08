# 宿主外调命令收口 —— 诊断、修复与实测

日期：2026-09-28
范围：`mini-agent-tools`
结论：**已修复并实测通过（46 + 12 = 58 项断言，0 失败）**

---

## 一、结论先说

出厂客户机上没有 `npx` / `mvn` / `sh`。本次重新逐处读码后，结论与上一版记载**不同**：

| 位置 | 调用 | 真实情况 | 处置 |
|---|---|---|---|
| `BrowserService:751-777` | 主路径 `tryInstallViaClasspathCli()`（随包 JRE + classpath 里的 playwright jar），`npx` / `mvn` 是 `commandOnPath()` 守卫后的兜底 | **处理得当，不是问题** | 只把探测实现统一到一处 |
| `RenderDiagramTool:141-176` | `npx -y @mermaid-js/mermaid-cli` | **无守卫**，失败信息是 `CreateProcess error=2` | 加守卫 + 可操作替代方案 |
| `AgentEnvironmentTool:127-153` | `sh -c <command>` | **有 catch 但吞异常返回空串** → 产出假数据 | 去掉 shell 包装 + 三个 bug 一起修 |

**⚠ 纠正上一版技能里的错误记载**：原先记的「`BrowserService:890/929` 致命无兜底」是错的。
那两行在 `tryInstallNpx()` / `tryInstallMaven()` 方法体内，**调用点已经被 `commandOnPath()` 守卫**：

```java
private void tryInstallViaCommand() {
    if (tryInstallViaClasspathCli()) return;              // 主路径
    if (commandOnPath("npx") && tryInstallViaNpx()) return;  // ← 守卫
    if (commandOnPath("mvn") && tryInstallViaMaven()) return; // ← 守卫
    log.error(""" ... 明确的人工提示 ... """);               // ← 兜底也有
}
```

**教训**：`ProcessBuilder` 所在的那一行不等于调用点。必须找到方法定义、反查所有调用点、
再看调用点有没有守卫。只按 `grep ProcessBuilder` 的行号下结论，会把"已被守卫的兜底"
误报成"致命硬伤"，也会漏掉"方法内部自己吞异常"这种更隐蔽的问题。

---

## 二、`AgentEnvironmentTool`：三个真 bug

### 2.1 `sh -c` 在 Windows 上必然失败

```java
// 原实现
new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
```

`sh` 在 Windows 上不存在（除非客户机装了 Git Bash）。而它想做的事 —— 跑一个 `git` 子命令 ——
用参数列表完全能做：

```java
// 修后
HostCommand.exec(GIT_TIMEOUT, "git", "status", "--porcelain");
```

`sh -c` 在这里是**纯粹的冗余包装**。去掉它同时解决两件事：Windows 兼容 + 不再需要一个
"客户机有没有 sh" 的隐性硬依赖。

### 2.2 失败被吞成空串 → 产出假数据

原 `executeCommand()` 的 `catch` 里 `return "";`。于是：

```java
String status = executeCommand("git status --porcelain");   // 失败 → ""
gitInfo.put("is_clean", status.isEmpty());                  // → true  ← 假数据
```

客户机上 `sh` 不存在 → 空串 → **向用户报告"工作区干净"**，而实际上 git 一次都没跑过。

这类"看起来正常但完全是假的"数据比报错危害大得多：报错至少能定位，假数据会被当成事实用下去。

修法：不用空串兼表失败。`HostCommand.Exec` 是一个显式的三段结果：

```java
public record Exec(boolean ok, String output, String error) {
}
```

（为什么必须用 record 而不能继续用 String：**空输出是合法结果** ——
`git remote get-url origin` 在没配 remote 时输出就是空。用空串表示失败，就再也分不出这两种情况了。）

现状：git 不可用时返回 `{"available": false, "error": "..."}`，**不写** `is_clean` /
`modified_files_count` / `branch` 这些字段。实测 keys 只有 `[available, error]`。

### 2.3 逐行 append 不加 `\n` → `modified_files_count` 一直是错的

```java
// 原实现
while ((line = reader.readLine()) != null) {
    output.append(line);              // ← 少了 append("\n")
}
...
gitInfo.put("modified_files_count", status.lines().count());
```

`git status --porcelain` 输出两行 `?? f1.txt` / `?? f2.txt`，被拼成 `?? f1.txt?? f2.txt`，
`lines().count()` = **1**。

这条不是理论推演 —— 实测复现（`.verify/gitprobe`，2 个未跟踪文件）：

| | 原实现 | 修后 |
|---|---|---|
| `modified_files_count` | 1 | **2** |

### 2.4 附带修掉的第四个问题：stderr 并进 stdout → 错误文本被当成 `remote_url`

`redirectErrorStream(true)` 把 stderr 并进 stdout，而原实现用 `!remote.isEmpty()` 判断有没有 remote：

```java
String remote = executeCommand("git remote get-url origin");
if (!remote.isEmpty()) { gitInfo.put("remote_url", remote); }
```

没配 origin 时 git 退出码 2 并往 stderr 吐 `error: No such remote 'origin'` →
被并进 stdout → **非空** → 于是 `remote_url` 的值变成了那句错误文本。

修后按 **exit code** 判定：只有真正成功且非空才记。实测 `git = {...}` 里**没有** `remote_url` 键。

### 2.5 第五个问题：可用性探测本身选错了命令

修完后第一次跑实测，发现 `getGitInfo()` 在**刚 `git init`、零提交**的仓库上返回 `available=false`。
根因是我最初用 `git rev-parse --abbrev-ref HEAD` 兼作探测，而这条命令在无提交的仓库上 exit 128：

```
$ cd 空仓库 && git rev-parse --abbrev-ref HEAD
fatal: ambiguous argument 'HEAD': unknown revision or path not in the working tree.
exit=128
```

于是把一个**正常的空仓库**误报成"git 不可用"。

改用 `git rev-parse --is-inside-work-tree`，语义正好：在仓库里输出 `true` 且 exit 0，不在仓库里 exit 128。
分支单独取，并给一条回退链：

| 命令 | 正常仓库 | 零提交仓库 | detached HEAD |
|---|---|---|---|
| `symbolic-ref --short HEAD` | 分支名 | **分支名**（可用） | 失败 |
| `rev-parse --abbrev-ref HEAD` | 分支名 | exit 128 | `HEAD` |

→ 先试 `symbolic-ref`，失败再试 `rev-parse`，**两条都失败就不写 `branch`**（不写比写个错的好）。

实测（`.verify/gitprobe-empty`，零提交）：

| | 修前 | 修后 |
|---|---|---|
| `available` | false（误判） | **true** |
| `branch` | 无 | **master** |
| `last_commit_hash` | 无 | 无（正确地不写；没有提交不能编一个） |
| `is_clean` / `modified_files_count` | 无 | true / 0 |

---

## 三、`RenderDiagramTool`：`npx` 守卫

原实现在 `npx` 不存在时，`pb.start()` 抛 `IOException: Cannot run program "npx.cmd": CreateProcess error=2`，
被 `handle()` 的 `catch (Exception e)` 兜成 `{"success":false,"error":"渲染失败: ...error=2..."}`。

会报错（不是静默），但客户看不出该装什么，也不知道**还有不需要 Node 的替代路径**。

修后：

```java
String npx = HostCommand.isWindows() ? "npx.cmd" : "npx";
if (!HostCommand.onPath("npx")) {
    return "本机没有 npx（Node.js）。render_diagram 出 PNG 走的是 npx -y @mermaid-js/mermaid-cli，"
         + "既要有 Node 又要能联网下载该包。替代做法（二选一）："
         + "1) 输出 SVG —— 把 source 写成 <svg>…</svg> 且 path 用 .svg，不需要任何外部程序；"
         + "2) 只产出 .mmd 源码文件，交给有渲染环境的一方出图。";
}
```

超时提示也补上了"`npx -y` 首次运行要联网下载包，离线必然超时"这一句。

**为什么不能整体不注册这个工具**：`render_diagram` 除了 mermaid→PNG，还支持 SVG 直写
（不需要任何外部程序）。SVG 那条路在客户机上可用，所以工具要留着，只在 PNG 那条路上拦。

**顺带明确一件容易混的事**：`agent.tools.exec-enabled=false` 挡住的是「**模型发起**命令行」，
**挡不住 `RenderDiagramTool` 内部自己起进程**。这两件事要分开写，不能混成一条。

---

## 四、新增 `HostCommand`（`mini-agent-tools`）

原来 `BrowserService` 里有一份私有的 `commandOnPath`，`RenderDiagramTool` 需要同样的能力 ——
两份拷贝就是两次漂移的机会，抽成 `com.miniagent.agent.tool.HostCommand`，`BrowserService` 改为委托。

对外三个入口：

| 方法 | 用途 |
|---|---|
| `onPath(String name)` | Windows 走 `where`，其余走 `which`。探测本身失败（起不来 / 超时）一律返回 `false` —— 宁可让调用方降级，也不赌它能跑 |
| `exec(Duration, String... argv)` | 按参数列表起进程，**不提供 `sh -c` 重载** |
| `describe(String... argv)` | 仅用于日志/错误提示，不拿去执行 |

**刻意不提供 `sh -c <整串>` 的重载**：那条路在 Windows 上必然失败，而它想做的事用参数列表完全能做。
除非真需要管道 / 重定向 / 通配符展开，否则不引 shell —— 引了就把"客户机有没有 sh"变成隐性硬依赖。

**刻意不做探测缓存**：PATH 在本进程生命周期内可能变（用户装了 Node），一次探测约十几毫秒，
而调用它的场景（渲染图、装 Chromium）本身要几秒到几分钟，这点开销不值得引入缓存过期的负面 bug。

`exec` 里**先判超时再读输出**，并在注释里写明理由：git 这类命令的输出远小于管道缓冲区，
不会因"子进程等我们读"而死锁；反过来若先读，超时就形同虚设。

---

## 五、实测证据

### 5.1 git 场景三连（`EnvToolProbe.java`，46 项 / 0 失败）

| 场景 | 工作目录 | 断言 | 结果 |
|---|---|---|---|
| `git-repo` | 有 1 个提交 + 2 个未跟踪文件、**无 origin** | 16 | **16 / 0** |
| `git-repo-empty` | 刚 `init`、零提交 | 15 | **15 / 0** |
| `non-git` | 不在任何 git 仓库内 | 15 | **15 / 0** |

关键输出：

```
# git-repo
git = {available=true, branch=master,
       last_commit_hash=60f3d47e852225266894fc4c0c9f2f6f89307715,
       last_commit_message=probe baseline,
       is_clean=false, modified_files_count=2}
  [OK] modified_files_count == 2（原实现恒为 1）
  [OK] 没有 origin 时【不写】 remote_url（原实现会把 stderr 的错误文本填进去）

# git-repo-empty
git = {available=true, branch=master, is_clean=true, modified_files_count=0}
  [OK] 零提交仓库里 available == true（原实现误判为 false）
  [OK] 零提交仓库里【不写】 last_commit_hash（没有提交，不能编一个）

# non-git
git = {available=false, error=exit=128 fatal: not a git repository (or any of the parent directories): .git}
  [OK] 非 git 目录下【不出现】 is_clean（原实现返回 true = 假数据）
  [OK] 非 git 目录下【不出现】 modified_files_count
  [OK] 非 git 目录下【不出现】 branch
      → keys=[available, error]
```

`HostCommand` 自身的断言（三个场景各跑一遍）：

```
  [OK] onPath("git") == true
  [OK] onPath("npx") == true（本机装了 Node）
  [OK] onPath("definitely-absent-xyz") == false
  [OK] onPath(null) == false / onPath("") == false
  [OK] 命令不存在时 error 非空
      -> error=IOException: Cannot run program "definitely-absent-xyz": CreateProcess error=2, 系统找不到指定的文件。
  [OK] 输出行数 >= 2（没有被拼成一行）  -> 行数=2 raw=?? f1.txt\n?? f2.txt
  [OK] 无 origin 时 ok == false  -> ok=false exit=2 error: No such remote 'origin'
```

### 5.2 `npx` 缺失守卫（`RenderGuardProbe.java`，12 项 / 0 失败）

**怎么忠实模拟客户机**：把子进程的 PATH 剥到只剩 `C:\Windows\System32`。
这样 `where.exe`（在 System32 里）仍然可用，探测机制与客户机**一致** ——
真的跑了一次 `where` 拿到 exit=1，而不是"探测工具自己丢了"。

⚠ 这里踩过一次坑：在 Git Bash 里用 `env PATH='C:\Windows\System32' java ...` 是**不可靠**的 ——
MSYS 会在 exec 时改写 PATH（实测被拼成 `<git-root>\Windows\System32` 并前置了一个 `C;`），
于是 `where.exe` 本身都找不到，测出来的就不是"缺 npx"而是"探测工具丢了"。
改用 PowerShell 设 `$env:PATH` 才是忠实的。

**并且必须带一条直接证据**：光看 `System.getenv("PATH")` 不够 ——
实测它打印出来的仍是改之前的完整 PATH，而子进程里 `where` 实际用的并不是那个值。
所以探针额外在 JVM 内部跑了一次 `where npx`，把真实的 exit code 与原样输出打出来：

```
   PATH=C:\Windows\System32
  [OK] 剥掉 Node 后 onPath("npx") == false          -> present=false
  [OK] 剥掉 Node 后 onPath("where") 仍可为 true      -> where=true
  [证据] where npx -> ok=false error=exit=1 信息: 用提供的模式无法找到文件。
  [OK] where 真的跑起来了（失败原因是 exit=N，不是 IOException）
  [OK] where npx 以非 0 退出 = 真的没找到            -> ok=false
  [OK] where npx 的输出里不含任何路径                -> output=（只有那句提示）
  [OK] 没有抛异常（原实现会抛 CreateProcess error=2）
  [OK] 返回了非空提示而非 null
  [OK] 提示里点明缺 npx
  [OK] 提示里给出可操作的替代方案（提到 svg）
  [OK] 提示里【不出现】 CreateProcess error=2
  [OK] 没有产出文件（守卫生效，压根没起进程）        -> exists=false
小计: 11 通过 / 0 失败
```

反证（完整 PATH 下必须能找到 npx，否则说明探针测的不是 npx）：

```
== RenderDiagramTool 守卫: npx-present ==
  [OK] 完整 PATH 下 onPath("npx") == true（反证：探针确实在测 npx）  -> present=true
小计: 1 通过 / 0 失败
```

### 5.3 应用级回归

`./mvnw -B install -DskipTests -pl mini-agent-tools,mini-agent-app` → **BUILD SUCCESS**
重启桌面档（端口 18083）：

```
health = {"status":"UP"}
BuiltinTools    : exec_command 已注册，默认需会话批准（agent.tools.exec-enabled=false）
CapabilityRegistry : CapabilityRegistry 自检通过，capability 词表
              [file_read, file_write, web, code, image, browser, shell, research, deliver, plan, general, qa] 均有可用工具
MiniAgentSpringbootApplication : Started MiniAgentSpringbootApplication in 9.767 seconds
```

无 ERROR 行。说明新增 `HostCommand` 与 `BrowserService` 的委托改动没有引入装配问题。

---

## 六、未覆盖

1. **`RenderDiagramTool` 的正向路径没跑**：本机有 Node，`npx -y @mermaid-js/mermaid-cli`
   会真的联网下载并渲染。本次只验了守卫分支（缺 npx），没验"有 npx 时能正常出图" ——
   那需要一次真实的 CDN 下载，与本次要证明的"客户机上不会抛 error=2"无关。
2. **`AgentEnvironmentTool.handle()` 的整条 JSON 输出没验**：探针只打在被改的 `getGitInfo()` 上。
   `handle()` 里 `env.put("git", getGitInfo())` 是纯透传，没有额外逻辑。
3. **`BrowserService` 的 Chromium 安装链路没跑**：本次只把探测实现统一到 `HostCommand`，
   行为等价（原实现是 `done && p.exitValue() == 0`，`HostCommand.onPath` 相同，
   只是多了"排空管道"和"超时 destroyForcibly"两个健壮性处理）。没有实测安装流程。
4. **`mvn` 走 `MavenWrapper` 还是宿主 `mvn` 的问题**没动：`BrowserService` 用的是宿主 `mvn`，
   客户机上没有。但它是**第三条**兜底（主路径是随包 JRE + classpath jar），不构成硬伤。

---

## 七、复现方式

```bash
cd /d/AI/miniagent
./mvnw -B install -DskipTests -pl mini-agent-tools,mini-agent-app

# 生成依赖 classpath（注意输出文件会落在模块目录下，要移出来）
./mvnw -B -q dependency:build-classpath -Dmdep.outputFile=cp-tools.txt -pl mini-agent-tools
mv mini-agent-tools/.verify/cp-tools.txt .verify/   # 视 -Dmdep.outputFile 的相对路径而定
```

### git 三场景

```bash
CP="D:/AI/miniagent/mini-agent-tools/target/classes;$(cat .verify/cp-tools.txt)"

# 场景 1（需先准备：有提交 + 2 个未跟踪文件、无 origin）
cd .verify/gitprobe && java -Dstdout.encoding=UTF-8 -cp "$CP" \
  "D:/AI/miniagent/.verify/EnvToolProbe.java" git-repo

# 场景 2（刚 init、零提交）
cd .verify/gitprobe-empty && java -Dstdout.encoding=UTF-8 -cp "$CP" \
  "D:/AI/miniagent/.verify/EnvToolProbe.java" git-repo-empty

# 场景 3（不在任何 git 仓库内；注意不能用仓库里的目录，git 会往上找到父仓库）
mkdir -p "$TEMP/miniagent-nongit-probe" && cd "$TEMP/miniagent-nongit-probe" && \
  java -Dstdout.encoding=UTF-8 -cp "$CP" "D:/AI/miniagent/.verify/EnvToolProbe.java" non-git
```

### npx 守卫两场景（PowerShell，不用 Git Bash —— MSYS 会改写 PATH）

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe'
$cp = "D:\AI\miniagent\mini-agent-tools\target\classes;" +
      (Get-Content "D:\AI\miniagent\.verify\cp-tools.txt" -Raw).Trim()

# 缺 npx（模拟客户机）
$env:PATH = 'C:\Windows\System32'
& $java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $cp `
        'D:\AI\miniagent\.verify\RenderGuardProbe.java' npx-missing

# 反证：有 npx
$env:PATH = [System.Environment]::GetEnvironmentVariable('PATH','Machine') + ';' +
            [System.Environment]::GetEnvironmentVariable('PATH','User')
& $java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $cp `
        'D:\AI\miniagent\.verify\RenderGuardProbe.java' npx-present
```

⚠ PowerShell 调 java 时 `-D` 参数**必须加引号**（`'-Dstdout.encoding=UTF-8'`），
否则 PowerShell 会把它拆开，java 收到 `.encoding=UTF-8` 当主类名报 `ClassNotFoundException`。

---

## 八、受影响文件

| 文件 | 状态 |
|---|---|
| `mini-agent-tools/.../agent/tool/HostCommand.java` | **新增**（探测 + 显式结果的执行封装） |
| `mini-agent-tools/.../agent/tool/AgentEnvironmentTool.java` | 修改（去 shell 包装、三个 bug、探测命令换掉、删掉 `executeCommand`） |
| `mini-agent-tools/.../agent/tool/RenderDiagramTool.java` | 修改（`npx` 守卫 + 超时提示 + 类注释明确 `exec-enabled` 管不到它） |
| `mini-agent-tools/.../agent/browser/BrowserService.java` | 修改（`commandOnPath` 改为委托 `HostCommand.onPath`，删掉重复实现） |
| `.verify/EnvToolProbe.java` | 新增（git 三场景探针） |
| `.verify/RenderGuardProbe.java` | 新增（npx 守卫探针） |
| `.verify/run-env-probes.cmd` | 新增（git 三场景一键跑） |
| `.verify/env-probe-*.log`、`.verify/render-guard-*.log` | 新增（原始证据，已被 `.gitignore` 覆盖） |
| `.verify/gitprobe/`、`.verify/gitprobe-empty/`、`.verify/cp-tools.txt` | 测试夹具与中间产物 |
