# MiniAgent 严重缺陷复核（2026-10-08）

> 复核对象：`D:\AI\miniagent` 全部 7 个 Java 模块（578 文件 / 约 7 万行）
> 方法：4 路并行分模块审计 → **每一条都回源码逐行坐实**（下文全部给出真实行号与代码事实）
> 与既有审计的关系：项目已有 `docs/agent-production-gap-audit.md`（8 P0 + 18 P1 + 45 P2/P3）
> 与 `docs/audit/*.md`。本报告只回答两件事：**① 已修的到底修没修；② 还有哪些文档没记过的**

---

## 0. 结论

| 项                | 结果                                                                                                                               |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| 已修抽查（反向验证）       | P0-2 `PathGuard` 已接入 4 个工具类 ✓ ｜ P0-7 `portal-confirm-enabled: false` 默认关 ✓ ｜ P0-8 compose 强制 `${VAR:?}` ✓ ｜ P0-5 幂等键已带参数摘要（主路径）✓ |
| **本轮新发现（文档未记录）** | **12 条**，其中 P1 级 4 条                                                                                                             |
| 文档已记、本轮复核**仍存在** | 11 条 P1 + 若干 P2                                                                                                                  |

**最需要立刻处理的是 3 个「任意文件」入口**（第 1 节 #1/#2/#3）—— 它们绕过了 P0-2 刚建的 `PathGuard`，
等于 P0-2 的修复只覆盖了 `read_file/edit_file` 等主链路，**旁路还在**。

---

## 1. 新发现 · P1（安全 / 数据正确性，文档未记录）

### #1 `ImageQualityChecker` 可读任意路径文件并外送模型；质检失败一律判「通过」

- 位置：`mini-agent-tools/.../agent/comfyui/ImageQualityChecker.java:186-200`（解析）、`:137`（读取）、`:167,181`（假通过）
- 代码事实：

```java
// :186-200  解析：绝对路径直接放行，无 PathGuard
private Path resolvePath(String imagePath) {
    Path path = Path.of(imagePath);
    if (Files.exists(path)) { return path; }        // ← 任意绝对路径
    ...
}
// :137  读到内存 → :152-153 base64 → :165 作为图像内容发给 LLM
byte[] bytes = Files.readAllBytes(path);
...
String dataUrl = "data:" + mimeType + ";base64," + base64;
ChatResponse resp = EffectiveModelContext.chatOr(chatModel).chat(...);
// :181  异常兜底
return "{\"pass\":true,\"score\":5,\"issues\":[\"质检异常: ...\"],...}";
```

- 为什么是 bug：工具参数 `image_path` 由模型控制（可被提示注入），同模块的 `read_file`/`edit_file`/`codebase_search`
  全部强制过 `PathGuard`，**只有这里和 ComfyUI 没有**。传 `C:\Users\<u>\.ssh\id_rsa` 即可让文件字节经
  base64 发往模型提供方 → 任意文件读取 + 外泄。另外质检模型调用失败/抛异常时返回 `pass:true`，
  上层 `autoQualityCheck` 与用户看到的都是**假阳性**。
- 修法：`resolvePath` 改用 `PathGuard.assertAllowed(...)`（与该模块其它工具一致）+ 文件大小上限
  （如 ≤10 MB，超过直接拒绝）；异常分支返回 `pass:false` 或显式 `unknown`，不要用 `true` 掩盖。

### #2 `ComfyUIService.uploadImage` 任意路径读取 + 无大小上限

- 位置：`mini-agent-tools/.../agent/comfyui/ComfyUIService.java:621-630`
- 代码事实：

```java
public String uploadImage(String imagePath) {
    java.io.File file = new java.io.File(imagePath);     // 任意路径
    if (!file.exists()) { return error(...); }
    byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());   // 无上限
```

- 为什么是 bug：`comfyui_execute(action=upload)`、`comfyui_img2img`、`comfyui_img2video` 都把模型给的
  `image_path` 直接交到这里。既无路径约束（可读主机任意文件并上传到本机 ComfyUI），又把整文件读入堆
  （超大文件直接 OOM）。
- 修法：同上过 `PathGuard` + 大小上限；顺手把 `Content-Type` 从写死的 `image/png` 改成按扩展名判定。

### #3 `SkillStore` 路径穿越 —— 读 / 写 / 删三个方向都通

