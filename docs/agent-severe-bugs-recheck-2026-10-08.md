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

*复核范围：`mini-agent-common / memory / tools / loop / planner / app / account` 7 个模块。  
四路审计的原始候选共 43 条，经回源核对后：确证 12 条新缺陷、剔除 6 条（无害清理路径 / 未达门槛）、  
其余 25 条落入文档已记范围（其中 11 条 P1 复核仍在）。*
