# 记忆架构 + 上下文窗口 改造计划

目标：修复记忆架构的全部已知问题，并重新设计上下文窗口，让多轮对话稳定承接用户意图。
决策已定：长期记忆「确定性改进 + 向量检索」一起做；上下文窗口按 **256K** 保守设。

## 设计原则
- 用户隔离用 `ThreadLocal<Long> currentUserId`（复刻已有 `currentSessionId` 模式），避免 7 处调用方全部改签名。
- 统一 token 预算：滑动窗口、压缩器共用同一套 token 估算与上限配置。
- 永远保护「用户原始意图」与 tool_call/result 配对，绝不在压缩/截断时切散。
- 向量检索优雅降级：无 embedding key 时回退为现有全量注入。

---

## 批次 1：低风险、独立项（不改签名）

### #5 修正 TokenEstimator 中文低估
`TokenEstimator.java`：`TOKENS_PER_CHAR=0.5` → 按字符类型分别估算：CJK ~0.7/字，其余 ~0.3/字。图片开销从 85 提到更真实的区间（按是否高清，给 ~700 估）。

### #6 统一上下文窗口上限
- `AgentLoop.MAX_CONTEXT_TOKENS=728000` 与 `ContextCompressor.DEFAULT=16000` 矛盾。
- 统一配置项 `agent.context.max-tokens:256000`、`agent.context.compress-threshold:0.75`。
- `AgentLoop`、`ContextCompressor` 都读这套；压缩在 ~192K 触发，给输出留余量。

### #3 删除废弃空目录
删除 `memory/short-term`、`memory/mid-term`、`memory/long-term`（从未接线）。

### #2 修复 MemoryStore 并发锁域
`loadFromDisk`/`updateMidtermMemory` 当前 `synchronized(this)`，与 `add/replace/remove` 的 `ReentrantLock` 不互斥。统一为单一 `ReentrantLock`（或读写锁），消除 reload 与写入并发的丢更新。

**批次 1 末：mvn compile 验证。**

---

## 批次 2：上下文窗口重设计（#7 #8）

### #7 ContextCompressor：保护意图与 tool 配对
- `PROTECT_FIRST_N`：改为「锚定首个真实 UserMessage（用户原始意图）」，而非机械保护前 2 条（前 2 条可能是 system+taskPlan）。
- 摘要注入：`new UserMessage("【历史对话摘要】…")` → 改用 `SystemMessage`，标注为背景而非用户输入。
- `findTailCutIndex` / 截断：以完整对话轮次为单位，保证不切散 AiMessage(toolReq) 与 ToolExecutionResultMessage。
- 修已知 bug：`tr.text()` NPE 防护；「放弃压缩」分支返回原始 messages 而非已 prune 的列表（396 行）；尾部超大消息丢最新轮的边界问题。

### #8 滑动窗口按轮次/token 截断
- `ChatMemoryConfig` 滑动窗口重建：按 token 预算（统一配置）裁剪，且以完整轮次为边界。
- `AgentChatApplicationService` 中 `shouldUseHistory=false` 的 `subList(最近4条)`：改为按轮次取，避免切散 tool 配对。
- 历史图片降级：仅最近 1~2 轮保留真图，更早轮次的图转为文字占位（保留路径），避免 base64 反复重载。

**批次 2 末：mvn compile 验证。**

---

## 批次 3：记忆按 userId 隔离（#1 #4）—— 大手术

### #1 MemoryStore 按 userId 隔离
- 目录结构：`memory/users/{userId}/{MEMORY,USER,MIDTERM}.md`；无 userId（系统/匿名）回退 `memory/users/_default/`。
- 引入 `ThreadLocal<Long> currentUserId`（在 `AgentChatApplicationService` 每轮入口 set/clear，复刻 currentSessionId）。
- `MemoryStore` 改为「按 userId 懒加载 + 缓存」的多用户存储：`Map<Long, UserMemory>`，每个 UserMemory 持有自己的 entries/snapshot/lock。
- 公共方法（add/replace/remove/read/getCombinedSnapshot/updateMidtermMemory…）内部按当前 userId 取对应 UserMemory；对外签名尽量不变（通过 ThreadLocal 取 userId）。
- 影响方：ChatMemoryConfig、MemoryTool、ContextCompressor、MemoryDailyAnalysisService、BuiltinTools、AgentChatApplicationService —— 均通过 ThreadLocal 拿 userId，调用点改动最小。
- 冻结快照语义保留：每轮入口按当前 userId 加载该用户快照注入系统提示。

### #4 每日分析覆盖持久化会话
- `MemoryDailyAnalysisService.collectAllChatMessages`：从 `DatabaseConversationStore` 按用户拉取当天活跃会话（而非只遍历内存 session）。
- 按 userId 分组分析，结果写入各自的 USER.md。

**批次 3 末：mvn compile 验证。**

---

## 批次 4：长期记忆向量检索（#9）

### 复用现有基础设施（不引入新依赖、不建新表）
- 复刻 `CodebaseSearchTool` 模式：`OpenAiEmbeddingModel(bge-m3)` + `InMemoryEmbeddingStore<TextSegment>`。
- 新增 `VectorMemoryStore`：
  - 每用户一个 store，持久化到 `memory/users/{userId}/.memory-vec.json`。
  - 长期记忆条目（MEMORY/USER）写入时同步 embed 入库；删除时移除。
  - 召回：每轮对话开始，用「当前用户消息 + taskGoal」做语义检索，取 Top-K（默认 6）相关条目注入系统提示，替代全量注入。
  - MEMORY.md/USER.md 保留为可读镜像 + 真值来源（重建索引依据）。
  - 降级：`embedding-enabled=false` 或无 key → 回退现有全量快照注入。
- 配置：`agent.memory.vector.enabled`、`top-k`、`min-score`，默认复用 codebase 的 embedding key/url/model。

**批次 4 末：mvn compile 验证。**

---

## 验证（#10）
- 每批 `mvn -pl mini-agent-app -am compile`。
- 全部完成后整体编译 + grep 确认无残留旧签名引用。
- 不跑集成测试（需要 MySQL 192.168.1.102 + ComfyUI + 远程模型环境）。会明确告知哪些只过了编译、未运行时验证。

## 不在本次范围
- #3 越权/鉴权（IDOR）等安全问题（上次已列出，等你单独安排）。
- 多用户登录体系本身的完善（沿用现有 cookie→userId）。