- 位置：`mini-agent-tools/.../agent/skill/SkillStore.java:291-294`（读）、`:118-123`（写）、`:203-220`（删）
- 代码事实：

```java
// :291-294  读：name 未净化
private Path findSkillMd(String name) {
    Path direct = skillsDir.resolve(name).resolve("SKILL.md");
    if (Files.exists(direct)) { return direct; }
// :118-123  写：只有 name 过了 sanitizeName，category 原样拼
String cleanName = sanitizeName(name);
Path skillDir;
if (Objects.nonNull(category) && !category.isEmpty()) {
    skillDir = skillsDir.resolve(category).resolve(cleanName);   // ← category 未净化
}
// :203-212  删：findSkillDir(name) 命中后递归删
Path skillDir = findSkillDir(name);
...
deleteRecursively(skillDir);
```

- 为什么是 bug：`skill_manage` 的 `name` / `category` 都是模型可控参数。
  `createSkill(category="..\\..\\Users\\abc\\x")` → 把 `SKILL.md` 写到 skills 目录之外；
  `skill_view(name="../../some/dir")` → 读任意 `SKILL.md`；`deleteSkill(name="../../proj")` → **递归删除**
  任意含 `SKILL.md` 的目录。
- 修法：`resolve` 之后统一 `normalize()` + `startsWith(skillsDir)` 断言（三处都要）；
  `name`/`category` 走同一套白名单正则（如 `[A-Za-z0-9._-]+`，禁止 `.` 与路径分隔符）。

### #4 `DefaultConsolidationService`：事务内做远程 LLM 调用 + 吞异常不回滚

- 位置：`mini-agent-app/.../agent/memory/lifecycle/DefaultConsolidationService.java:86-97`、`:135-149`
- 代码事实：

```java
// :86-97  事务边界包住整个巩固流程
@Override
@Transactional
public void consolidate(String sessionId) {
    try (var ignoredModel = bindSessionModel(sessionId)) {
        doConsolidate(sessionId);        // 同类自调用，仍在同一事务内
    }
}
// :107 → extractEpisode → llmExtract → model.chat(...)  ← 同步 HTTP（readTimeout 1200s）
// :135-149
try {
    AgentEpisodeEntity episode = extractEpisode(unprocessed, workingMemory);
    if (episode != null) { episodeRepository.save(episode); ... }
    eventRepository.markProcessed(ids);
} catch (Exception e) {
    log.error("巩固失败: session={}", sessionId, e);      // ← 吞掉，不 rethrow
}
```

- 为什么是 bug：两个独立缺陷叠在一处。
  ① **长事务占连接**：`@Transactional` 内发起对 LLM 的同步 HTTP（模型 `readTimeout` 1200s = 20 分钟），
  一次 session 巩固可让 Hikari 连接被占 20 分钟；触发源是每 10 分钟、批量最多 50 个 session 的
  `EventDrivenConsolidationWorker`（串行）→ 连接池被打满，全站写路径阻塞。
  ② **吞异常 → 不回滚 → 重复 Episode**：Spring 只在异常冒泡时回滚。`episodeRepository.save` 成功而
  `markProcessed` 失败时，Episode 已落库、事件仍 `processed=false`，下一轮 Worker 再取同一批事件
  **再生成一遍**，污染后续检索与提示注入。
- 修法：把 LLM 调用与持久化拆到**两个事务**（先在无事务方法里算 Episode，再进 `REQUIRES_NEW` 事务
  落库 + 标记；或干脆整体脱离事务，用显式补偿）；`catch` 里必须 `throw` 或调用
  `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`。

---

## 2. 新发现 · P2（一致性 / 资源 / 静默失败）

