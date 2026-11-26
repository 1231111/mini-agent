# 工具实现审计（实测版）

- 日期：2026-11-26
- 上一版：`tool-production-audit-2026-11-26.md` 初稿已被本文件取代
- 触发：上一版多条结论是"读代码推断后果"，未区分必然与条件成立。本版重做，**能跑的跑出证据，跑不了的标注为代码事实并写明触发条件**。

## 0. 方法与证据分级

审计对象：`mini-agent-tools/src/main/java/com/miniagent/agent/**` 66 个文件（13.7k 行）。

本版对每条结论标注证据等级：

| 等级 | 含义 | 本版数量 |
|---|---|---|
| **实测** | 用生产代码跑出结果，原始输出附在文内 | 6 |
| **代码事实** | 读源码可确定，不依赖运行环境；但"造成事故"需满足写明的前提条件 | 18 |
| **推断** | 无法直接验证，只能从机制推导 | 3（保留但降级，不进入 P0） |

实测用的探针在 `tmp/docx-probe/`，编译 `mini-agent-tools/target/classes` 后直接调用生产类，**未修改任何实现**：

```bash
CP="mini-agent-tools/target/classes;$(cat /tmp/cp-tools.txt)"
javac -encoding UTF-8 -proc:none -cp "$CP" -d tmp/docx-probe/classes tmp/docx-probe/*.java
java -Dstdout.encoding=UTF-8 -cp "tmp/docx-probe/classes;$CP" DocxProbe
```

---

## 1. 实测证据

### 实测-1 `read_file` 能读出 `.env` 里的真实密钥

探针 `PathProbe`：先问 `PathGuard.isAllowed(...)`，再反射调用 `BuiltinTools.readFile(".env", 1, 6)`。

```
=== allowedRoots ===
  D:\AI\miniagent\tmp\docx-probe\out
  D:\AI\miniagent

=== isAllowed ===
  .env            -> true
  .ssh/id_rsa     -> true

=== read_file(".env", limit=6) 实际返回 ===
  [read_file path=***（长度 96）
  1|# 由部署脚本生成 2026-11-04。不要提交（.gitignore 已忽略）。
  2|MYSQL_ROOT_PASSWORD=***（长度 28）
  3|REDIS_PASSWORD=***（长度 28）
  4|MODEL_CONFIG_ENCRYPTION_KEY=***（长度 44）
  5|LLM_API_KEY=***（长度 51）
```

不是"可能读到"，是已经读出来了。值在探针里替换成 `***`，长度说明它是完整密钥。

**成立的前提**：`PathGuard.allowedRoots()` 里的 `D:\AI\miniagent` 来自 `projectRoot()`，即 `System.getProperty("user.dir")`（`PathGuard.java:89-92`）。

- 开发/源码模式：`user.dir` = 项目根，`.env` 就在这里 → 成立。
- 出厂桌面端：`user.dir` = 安装目录，`.env` 通常不在 → 取决于装了什么。

所以不是"出厂必中"，而是"**任何把 secrets 放在应用工作目录旁的部署形态都会中**"，包括开发机、源码模式，以及把 `config.yml` 放安装目录的客户环境（项目根有 `config.example.yml`，说明这是预期用法）。

配套事实：`read_file` 在 `PermissionPolicy.PLAN_SAFE_TOOLS`（`:23`），不在 `ASK_DANGEROUS_TOOLS`，`needsSessionGrant` 对它返回 `false`（`:165-168`）——读它**不需要用户批准**。

---

### 实测-2 工具结果缓存跨工作区串数据

探针 `CacheProbe`：真实 `ToolRegistry` + 真实 `registerFileTools()` + 真实 `execute("read_file", ...)`，只在两次调用之间切换 `WorkspaceContext.setRootOverride()`（这正是会话切换做的事）。

```
=== 会话 A（root=wsA）read_file report.md ===
[read_file path=D:\AI\miniagent\tmp\docx-probe\wsA\report.md ...]
1|A 工作区的机密内容：项目甲

=== 会话 B（root=wsB）read_file report.md ===
[read_file path=D:\AI\miniagent\tmp\docx-probe\wsA\report.md ...]
1|A 工作区的机密内容：项目甲

B 工作区磁盘上的真实内容: B 工作区的机密内容：项目乙
```

