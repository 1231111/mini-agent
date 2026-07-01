# Mini-Agent 优化迭代计划

## 当前状态
- Spring Boot 3.4.5 + LangChain4j 1.8.0
- Plan-and-Execute 模式（TaskPlanner → PlanStepExecutor → Validator → Synthesizer）
- 3级记忆（短期/中期/长期 Markdown 文件）
- 只有 mock 工具（CustomerSupportTools），无真正工具调用循环

## 问题诊断
1. **没有真正的 Agent 循环** — LLM 返回工具调用后没有解析+执行+反馈的循环
2. **工具不可扩展** — 硬编码 CustomerSupportTools，无注册机制
3. **上下文压缩是简单丢弃** — 没有 LLM 摘要，滑动窗口满了直接扔
4. **记忆写了但从不读** — memory 文件存在但不注入 system prompt
5. **同步阻塞** — 无流式输出，长任务用户干等

## 迭代计划

### 迭代1: 工具注册表 + Agent 循环 ✅ 已完成
- ✅ 创建 Tool (agent/tool/Tool.java) — 工具抽象，含 name/description/parameters/handler
- ✅ 创建 ToolRegistry (agent/tool/ToolRegistry.java) — 统一注册/发现/调度/ToolSpecification转换
- ✅ 创建 AgentLoop (agent/core/AgentLoop.java) — 真正的 tool_calls 解析循环
  - 循环: ChatModel.chat(messages, tools) → 解析 toolExecutionRequests → 执行 → 注入 ToolExecutionResultMessage → 继续
  - 支持最大迭代次数限制
  - 支持传入 chat history
- ✅ 创建 BuiltinTools (agent/tool/BuiltinTools.java) — 5个核心工具
  - read_file: 读取文件（支持 offset/limit 分页）
  - write_file: 写入文件（自动创建父目录）
  - list_files: 列出目录（支持递归）
  - http_get: HTTP GET 请求
  - exec_command: 执行 shell 命令（安全检查 + 超时）
- ✅ 改造 PlanStepChatExecutor — 使用 AgentLoop 代替 raw chat
- ✅ 改造 ReActTaskExecutor — 使用 AgentLoop，有真正的工具调用能力
- ✅ 新增 AgentType.DIRECT_AGENT — 直接对话模式（带记忆的 Agent 循环）
- ✅ 更新 AgentChatApplicationService — 支持 DIRECT_AGENT 路由
- ✅ 更新 PostChatMessageRequest — 支持 agentType 参数
- ✅ 更新 chat.html — 暗色主题，Agent 模式切换按钮

### 迭代2: 核心工具 ✅ 已合并到迭代1
- ✅ read_file / write_file / list_files / http_get / exec_command 已在 BuiltinTools 中实现

### 迭代3: 上下文智能压缩 ✅ 已完成
- ✅ TokenEstimator (agent/core/TokenEstimator.java) — 基于字符数估算 token（中文0.5/字符）
- ✅ ContextCompressor (agent/core/ContextCompressor.java) — LLM 摘要压缩
  - 触发条件: token 占比 >= 70%
  - 压缩目标: 降到 40%
  - 保护最近 6 条消息不被压缩
  - 摘要以 SystemMessage 形式注入（标注"历史对话摘要"）
- ✅ 集成到 AgentLoop — 每轮工具调用后自动检查并压缩

### 迭代4: 记忆检索注入 ✅ 已完成
- ✅ MemorySearchService (agent/memory/MemorySearchService.java)
  - 关键词搜索 memory/*.md（中文分词 + bigram）
  - 相关度评分 + 段落提取
  - writeMemory() 持久化重要对话
- ✅ 集成到 AgentChatApplicationService
  - 每次对话前搜索相关记忆注入 system prompt
  - 重要对话自动写入 session/*.md

### 迭代5: 浏览器工具 ✅ 已完成
- ✅ BrowserService (agent/browser/BrowserService.java) — Playwright 驱动无头 Chromium
  - accessibility tree 快照（对标 hermes-agent 的 agent-browser）
  - ref 编号系统：自动给可交互元素编号 [1], [2]...
  - navigate / snapshot / click / type / press / scroll / screenshot / evaluate / close
  - Session 隔离：每个 sessionId 独立页面
  - 懒初始化：首次调用时才启动浏览器
- ✅ BuiltinTools 注册 8 个浏览器工具（browser_navigate/browser_snapshot/browser_click/browser_type/browser_press/browser_scroll/browser_screenshot/browser_close）
- ✅ System prompt 更新：包含浏览器工具使用指南

### 迭代6: SSE 流式响应（待做）
- StreamingChatService — LangChain4j StreamingChatModel
- SseController — Server-Sent Events 端点
- 前端 fetch + ReadableStream 接收