| #  | 位置                                                                  | 代码事实                                                                                                                              | 后果                                                                                                                                                          |
| -- | ------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 5  | `mini-agent-account/.../AccountAuthService.java:86,113-127`         | `@Transactional register()` 内先 `createPersonalTenant(name)` 落库，再 catch `DataIntegrityViolationException` **直接 return**（不 rethrow） | 并发重名注册时事务照常提交 → 每次留下一条**孤儿租户** `u-<username>`                                                                                                               |
| 6  | `mini-agent-account/.../MembershipService.java:320-341`             | 先 `findByUserIdAndStatus(ACTIVE)` 把旧订阅置 `SUPERSEDED`（:320-324），**之后**才 `tenants.findByIdForUpdate(tenantId)` 加锁（:341）             | 锁在读旧订阅之后才拿：两笔订单并发 `applyPlan` 各自读到同一批旧订阅、各自插入新 ACTIVE → 同一用户**两条 ACTIVE**，破坏类注释承诺的不变式                                                                       |
| 7  | `mini-agent-planner/.../PlanningLoop.java:349,615`                  | `while (rounds++ < getMaxOuterRounds())` 跑满后 `rounds == max`，随后 `if (rounds > getMaxOuterRounds())`                               | 恒 false → 轮次耗尽分支**永不触发**：`metrics.outerTimeout()` 不累加、原因码保留默认 `NO_READY_NODES`（应 `>=`）。任务仍会返回 `unfinished`，但**原因码与指标失真**                                    |
| 8  | `mini-agent-planner/.../GraphScheduler.java:95-99`                  | 注释写「宁可让键里带上随机串」，代码 `return "undigestible";`（固定常量）                                                                                 | 序列化异常时同一 `planVersion+nodeId` 的**所有**动作算出同一幂等键 → 改正参数后的重试被 `ToolPipeline` 判重**静默跳过**、节点仍标成功。这正是 P0-5 要消灭的失败模式，修 P0-5 时留了这个缺口                                |
| 9  | `mini-agent-account/.../DesktopLoginTicketStore.java:21-38`         | `issue()` 只 `put`，只有 `consume()` 才 `remove`，**无任何定时/惰性清理**（TTL 只在消费时判）                                                            | 每次 `issue()` 泄漏一条记录；该接口可被反复调用 → map 无界增长                                                                                                                    |
| 10 | `mini-agent-loop/.../SessionEventCenter.java:91-92,201-214,466-472` | `SessionChannel.think/answer` 是**裸 `StringBuilder`**（无同步）；循环线程 append、HTTP 线程 `toString()` 重连重放                                   | 并发 append+toString 会产生撕裂字符串或抛 `StringIndexOutOfBounds`；异常被 `sendEvent` 吞掉后 `clients.remove(client)` 会把**刚重连成功的客户端误摘掉**。（文档 `:328` 只记了「answer 无上限」，没记跨线程不安全） |
| 11 | `mini-agent-tools/.../tool/EditDocumentTool.java:84-86,123-125`     | 两处 I/O 都不是 try-with-resources；且 `new FileOutputStream(path)` 先截断目标文件，再 `document.write(fos)`                                      | 构造/写入抛异常时：文件句柄泄漏；**原文档已被截断且无回滚** → 用户文档损坏                                                                                                                   |
| 12 | `mini-agent-common/.../milvus/SharedMilvusClient.java:39-44`        | `get()` 有 `synchronized`，`@PreDestroy close()` **不加锁、不把 `client` 置 null**                                                         | 关闭与应用在用线程并发时：`close()` 关闭后 `get()` 仍返回已关闭客户端，或新连接创建后永不被关闭（`LocalOnnxEmbeddingModel.close()` 同型：`initialized=false` 复用「未初始化」标志 → 关闭后调用会**重新创建**会话）           |

### 顺带核对的两条「疑似」→ 已排除

- `TaskScopeRegistry.bySession`（`:103,116-125`）确实无界，但**有持久化实现时走 `cache()` 且按 sessionId 收敛**，
  只在 `persistence == null` 的进程内回退模式才单调增长 → 归入第 3 节的泄漏簇，不单列。
- `FilePlannerStatePersistence:104-105` 非原子写（无 temp+rename）、`:51-54` 读失败吞成 `Optional.empty()`
  → **文档已记**（`docs/audit/planner-delegation-audit.md:700`，P2），见第 3 节。

---

## 3. 文档已记、本轮复核**仍存在**（未修）