会话 B 拿到的 `path=` 是 **wsA**，内容是 A 的。B 自己的工作区里有同名文件，内容完全不同。

**机制**（代码事实）：`BuiltinTools` 是单例 `@Component`（`:51`）；缓存 key = `"read_file|" + params.toJson()`（`:349`），不含 sessionId / userId / workspaceRoot；命中直接返回（`:353`）。

**触发条件**：两个会话绑定不同工作区，且在缓存存活期内调用相同参数。`clearToolCache()` 是全局清空（`:152-156`），由任意会话的新消息触发（`AgentChatApplicationService.java:666`），所以缓存窗口是"上一条消息开始 → 本条消息开始"。

并发前提同样成立：`ChatStreamingService.isBusy(sessionId)` 只拦同一 sessionId 重复启动（`:39-43`），不同 sessionId 可同时运行。

---

### 实测-3 `write_docx` 生成的文件没有 `styles.xml` / `numbering.xml`

探针 `DocxProbe`，调用真实 `WriteDocxTool.handle(...)`。

```
=== 包内文件 ===
  [Content_Types].xml / _rels/.rels / docProps/app.xml / docProps/core.xml
  word/document.xml / word/_rels/document.xml.rels / word/settings.xml

has styles.xml: False
has numbering.xml: False
```

而 `document.xml` 里写的是：

```xml
<w:p><w:pPr><w:pStyle w:val="Heading1"/><w:jc w:val="left"/></w:pPr>
  <w:r><w:rPr><w:b w:val="on"/><w:rFonts w:ascii="微软雅黑" .../><w:sz w:val="48"/></w:rPr>
  <w:t>一级标题</w:t></w:r></w:p>
```

`w:pStyle w:val="Heading1"` 引用了一个**包内不存在**的样式。Word 的样式定义在 `word/styles.xml`，编号定义在 `word/numbering.xml`，`new XWPFDocument()` 两者都不生成（`WriteDocxTool.java:51`）。

**后果**：标题不会有字号、不会进大纲、不会进自动目录；`ListBullet` 段落没有项目符号。我没有 Word 可以验证"打开时的具体提示"，所以只说到这一步，不写"Word 会报错"。

---

### 实测-4 `write_docx` 每个段落写出两个 `<w:pPr>`

同一份 `probe.docx`：

```
=== 每个 w:p 里 w:pPr 出现次数 ===
  段落0: pPr=1  一级标题
  段落1: pPr=2  这是正文段落，包含**重点**内容。
  段落2: pPr=1  第一项
  段落3: pPr=1  第二项
  段落4: pPr=2  3. 第三项
```

**规范依据**：ECMA-376 中 `CT_P` 的 `pPr` 元素为 `minOccurs="0" maxOccurs="1"`。两个 `<w:pPr>` 违反该约束。

**成因**（`WriteDocxTool.java:226-234`）：`setAlignment()` 内部经 `getCTPPr()` 创建了第一个 `pPr`，随后无条件 `paragraph.getCTP().addNewPPr().addNewSpacing()` 又加了一个。

**后果**：行间距写进第二个 `pPr`，读取方按规范只认第一个 → 行间距参数不生效。这条是"确定失效"。

顺带实测到：`**重点**` 没有被解析成粗体，星号原样留在正文（段落 1 的 text）。`WriteDocxTool.java:130-136` 只认"整行以 `**` 开头且以 `**` 结尾"。

---

### 实测-5 `html_to_docx` 打乱段落顺序，并留下一段标签残渣

输入：`h1 第一章总则` → `p 段落A` → `ul 两项` → `p 段落B`。

真实输出：

```
=== html_to_docx 实际段落顺序 ===
  [0] style=Heading1  text=第一章 总则
  [1] style=-         text=段落A：本段在前。
  [2] style=-         text=段落B：本段在后。      ← 跑到了列表前面
  [3] style=ListBullet text=列表项一
  [4] style=ListBullet text=列表项二
  [5] style=-         text=<html><body></body></html>   ← 标签残渣
```

两处都是确定的：

