package com.miniagent.agent.core;

import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.tool.ToolRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import com.miniagent.agent.trace.TraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Agent 循环：解析 tool_calls → 执行 → 注入结果 → 重试，直到模型返回最终文本。
 *
 * 设计要点（2026-04 重写）：
 *   之前的拦截逻辑过于激进：
 *     - 仅靠用户原话关键词（"设计/生成/写"）判断是否需要文件产出；
 *     - 验证证据全靠 finalText 关键词匹配，图片URL/Markdown图片不算数；
 *     - 结果是"生成一张图"被误判为产出物任务，强行让 LLM 多调一次 write_file。
 *   重写后：
 *     - 任务类型基于本轮实际调用过的工具来判断（observable），不是靠关键词猜；
 *     - 只有用户明确要求"保存为文件/写MD/出SQL等"才算 FILE 任务；
 *     - 其他任务（包括图片生成、搜索、问答）直接放行，不再兜底塞 write_file。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final ToolRegistry toolRegistry;
    private final ContextCompressor contextCompressor;
    private final StreamingChatModel streamingChatModel;
    /** 轨迹记录器（可选，注入后自动记录每步执行） */
    private TraceRecorder traceRecorder;
    public void setTraceRecorder(TraceRecorder traceRecorder) { this.traceRecorder = traceRecorder; }
    /** 流式事件中枢（用于运行中消息注入） */
    private SessionStreamHub streamHub;
    public void setStreamHub(SessionStreamHub streamHub) { this.streamHub = streamHub; }
    private final TaskTodoStore taskTodoStore;

    private static final int MAX_ITERATIONS = 90;
    /** 虚拟线程池：工具并行执行专用，IO 密集型任务零开销 */
    private static final java.util.concurrent.ExecutorService VIRTUAL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    /** 当前会话ID（供 token 追踪使用） */
    private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    public static void setCurrentSession(String sessionId) { currentSessionId.set(sessionId); }
    public static void clearCurrentSession() { currentSessionId.remove(); }
    private static final int MAX_EXPLORATION_CALLS = 40;  // read_file + list_files + exec_command 总上限（放开：复杂任务定位文件常需多次读取）

    /** 上下文窗口上限（token）。与 ContextCompressor 共用，统一从配置读取，默认 256K。 */
    @org.springframework.beans.factory.annotation.Value("${agent.context.max-tokens:256000}")
    private int maxContextTokens;

    /**
     * 各工具结果的上下文字符上限（按信息密度分级）。
     * 搜索/抓取类需要更多内容，命令/生图类结果本身较短不需要大限额。
     */
    private static final Map<String, Integer> TOOL_RESULT_LIMITS = Map.ofEntries(
            Map.entry("web_search",        8000),
            Map.entry("web_extract",       12000),
            Map.entry("read_file",         10000),
            Map.entry("browser_snapshot",   6000),
            Map.entry("browser_navigate",   5000),
            Map.entry("http_get",           8000),
            Map.entry("exec_command",       4000),
            Map.entry("list_files",         2000),
            Map.entry("image_generate",     1000),
            Map.entry("browser_screenshot", 1000),
            Map.entry("read_package",       15000)
    );
    /** 未在 TOOL_RESULT_LIMITS 中的工具使用此默认值 */
    private static final int DEFAULT_TOOL_RESULT_MAX = 4000;

    /**
     * 各工具的执行超时（秒）。慢工具（image_generate）给更多时间，
     * 快工具（read_file/list_files）设短超时避免拖慢整体。
     */
    private static final Map<String, Long> TOOL_TIMEOUT_SECONDS = Map.ofEntries(
            Map.entry("image_generate",     150L),
            Map.entry("web_search",          30L),
            Map.entry("web_extract",         30L),
            Map.entry("http_get",            30L),
            Map.entry("read_file",           10L),
            Map.entry("list_files",          10L),
            Map.entry("write_file",          15L),
            Map.entry("exec_command",        30L),
            Map.entry("browser_navigate",    30L),
            Map.entry("browser_snapshot",    15L),
            Map.entry("browser_click",       10L),
            Map.entry("browser_type",        10L),
            Map.entry("browser_press",       10L),
            Map.entry("browser_scroll",      10L),
            Map.entry("browser_screenshot",  20L),
            Map.entry("browser_evaluate",    15L),
            Map.entry("browser_close",       10L),
            Map.entry("read_package",        15L),
            Map.entry("comfyui_txt2img",    200L),
            Map.entry("comfyui_img2img",    200L),
            Map.entry("comfyui_img2video",  620L),
            Map.entry("comfyui_tts",        140L),
            Map.entry("comfyui_execute",     30L)
    );
    /** 未在 TOOL_TIMEOUT_SECONDS 中的工具使用此默认超时 */
    private static final long DEFAULT_TOOL_TIMEOUT_SECONDS = 60L;

    /**
     * 产生直接交付物（图片/截图/音视频）的工具白名单。
     * 这类工具一旦成功执行，就视为模型已经把产出物交付给了前端，
     * 不再要求额外调 write_file。
     */
    private static final Set<String> MEDIA_TOOLS = Set.of(
            "image_generate",
            "browser_screenshot",
            "video_generate",
            "audio_generate",
            "tts",
            "comfyui_txt2img",
            "comfyui_img2img",
            "comfyui_img2video",
            "comfyui_tts"
    );

    /** 会真正落盘的工具。 */
    private static final Set<String> WRITE_TOOLS = Set.of(
            "write_file"
    );

    /** 可缓存的只读工具：同一请求内重复调用直接返回缓存 */
    private static final Set<String> CACHEABLE_TOOLS = Set.of(
            "read_file", "list_files", "web_search", "web_extract", "http_get",
            "exec_command", "browser_snapshot", "read_package"
    );

    /**
     * 用户明确要求"保存成文件/写入文档"的信号词。
     * 只认：
     *   1) 明确的文件扩展名（.md/.sql/代码文件扩展名）；
     *   2) 明确的"保存/写入/输出 → 文件"动作短语；
     *   3) 明确的"脚手架/项目骨架/代码仓库"等需要落盘目录结构的关键词。
     * 不再把"写个项目/写一个工程"这种有歧义的短语算进来，避免误伤。
     */
    private static final Pattern EXPLICIT_FILE_REQ = Pattern.compile(
            "(?i)(" +
            // 1) 明确的扩展名
            "\\.(md|markdown|sql|java|py|ts|tsx|js|jsx|go|rs|rb|php|kt|swift|c|cpp|h|hpp|sh|yaml|yml|json|xml|html|css)\\b" +
            // 2) 明确的"落盘/写入文件"动词短语
            "|md\\s*文档|markdown\\s*文档|写\\s*md|保存到文件|保存为文件|另存为文件" +
            "|输出到文件|写入文件|写到文件|落盘|落地文件" +
            // 3) 明确要求生成代码产物 / 项目骨架
            "|生成\\s*sql|写一?段?\\s*sql|输出\\s*sql" +
            "|项目骨架|工程骨架|脚手架|代码仓库|单体仓库|monorepo" +
            ")"
    );

    /** Markdown 图片语法：直接命中说明模型已经把图交付给了前端。 */
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
            "!\\[[^\\]]*]\\((https?://|/static/|generated-images/|data:image/)[^)]+\\)"
    );

    /**
     * 执行 Agent 循环（纯文本用户消息）
     */
    public String run(ChatModel chatModel,
                      String systemMessage,
                      String userMessage,
                      List<ChatMessage> chatHistory,
                      int maxIterations) {
        return run(chatModel, systemMessage, userMessage, chatHistory, maxIterations, null);
    }

    public String run(ChatModel chatModel,
                      String systemMessage,
                      String userMessage,
                      List<ChatMessage> chatHistory,
                      int maxIterations,
                      Consumer<String> progressCallback) {
        return run(chatModel, systemMessage, userMessage, chatHistory, maxIterations, progressCallback, null);
    }

    public String run(ChatModel chatModel,
                      String systemMessage,
                      String userMessage,
                      List<ChatMessage> chatHistory,
                      int maxIterations,
                      Consumer<String> progressCallback,
                      TaskPlan taskPlan) {
        return run(chatModel, systemMessage, userMessage, chatHistory, maxIterations, progressCallback, taskPlan, null);
    }

    /**
     * 主入口（带实时流式回调）。{@code streamSink} 非空时，循环改用流式模型调用，
     * 把思考 / 答案增量实时推给前端；为空时走原阻塞路径，行为完全不变。
     */
    public String run(ChatModel chatModel,
                      String systemMessage,
                      String userMessage,
                      List<ChatMessage> chatHistory,
                      int maxIterations,
                      Consumer<String> progressCallback,
                      TaskPlan taskPlan,
                      AgentStreamSink streamSink) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemMessage != null && !systemMessage.isBlank()) {
            messages.add(new SystemMessage(systemMessage));
        }
        if (taskPlan != null) {
            messages.add(new SystemMessage(taskPlan.toPromptBlock()));
        }
        if (chatHistory != null) {
            messages.addAll(chatHistory);
        }
        messages.add(new UserMessage(userMessage));
        return executeLoop(chatModel, messages, userMessage == null ? "" : userMessage, maxIterations, progressCallback, taskPlan, streamSink);
    }

    /**
     * 简化调用：无历史消息，默认最多 MAX_ITERATIONS 轮
     */
    public String run(ChatModel chatModel, String systemMessage, String userMessage) {
        return run(chatModel, systemMessage, userMessage, null, MAX_ITERATIONS, null);
    }

    /**
     * 多模态：最后一轮为 {@link UserMessage}（可含 {@link ImageContent}），与 {@link #run} 使用同一套工具循环与放行规则。
     */
    public String runWithMultimodal(ChatModel chatModel,
                                    String systemMessage,
                                    UserMessage userMessage,
                                    List<ChatMessage> chatHistory,
                                    int maxIterations) {
        return runWithMultimodal(chatModel, systemMessage, userMessage, chatHistory, maxIterations, null);
    }

    public String runWithMultimodal(ChatModel chatModel,
                                    String systemMessage,
                                    UserMessage userMessage,
                                    List<ChatMessage> chatHistory,
                                    int maxIterations,
                                    Consumer<String> progressCallback) {
        return runWithMultimodal(chatModel, systemMessage, userMessage, chatHistory, maxIterations, progressCallback, null);
    }

    public String runWithMultimodal(ChatModel chatModel,
                                    String systemMessage,
                                    UserMessage userMessage,
                                    List<ChatMessage> chatHistory,
                                    int maxIterations,
                                    Consumer<String> progressCallback,
                                    TaskPlan taskPlan) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemMessage != null && !systemMessage.isBlank()) {
            messages.add(new SystemMessage(systemMessage));
        }
        if (taskPlan != null) {
            messages.add(new SystemMessage(taskPlan.toPromptBlock()));
        }
        if (chatHistory != null) {
            messages.addAll(chatHistory);
        }
        messages.add(userMessage);
        return executeLoop(chatModel, messages, firstUserTextForFileIntent(userMessage), maxIterations, progressCallback, taskPlan, null);
    }

    /**
     * 与 {@link #run} 共用的主循环。
     * {@code progressCallback} 非空时，每次工具调用前后推送进度文本（用于 SSE 实时反馈）。
     */
    // ==================== Loop 状态 ====================

    /** Agent 循环可变状态，避免 executeLoop 里满天飞的局部变量 */
    private static class LoopState {
        int currentTurn = 0;  // 当前迭代轮次，供工具执行路径使用
        final Set<String> toolsInvoked = new HashSet<>();
        boolean writeFileSucceeded = false;
        boolean mediaDelivered = false;
        boolean mediaReminderSent = false;
        int mediaIgnoreCount = 0;
        String lastMediaResult = null;
        boolean imageGenerateUnavailable = false;
        boolean fileReminderSent = false; // 是否已注入过一次性文件落盘提醒
        final Map<String, String> toolResultCache = new HashMap<>();
        final Map<String, Integer> dupCallCounter = new HashMap<>();
        int explorationCount = 0;
        int consecutiveFailures = 0;  // 连续同类工具失败计数
        String lastFailedTool = null; // 上次失败的工具名
        boolean reflectionInjected = false; // 本轮是否已注入反思提示
        SystemMessage currentSubGoalMsg = null; // 框架注入的「当前子目标」可刷新指针消息
        String lastSubGoalText = null;          // 上次推送给前端的子目标文字（去重用）
        int llmFailureCount = 0;  // 连续 LLM 调用失败计数（用于降级策略）
        int lengthTruncationCount = 0; // 连续输出被长度上限截断计数（用于分块续写引导/兜底终止）
        boolean injectAppendHintAfterTools = false; // tool_call 被长度截断：工具执行后需注入续写引导
        boolean lengthTruncatedToolCalls = false; // 当前轮 tool_call 参数可能被截断
        boolean requiresStructuredPlan = false; // 复杂任务：未 set 计划前只放行 todo
        int planReminderCount = 0;              // 催促先写 todo 的次数
        int incompleteTodoReminders = 0;        // 未完成 todo 却试图收尾的次数
        int driftReminders = 0;                 // 偏离当前子目标的纠偏次数
        boolean batchDelegateHintSent = false;  // 是否已提示用 delegate_task 并行
        boolean goalAnchorInjected = false;     // 是否已注入任务目标锚定

        /** 死循环检测：本轮全部工具是否已重复 >= 3 次 */
        boolean allCallsRepeated(List<?> toolCalls) {
            boolean allRepeated = true;
            for (var tc : toolCalls) {
                try {
                    String key = tc.getClass().getMethod("name").invoke(tc) + "|"
                            + (tc.getClass().getMethod("arguments").invoke(tc) == null ? ""
                               : tc.getClass().getMethod("arguments").invoke(tc));
                    int count = dupCallCounter.getOrDefault(key, 0) + 1;
                    dupCallCounter.put(key, count);
                    if (count < 3) allRepeated = false;
                } catch (Exception e) {
                    allRepeated = false;
                }
            }
            return allRepeated;
        }
    }

    // ==================== executeLoop ====================

    private String executeLoop(ChatModel chatModel,
                              List<ChatMessage> messages,
                              String userTextForFilePattern,
                              int maxIterations,
                              Consumer<String> progressCallback,
                              TaskPlan taskPlan,
                              AgentStreamSink streamSink) {
        var toolSpecs = taskPlan == null
                ? toolRegistry.getSpecifications()
                : toolRegistry.getSpecifications(taskPlan.allowedToolSet());
        boolean hasTools = !toolSpecs.isEmpty();
        int iterations = Math.min(maxIterations, MAX_ITERATIONS);

        LoopState state = new LoopState();
        boolean explicitlyNeedsFile = EXPLICIT_FILE_REQ.matcher(
                userTextForFilePattern == null ? "" : userTextForFilePattern).find();
        String taskGoal = summarizeTaskGoal(
                taskPlan != null ? taskPlan.taskGoal() : userTextForFilePattern);
        state.requiresStructuredPlan = taskPlan != null && taskPlan.requiresStructuredPlan();

        // sub-goal 栈播种：用意图规划的 steps 预填 todo（仅当该 session 还没有计划时）
        String sessionId = currentSessionId.get();
        // 开始轨迹记录
        String executionId = "exec_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        if (traceRecorder != null) {
            traceRecorder.beginExecution(sessionId, executionId, userTextForFilePattern);
        }
        try {
        if (taskPlan != null && taskPlan.steps() != null && !taskPlan.steps().isEmpty()) {
            boolean seeded = taskTodoStore.seedFromSteps(sessionId, taskPlan.steps());
            if (seeded) log.info("sub-goal 栈已播种 {} 步: {}", taskPlan.steps().size(), taskGoal);
        }

        if (taskPlan != null) {
            log.info("Agent计划: intent={}, taskGoal='{}', requiresPlan={}, allowedToolCount={}",
                    taskPlan.intent(), taskGoal, taskPlan.requiresStructuredPlan(),
                    taskPlan.allowedTools() == null ? 0 : taskPlan.allowedTools().size());
        }
        log.info("Agent任务开始: taskGoal='{}', maxIterations={}, toolsAvailable={}, explicitFileDelivery={}, requiresPlan={}",
                taskGoal, iterations, toolSpecs.size(), explicitlyNeedsFile, state.requiresStructuredPlan);

        for (int turn = 0; turn < iterations; turn++) {
            // 检查用户执行中追加的消息
            if (streamHub != null) {
                java.util.List<String> injected = streamHub.drainMessages(sessionId);
                if (!injected.isEmpty()) {
                    for (String msg : injected) {
                        messages.add(new UserMessage(msg));
                        log.info("用户追加指令注入: {}", msg.length() > 100 ? msg.substring(0, 100) + "..." : msg);
                    }
                    if (streamSink != null) streamSink.onThinking("\n（已收到你的补充说明，正在融入当前任务...）\n");
                    if (traceRecorder != null) traceRecorder.recordThinking(sessionId, turn,
                            "【用户追加】" + injected.size() + " 条补充指令注入");
                }
            }

            // 任务目标锚定（复杂任务注入一次，压缩后仍靠头部 user + 此提醒保持方向）
            if (state.requiresStructuredPlan && !state.goalAnchorInjected) {
                messages.add(new SystemMessage(
                        "【任务目标锚定】本轮 taskGoal：" + taskGoal
                                + "\n所有工具调用与计划步骤必须服务该目标；禁止跑去处理无关需求。"
                                + "若需修订目标，等用户明确追加指令后再改 todo。"));
                state.goalAnchorInjected = true;
            }

            // 刷新「当前子目标」可刷新指针：移除上一条，按栈顶活动项注入新的一条到末尾
            refreshSubGoal(messages, state, sessionId, streamSink);

            String subGoalLog = state.lastSubGoalText != null ? state.lastSubGoalText : taskGoal;
            state.currentTurn = turn;
            if (traceRecorder != null) traceRecorder.recordTurnStart(sessionId, turn, state.lastSubGoalText);

            // 1) 构建请求（按需过滤工具：复杂任务未 set 计划时仅放行 todo）
            var specsForTurn = buildToolSpecsForTurn(toolSpecs, hasTools, state.mediaDelivered, state, sessionId);
            log.info("Agent循环第 {}/{} 轮: {}, messages={}, availableTools={}, alreadyInvoked={}",
                    turn + 1, iterations, truncate(subGoalLog, 80), messages.size(),
                    specsForTurn.size(), state.toolsInvoked);
            // 记录 LLM 请求上下文（超长 prompt 截断，避免轨迹写入拖死线程）
            if (traceRecorder != null) {
                String promptCtx = truncate(formatMessagesForTrace(messages), 8000);
                traceRecorder.recordThinking(sessionId, turn,
                        "【LLM 请求】消息数: " + messages.size() + ", 工具数: " + specsForTurn.size() + "\n\n" + promptCtx);
            }
            log.info("开始调用 LLM（流式={}，工具数={}）…", streamSink != null, specsForTurn.size());
            long llmStartMs = System.currentTimeMillis();
            ChatResponse response = callLlm(chatModel, messages, specsForTurn, hasTools, streamSink);
            log.info("LLM 调用结束，耗时 {}ms，response={}",
                    System.currentTimeMillis() - llmStartMs, response == null ? "null" : "ok");
            long llmEndMs = System.currentTimeMillis();

            // 记录 LLM 响应
            if (traceRecorder != null && response != null) {
                AiMessage ai = response.aiMessage();
                // 记录 LLM 调用耗时
                int inTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
                int outTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;
                traceRecorder.recordLlmLatency(sessionId, turn, llmEndMs - llmStartMs, inTokens, outTokens);
                // 记录决策推理（模型在调工具前的思考文字）
                if (ai.hasToolExecutionRequests() && ai.text() != null && !ai.text().isBlank()) {
                    traceRecorder.recordDecision(sessionId, turn, ai.text());
                }
                String respText = "【LLM 响应】";
                if (ai.text() != null && !ai.text().isBlank()) respText += "\n文本: " + truncate(ai.text(), 2000);
                if (ai.hasToolExecutionRequests()) {
                    respText += "\n工具调用: " + ai.toolExecutionRequests().size() + " 个";
                    for (var tc : ai.toolExecutionRequests()) {
                        respText += "\n  - " + toolNameOf(tc) + "(" + truncate(argumentsOf(tc), 500) + ")";
                    }
                }
                if (response.tokenUsage() != null) {
                    respText += "\nToken: input=" + response.tokenUsage().inputTokenCount() + ", output=" + response.tokenUsage().outputTokenCount();
                }
                traceRecorder.recordThinking(sessionId, turn, respText);
            }

            if (response == null) {
                state.llmFailureCount++;
                // 智能降级策略：不立即放弃，尝试恢复
                if (state.llmFailureCount == 1) {
                    // 首次失败：通知前端、注入恢复提示，给模型一次重连机会
                    log.warn("LLM 调用失败（第 {} 次），注入恢复提示后继续", state.llmFailureCount);
                    if (streamSink != null) streamSink.onThinking("\n（模型响应超时，正在重试...）\n");
                    messages.add(new SystemMessage("【系统通知】上次模型调用遇到临时网络问题，已自动重连。请继续刚才的任务。"));
                    continue;
                } else if (state.llmFailureCount == 2 && messages.size() > 20) {
                    // 第二次失败 + 上下文过长：可能 token 过多超时，裁剪后做最后一次尝试
                    log.warn("LLM 连续失败 2 次且上下文过长（{} 条），裁剪至最近 10 条后做最终尝试", messages.size());
                    if (streamSink != null) streamSink.onThinking("\n（第二次重试失败，正在精简上下文后做最后尝试...）\n");
                    List<ChatMessage> trimmed = new ArrayList<>();
                    // 保留 system prompt（前几条 SystemMessage）
                    int sysEnd = 0;
                    for (int i = 0; i < Math.min(5, messages.size()); i++) {
                        if (messages.get(i) instanceof SystemMessage) sysEnd = i + 1;
                        else break;
                    }
                    trimmed.addAll(messages.subList(0, sysEnd));
                    // 加最近 10 条对话（更激进裁剪，减少 token 数）
                    int keep = Math.min(10, messages.size() - sysEnd);
                    trimmed.addAll(messages.subList(messages.size() - keep, messages.size()));
                    messages = trimmed;
                    state.currentSubGoalMsg = null;
                    continue;
                } else {
                    // 连续失败 3 次或降级策略用尽：最终放弃
                    log.error("LLM 调用连续失败 {} 次（已尝试恢复与降级），任务终止", state.llmFailureCount);
                    if (traceRecorder != null) traceRecorder.recordError(sessionId, turn, "LLM 连续失败 " + state.llmFailureCount + " 次");
                    return "（模型连接异常，已自动重试但仍失败，请稍后再试或联系管理员）";
                }
            }
            // 成功后重置失败计数
            state.llmFailureCount = 0;

            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            // 1.5) 长度截断检测：上游因输出上限把本轮内容截断（finish_reason=length）。
            // 此时 tool_call 的 arguments 往往是半截 JSON（write_file 的 content 写不全），
            // 或纯文本答案被腰斩。让框架"看见"截断并给出可行动引导，而不是任由模型从头重发→再截断。
            boolean lengthTruncated = response.finishReason() == dev.langchain4j.model.output.FinishReason.LENGTH;
            if (lengthTruncated) {
                state.lengthTruncationCount++;
                log.warn("检测到输出被长度上限截断（第 {} 次）。hasToolCalls={}",
                        state.lengthTruncationCount, aiMessage.hasToolExecutionRequests());
                if (state.lengthTruncationCount >= 4) {
                    log.warn("连续 {} 次长度截断仍未完成，终止避免空转", state.lengthTruncationCount);
                    if (state.writeFileSucceeded) {
                        return "内容较长、多次写入后仍超出单轮输出上限。已写入的部分见 workspace/，建议把需求拆成更小的文件分别生成。";
                    }
                    return "要生成的内容超出了单轮输出上限，且多次分块续写仍未完成。建议把任务拆小（例如把 3D 仿真拆成 HTML 骨架、JS 逻辑、样式分别生成）后再试。";
                }
                if (aiMessage.hasToolExecutionRequests()) {
                    // tool_call 被截断：先把工具结果产出（截断的 write_file 会回报参数错误），
                    // 再注入续写引导。这里只置标志，引导在工具执行后注入，确保紧贴工具结果。
                    state.injectAppendHintAfterTools = true;
                    state.lengthTruncatedToolCalls = true;
                } else {
                    // 纯文本被截断：直接引导续写，不要从头重来。
                    messages.add(new SystemMessage(
                            "【系统提醒】你上一条回复因达到单轮输出上限被截断了，没有写完。"
                            + "不要从头重写。如果你正在生成文件，请用 write_file 的 mode=\"append\" 把"
                            + "尚未写出的剩余部分继续追加到同一文件；如果是普通回答，请直接接着上次断掉的地方继续写完。"));
                    continue;
                }
            } else {
                state.lengthTruncationCount = 0;
            }

            // 2) 工具调用 → 执行工具，继续循环
            if (aiMessage.hasToolExecutionRequests()) {
                // 本轮流出的文本只是中间思考，提示前端清空已渲染的答案增量
                if (streamSink != null) streamSink.onAnswerReset();
                var toolCalls = aiMessage.toolExecutionRequests();
                log.info("模型请求 {} 个工具调用,tools:{}", toolCalls.size(),toolCalls);
                if (traceRecorder != null) {
                    for (var tc : toolCalls) {
                        traceRecorder.recordToolCall(sessionId, turn, toolNameOf(tc), argumentsOf(tc));
                    }
                }

                if (state.allCallsRepeated(toolCalls)) {
                    log.warn("死循环检测：全部工具已重复 >= 3 次，终止");
                    return "我连续多次用相同参数调用同样的工具但没拿到新结果，停止避免空转。已调用：" + state.toolsInvoked;
                }

                executeToolCalls(toolCalls, messages, progressCallback, state);

                // tool_call 被长度截断：工具结果已入列（截断的 write_file 会回报"参数被截断"），
                // 紧接着注入续写引导，让模型用 append 续写而不是重头再来。
                if (state.injectAppendHintAfterTools) {
                    state.injectAppendHintAfterTools = false;
                    state.lengthTruncatedToolCalls = false;
                    messages.add(new SystemMessage(
                            "【系统提醒】你上一次的工具调用因达到单轮输出上限被截断，参数没传完整。"
                            + "如果你在写文件：不要从头重写，请用 write_file 且 mode=\"append\"，"
                            + "把上次没写完的剩余内容分多次追加到同一文件，每次片段不要太长，直到整份文件写完。"));
                }

                // 子目标漂移纠偏 + 批量并行派发提示
                injectDriftCorrectionIfNeeded(messages, toolCalls, state, sessionId);
                injectBatchDelegateHintIfNeeded(messages, state, sessionId);

                // 媒体已交付后，如果 LLM 还在调非输出工具，强制提醒或终止
                if (state.mediaDelivered) {
                    boolean hasNonOutputTool = toolCalls.stream()
                            .anyMatch(tc -> {
                                String name = toolNameOf(tc);
                                return !"comfyui_txt2img".equals(name)
                                        && !"comfyui_img2img".equals(name)
                                        && !"comfyui_check_quality".equals(name);
                            });
                    if (hasNonOutputTool) {
                        state.mediaReminderSent = true;
                        state.mediaIgnoreCount++;
                        log.info("媒体已交付但 LLM 还在调其他工具，第 {} 次忽略提醒", state.mediaIgnoreCount);
                        if (state.mediaIgnoreCount >= 1) {
                            log.warn("LLM 连续忽略 {} 次提醒，强制结束循环", state.mediaIgnoreCount);
                            return state.lastMediaResult != null ? state.lastMediaResult : "图片已生成。";
                        }
                        messages.add(new SystemMessage(
                                "图片已经生成完毕，不要再调用任何工具，直接输出 markdown 图片链接给用户。"));
                    }
                }

                int msgCountBefore = messages.size();
                messages = contextCompressor.maybeCompress(messages, maxContextTokens, sessionId);
                if (traceRecorder != null && messages.size() < msgCountBefore) {
                    traceRecorder.recordCompression(sessionId, turn, msgCountBefore, messages.size(), 0, 0);
                }
                continue;
            }

            // 3) 模型返回文本（无工具调用）→ 模型主动收尾，作为最终回复
            String result = tryReturnFinalText(aiMessage, messages, state, explicitlyNeedsFile,
                    turn, iterations, sessionId);
            if (result != null) {
                if (traceRecorder != null) {
                    traceRecorder.recordAnswer(sessionId, turn, result);
                    traceRecorder.recordLoopEnd(sessionId, turn, "SUCCESS", null, null);
                }
                return result;
            }
            // tryReturnFinalText 注入了提醒，继续循环
        }

        log.warn("Agent 循环达到最大迭代次数 {}", iterations);
        if (traceRecorder != null) traceRecorder.recordLoopEnd(sessionId, iterations - 1, "MAX_ITERATIONS", null, null);
        return buildMaxIterationFallback(state, iterations);
        } finally {
            if (traceRecorder != null) traceRecorder.endExecution();
        }
    }

    // ==================== 工具过滤 ====================

    /**
     * 刷新框架维护的「当前子目标」指针消息。
     * 采用「移除旧的 → 末尾加新的」的可刷新策略，避免过期子目标累积污染上下文。
     * 子目标变化时同步推送前端。栈空（简单任务或全部完成）时不注入，优雅降级。
     */
    private void refreshSubGoal(List<ChatMessage> messages, LoopState state,
                                String sessionId, AgentStreamSink streamSink) {
        TaskTodoStore.SubGoal sg;
        try {
            sg = taskTodoStore.currentSubGoalDetail(sessionId);
        } catch (Exception e) {
            return; // 子目标机制是增强项，任何异常都不应中断主循环
        }

        // 子目标未变且指针消息仍在 messages 中 → 跳过，避免每轮 remove+add 开销
        if (sg != null && sg.text().equals(state.lastSubGoalText) && state.currentSubGoalMsg != null
                && messages.contains(state.currentSubGoalMsg)) {
            return;
        }

        // 先移除上一条指针消息（如果还在 messages 里）
        if (state.currentSubGoalMsg != null) {
            messages.remove(state.currentSubGoalMsg);
            state.currentSubGoalMsg = null;
        }
        if (sg == null) return; // 无可执行子目标：不注入

        String pointer = "当前子目标 (#" + sg.position() + "/" + sg.total() + ")：" + sg.text()
                + "\n聚焦完成这一步即可，完成后用 todo 工具把它标记为 completed 再进入下一步。";
        SystemMessage msg = new SystemMessage(pointer);
        messages.add(msg);
        state.currentSubGoalMsg = msg;

        // 子目标文字变化才推前端，避免重复事件
        if (!sg.text().equals(state.lastSubGoalText)) {
            state.lastSubGoalText = sg.text();
            if (streamSink != null) {
                try { streamSink.onSubGoal(sg.text(), sg.done(), sg.total()); }
                catch (Exception ignored) {}
            }
            if (traceRecorder != null) {
                String sid = currentSessionId.get();
                if (sid != null) traceRecorder.recordSubGoal(sid, 0, sg.text(), sg.done(), sg.total());
            }
        }
    }


    /** 根据当前状态过滤本轮可用工具 */
    private List<?> buildToolSpecsForTurn(List<?> toolSpecs, boolean hasTools,
                                           boolean mediaDelivered, LoopState state, String sessionId) {
        if (!hasTools) return List.of();  // mediaDelivered不再清空工具，让模型自己决定是否继续生成
        var specs = toolSpecs;

        // 复杂任务：尚未 todo.set 时只允许 todo，强制先写计划
        if (state.requiresStructuredPlan && sessionId != null && !taskTodoStore.hasPlan(sessionId)) {
            specs = specs.stream()
                    .filter(s -> "todo".equals(((dev.langchain4j.agent.tool.ToolSpecification) s).name()))
                    .toList();
            if (!specs.isEmpty()) {
                log.info("复杂任务未建立计划，本轮仅放行 todo 工具");
                return specs;
            }
        }

        if (state.imageGenerateUnavailable) {
            specs = specs.stream().filter(s -> !"image_generate".equals(((dev.langchain4j.agent.tool.ToolSpecification)s).name())).toList();
        }
        if (state.explorationCount >= MAX_EXPLORATION_CALLS) {
            specs = specs.stream()
                    .filter(s -> {
                        String name = ((dev.langchain4j.agent.tool.ToolSpecification)s).name();
                        return !"read_file".equals(name) && !"list_files".equals(name)
                                && !"exec_command".equals(name) && !"read_package".equals(name);
                    }).toList();
            if (specs.isEmpty()) {
                specs = toolSpecs.stream().filter(s -> "memory".equals(((dev.langchain4j.agent.tool.ToolSpecification)s).name())).toList();
            }
        }
        return specs;
    }

    /** 工具调用明显偏离当前子目标时注入纠偏（最多 2 次，避免刷屏） */
    private void injectDriftCorrectionIfNeeded(List<ChatMessage> messages, List<?> toolCalls,
                                               LoopState state, String sessionId) {
        if (sessionId == null || state.driftReminders >= 2) return;
        TaskTodoStore.SubGoal sg = taskTodoStore.currentSubGoalDetail(sessionId);
        if (sg == null || sg.text() == null || sg.text().isBlank()) return;
        if (!looksLikeSubGoalDrift(sg.text(), toolCalls)) return;
        state.driftReminders++;
        log.info("检测到子目标漂移，注入纠偏 #{}/2: {}", state.driftReminders, sg.text());
        messages.add(new SystemMessage(
                "【方向纠偏】当前子目标是：#" + sg.position() + "/" + sg.total() + " " + sg.text()
                        + (sg.doneWhen() == null || sg.doneWhen().isBlank() ? "" : "（done_when=" + sg.doneWhen() + "）")
                        + "\n你刚才的工具调用看起来与该步无关。请立刻回到当前子目标："
                        + "用相关工具完成它，再 todo update 标 completed（附 evidence），不要继续无关操作。"));
    }

    /** 粗粒度相关性：元工具放行；否则要求参数/工具类型与子目标语义沾边 */
    private boolean looksLikeSubGoalDrift(String subGoal, List<?> toolCalls) {
        Set<String> meta = Set.of("todo", "memory", "delegate_task");
        boolean hasNonMeta = false;
        String sg = subGoal.toLowerCase();
        for (Object tc : toolCalls) {
            String name = toolNameOf(tc);
            if (meta.contains(name)) continue;
            hasNonMeta = true;
            String args = argumentsOf(tc) == null ? "" : argumentsOf(tc).toLowerCase();
            if (sharesSignificantToken(sg, args)) return false;
            if (("write_file".equals(name) || "edit_file".equals(name) || "read_file".equals(name))
                    && (sg.contains("写") || sg.contains("生成") || sg.contains("文件")
                    || sg.contains("实现") || sg.contains("文档") || sg.contains("代码"))) return false;
            if (("image_generate".equals(name) || name.startsWith("comfyui"))
                    && (sg.contains("图") || sg.contains("画") || sg.contains("image") || sg.contains("视觉"))) return false;
            if (("web_search".equals(name) || "web_extract".equals(name) || "http_get".equals(name))
                    && (sg.contains("搜索") || sg.contains("调研") || sg.contains("查") || sg.contains("资料"))) return false;
            if (name.startsWith("browser")
                    && (sg.contains("浏览器") || sg.contains("网页") || sg.contains("打开") || sg.contains("验证"))) return false;
            if ("exec_command".equals(name)
                    && (sg.contains("编译") || sg.contains("测试") || sg.contains("验证") || sg.contains("运行"))) return false;
            if (("search_code".equals(name) || "codebase_search".equals(name) || "ast_search".equals(name)
                    || "read_package".equals(name))
                    && (sg.contains("代码") || sg.contains("定位") || sg.contains("搜索") || sg.contains("分析"))) return false;
        }
        return hasNonMeta;
    }

    private static boolean sharesSignificantToken(String subGoal, String args) {
        if (subGoal.isBlank() || args.isBlank()) return false;
        // 中文 2+ 字片段 / 英文 4+ 字母词
        java.util.regex.Matcher zh = java.util.regex.Pattern.compile("[\\u4e00-\\u9fff]{2,}").matcher(subGoal);
        while (zh.find()) {
            if (args.contains(zh.group())) return true;
        }
        java.util.regex.Matcher en = java.util.regex.Pattern.compile("[a-zA-Z0-9_./-]{4,}").matcher(subGoal);
        while (en.find()) {
            if (args.contains(en.group().toLowerCase())) return true;
        }
        return false;
    }

    /** 计划里有大量可并行产出步时，提示用 delegate_task（只提示一次） */
    private void injectBatchDelegateHintIfNeeded(List<ChatMessage> messages, LoopState state, String sessionId) {
        if (state.batchDelegateHintSent || sessionId == null) return;
        int batchable = taskTodoStore.countBatchablePending(sessionId);
        if (batchable < 3) return;
        state.batchDelegateHintSent = true;
        log.info("批量产出提示：{} 个可并行步骤，建议 delegate_task", batchable);
        messages.add(new SystemMessage(
                "【并行加速】当前计划仍有 " + batchable + " 个可独立的产出步骤。"
                        + "请在本回合用多个 delegate_task 并行派发（每个子任务写清 goal/context/产出路径），"
                        + "不要在主循环里一个文件一个回合串行 write_file。"
                        + "子任务完成后根据摘要勾选对应 todo（附 evidence）。"));
    }

    // ==================== LLM 调用 ====================

    /** 调用 LLM 并记录 token 用量。streamSink 非空时走流式桥接，实时推送思考/答案增量。 */
    private ChatResponse callLlm(ChatModel chatModel, List<ChatMessage> messages,
                                  List<?> specsForTurn, boolean hasTools, AgentStreamSink streamSink) {
        ChatRequest.Builder reqBuilder = ChatRequest.builder().messages(messages);
        if (hasTools && !specsForTurn.isEmpty()) {
            @SuppressWarnings("unchecked")
            var typedSpecs = (List<dev.langchain4j.agent.tool.ToolSpecification>) (List<?>) specsForTurn;
            reqBuilder.toolSpecifications(typedSpecs);
        }
        ChatRequest request = reqBuilder.build();

        // 指数退避重试：临时性错误（网络抖动/超时/503）最多重试 1 次（超时类错误单次已很久）
        int maxRetries = 1;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                ChatResponse response = streamSink == null
                        ? chatModel.chat(request)
                        : callLlmStreaming(request, streamSink);
                if (response != null && response.tokenUsage() != null) {
                    String sid = currentSessionId.get();
                    if (sid != null) {
                        TokenUsageTracker.add(sid,
                                response.tokenUsage().inputTokenCount(),
                                response.tokenUsage().outputTokenCount(), 0);
                    }
                }
                return response;
            } catch (dev.langchain4j.exception.InternalServerException e) {
                // 503 通常是临时过载，值得重试
                if (attempt < maxRetries) {
                    long backoff = (long) Math.pow(2, attempt) * 1000; // 1s, 2s, 4s
                    log.warn("LLM API 503 (尝试 {}/{}): {}，{}ms 后重试", attempt + 1, maxRetries + 1, e.getMessage(), backoff);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    log.error("LLM API 503 最终失败（已重试 {} 次）: {}", maxRetries, e.getMessage());
                    return null;
                }
            } catch (java.io.IOException e) {
                // 网络错误（closed/timeout/reset/connection refused）值得重试
                if (attempt < maxRetries) {
                    long backoff = (long) Math.pow(2, attempt) * 1000;
                    log.warn("LLM API 网络错误 (尝试 {}/{}): {}，{}ms 后重试", attempt + 1, maxRetries + 1, e.getClass().getSimpleName() + ": " + e.getMessage(), backoff);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    log.error("LLM API 网络错误最终失败（已重试 {} 次）: {}", maxRetries, e.getMessage(), e);
                    return null;
                }
            } catch (Exception e) {
                // 流式 SSE 路径下，底层 IOException（连接 closed/reset、读超时等）会被 langchain4j
                // 包成通用 LangChain4jException，绕过上面的 IOException 分支。这里按"根因是否为瞬时网络错误"
                // 判断：是 → 走重试；否（参数/认证/格式错误）→ 不重试直接放弃。
                if (isTransientNetworkError(e)) {
                    if (attempt < maxRetries) {
                        long backoff = (long) Math.pow(2, attempt) * 1000; // 1s, 2s, 4s
                        log.warn("LLM API 流式断连 (尝试 {}/{}): {}，{}ms 后重试",
                                attempt + 1, maxRetries + 1, e.getClass().getSimpleName() + ": " + e.getMessage(), backoff);
                        try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue;
                    } else {
                        log.error("LLM API 流式断连最终失败（已重试 {} 次）: {}", maxRetries, e.getMessage(), e);
                        return null;
                    }
                }
                // 其他错误（参数/认证/格式）通常不值得重试
                log.error("LLM API 失败（不可重试）: {}", e.getMessage(), e);
                return null;
            }
        }
        return null; // 所有重试耗尽
    }
    /**
     * 判断异常根因是否为瞬时网络错误（值得重试）。
     * 流式 SSE 断流被 langchain4j 包成 LangChain4jException，原始 IOException 藏在 cause 链里，
     * 故沿 cause 链同时检查异常类型与 message 关键词。
     */
    private static boolean isTransientNetworkError(Throwable t) {
        for (Throwable cur = t; cur != null && cur != cur.getCause(); cur = cur.getCause()) {
            if (cur instanceof java.io.IOException
                    || cur instanceof java.util.concurrent.TimeoutException
                    || cur instanceof dev.langchain4j.exception.TimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("closed") || m.contains("reset")
                        || m.contains("timeout") || m.contains("timed out")
                        || m.contains("connection") || m.contains("broken pipe")
                        || m.contains("eof") || m.contains("goaway")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 流式调用桥接：用流式模型发请求，把思考/答案增量实时推给 sink，
     * 但通过 CountDownLatch 阻塞等到 onCompleteResponse，使外层循环的顺序控制流保持不变。
     */
    private ChatResponse callLlmStreaming(ChatRequest request, AgentStreamSink streamSink) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatResponse> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean receivedAny = new java.util.concurrent.atomic.AtomicBoolean(false);

        // 超长库表/建表类 prompt + 思考链，首包常超过 90s；按输入规模自适应
        long firstTokenTimeoutSec = estimateFirstTokenTimeoutSec(request);
        log.info("流式 LLM 等待首包超时={}s（大上下文会自动加长）", firstTokenTimeoutSec);

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                if (partialThinking == null) return;
                receivedAny.set(true);
                String t = partialThinking.text();
                if (t != null && !t.isEmpty()) {
                    try { streamSink.onThinking(t); } catch (Exception ignored) {}
                }
            }

            @Override
            public void onPartialResponse(String token) {
                if (token != null && !token.isEmpty()) {
                    receivedAny.set(true);
                    try { streamSink.onAnswerToken(token); } catch (Exception ignored) {}
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                responseRef.set(response);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        // 分段等待：每 15s 打日志，避免看起来像卡死；首包超时后再判失败
        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(firstTokenTimeoutSec);
        while (!latch.await(15, TimeUnit.SECONDS)) {
            if (receivedAny.get()) {
                // 已有思考/答案流入，再给最多 10 分钟收尾（大 SQL 生成）
                if (!latch.await(600, TimeUnit.SECONDS)) {
                    throw new java.util.concurrent.TimeoutException(
                            "流式 LLM 调用超时（已收到部分输出但未完成）");
                }
                break;
            }
            long remainSec = TimeUnit.NANOSECONDS.toSeconds(deadlineNs - System.nanoTime());
            if (remainSec <= 0) {
                throw new java.util.concurrent.TimeoutException(
                        "流式 LLM 首包超时（" + firstTokenTimeoutSec + "s 内无任何增量）");
            }
            log.info("仍在等待 LLM 首包响应… 约剩余 {}s", remainSec);
            try {
                streamSink.onThinking("\n（模型处理中，长文档/建表任务首包可能较慢，请稍候…）\n");
            } catch (Exception ignored) {}
        }
        Throwable err = errorRef.get();
        if (err != null) {
            if (err instanceof Exception ex) throw ex;
            throw new RuntimeException(err);
        }
        return responseRef.get();
    }

    /** 按请求体大小估计首包等待：思考模型 + 超长 schema 需要更久 */
    private static long estimateFirstTokenTimeoutSec(ChatRequest request) {
        long chars = 0;
        if (request != null && request.messages() != null) {
            for (ChatMessage m : request.messages()) {
                if (m == null) continue;
                String s = m.toString();
                chars += s == null ? 0 : s.length();
            }
        }
        int toolCount = request != null && request.toolSpecifications() != null
                ? request.toolSpecifications().size() : 0;
        chars += (long) toolCount * 400L;
        if (chars >= 80_000) return 420; // ~7 min
        if (chars >= 40_000) return 300; // 5 min
        if (chars >= 15_000) return 180; // 3 min
        return 120; // 默认 2 min（原 90s 对思考模型偏紧）
    }

    // ==================== 工具执行 ====================

    /** 统一执行工具调用（多工具并行，单工具串行），结果追加到 messages */
    private void executeToolCalls(List<?> toolCalls, List<ChatMessage> messages,
                                   Consumer<String> progressCallback, LoopState state) {
        // 推送进度
        if (progressCallback != null) {
            String names = toolCalls.stream()
                    .map(tc -> formatProgressMsg(toolNameOf(tc), argumentsOf(tc)))
                    .reduce((a, b) -> a + " | " + b).orElse("");
            progressCallback.accept(names);
        }

        if (toolCalls.size() > 1) {
            executeToolCallsParallel(toolCalls, messages, progressCallback, state);
        } else {
            executeToolCallSingle(toolCalls.get(0), messages, progressCallback, state);
        }
    }

    /** 并行执行多个工具 */
    private void executeToolCallsParallel(List<?> toolCalls, List<ChatMessage> messages,
                                           Consumer<String> progressCallback, LoopState state) {
        Map<String, String> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 主线程上下文快照：虚拟线程不继承 ThreadLocal，必须显式传递，
        // 否则并行批次里的 todo/memory 工具会拿到 "default" 会话 / null 用户，写错状态。
        final String ctxSessionId = currentSessionId.get();
        final Long ctxUserId = com.miniagent.agent.memory.MemoryStore.getCurrentUser();

        for (var tc : toolCalls) {
            String name = toolNameOf(tc);
            String args = argumentsOf(tc);
            String cacheKey = name + ":" + args;

            // 缓存命中
            if (CACHEABLE_TOOLS.contains(name) && state.toolResultCache.containsKey(cacheKey)) {
                log.info("  [并行缓存] {}", name);
                results.put(toolIdOf(tc) + "|" + name, state.toolResultCache.get(cacheKey));
                futures.add(CompletableFuture.completedFuture(null));
                continue;
            }

            log.info("  [并行] {}({})", name, truncate(redactSensitive(args), 150));
            if (progressCallback != null) progressCallback.accept(formatProgressMsg(name, args));

            long timeout = TOOL_TIMEOUT_SECONDS.getOrDefault(name, DEFAULT_TOOL_TIMEOUT_SECONDS);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                // 子线程内重建上下文，使 todo/memory 等依赖 ThreadLocal 的工具拿到正确会话/用户
                if (ctxSessionId != null) {
                    currentSessionId.set(ctxSessionId);
                    com.miniagent.agent.todo.TaskTodoContext.set(ctxSessionId);
                }
                if (ctxUserId != null) com.miniagent.agent.memory.MemoryStore.setCurrentUser(ctxUserId);
                try {
                    String r = toolRegistry.execute(name, args);
                    results.put(toolIdOf(tc) + "|" + name, r == null ? "" : r);
                } finally {
                    currentSessionId.remove();
                    com.miniagent.agent.todo.TaskTodoContext.clear();
                    com.miniagent.agent.memory.MemoryStore.clearCurrentUser();
                }
            }, VIRTUAL_EXECUTOR).orTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(300, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("并行工具异常: {}，等待剩余 future 完成", e.getMessage());
            for (CompletableFuture<Void> f : futures) {
                try { f.get(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
        }

        // 按顺序收集结果
        for (var tc : toolCalls) {
            String name = toolNameOf(tc);
            String result = results.getOrDefault(toolIdOf(tc) + "|" + name, "{\"error\":\"执行超时\"}");
            String cacheKey = name + ":" + argumentsOf(tc);
            if (CACHEABLE_TOOLS.contains(name) && !result.isEmpty()) {
                state.toolResultCache.put(cacheKey, result);
            }
            trackResult(name, result, state);

            // 反思机制（并行）：失败时把反思提示并入工具结果，不伪装成用户消息
            String parallelReflection = buildReflectionHint(name, argumentsOf(tc), result);
            String resultForContext = clampForContext(result, name);
            if (parallelReflection != null) {
                state.consecutiveFailures++;
                if (!state.reflectionInjected && state.consecutiveFailures >= 2) {
                    resultForContext = resultForContext + "\n\n" + parallelReflection;
                    state.reflectionInjected = true;
                    log.info("  [反思-并行] 工具结果附加失败反思提示: {}", name);
                }
            } else {
                state.consecutiveFailures = 0;
                state.reflectionInjected = false;
            }

            log.info("  [并行] {}: {}", name, truncate(redactSensitive(result), 200));
            if (traceRecorder != null) traceRecorder.recordToolResult(currentSessionId.get(), state.currentTurn, name, result, 0, result.contains("\"error\"") ? "FAILURE" : "SUCCESS");
            messages.add(ToolExecutionResultMessage.from(
                    toolIdOf(tc), name, resultForContext));
        }
    }

    /** 串行执行单个工具 */
    private void executeToolCallSingle(Object tc, List<ChatMessage> messages,
                                         Consumer<String> progressCallback, LoopState state) {
        String name = toolNameOf(tc);
        String args = argumentsOf(tc);

        // 截断保护：当本轮 tool_call 因 LENGTH 截断，非文件写入工具的参数大概率是半截 JSON，不执行
        if (state.lengthTruncatedToolCalls && !"write_file".equals(name) && !"read_file".equals(name)) {
            String truncErr = "{\"error\":\"本次工具调用的参数因模型输出长度上限被截断，无法执行。请用更短的参数重试。\"}";
            log.warn("  [截断保护] 跳过执行 {}，参数可能不完整", name);
            messages.add(ToolExecutionResultMessage.from(toolIdOf(tc), name, truncErr));
            return;
        }

        String cacheKey = name + ":" + args;

        log.info("  {}({})", name, truncate(redactSensitive(args), 150));
        if (progressCallback != null) progressCallback.accept(formatProgressMsg(name, args));

        String result;
        if (CACHEABLE_TOOLS.contains(name) && state.toolResultCache.containsKey(cacheKey)) {
            result = state.toolResultCache.get(cacheKey);
            log.info("  [缓存命中] {}", name);
        } else {
            // 外层超时兜底：即使某工具内部超时逻辑失灵（如常驻进程把读流挂死），
            // 也能在此处被强制中断，绝不让单个工具拖死整个 Agent 循环。
            // 与并行路径共用 TOOL_TIMEOUT_SECONDS 预算；给读流/清理留 10s 余量。
            long timeout = TOOL_TIMEOUT_SECONDS.getOrDefault(name, DEFAULT_TOOL_TIMEOUT_SECONDS);
            final String fName = name, fArgs = args;
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> toolRegistry.execute(fName, fArgs), VIRTUAL_EXECUTOR);
            try {
                result = future.get(timeout + 10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                future.cancel(true);
                log.warn("  工具 {} 执行超时（外层兜底 {}s），强制中断", name, timeout + 10);
                result = "{\"error\":\"工具执行超时（" + (timeout + 10) + "s），已强制中断。"
                        + "若是启动服务器/常驻进程，请改为交付文件由用户自行运行。\"}";
            } catch (Exception e) {
                future.cancel(true);
                log.warn("  工具 {} 执行异常: {}", name, e.getMessage());
                result = "{\"error\":\"工具执行异常: " + e.getMessage() + "\"}";
            }
            if (CACHEABLE_TOOLS.contains(name) && result != null) {
                state.toolResultCache.put(cacheKey, result);
            }
        }

        trackResult(name, result, state);

        // 反思机制：工具失败时把反思提示并入工具结果，不伪装成用户消息
        String reflectionHint = buildReflectionHint(name, args, result);
        String resultForContext = clampForContext(result, name);
        if (reflectionHint != null) {
            state.consecutiveFailures++;
            state.lastFailedTool = name;
            if (!state.reflectionInjected && state.consecutiveFailures >= 1) {
                resultForContext = resultForContext + "\n\n" + reflectionHint;
                state.reflectionInjected = true;
                log.info("  [反思] 工具结果附加失败反思提示: {}", name);
            }
        } else {
            state.consecutiveFailures = 0;
            state.lastFailedTool = null;
            state.reflectionInjected = false;
        }

        log.info("  {}: {}", name, truncate(redactSensitive(result), 300));
        if (traceRecorder != null) traceRecorder.recordToolResult(currentSessionId.get(), state.currentTurn, name, result, 0, result.contains("\"error\"") ? "FAILURE" : "SUCCESS");
        messages.add(ToolExecutionResultMessage.from(
                toolIdOf(tc), name, resultForContext));
    }

    /** 格式化消息列表为可读的 trace 文本（截断长内容） */
    private String formatMessagesForTrace(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ChatMessage msg : messages) {
            if (shown >= 15) { sb.append("\n... (共 ").append(messages.size()).append(" 条消息，已省略)"); break; }
            String role = "unknown";
            String text = "";
            if (msg instanceof SystemMessage sm) { role = "system"; text = sm.text(); }
            else if (msg instanceof UserMessage um) {
                role = "user";
                if (um.singleText() != null) text = um.singleText();
                else text = um.contents().stream().map(c -> c instanceof TextContent tc ? tc.text() : "[图片]").reduce("", (a, b) -> a + b);
            }
            else if (msg instanceof AiMessage am) {
                role = "assistant";
                if (am.text() != null) text = am.text();
                if (am.hasToolExecutionRequests()) text += " [tool_calls: " + am.toolExecutionRequests().size() + "]";
            }
            else if (msg instanceof ToolExecutionResultMessage tr) { role = "tool:" + tr.toolName(); text = tr.text(); }
            sb.append("\n[").append(role).append("] ").append(truncate(text, 300));
            shown++;
        }
        return sb.toString().trim();
    }

    /** 工具失败时注入反思上下文，引导模型换策略 */
    private String buildReflectionHint(String toolName, String args, String result) {
        if (result == null || result.isBlank()) return null;
        // 对于 read_file / search_code，只有明确的错误 JSON 才算失败，文件内容本身不算
        if ("read_file".equals(toolName) || "search_code".equals(toolName) || "read_package".equals(toolName)) {
            boolean isExplicitError = result.startsWith("{\"error\"");
            if (!isExplicitError) return null;
        }
        boolean failed = result.startsWith("{\"error\"")
                || (result.contains("\"error\"") && result.length() < 500)
                || result.startsWith("错误：")
                || result.startsWith("Error:");
        if (!failed) return null;

        StringBuilder hint = new StringBuilder();
        hint.append("⚠️ 工具 ").append(toolName).append(" 执行失败。\n");
        hint.append("错误：").append(result.length() > 200 ? result.substring(0, 200) : result).append("\n\n");
        hint.append("请反思并换策略：\n");

        if ("list_files".equals(toolName)) {
            String path = extractJsonField(args, "path");
            hint.append("- 路径 '").append(path).append("' 可能不存在\n");
            hint.append("- 试用户主目录：list_files(System.getProperty(\"user.home\"))\n");
            hint.append("- 试搜索：exec_command(\"where ").append(guessProgramName(path)).append("\")\n");
            hint.append("- 试 web_search 搜索程序安装位置\n");
        } else if ("exec_command".equals(toolName)) {
            hint.append("- 命令可能被安全策略拒绝或工作目录不对\n");
            hint.append("- 试用绝对路径执行\n");
            hint.append("- 试先 list_files 确认文件存在再执行\n");
        } else if ("read_file".equals(toolName)) {
            hint.append("- 文件路径可能不对，先 list_files 确认目录结构\n");
            hint.append("- 检查文件扩展名和大小写\n");
        }

        hint.append("\n不要用相同参数重试。换一个完全不同的方法。");
        return hint.toString();
    }

    /** 从路径中猜测程序名 */
    private static String guessProgramName(String path) {
        if (path == null) return "program";
        String name = path.replace("\\", "/").trim();
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        name = name.replace(".lnk", "").replace(".exe", "").replace(".app", "");
        return name.isEmpty() ? "program" : name;
    }

    /** 更新循环状态 */
    private void trackResult(String toolName, String result, LoopState state) {
        state.toolsInvoked.add(toolName);
        if ("read_file".equals(toolName) || "list_files".equals(toolName)
                || "exec_command".equals(toolName)) state.explorationCount++;
        if ("read_package".equals(toolName)) state.explorationCount += 3;
        if (WRITE_TOOLS.contains(toolName) && result != null
                && (result.contains("\"success\":true") || result.startsWith("写入成功"))) {
            state.writeFileSucceeded = true;
            // 写入成功后失效文件相关的缓存，避免后续读到过期内容
            state.toolResultCache.entrySet().removeIf(e ->
                    e.getKey().startsWith("read_file:") || e.getKey().startsWith("exec_command:")
                            || e.getKey().startsWith("list_files:") || e.getKey().startsWith("read_package:"));
        }
        if ("exec_command".equals(toolName) && result != null
                && !result.contains("\"error\"")) {
            // exec_command 可能修改文件系统，失效 read_file 缓存
            state.toolResultCache.entrySet().removeIf(e ->
                    e.getKey().startsWith("read_file:") || e.getKey().startsWith("list_files:")
                            || e.getKey().startsWith("read_package:"));
        }
        if ("image_generate".equals(toolName) && isImageGenerateUnavailable(result)) {
            state.imageGenerateUnavailable = true;
        }
        if (MEDIA_TOOLS.contains(toolName) && result != null && looksLikeMediaSuccess(result)) {
            state.mediaDelivered = true;
            state.lastMediaResult = result;
        }
    }

    // ==================== 完成判断 ====================

    /**
     * 尝试返回最终回复。返回 null 表示需要继续循环（注入了文件/计划/todo 提醒）。
     */
    private String tryReturnFinalText(AiMessage aiMessage, List<ChatMessage> messages,
                                       LoopState state, boolean explicitlyNeedsFile,
                                       int turn, int iterations, String sessionId) {
        String finalText = aiMessage.text();
        log.info("Agent收尾 {}/{}: 生成最终回复", turn + 1, iterations);

        if (finalText == null || finalText.isBlank()) {
            if (state.mediaDelivered) return ensureMarkdownImage(state.lastMediaResult);
            if (state.writeFileSucceeded && !hasBlockingTodos(sessionId)) {
                return "（文件已生成，见 workspace/）";
            }
            if (hasBlockingTodos(sessionId) || (state.requiresStructuredPlan && !taskTodoStore.hasPlan(sessionId))) {
                // 走下面的拦截逻辑
                finalText = "";
            } else {
                return "（模型未返回内容）";
            }
        }

        // 复杂任务尚未建立计划 → 拦截收尾，强制 todo.set
        if (state.requiresStructuredPlan && sessionId != null && !taskTodoStore.hasPlan(sessionId)) {
            if (state.planReminderCount < 2) {
                state.planReminderCount++;
                log.info("复杂任务未建立 todo，拦截收尾 #{}/2", state.planReminderCount);
                messages.add(new SystemMessage(
                        "【收尾被拦截】这是复杂任务，你还没有用 todo(action=set) 写出带 done_when 的计划。"
                                + "下一轮必须先调用 todo set，再逐步执行。不要直接给最终结论。"));
                return null;
            }
        }

        // 存在未完成 todo → 拦截收尾（提醒 2 次后附清单放行，避免死锁）
        if (sessionId != null && taskTodoStore.hasPlan(sessionId) && taskTodoStore.hasIncomplete(sessionId)) {
            if (state.incompleteTodoReminders < 2) {
                state.incompleteTodoReminders++;
                log.info("todo 未完成，拦截收尾 #{}/2", state.incompleteTodoReminders);
                messages.add(new SystemMessage(
                        "【收尾被拦截】仍有未完成的子任务。请继续执行「当前子目标」，"
                                + "完成后 todo update 标 completed 并提供 evidence。"
                                + "全部完成后才能最终回复。\n"
                                + taskTodoStore.render(sessionId)));
                return null;
            }
            log.warn("todo 未完成但已提醒多次，附带未完成清单后放行。轮次 {}", turn + 1);
            if (finalText == null) finalText = "";
            finalText = finalText.stripTrailing()
                    + "\n\n⚠️ 以下子任务尚未完成（可发送「继续」接着做）：\n"
                    + taskTodoStore.render(sessionId);
        }

        if (finalText == null || finalText.isBlank()) {
            if (state.mediaDelivered) return ensureMarkdownImage(state.lastMediaResult);
            if (state.writeFileSucceeded) return "（文件已生成，见 workspace/）";
            return "（模型未返回内容）";
        }

        // 媒体已交付 → 确保 markdown 图片在回复中
        if (state.mediaDelivered) {
            if (state.lastMediaResult != null && !MARKDOWN_IMAGE.matcher(finalText).find()) {
                finalText = finalText.stripTrailing() + "\n\n" + ensureMarkdownImage(state.lastMediaResult);
            }
            return finalText;
        }

        // 回复中包含图片 markdown → 直接返回
        if (MARKDOWN_IMAGE.matcher(finalText).find()) return finalText;

        // 非文件交付类任务 → 直接返回
        if (!explicitlyNeedsFile) return finalText;

        // 文件任务已落盘 → 返回
        if (state.writeFileSucceeded) return finalText;

        // 已提醒过一次 → 放行避免死循环
        if (state.fileReminderSent) {
            log.warn("已提醒 write_file 仍未落盘，放行。轮次 {}", turn + 1);
            return finalText;
        }

        // 注入一次性提醒（SystemMessage，不伪装成用户消息）
        log.info("文件任务未落盘，注入提醒。轮次 {}", turn + 1);
        state.fileReminderSent = true;
        messages.add(new SystemMessage(
                "用户要求输出文件但你还没调用 write_file，请写入后再给出产出物清单。"));
        return null;
    }

    private boolean hasBlockingTodos(String sessionId) {
        return sessionId != null && taskTodoStore.hasPlan(sessionId) && taskTodoStore.hasIncomplete(sessionId);
    }

    /** 构建达到最大迭代次数时的兜底回复 */
    private String buildMaxIterationFallback(LoopState state, int iterations) {
        StringBuilder sb = new StringBuilder();
        String sid = currentSessionId.get();
        if (state.writeFileSucceeded || state.mediaDelivered || (sid != null && taskTodoStore.hasPlan(sid))) {
            sb.append("任务轮次已达上限（").append(iterations).append("轮），但已有部分成果：");
            if (state.writeFileSucceeded) sb.append("\n- 文件已写入 workspace/");
            if (state.mediaDelivered) sb.append("\n- 图片已生成");
            if (sid != null && taskTodoStore.hasIncomplete(sid)) {
                sb.append("\n- 未完成子任务：\n").append(taskTodoStore.render(sid));
            }
            sb.append("\n\n剩余未完成的子任务可以通过发送「继续」来让我接着完成。");
            return sb.toString();
        }
        return "（达到最大迭代次数 " + iterations + "，Agent 循环终止。发送「继续」可让我接着上次进度继续。）";
    }

    // ==================== 反射工具方法 ====================

    private static String toolNameOf(Object tc) {
        try { return (String) tc.getClass().getMethod("name").invoke(tc); }
        catch (Exception e) { return "unknown"; }
    }

    private static String argumentsOf(Object tc) {
        try { return (String) tc.getClass().getMethod("arguments").invoke(tc); }
        catch (Exception e) { return ""; }
    }

    private static String toolIdOf(Object tc) {
        try { return (String) tc.getClass().getMethod("id").invoke(tc); }
        catch (Exception e) { return ""; }
    }


    /** 多模态消息中取第一段文字，供「显式要文件」正则使用；无文字则视为非文件类意图。 */
    private static String firstUserTextForFileIntent(UserMessage um) {
        if (um == null) {
            return "";
        }
        if (um.hasSingleText()) {
            return um.singleText();
        }
        for (Content c : um.contents()) {
            if (c instanceof TextContent tc) {
                return tc.text();
            }
        }
        return "";
    }

    // ==================== 工具方法 ====================

    /**
     * 兜底格式转换：确保工具返回的图片结果是 markdown 格式。
     * 即使工具已统一返回 markdown，仍需兜底处理历史遗留的 JSON/纯文本格式。
     */
    private static String ensureMarkdownImage(String result) {
        if (result == null || result.isBlank()) return result;
        // 已是 markdown
        if (result.startsWith("![") && result.contains("](")) return result;
        // JSON 中提取 markdown_images 字段（ComfyUI 旧格式兼容）
        if (result.contains("\"markdown_images\"")) {
            String extracted = extractJsonField(result, "markdown_images");
            if (extracted != null && extracted.startsWith("![")) return extracted;
        }
        // JSON 中提取 image URL（image_generate 旧格式兼容）
        if (result.contains("\"image\"") && result.contains("\"success\"")) {
            String url = extractJsonField(result, "image");
            if (url != null && !url.isBlank()) return "![生成的图片](" + url + ")";
        }
        // 纯文本（如截图路径）— 无法转换，原样返回
        return result;
    }

    /** 判断 image_generate / browser_screenshot 的返回值是否指示成功并附带了图片/文件。 */
    private boolean looksLikeMediaSuccess(String result) {
        if (result == null || result.isBlank()) return false;
        // BuiltinTools.formatImageResult 会把成功结果包成 "![图片](url)"
        if (result.startsWith("![") && result.contains("](")) return true;
        // browser_screenshot 直接返回类似 "截图已保存: ..." 的字样
        if (result.contains("截图已保存") || result.contains("saved screenshot")) return true;
        // ComfyUI 文生图/图生图：{"status":"success",...,"markdown_images":"![...](...)"}
        if (result.contains("\"status\":\"success\"")
                && (result.contains("markdown_images") || result.contains("\"images\""))) return true;
        // 兜底：JSON 里 success=true 且带 image
        return result.contains("\"success\":true") && result.contains("\"image\"");
    }

    /**
     * 生图后端不可用时，从下一轮请求中移除 image_generate，避免模型反复尝试生图、
     * 退化成写脚本/找本地图片，最后撞 maxIterations。
     */
    private boolean isImageGenerateUnavailable(String result) {
        if (result == null) return false;
        return result.contains("图片生成失败")
                || result.contains("API Key 无效")
                || result.contains("Invalid token")
                || result.contains("不要再重试");
    }

    /** 把工具结果压到可接受长度再塞进上下文（按工具类型取不同上限）。 */
    private String clampForContext(String result, String toolName) {
        if (result == null || result.isBlank()) return "{\"success\":true,\"message\":\"工具执行完成\"}";
        int limit = TOOL_RESULT_LIMITS.getOrDefault(toolName, DEFAULT_TOOL_RESULT_MAX);
        if (result.length() <= limit) return result;
        return result.substring(0, limit)
                + "\n... （结果过长已截断，共 " + result.length() + " 字符）";
    }

    /** 生成人可读的工具调用进度描述，推送给前端 */
    private static String formatProgressMsg(String toolName, String arguments) {
        return switch (toolName) {
            case "web_search"        -> "🔍 正在搜索：" + extractJsonField(arguments, "query");
            case "web_extract"       -> "🌐 正在抓取网页：" + extractJsonField(arguments, "url");
            case "image_generate"    -> "🎨 正在生成图片：" + truncate(extractJsonField(arguments, "prompt"), 40);
            case "read_file"         -> "📄 读取文件：" + extractJsonField(arguments, "path");
            case "write_file"        -> "💾 写入文件：" + extractJsonField(arguments, "path");
            case "exec_command"      -> "⚙️ 执行命令：" + truncate(extractJsonField(arguments, "command"), 50);
            case "browser_navigate"  -> "🌍 打开网页：" + extractJsonField(arguments, "url");
            case "browser_screenshot"-> "📸 截图中...";
            case "browser_click"     -> "🖱️ 点击：" + extractJsonField(arguments, "ref");
            case "browser_type"      -> "⌨️ 输入：" + truncate(extractJsonField(arguments, "text"), 30);
            case "memory"            -> "🧠 更新记忆...";
            case "http_get"          -> "📡 请求：" + extractJsonField(arguments, "url");
            default                  -> "🔧 调用工具：" + toolName;
        };
    }

    /** 从 JSON 字符串里粗提取指定字段值（不引入 Jackson 依赖，轻量实现） */
    static String extractJsonField(String json, String field) {
        if (json == null || json.isBlank()) return "";
        String key = "\"" + field + "\"";
        int ki = json.indexOf(key);
        if (ki < 0) return "";
        int colon = json.indexOf(':', ki + key.length());
        if (colon < 0) return "";
        int vs = colon + 1;
        while (vs < json.length() && Character.isWhitespace(json.charAt(vs))) vs++;
        if (vs >= json.length()) return "";
        if (json.charAt(vs) == '"') {
            int end = json.indexOf('"', vs + 1);
            return end < 0 ? "" : json.substring(vs + 1, end);
        }
        int end = vs;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(vs, end).trim();
    }

    /** 纯日志/匹配用的截断。 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 提取一行可读任务目标用于日志，避免长 prompt/密钥污染日志。 */
    private static String summarizeTaskGoal(String userText) {
        if (userText == null || userText.isBlank()) return "(empty)";
        String oneLine = userText.replaceAll("[\\r\\n]+", " ").trim();
        return truncate(redactSensitive(oneLine), 120);
    }

    /** 日志脱敏：避免 access_token、secret、api-key 等敏感信息进入控制台。 */
    private static String redactSensitive(String s) {
        if (s == null) return "";
        return s
                .replaceAll("(?i)(access_token=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(secret=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(api[_-]?key=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(\"access_token\"\\s*:\\s*\")[^\"]+\"", "$1***\"")
                .replaceAll("(?i)(\"secret\"\\s*:\\s*\")[^\"]+\"", "$1***\"")
                .replaceAll("(?i)(\"api[_-]?key\"\\s*:\\s*\")[^\"]+\"", "$1***\"");
    }
}