| 原编号           | 现状（本轮复核方式）                                                                                                                                                                                                                         |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P1-4 泄漏簇      | `TokenUsageTracker.clear()` 与 `ContextCompressor.clearSession()` **全仓零调用者**（grep 实证）；`TaskScopeRegistry.forget()` 仅测试调用；`ExecutionControl.leases` 仍可被 `cancel/isCancelled` 灌入 fallback 租约，且 fallback `tenantId=null` 会**静默跳过租户配额** |
| P1-13 取消/断连   | `SessionEventCenter.java:227-229` 仍在 `onCompletion` 只 `detachClient`，**无任何 `executionControl.cancel(...)`**；`:175-190` 仍在事件线程同步 `client.send(...)`（无背压）；主聊天流仍无心跳/`Last-Event-ID`                                                   |
| P1-11 共享租户    | 本地注册仍把用户塞进同一个无限额租户                                                                                                                                                                                                                 |
| E-1 限流        | `RateLimitFilter.java:37` 的 `windows` 全类**无 `remove()`**；超 10000 条后**任何新 key 永久 429**（我逐行确认，与文档 E-1 描述一致）                                                                                                                          |
| A-4 MCP       | `McpStdioClient` 的 `pending` 超时不清理、`Content-Length` 不校验、头部行无上限（文档 A-4 已记）                                                                                                                                                          |
| B-6 skills    | `skill_manage` 仍是模型可写的持久指令通道（其路径穿越是本报告 #3）                                                                                                                                                                                         |
| 其余 P1         | P1-1 自证验收、P1-5 全量工具面、P1-9 记忆跨租户/无删除、P1-12 可观测性（追踪断链/无指标）、P1-14 无界查询与 N+1、P1-15/16/17/18 —— 文档已记，本轮未逐条复验（**未复验 ≠ 已修**，按文档口径视为存在）                                                                                                    |
| planner 非原子落盘 | `FilePlannerStatePersistence` 非原子写 + `load` 吞解析异常（`planner-delegation-audit.md:700`，P2）                                                                                                                                            |

---

## 4. 建议修复顺序

| 批次                | 内容                                                                     | 理由                                                 |
| ----------------- | ---------------------------------------------------------------------- | -------------------------------------------------- |
| **第 1 批（安全，优先）**  | #1 #2 #3 —— 三个「任意文件」入口全部过 `PathGuard` + 大小上限；#3 同时净化 `category`/`name` | P0-2 的 `PathGuard` 已建好，这三个是**旁路**，不堵等于白修；#1 还有数据外泄 |
| **第 2 批（数据一致性）**  | #4 拆事务（LLM 调用移出事务 + 异常显式回滚）→ #5 → #6                                   | 都是"写错了但提交了"这一类，数据错了比慢更贵                            |
| **第 3 批（静默失败收口）** | #7 #8 —— 把所有「静默跳过 / 静默不执行 / 原因码失真」换成显式失败或显式降级                          | 与 P0-5 同一主题，收干净才叫修完                                |
| **第 4 批（资源）**     | #9 #10 #11 #12 + P1-4 泄漏簇（加 TTL/上限清理）                                  | 长跑才暴露，但不修迟早 OOM / 句柄耗尽                             |

---

## 5. 口径说明（未证实项，别当已发生）

1. **#4 长事务的实际影响**：取决于 Hikari `maximum-pool-size` 与巩固并发度，本轮按**代码结构**判定
   （事务确实包住远程调用），未跑压测。
2. **#4 的重复 Episode**：需要「`episodeRepository.save` 成功 + `markProcessed` 失败」这个部分失败组合，
   不是每次必现。
3. **#1 的外泄范围**：字节**确实**离开主机（base64 进 `data:` URL 发给模型提供方）；
   模型是否"看懂"该文件不影响外泄成立。
4. **#6**：需要同一用户两笔订单并发确认；**读锁序即为证据**（先读后锁），无需复现。
5. 第 3 节标「未逐条复验」的项，按既有文档口径视为存在，**不要**据本报告认为它们已修。

---

## 6. 修复方案（已批准 · 2026-10-09 补充）

> 本节为动手前的方案；**实际落地与实测证据见第 7 节**。方案在实施中有一处修正（见 7.4）。

**总原则：复用既有设施，不新增并行机制，不在代码里出现任何路径/大小/时长的字面量。**

现状盘点（决定了方案形态）：