1. **顺序**：`HtmlToDocxTool.java:68-88` 依次跑 `processHeadings` → `processParagraphs` → `processLists` → `processInlineStyles`，每轮命中就立即 append 到文档末尾。文档流顺序由"处理轮次"决定，不由原文位置决定。
2. **残渣**：四轮都没匹配到的 `<html><body></body></html>`，最后被 `if (!text.trim().isEmpty()) addParagraph(document, text, false, false)` 原样写进正文。

返回 `{"success":true,...,"message":"HTML已成功转换为Word文档"}`。

---

### 实测-6 `edit_document` 的 `prepend` 实际追加到末尾

原文档两段；要求 `operation=prepend` 插入"【这段要求插到最前面】"。

```
PREPEND_RESULT={"success":true,...,"message":"文档修改成功"}

=== prepend 之后的实际段落顺序 ===
  [0] 原第一段：这是文档开头。
  [1] 原第二段：这是文档结尾。
  [2] 【这段要求插到最前面】     ← 末尾
```

成因：`EditDocumentTool.java:224-236` 的 `prependToDocx` 注释写了"POI 不直接支持在指定位置插入，这里简化处理"，然后 `document.createParagraph()` —— 追加。

**只实测了 `prepend`。** `delete`（`:241-252` 只 `run.setText("", 0)`，段落保留）和 `smartEdit`（`:274-277` 兜底 `appendToDocx`）是代码事实，未实测，不写成"已验证"。

---

## 2. 代码事实（未实测，附触发条件）

下表每条都能在源码里直接看到。把"必然成立的机制"和"需要前提的后果"分开写。

| # | 位置 | 机制（确定） | 后果成立的前提 |
|---|---|---|---|
| 1 | `McpStdioClient.java:218-261` | 先 `tryAcquire` 槽位，再 `synchronized writeFrame` 直写 `process.getOutputStream()`，写无超时 | 子进程停止读 stdin（自身死锁/被挂起/处理超大参数），管道缓冲（约 64KB）写满 |
| 2 | `McpStdioClient.java:125-143` | `tools/list` 只读 `result.tools`，不读 `nextCursor` | 服务器返回分页结果 |
| 3 | `McpStdioClient.java:165-177` | 超时后只清本地 `pending`，不发 `notifications/cancelled` | 服务端执行超时请求且该请求有副作用 |
| 4 | `McpStdioClient.java:411-423` + `ToolRegistry.java:327-335` | `inputSchema` 只复制 `type`，丢 `enum`/`items`/`$ref`/嵌套 `properties`；`object` 在 toSpecification 落到 `default ->` 渲染成 string | MCP 工具参数含嵌套对象或 `$ref` |
| 5 | `ImageGenerationService.java:863-881` | `HttpTimeoutException` 与 `Exception` 两条路径都 `findRecentGeneratedImage(imageDir, 600)`，只按 `lastModifiedTime` 选最新 | 该目录 10 分钟内存在任意一张旧图 |
| 6 | `BrowserService.java:728,825` | 全局单线程 actor（`1,1` + `ArrayBlockingQueue(64)`），调用方 `pending.get()` 无超时；`page.evaluate` 无超时（`:3064`） | 某次页面操作在 CDP 层不返回（evaluate 死循环、渲染进程挂起） |
| 7 | `BrowserService.java:2390-2411` | `type` 写完直接返回成功，不读回 `value`；对照 `select`（`:2256`）、`setChecked`（`:2305`）、`upload`（`:2350`）都有回读 | 页面 JS 回滚/截断/拒绝写入 |
| 8 | `WriteDocxTool.java:76`、`WritePptxTool.java:53`、`WriteXlsxTool.java:76`、`HtmlToDocxTool.java:44` | `new FileOutputStream(target)` 直写，非临时文件 + rename | 序列化过程中超时/异常/进程被杀 |
| 9 | `WriteDocxTool.java:332-339` | 页脚 `createRun()` 三次，中间那个空 run 本该是 `PAGE` 域 | 开启页眉页脚 |
| 10 | `WriteXlsxTool.java:110-111` | `createCellStyle()` / `createFont()` 在表头循环内逐格创建 | 表头列数多（Excel 单簿样式上限 64,000） |
| 11 | `WriteXlsxTool.java:132-133` | `Number` 一律 `doubleValue()` | 19 位订单号/长整型 ID |
| 12 | `WriteDocumentTool.java:97-102` | `content.contains("<")` 决定走 HTML 分支 | 内容含 `<`（`a < b`、`<项目名>`） |
| 13 | `WriteDocumentParams.java:115` | `getDocumentStyle()` 有定义，**无调用点**（grep 仅定义处） | 模型传了 style/fontFamily/fontSize 等参数 |
| 14 | `BuiltinTools.java:1891-1909` | `listFiles` 无条目/深度上限，`Files.walk`/`Files.list` 未关闭 | 目录很大（node_modules、整个盘） |
| 15 | `BuiltinTools.java:1731-1744` | `readPackage` 的 `maxChars` 是**每文件**上限，无总预算 | 包内文件多 |
| 16 | `BuiltinTools.java:515-537` | `readAllBytes()` 在 `waitFor(30s)` **之前**，超时判定在阻塞读之后 | rg 卡住不输出 |
| 17 | `ContextContributorConfiguration.java:90-96` | `getSkillListSummary()` 裸拼进 `ContextSlot.SKILLS`；同文件 MEMORY 有 `MEMORY_UNTRUSTED_HEADER` 包装 | 有人能写 `skills/SKILL.md`（模型经 `skill_manage`，或本地进程） |
| 18 | `WebSearchService.java:753,794,832,540` | Bing/Google/DDG/Tavily-Extract 都不检查 `statusCode` | provider 返回 401/429/5xx |