| 事实 | 出处 | 对方案的含义 |
|---|---|---|
| 清理方法**已存在**，只是零调用方 | `ContextCompressor.clearSession`、`TokenUsageTracker.clear`、`TaskScopeRegistry.forget`、`ExecutionControl.finish` | Bug 3 是"把线接上"，不是"新写回收逻辑" |
| 会话删除路径**已在做派生态清理** | `ConversationService.deleteConversationForUser`（`:58`）→ `deleteSessionHistoryVectors`（`:70`） | 事件发布点选它，扩展既有职责 |
| `@EventListener` 是项目既有惯例 | `McpToolBridge:40`、`DesktopReadyReporter:34` | 用 Spring 事件，不引新框架 |
| `@Scheduled(fixedDelayString = "${...:默认}")` 是既有惯例（3 处） | `EventDrivenConsolidationWorker:48`、`MemoryIndexOutboxService:117`、`MemoryDailyAnalysisService:50` | 兜底 TTL 扫描沿用同一写法 |
| 段校验器已存在但**在 app 模块** | `MiniAgentChatPageController.safePathSegment:570`、`FileStorageService.safeExtension:209` | tools 不能依赖 app（pom 已证）→ 上移到 common |
| 原子落盘写法**已有 3 份重复** | `MemoryStore:299-304`、`:607-609`、`ConversationStore:184-187` | 抽一个实现，EditDocumentTool 用它（顺带去重） |
| `PathGuard` 根 = workspace + `miniagent.data.dir` + 项目根 + `agent.tools.allowed-read-roots` | `PathGuard:63-90` | 放行**只由配置驱动**，不需要任何新硬编码清单 |

### Bug 1 · `EditDocumentTool` 写坏用户文档

- **根因**：① 两处 I/O 非 try-with-resources；② `new FileOutputStream(path)` 先截断目标再 `document.write`，
  中途失败即"原文件已空、新内容没写进去"，无回滚。
- **落点**：新增 `mini-agent-common` 的 `AtomicFiles.write(Path target, IOConsumer<OutputStream>)`：
  同目录 `createTempFile`（前缀由**目标文件名派生**）→ 写 → `flush` → `ATOMIC_MOVE + REPLACE_EXISTING`；
  任一环节失败删临时文件、**原文件一个字节都不动**。
- `editDocx` 改为 try-with-resources（`XWPFDocument` 本身 `Closeable`），写入走 `AtomicFiles.write`。
- **不硬编码**：临时文件名由目标派生；无路径/大小常量；不引入新配置项。

### Bug 2 · 三个「任意文件」入口

- **统一原则（收口而不是过滤）**：这三个工具的业务语义都是"**媒体库里的图**"，不是"主机上的任意文件"。
  所以参数只接受两形态：媒体相对路径（`MediaStorage.resolve`）或落在**允许根**内的路径（`PathGuard`），
  其余一律拒绝，拒绝文案复用 `PathGuard` 现有那句（含 `agent.tools.allowed-read-roots` 放行指引）。
- **落点**：
  1. 新增 `LocalMediaReader.read(Path)`（tools 模块内）：`PathGuard.assertAllowed(p, "read")` →
     大小上限 → **magic bytes 判类型**（不信任扩展名）→ 返回字节。
     `ImageQualityChecker` 与 `ComfyUIService.uploadImage` 都改走它 —— 这一条同时挡掉
     "把 `id_rsa` 改名 `.png` 送出去"。
  2. 大小上限**单点定义**：新增 `agent.tools.local-media.max-bytes`，默认值与既有
     `file.extract.max-bytes`（50MB）同口径，代码里不出现数字。
  3. `ImageQualityChecker` 的异常/失败兜底由 `pass:true` 改为**显式失败**（fail-closed）——
     否则修好读取也仍在骗上层。
  4. `SkillStore`：`name` 与 `category` 都过**同一个**段校验器（`[A-Za-z0-9._-]+`；
     拒 `.`/`..`/分隔符/绝对路径），`resolve` 后再加 `normalize() + startsWith(skillsDir)` 兜底断言（双保险）。
     校验器由 `MiniAgentChatPageController.safePathSegment` 上移到 `mini-agent-common`，
     app 与 tools 共用一份（app 那两处改为委托）。
- **不硬编码**：不列死目录名、不写死白名单；放行权在配置；大小走配置；类型判定用文件真实内容。

### Bug 3 · 内存泄漏簇（桌面档最慢性）

两条腿，都不写死：

1. **事件驱动（主）**：新增 `SessionEvictedEvent(sessionId, reason)`（放 `mini-agent-common`，
   所有模块都能订阅）。发布点挂在**既有清理路径** `ConversationService.deleteConversationForUser`。
   订阅方各自 evict，**全部调用已存在的方法**：`ChatMemoryConfig`（新增 `remove`）、
   `ContextCompressor.clearSession`、`TokenUsageTracker.clear`、`ExecutionControl.finish`、
   `TaskScopeRegistry.forget`。
2. **空闲兜底（次）**：沿用既有 `@Scheduled(fixedDelayString = "${...:默认}")` 惯例加一个"会话态回收"任务，
   按**空闲时长**回收（TTL 走配置，`UsageStats.lastUpdated` 已存在可直接用）。
   覆盖"用户没删会话、进程也不重启"的场景 —— 正是你桌面档点 × 只隐藏的常态。
3. 顺带（可选）：`ExecutionControl` 的 `cancel/isCancelled` 不再 `computeIfAbsent` 造 fallback 租约；
   fallback 不带 `tenantId` 时不再静默跳过租户配额（属 P1-11 口径，要不要一起做你定）。

### 验证方式（每条都按"行为边界"断言，不写"看起来对"）

| 断言 | 怎么测 |
|---|---|
| 写失败不毁原文件 | 造一个写入必失败的场景（只读属性/注入的 Writer 抛异常），断言**原文件字节与哈希不变** |
| `../` 与绝对路径被拒 | `SkillStoreTraversalTest`：`../x`、`..\\x`、`/etc/x`、`C:\\x` 全部拒绝，且消息含允许根 |
| 非图片文件不能外送 | 把 `id_rsa` 伪装成 `.png`，断言被 magic bytes 判定拒绝 |
| 质检失败不再假通过 | 让模型调用抛异常，断言返回 `pass:false`（或 unknown），不是 `true` |
| 删会话后内存归零 | 删会话 → 断言 `ChatMemoryConfig.getAllSessionIds()`、`TokenUsageTracker.getAll()` 等**不再包含该 session** |
| 空闲回收 | 注入假时钟推进超过 TTL，断言条目被回收 |

**改动规模**：2 个新公共类（`AtomicFiles`、段校验器）+ 1 个事件类 + 1 个配置项 + 6 处调用点改造，
可拆 3 个独立提交（Bug 1 / Bug 2 / Bug 3），互不依赖。

---

*复核范围：`mini-agent-common / memory / tools / loop / planner / app / account` 7 个模块。
四路审计的原始候选共 43 条，经回源核对后：确证 12 条新缺陷、剔除 6 条（无害清理路径 / 未达门槛）、
其余 25 条落入文档已记范围（其中 11 条 P1 复核仍在）。*

---

## 7. 实施结果（2026-10-09）

### 7.1 新增文件

| 文件 | 模块 | 职责 |
|---|---|---|
| `com/miniagent/common/io/AtomicFiles.java` | common | 原子替换落盘：同目录临时文件 → 写 → `ATOMIC_MOVE + REPLACE_EXISTING`，失败删临时文件、原文件零改动 |
| `com/miniagent/common/SafeSegments.java` | common | 路径段校验 `[A-Za-z0-9][A-Za-z0-9._-]{0,99}`；"首字符必须字母数字"一条同时挡掉 `.` / `..` / 隐藏名 / 绝对路径 |
| `com/miniagent/common/event/SessionEvictedEvent.java` | common | `(sessionId, reason)`，`reason = DELETED \| IDLE`；`isDeleted()` 供订阅方分流 |
| `com/miniagent/agent/tool/LocalMediaReader.java` | tools | 本机图片读取唯一入口：路径闸 → 大小闸 → magic bytes 类型闸 |
| `com/miniagent/agent/memory/lifecycle/SessionIdleReaper.java` | app | 空闲会话回收（`@Scheduled`，TTL / 单轮上限走配置） |

### 7.2 修改文件

**Bug 1**
- `EditDocumentTool`：两处 I/O 改 try-with-resources；`editDocx` 与 `editText` 的写入全部走 `AtomicFiles.write`（原 `Files.writeString` 是先截断语义）。
- `path`/`content` 等被复用的局部变量抽成 `final` 快照后再进 lambda（否则编译期就过不去，见 7.4）。