---

## 3. 对上一版的修正

斌哥质疑"有些问题不存在"，复核后确实有需要改的：

| 上一版说法 | 复核结果 |
|---|---|
| "一次批准在 300s 内放行同工具的任意调用（任意目标域/文件）" | **错，撤回。** `SessionPermissionStore.isAskGranted` 是 tool + `argumentDigest` 精确匹配（`:751-756`）。按工具名放行的是用户显式点选的 `alwaysAllowedTools`，会话级、可撤销。这块设计是对的。 |
| "密钥外带需要 `http_post` 批准" | **不准确。** `http_get` 同样在 `PLAN_SAFE_TOOLS` 且免批准，GET 也能带 query。但"外带"没实测，降级为推断。 |
| "浏览器一个页面挂死所有会话" | **降级。** 机制确定（单 actor + 无超时），但需要"CDP 调用不返回"这个前提。没实测。 |
| "MCP 会死锁" | **降级为条件成立。** 机制确定，触发条件是子进程不读 stdin。没实测。 |
| "docx 打开会提示修复" | **撤回。** 我没有 Word，无法验证。只保留"违反 ECMA-376 的 maxOccurs=1"和"行间距不生效"这两个可验证结论。 |
| "P0-01 是零交互外带链" | **改为条件表述**：`read_file` 读 `.env` 已实测；能否外带取决于模型是否被诱导发出一条 GET，未实测。 |

上一版把"代码里存在某机制"直接写成"线上会发生某事故"，这是我的错。本版 18 条代码事实都补了触发条件。

---

## 4. 对标：同一件事，生产级要求是什么

只列有明确出处的，不写"某某产品内部怎么实现"（我无法验证它的源码）。