**Bug 2**（收口，不是加过滤）
- `PathGuard`：新增 `resolveInside(root, purpose, segments...)` 与 `resolveRelative(root, purpose, relativePath)` —— 逐段过 `SafeSegments`，拼完再用 `realPathOfNearestExisting` 对真实路径复核一次（挡 junction / 符号链接逃逸，纯词法 `startsWith` 挡不住）。
- `SkillStore`：`createSkill` 的 `category` 由原样拼接改为 `resolveInside`；`findSkillMd` 开头加 `SafeSegments.isValid` 短路 + `resolveInside` 解析；`viewSkillFile` 由词法判断改为 `resolveRelative`。读 / 写 / 删三条路一起收口。
- `SessionIds`：删掉自带的重复正则，委托 `SafeSegments.isValid`（同一规则只留一份）。
- `ImageQualityChecker`：注入 `LocalMediaReader`，三处读取全改走它；**四处 `pass:true` 兜底改为 fail-closed**（模型调用失败/异常 → `pass:false` + `checked:false`）。
- `ComfyUIService.uploadImage`：改走 `LocalMediaReader`，MIME 用嗅探结果、文件名用解析后的 `Path.getFileName()`。

**Bug 3**（把线接到已存在的 evict 方法上）
- `ChatMemoryConfig`：新增 `lastAccess` 计时表 + `evict(sessionId)` + `idleSessions(now, ttl)` + `@EventListener`。
- `ContextCompressor`：`sessionStates` 的键是**任务作用域键**（`sessionId` 或 `sessionId#N`），既有 `clearSession` 只删当前那一个 —— 新增 `evictSession` 按会话整片回收；`ThreadLocal currentSessionId` 只在确实指向该会话时才清（事件同步派发，否则会误清正在服务另一会话的线程）。
- `TaskScope`：新增静态 `belongsTo(scopeKey, sessionId)` —— 键的拼法知识只留一处，避免各回收方各写一遍 `startsWith(sid + "#")`。
- `TaskScopeRegistry`：DELETED → `forget`（连持久化指针）；IDLE → `forgetCached`（**保留**指针，用户回来说「继续」还要用）。
- `ExecutionControl`：新增 `evict(sessionId, dropSignals)`，同时清 `leases` 与 `childToParent` 的两个方向（只删一边会留悬空父指针）；DELETED 才动跨进程信号。
- `TokenUsageTracker` / `SessionPermissionStore` / `LocalSessionHistoryVectorStore`：加 `@EventListener`，各自区分 DELETED / IDLE 深度。
- `ConversationService`：`resetConversation` / `deleteConversation` / `deleteConversationForUser` 三条成功路径统一广播 `SessionEvictedEvent.deleted(...)`（失败只记日志，不回滚已成功的删除）。

### 7.3 实测证据

| 用例 | 结果 |
|---|---|
| `AtomicFilesTest` | 4 / 4 ✅ |
| `SkillStoreTraversalTest` | 6 / 6 ✅ |
| `LocalMediaReaderTest` | 5 / 5 ✅ |
| `ContextCompressorEvictionTest`（新增） | 3 / 3 ✅ |
| `SessionIdleReaperTest`（新增） | 5 / 5 ✅ |
| 相关既有回归（`ContextCompressorHandoffTest`、`ExecutionControlTest`、`DbTaskScopePersistenceTest`、`DbSessionPermissionPersistenceTest`、`MemoryTaskLockTest`） | 9 / 9 ✅ |
| 全模块 `compile` | BUILD SUCCESS（7 模块全过） |

### 7.4 实施中被测试逼出来的两个真问题（方案里没预料到）

1. **`LocalMediaReader.maxBytes` 只在 `@Value` 里给默认值 → 脱离 Spring 就是 `0`**。
   后果是闸门语义反过来：默认**全拒**而不是默认放行。修法是把默认值提到 `static final long DEFAULT_MAX_BYTES`
   常量，注解与字段初始化器共用（`"${agent.tools.local-media.max-bytes:" + DEFAULT_MAX_BYTES + "}"`）。
   教训：凡是「字段初始化即被使用」的组件，默认值不能只写在注解占位符里。
2. **magic bytes 判定统一卡了 `length < 12`** → 6 字节的 `GIF89a` 头被判成"不是图片"。
   修法：每种格式只要求判定自己需要的那几个字节（PNG/GIF 4、JPEG 3、BMP 2、WebP 12）。
   一刀切长度会把合法短输入误判成非法 —— 而误判的后果是拒绝一张真图片。

另有 1 处**用例自身写错**：`SkillStoreTraversalTest.traversalNameCannotReadOutsideSkill` 里
`store.viewSkill("good").contains(...)` —— 不存在的技能本就返回 `null`，拿 `null` 去断言"不含敏感内容"，
会把"没找到"误当成"防住了"。已改为先真的建出 `good` 再断言内容。

### 7.6 全量回归顺带挖出的一个既有缺陷（用例失效，非产品缺陷）

全量 `mvnw test` 在 `PathGuardTest.symlinkEscapeIsRejected` 上失败。**已用 pristine worktree 在 HEAD
（`f318131`）复现同一失败，确认与本次改动无关**（`PathGuard` 的 diff 是 54 行纯新增、0 删除，
`isAllowed` / `assertAllowed` / `realPathOfNearestExisting` 一行未动）。

回源诊断为**用例自身失效**，而且失败信息指向的方向是**反的**：

| 实测（本机 Windows） | 值 |
|---|---|
| `Files.createSymbolicLink(link, outsideDir)` | 未抛异常 |
| `Files.isSymbolicLink(link)` | `false` |
| `Files.isDirectory(link)` | `true`（落盘的是**普通空目录**，不是链接） |
| `link.toRealPath()` | 返回 link 自身 |
| `link/secret.txt` 是否存在 | `false` |
| `cmd /c mklink /J`（兜底分支） | 本来可用，exit 0，`toRealPath()` 正确解析到目标 |
| 用**真** junction 复测 `PathGuard.isAllowed(junction/secret.txt)` | **`false`（拦住了）** |

失败链条：`createDirectoryLink` 一看 `createSymbolicLink` 没抛异常就 `return true`，
于是 ① `mklink /J` 兜底分支**永远走不到**，② 用例开头的 `assumeTrue(false, "跳过")` 也**永远不触发**。
而链接没建成时 `viaLink` 退化成"工作区内一个尚不存在的普通路径"——
这种路径 `isAllowed` 返回 `true` **本来就是正确行为**。断言失败报的是"逃逸没被拦住"，
真因却是"链接压根没建成"。

**结论：`PathGuard` 的真实路径判定是对的，被验证对象没被真正测到。** 已修用例：
判据从"有没有抛异常"改成"解析出的真实路径是否等于目标"（`isWorkingLink`），
不成立则先清掉残留目录再走 junction 兜底。修后 `PathGuardTest` 5/5 通过 ——
**junction 逃逸这条防线从"名义上有测试"变成"实测拦住"。**

这条对本次 Bug 2 有直接意义：`SkillStore` 的越界防护正建立在
`realPathOfNearestExisting` 之上，此前没有任何一条用例真正跑过"真实链接"这条路径。

### 7.7 审计后**有意不动**的部分

| 容器 | 为什么不动 |
|---|---|
| `TokenEstimator.estimateCache` | 键是**文本**不是 sessionId，且已有 `CACHE_MAX_SIZE = 1000` + 超限清空，不是泄漏 |
| `ConversationStore.cache` | 除自身外**零引用**（死代码）；先决定删不删这个类，再谈回收 |
| `TaskTodoStore.todos / suspendedJson / fileMtimes`、`PlannerStateStore.memory / memoryEvents` | 是「任务工作集 + 文件 mtime」的写穿式镜像，其中 `suspendedJson` 直接服务「继续上次任务」。回收时机没读透就动，风险是**把用户的任务清单弄丢** —— 宁可留它多占一点内存 |
| `ChatStreamingService.runningTasks` | `markRunning` / `markIdle` 成对，且被本轮的 `SessionIdleReaper` 当"有没有活儿在跑"的判据读取，改它要连回收逻辑一起动 |

---

*实施范围：`mini-agent-common / tools / loop / app` 4 个模块。
新增 **5 个主类**（`AtomicFiles`、`SafeSegments`、`SessionEvictedEvent`、`LocalMediaReader`、`SessionIdleReaper`）
+ **5 个测试类 / 23 个用例**；改动 **16 个主文件 + 1 个测试文件 + 1 份文档**。
验证：`mvnw test` 全 7 模块 **530 run / 0 fail / 0 err / 0 skip，BUILD SUCCESS**。*