| 维度 | 这里的实现 | 生产级要求 | 出处 |
|---|---|---|---|
| OOXML 段落 | 一个 `<w:p>` 两个 `<w:pPr>` | `CT_P.pPr` 为 `maxOccurs="1"`，样式必须由 `styles.xml` 的 `docDefaults`/`w:style` 定义 | ECMA-376 Part 1 |
| 文档落盘 | `new FileOutputStream(target)` 直写 | 写临时文件 → 校验 → 原子替换；项目内已有 `AtomicFiles`，`EditDocumentTool` 已在用 | POSIX `rename(2)` 语义 |
| HTML→文档 | 4 轮正则抽取，按轮次追加 | 解析成 DOM/AST 后单趟按文档流输出；不支持的元素显式报告丢弃 | — |
| MCP 取消 | 超时只清本地 `pending` | 超时必须发 `notifications/cancelled` 携带 `requestId` | MCP 规范（Cancellation） |
| MCP 工具发现 | 只读一页 `result.tools` | 循环读取 `nextCursor` 直到为空 | MCP 规范（`tools/list` 分页） |
| MCP 参数契约 | 自造 6 种类型映射，`object` 渲染成 string | `inputSchema` 是 JSON Schema，应原样下发并用通用校验器 | MCP 规范（`inputSchema`） |
| MCP 传输 | 写端 `synchronized` 直写管道，无超时 | stdio 传输写端必须处理背压：独立写队列 + 写超时 + 超时即判定连接失效 | MCP 规范（stdio transport） |
| 浏览器 JS 执行 | `page.evaluate(expression)` 无超时 | `evaluate` 不接受 timeout 选项，超时只能靠外部中止执行上下文；因此必须能销毁/重建 context | Playwright Java API 签名 |
| 表单写入 | `fill` 后直接报成功 | 写入类操作必须回读 `value`/`checked`/`selectedValues` 并与意图比对（同文件的 `select`/`setChecked` 已这么做） | 不变量：写操作需可观察确认 |
| 异步生图恢复 | 按 `lastModifiedTime` 在目录里猜最新文件 | 结果必须按任务句柄/`request_id` 回查，不得用"目录里最新的产物"替代请求关联 | 异步任务通用约束 |
| 工具结果缓存 | key = 工具名 + 参数 | 多租户/多工作区下，key 必须含租户、会话、路径根中至少一项 | 多租户不变量 |
| 文件访问 | 根收敛（workspace / data / `user.dir`） | 根收敛 **+** 敏感文件 deny（`.env*`、`*.pem`、`*.key`、`.ssh/**`、`.aws/**`） | 密钥管理通用要求 |
| Excel 样式 | 每格 `createCellStyle` | 样式对象复用；单簿唯一格式上限 64,000 | Excel 规格限制 |
| 工具输出 | `list_files` / `read_package` 无上限 | 结果必须有条目数或字节数上限，并显式标注 truncated | 上下文预算通用约束 |
| 技能文本 | 裸拼进系统提示 | 模型可写/磁盘可写的数据进入提示时，必须包在不可信数据边界内 | 数据/指令分离 |

---

## 5. 修复优先级

按"证据等级 × 影响面"排，不按代码量。

**第一批（已实测，改动小）**

1. `BuiltinTools` 缓存 key 加 `WorkspaceContext.getRootOverride()` + 会话标识；`clearToolCache` 按会话清。实测-2 已证明现在会串。
2. `PathGuard` 加敏感文件 deny 名单。实测-1 已证明 `.env` 可读。
3. `WriteDocxTool` 的 `addNewPPr` 改为 `setSpacingBetweenLines`。实测-4 已证明双 `pPr`。
4. `WriteDocxTool` 用模板 docx 作基线（带 `styles.xml`/`numbering.xml`）。实测-3 已证明缺样式。
5. `EditDocumentTool.prependToDocx` 改成返回 `unsupported`。实测-6 已证明它追加到末尾。

**第二批（代码事实，需起进程/起浏览器才能验证）**

6. `McpStdioClient` 写端独立线程 + 写超时。
7. `BrowserService` 按会话拆 actor + 操作硬超时 + `type` 回读。
8. 文档四件套改 `AtomicFiles` + 纳入撤销账本。
9. `html_to_docx` 换 DOM 单趟遍历；修掉残渣段落（实测-5）。
10. `findRecentGeneratedImage` 删除。

**第三批（协议合规与契约）**

11. MCP：`nextCursor` 分页、`cancelled` 通知、`inputSchema` 原样透传。
12. `web_search` 状态码 + 重试退避。
13. 技能摘要套不可信包装。

---

## 6. 附：复现方式

探针源码在 `tmp/docx-probe/`：

| 探针 | 验证内容 |
|---|---|
| `DocxProbe.java` | 实测-3/4/5/6（docx 包结构、双 pPr、HTML 顺序、prepend） |
| `PathProbe.java` | 实测-1（`PathGuard` 判定 + `read_file` 返回） |
| `CacheProbe.java` | 实测-2（跨工作区缓存） |

三个文件只调用生产类，不改实现。`tmp/` 已在 `.gitignore` 中，清理直接删 `tmp/docx-probe/`。
