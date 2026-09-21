package com.miniagent.agent.core;

import com.miniagent.common.ChatMessageTexts;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.RunStatus;
import com.miniagent.common.model.EffectiveModelContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.miniagent.agent.delegate.SubagentContext;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.execution.ToolInvocation;
import com.miniagent.agent.execution.ToolPipeline;
import com.miniagent.agent.execution.ToolRequest;
import com.miniagent.agent.hook.StopContext;
import com.miniagent.agent.hook.StopDecision;
import com.miniagent.agent.hook.StopHookChain;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import com.miniagent.agent.surface.ToolSurface;
import com.miniagent.agent.context.ContextContributorConfiguration;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.PermissionPolicy;
import com.miniagent.agent.todo.HumanYield;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.tool.AskUserQuestionTool;
import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolSideEffect;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.agent.tool.impl.ExecCommandParams;
import com.miniagent.agent.tool.impl.SongGenerateParams;
import dev.langchain4j.agent.tool.ToolSpecification;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * Agent 循环公开面：拼消息、会话 TLS，把一轮交给内部循环。
 * 工具执行只走 {@link ToolPipeline}，不在此类再写第二条执行路径。
 *
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
public class AgentLoop {

    @Autowired
    private ToolRegistry toolRegistry;
    @Autowired
    private ToolSurface toolSurface;
    @Autowired
    private ToolExecutionGuards toolExecutionGuards;
    @Autowired
    private ToolPipeline toolPipeline;
    @Autowired
    private ExecutionProperties executionProperties;
    @Autowired
    private ExecutionControl executionControl;
    @Autowired
    private ContextCompressor contextCompressor;
    @Autowired
    private StreamingChatModel streamingChatModel;
    @Autowired
    private TaskTodoStore taskTodoStore;
    @Autowired
    private StopHookChain stopHookChain;
    /** 轨迹记录器（可选，注入后自动记录每步执行） */
    private TraceRecorder traceRecorder;
    public void setTraceRecorder(TraceRecorder traceRecorder) { this.traceRecorder = traceRecorder; }
    /** 流式事件中枢（用于运行中消息注入） */
    private SessionEventCenter eventCenter;
    public void setEventCenter(SessionEventCenter eventCenter) { this.eventCenter = eventCenter; }
    /**
     * 工具执行事件出口（可选）。缺了它 {@code AgentEvent.EventType.TOOL_EXECUTION}
     * 就只有消费者没有生产者 —— 记忆巩固的 Episode 统计会恒为「执行了 0 个工具调用」。
     */
    private ToolExecutionSink toolExecutionSink;
    public void setToolExecutionSink(ToolExecutionSink sink) { this.toolExecutionSink = sink; }

    public static final String LOOP_MAX_ITERATIONS = "MAX_ITERATIONS";
    public static final String PERM_ASK = "PERM_ASK";
    public static final String USER_QUESTION = "USER_QUESTION";
    public static final String OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";
    public static final String CANCELLED = "CANCELLED";
    public static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
    public static final String RESOURCE_QUOTA_EXCEEDED = "RESOURCE_EXCEEDED";
    public static final String STEP_SEGMENT_DONE = "本步已完成。";
    private static final int FAIL_REPEAT_ABORT = 3;

    public record LoopOutcome(String text, String endReason, List<ChatMessage> messages,
                              List<String> toolsInvoked, ToolErrorCode lastErrorCode) {
        public LoopOutcome {
            toolsInvoked = toolsInvoked == null ? List.of() : List.copyOf(toolsInvoked);
            lastErrorCode = lastErrorCode == null ? ToolErrorCode.NONE : lastErrorCode;
        }
    }
    /** 虚拟线程池：工具并行执行专用，IO 密集型任务零开销 */
    private static final java.util.concurrent.ExecutorService VIRTUAL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    /** 当前会话ID（供 token 追踪使用） */
    private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    public static void setCurrentSession(String sessionId) { currentSessionId.set(sessionId); }
    public static void clearCurrentSession() { currentSessionId.remove(); }
    public static String getCurrentSession() { return currentSessionId.get(); }

    /**
     * 本轮请求绑定的模型（子 Agent / 并行工具线程可读取）。
     *
     * <p>真正的持有者是 common 模块的 {@link EffectiveModelContext}：tools、planner 这些
     * 看不到本类的模块也要读同一份「当前模型」，持有者必须落在公共祖先上。
     * 以下方法保留为转发入口，既有调用点无需改名。
     */
    public static void setCurrentModels(ChatModel chat, StreamingChatModel streaming) {
        EffectiveModelContext.set(chat, streaming);
    }

    public static void clearCurrentModels() {
        EffectiveModelContext.clear();
    }

    public static ChatModel getCurrentChatModel() { return EffectiveModelContext.currentChat(); }
    public static StreamingChatModel getCurrentStreamingModel() { return EffectiveModelContext.currentStreaming(); }
    private static final int MAX_EXPLORATION_CALLS = 40;  // read_file + list_files + exec_command 总上限（放开：复杂任务定位文件常需多次读取）

    /** 单次请求工作窗口（估算 token）。与 ContextCompressor 共用。 */
    @Value("${agent.context.max-tokens:512000}")
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
            Map.entry("browser_evaluate",   8000),
            Map.entry("browser_navigate",   5000),
            Map.entry("http_get",           8000),
            Map.entry("http_post",          8000),
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
            Map.entry("http_post",           30L),
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
            Map.entry("browser_extract_text", 300L),
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
     * 产生直接交付物（图片/音视频）的工具白名单。
     * 截图是探路手段，不算交付（否则读网页任务会被强制收尾）。
     *
     * <p>只列<b>真实注册</b>的工具。原先的 {@code video_generate} / {@code audio_generate} / {@code tts}
     * 全仓没有任何 {@code registry.register}，是永远不会命中的死名字，已清掉。</p>
     */
    private static final Set<String> MEDIA_TOOLS = Set.of(
            "image_generate",
            SongGenerateParams.TOOL_NAME,
            "comfyui_txt2img",
            "comfyui_img2img",
            "comfyui_img2video",
            "comfyui_tts"
    );

    /** 会真正落盘的工具。 */
    private static final Set<String> WRITE_TOOLS = Set.of(
            "write_file", "browser_extract_text"
    );

    /** 虚拟列表页：滚动/evaluate 探路，不是交付。 */
    static final Set<String> BROWSER_PROBE_TOOLS = Set.of(
            "browser_evaluate", "browser_scroll", "browser_screenshot");
    /** 连续探测超过此次数后从工具面拿掉 evaluate/scroll。 */
    static final int BROWSER_PROBE_CAP = 4;
    /** 单次探测结果达到此长度：本节可 append，不是整篇已完成。 */
    static final int BROWSER_EXTRACT_WRITE_CHARS = 1500;
    static final String BROWSER_WRITE_NOW_HINT =
            "【已拿到本节正文】目录未走完则继续 browser_extract_text；"
                    + "不要把最终成稿当成已完成。";
    static final String BROWSER_PROBE_CAP_HINT =
            "【滚动探测多轮】改用 browser_extract_text（有目录会一次抽全），"
                    + "写入 _source.md；不要把最终成稿当成已完成。";
    static final String BROWSER_PROBE_DENIED =
            "禁止继续 browser_evaluate/scroll 探路。"
                    + "立刻 browser_extract_text（默认写入 _source.md）。";

    /** 可缓存的只读工具：同一请求内重复调用直接返回缓存 */
    private static final Set<String> CACHEABLE_TOOLS = Set.of(
            "read_file", "list_files", "web_search", "web_extract", "http_get",
            "read_package"
    );

    private boolean isCacheableTool(String name) {
        if (name == null || !CACHEABLE_TOOLS.contains(name)) {
            return false;
        }
        ToolDescriptor descriptor = toolExecutionGuards.descriptor(name);
        return descriptor.sideEffect() == ToolSideEffect.READ_ONLY && descriptor.idempotent();
    }

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
        if (StringUtils.isNotBlank(systemMessage)) {
            messages.add(new SystemMessage(systemMessage));
        }
        if (Objects.nonNull(taskPlan)) {
            messages.add(new SystemMessage(taskPlan.toPromptBlock(
                    LoopTurnContext.current().hardGate())));
        }
        if (Objects.nonNull(chatHistory)) {
            messages.addAll(chatHistory);
        }
        messages.add(new UserMessage(userMessage));
        return executeLoop(chatModel, messages, Optional.ofNullable(userMessage).orElse(""),
                maxIterations, progressCallback, taskPlan, streamSink).text();
    }

    public LoopOutcome runOutcome(ChatModel chatModel,
                                  String systemMessage,
                                  String userMessage,
                                  List<ChatMessage> chatHistory,
                                  int maxIterations,
                                  Consumer<String> progressCallback,
                                  TaskPlan taskPlan,
                                  AgentStreamSink streamSink) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StringUtils.isNotBlank(systemMessage))
            messages.add(new SystemMessage(systemMessage));
        if (Objects.nonNull(taskPlan))
            messages.add(new SystemMessage(taskPlan.toPromptBlock(
                    LoopTurnContext.current().hardGate())));
        if (Objects.nonNull(chatHistory))
            messages.addAll(chatHistory);
        messages.add(new UserMessage(userMessage));
        return executeLoop(chatModel, messages, Optional.ofNullable(userMessage).orElse(""),
                maxIterations, progressCallback, taskPlan, streamSink);
    }

    public LoopOutcome continueLoop(ChatModel chatModel,
                                    List<ChatMessage> messages,
                                    String userTextForFilePattern,
                                    int maxIterations,
                                    Consumer<String> progressCallback,
                                    TaskPlan taskPlan,
                                    AgentStreamSink streamSink) {
        return executeLoop(chatModel, messages, Optional.ofNullable(userTextForFilePattern).orElse(""),
                maxIterations, progressCallback, taskPlan, streamSink);
    }

    /**
     * 简化调用：无历史消息，默认最多 MAX_ITERATIONS 轮
     */
    public String run(ChatModel chatModel, String systemMessage, String userMessage) {
        return run(chatModel, systemMessage, userMessage, null,
                executionProperties.getMaxIterations(), null);
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
        return runWithMultimodal(chatModel, systemMessage, userMessage, chatHistory,
                maxIterations, progressCallback, taskPlan, null);
    }

    public String runWithMultimodal(ChatModel chatModel,
                                    String systemMessage,
                                    UserMessage userMessage,
                                    List<ChatMessage> chatHistory,
                                    int maxIterations,
                                    Consumer<String> progressCallback,
                                    TaskPlan taskPlan,
                                    AgentStreamSink streamSink) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StringUtils.isNotBlank(systemMessage)) {
            messages.add(new SystemMessage(systemMessage));
        }
        if (Objects.nonNull(taskPlan)) {
            messages.add(new SystemMessage(taskPlan.toPromptBlock(
                    LoopTurnContext.current().hardGate())));
        }
        if (Objects.nonNull(chatHistory)) {
            messages.addAll(chatHistory);
        }
        messages.add(userMessage);
        return executeLoop(chatModel, messages, firstUserTextForFileIntent(userMessage),
                maxIterations, progressCallback, taskPlan, streamSink).text();
    }

    public LoopOutcome runWithMultimodalOutcome(ChatModel chatModel,
                                                String systemMessage,
                                                UserMessage userMessage,
                                                List<ChatMessage> chatHistory,
                                                int maxIterations,
                                                Consumer<String> progressCallback,
                                                TaskPlan taskPlan,
                                                AgentStreamSink streamSink) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StringUtils.isNotBlank(systemMessage))
            messages.add(new SystemMessage(systemMessage));
        if (Objects.nonNull(taskPlan))
            messages.add(new SystemMessage(taskPlan.toPromptBlock(
                    LoopTurnContext.current().hardGate())));
        if (Objects.nonNull(chatHistory))
            messages.addAll(chatHistory);
        messages.add(userMessage);
        return executeLoop(chatModel, messages, firstUserTextForFileIntent(userMessage),
                maxIterations, progressCallback, taskPlan, streamSink);
    }

    /**
     * 与 {@link #run} 共用的主循环。
     * {@code progressCallback} 非空时，每次工具调用前后推送进度文本（用于 SSE 实时反馈）。
     */
    // ==================== Loop 状态 ====================

    /** Agent 循环可变状态，避免 executeLoop 里满天飞的局部变量 */
    private static class LoopState {
        final String runId = java.util.UUID.randomUUID().toString().substring(0, 12);
        int currentTurn = 0;  // 当前迭代轮次，供工具执行路径使用
        final Set<String> toolsInvoked = new LinkedHashSet<>();
        ToolErrorCode lastErrorCode = ToolErrorCode.NONE;
        boolean writeFileSucceeded = false;
        boolean mediaDelivered = false;
        boolean mediaReminderSent = false;
        int mediaIgnoreCount = 0;
        String lastMediaResult = null;
        boolean imageGenerateUnavailable = false;
        boolean fileReminderSent = false; // 是否已注入过一次性文件落盘提醒
        final Map<String, String> toolResultCache = new HashMap<>();
        final Map<String, Integer> failDupCounter = new HashMap<>();
        /** 硬闸门拒绝次数，按工具名计：闸门拒的是工具本身，换参数重试永远过不去 */
        final Map<String, Integer> gateDenyCounter = new java.util.concurrent.ConcurrentHashMap<>();
        int explorationCount = 0;
        int consecutiveFailures = 0;  // 连续同类工具失败计数
        String lastFailedTool = null; // 上次失败的工具名
        SystemMessage currentSubGoalMsg = null; // 框架注入的「当前子目标」可刷新指针消息
        String lastSubGoalText = null;          // 上次推送给前端的子目标文字（去重用）
        int lastSubGoalDone = 0;
        int lastSubGoalTotal = 0;
        int llmFailureCount = 0;  // 连续 LLM 调用失败计数（用于降级策略）
        int lengthTruncationCount = 0; // 连续输出被长度上限截断计数（用于分块续写引导/兜底终止）
        /** 主循环结束原因，finally 打在 AGENT_LOOP_END（勿另写 LOOP_END） */
        String loopEndReason = "DONE";
        boolean injectAppendHintAfterTools = false; // tool_call 被长度截断：工具执行后需注入续写引导
        boolean lengthTruncatedToolCalls = false; // 当前轮 tool_call 参数可能被截断
        boolean requiresStructuredPlan = false; // 复杂任务：未 set 计划前只放行 todo
        int planReminderCount = 0;              // 催促先写 todo 的次数
        int incompleteTodoReminders = 0;        // 未完成 todo 却试图收尾的次数
        boolean goalAnchorInjected = false;     // 是否已注入任务目标锚定
        /** 寒暄/轻问答：不吃上一轮残留 todo，也不拦截收尾 */
        boolean lightQa = false;
        volatile String permissionAskTool = null;
        volatile String userQuestionText = null;
        volatile boolean unknownOutcome = false;
        volatile String unknownOutcomeMessage = "";

        void noteStructuredResult(String toolName, ToolResult result) {
            if (result == null || result.status() != com.miniagent.agent.tool.ToolStatus.UNKNOWN) {
                return;
            }
            // 页面操作超时后可以 snapshot 核验，不必把整条任务打死
            if (ToolConcurrencyPolicy.isOutcomeVerifiable(toolName)) {
                return;
            }
            unknownOutcome = true;
            unknownOutcomeMessage = Objects.requireNonNullElse(result.message(), "");
        }

        void noteToolFinished(String name, String args, String result) {
            String key = name + "|" + (args == null ? "" : args);
            if (TraceRecorder.isFailedResult(result))
                failDupCounter.merge(key, 1, Integer::sum);
            else
                failDupCounter.remove(key);
        }

        void noteGateDenied(String name) {
            if (name != null) {
                gateDenyCounter.merge(name, 1, Integer::sum);
            }
        }

        boolean gateBlocked(String name) {
            return gateDenyCounter.getOrDefault(name, 0) >= FAIL_REPEAT_ABORT;
        }

        boolean allFailedRepeated(List<?> toolCalls) {
            if (toolCalls == null || toolCalls.isEmpty()) {
                return false;
            }
            for (var tc : toolCalls) {
                String name = toolNameOf(tc);
                // 被闸门反复拒掉的工具：参数换了也是拒，不能让参数变化把 failDupCounter 清零
                if (gateBlocked(name)) {
                    continue;
                }
                String key = name + "|" + argumentsOf(tc);
                if (failDupCounter.getOrDefault(key, 0) < FAIL_REPEAT_ABORT)
                    return false;
            }
            return true;
        }

        /** 被闸门锁死的工具名，用于终止时说清是哪一步的工具面不够。 */
        List<String> blockedTools() {
            return gateDenyCounter.entrySet().stream()
                    .filter(e -> e.getValue() >= FAIL_REPEAT_ABORT)
                    .map(Map.Entry::getKey)
                    .toList();
        }
    }

    private static LoopOutcome finish(String text, LoopState state, List<ChatMessage> messages) {
        return new LoopOutcome(
                text == null ? "" : text,
                state.loopEndReason,
                messages,
                List.copyOf(state.toolsInvoked),
                state.lastErrorCode);
    }

    // ==================== executeLoop ====================

    private LoopOutcome executeLoop(ChatModel chatModel,
                              List<ChatMessage> messages,
                              String userTextForFilePattern,
                              int maxIterations,
                              Consumer<String> progressCallback,
                              TaskPlan taskPlan,
                              AgentStreamSink streamSink) {
        ToolSurface.Resolved base = resolveBaseSurface(taskPlan);
        int iterations = executionProperties.capIterations(maxIterations);

        LoopState state = new LoopState();
        boolean explicitlyNeedsFile = EXPLICIT_FILE_REQ.matcher(
                Optional.ofNullable(userTextForFilePattern).orElse("")).find();
        String taskGoal = summarizeTaskGoal(
                Objects.nonNull(taskPlan) ? taskPlan.taskGoal() : userTextForFilePattern);
        state.requiresStructuredPlan = Objects.nonNull(taskPlan) && taskPlan.requiresStructuredPlan();
        state.lightQa = Objects.nonNull(taskPlan) && taskPlan.signals().lightTurn();

        String sessionId = currentSessionId.get();
        executionControl.heartbeat(sessionId);
        if (!state.lightQa
                && sessionId != null
                && taskTodoStore.hasAwaitingConfirm(sessionId)
                && !HumanYield.looksLikeBareContinue(userTextForFilePattern)) {
            taskTodoStore.confirmAwaiting(sessionId, userTextForFilePattern);
            log.info("HITL 用户答复，放行 awaiting_confirm session={}", sessionId);
        }

        // sub-goal 栈播种：用 TaskPlan 里已有的 steps 预填 todo（仅当该 session 还没有计划时）
        // 复用上游 executionId；仅当本循环自己创建时才在 finally end（避免子 Agent 清掉父上下文）
        final boolean ownsExecution = Objects.nonNull(traceRecorder) && !traceRecorder.isActive();
        if (Objects.nonNull(traceRecorder)) {
            traceRecorder.ensureExecution(sessionId, userTextForFilePattern);
            boolean sub = SubagentContext.isActive();
            String loopType = sub ? "SUBAGENT_LOOP_START" : "AGENT_LOOP_START";
            var startStep = traceRecorder.recordNode(sessionId, 0, loopType,
                    "{\"signals\":\""
                            + (Objects.isNull(taskPlan)
                            ? TaskSignals.NONE.describe() : taskPlan.signals().describe())
                            + "\",\"requiresStructuredPlan\":"
                            + (Objects.nonNull(taskPlan) && taskPlan.requiresStructuredPlan()) + "}",
                    RunStatus.RUNNING.name(), 0);
            if (sub && Objects.nonNull(startStep) && Objects.nonNull(startStep.getId())) {
                // 后续子步骤挂到本 START 下（同 execution 树状嵌套）
                traceRecorder.enterChildScope(startStep.getId());
            }
        }
        try {
        if (!LoopTurnContext.current().hardGate()
                && Objects.nonNull(taskPlan)
                && Objects.nonNull(taskPlan.steps())
                && !taskPlan.steps().isEmpty()) {
            boolean seeded = taskTodoStore.seedFromSteps(sessionId, taskPlan.steps());
            if (seeded) {
                log.info("sub-goal 栈已播种 {} 步: {}", taskPlan.steps().size(), taskGoal);
                if (Objects.nonNull(traceRecorder)) {
                    traceRecorder.recordNode(sessionId, 0, "TASK_SEED",
                            "{\"steps\":" + taskPlan.steps().size() + "}", RunStatus.SUCCESS.name(), 0);
                }
            }
        }

        if (Objects.nonNull(taskPlan)) {
            log.info("Agent计划: signals=[{}], taskGoal='{}', requiresPlan={}, toolSurface={}",
                    taskPlan.signals().describe(), taskGoal, taskPlan.requiresStructuredPlan(),
                    surfaceLabel(base));
        }
        log.info("Agent任务开始: taskGoal='{}', maxIterations={}, toolsAvailable={}, explicitFileDelivery={}, requiresPlan={}",
                taskGoal, iterations, base.names().size(), explicitlyNeedsFile, state.requiresStructuredPlan);

        for (int turn = 0; turn < iterations; turn++) {
            ExecutionControl.StopReason turnStop = executionControl.afterModelTokens(sessionId, 0);
            if (turnStop != ExecutionControl.StopReason.NONE) {
                state.loopEndReason = controlEndReason(turnStop);
                return finish(controlMessage(turnStop), state, messages);
            }
            // 检查用户执行中追加的消息
            if (Objects.nonNull(eventCenter)) {
                java.util.List<String> injected = eventCenter.takePendingUserMessages(sessionId);
                if (!injected.isEmpty()) {
                    for (String msg : injected) {
                        messages.add(new UserMessage(msg));
                        log.info("用户追加指令注入: {}", msg.length() > 100 ? msg.substring(0, 100) + "..." : msg);
                    }
                    if (Objects.nonNull(streamSink)) {
                        streamSink.onThinking(MessageConstants.AGENT_SUBTASK_PROMPT);
                    }
                    if (Objects.nonNull(traceRecorder)) traceRecorder.recordThinking(sessionId, turn,
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
            if (turn == 0 && LoopTurnContext.current().hardGate()) {
                messages.add(new SystemMessage(
                        "【规划器已投影任务图】禁止 todo.set/clear。"
                                + "不要 list_files 探盘，不要重建计划。"
                                + "立刻执行 ActionProposal 中的当前步骤。"));
            }

            // 轻问答不挂上一轮残留子目标，避免「你好」被旧图任务劫持
            if (!state.lightQa) {
                refreshSubGoal(messages, state, sessionId, streamSink);
            }

            String subGoalLog = state.lightQa
                    ? taskGoal
                    : Optional.ofNullable(state.lastSubGoalText).orElse(taskGoal);
            state.currentTurn = turn;
            if (Objects.nonNull(traceRecorder)) {
                traceRecorder.recordTurnStart(sessionId, turn, state.lastSubGoalText,
                        state.lastSubGoalDone, state.lastSubGoalTotal);
            }

            var specsForTurn = specsAndBindTurnTools(base, state, messages);
            log.info("Agent循环第 {}/{} 轮: {}, messages={}, availableTools={}, alreadyInvoked={}",
                    turn + 1, iterations, truncate(subGoalLog, 80), messages.size(),
                    specsForTurn.size(), state.toolsInvoked);
            // 记录 LLM 请求上下文（超长 prompt 截断，避免轨迹写入拖死线程）
            if (Objects.nonNull(traceRecorder)) {
                String promptCtx = truncate(formatMessagesForTrace(messages), 8000);
                traceRecorder.recordThinking(sessionId, turn,
                        "【LLM 请求】消息数: " + messages.size() + ", 工具数: " + specsForTurn.size() + "\n\n" + promptCtx);
            }
            log.info("开始调用 LLM（流式={}，工具数={}）…", Objects.nonNull(streamSink), specsForTurn.size());
            long llmStartMs = System.currentTimeMillis();
            ChatResponse response = callLlm(chatModel, messages, specsForTurn,
                    !specsForTurn.isEmpty(), streamSink);
            log.info("LLM 调用结束，耗时 {}ms，response={}",
                    System.currentTimeMillis() - llmStartMs, Objects.isNull(response) ? "null" : "ok");
            long llmEndMs = System.currentTimeMillis();
            long estimatedTokens = response != null && response.tokenUsage() != null
                    ? response.tokenUsage().inputTokenCount() + response.tokenUsage().outputTokenCount()
                    : Math.max(1, messages.size() * 32L);
            ExecutionControl.StopReason modelStop = executionControl.afterModelTokens(sessionId, estimatedTokens);
            if (modelStop != ExecutionControl.StopReason.NONE) {
                state.loopEndReason = controlEndReason(modelStop);
                return finish(controlMessage(modelStop), state, messages);
            }

            // 记录 LLM 响应
            if (Objects.nonNull(traceRecorder) && Objects.nonNull(response)) {
                AiMessage ai = response.aiMessage();
                // 记录 LLM 调用耗时
                int inTokens = Objects.nonNull(response.tokenUsage()) ? response.tokenUsage().inputTokenCount() : 0;
                int outTokens = Objects.nonNull(response.tokenUsage()) ? response.tokenUsage().outputTokenCount() : 0;
                traceRecorder.recordLlmLatency(sessionId, turn, llmEndMs - llmStartMs, inTokens, outTokens);
                // 记录决策推理（模型在调工具前的思考文字）
                if (ai.hasToolExecutionRequests() && StringUtils.isNotBlank(ai.text())) {
                    traceRecorder.recordDecision(sessionId, turn, ai.text());
                }
                String respText = "【LLM 响应】";
                if (StringUtils.isNotBlank(ai.text())) {
                    respText += "\n文本: " + truncate(ai.text(), 2000);
                }
                if (ai.hasToolExecutionRequests()) {
                    respText += "\n工具调用: " + ai.toolExecutionRequests().size() + " 个";
                    for (var tc : ai.toolExecutionRequests()) {
                        respText += "\n  - " + toolNameOf(tc) + "(" + truncate(argumentsOf(tc), 500) + ")";
                    }
                }
                if (Objects.nonNull(response.tokenUsage())) {
                    respText += "\nToken: input=" + response.tokenUsage().inputTokenCount() + ", output=" + response.tokenUsage().outputTokenCount();
                }
                traceRecorder.recordThinking(sessionId, turn, respText);
            }

            if (Objects.isNull(response)) {
                state.llmFailureCount++;
                if (Objects.nonNull(traceRecorder)) {
                    traceRecorder.recordLlmCallError(sessionId, turn, MessageConstants.AGENT_LLM_NO_RESPONSE, state.llmFailureCount);
                }
                if (state.llmFailureCount == 1) {
                    log.warn("LLM 调用失败（第 {} 次），注入恢复提示后继续", state.llmFailureCount);
                    if (Objects.nonNull(traceRecorder)) {
                        traceRecorder.recordLlmRetry(sessionId, turn, "reconnect_hint", state.llmFailureCount);
                    }
                    if (Objects.nonNull(streamSink)) {
                        streamSink.onThinking("\n（模型响应超时，正在重试...）\n");
                    }
                    messages.add(new SystemMessage(MessageConstants.AGENT_LLM_NETWORK_RECONNECT));
                    continue;
                } else if (state.llmFailureCount == 2 && messages.size() > 20) {
                    log.warn("LLM 连续失败 2 次且上下文过长（{} 条），裁剪至最近 10 条后做最终尝试", messages.size());
                    if (Objects.nonNull(traceRecorder)) {
                        traceRecorder.recordLlmRetry(sessionId, turn, "trim_context", state.llmFailureCount);
                    }
                    if (Objects.nonNull(streamSink)) {
                        streamSink.onThinking("\n（第二次重试失败，正在精简上下文后做最后尝试...）\n");
                    }
                    List<ChatMessage> trimmed = new ArrayList<>();
                    int sysEnd = 0;
                    for (int i = 0; i < Math.min(5, messages.size()); i++) {
                        if (messages.get(i) instanceof SystemMessage) {
                            sysEnd = i + 1;
                        } else {
                            break;
                        }
                    }
                    trimmed.addAll(messages.subList(0, sysEnd));
                    int keep = Math.min(10, messages.size() - sysEnd);
                    trimmed.addAll(messages.subList(messages.size() - keep, messages.size()));
                    messages = trimmed;
                    state.currentSubGoalMsg = null;
                    continue;
                } else {
                    log.error("LLM 调用连续失败 {} 次（已尝试恢复与降级），任务终止", state.llmFailureCount);
                    if (Objects.nonNull(traceRecorder)) {
                        traceRecorder.recordError(sessionId, turn, "LLM 连续失败 " + state.llmFailureCount + " 次");
                    }
                    state.loopEndReason = RunStatus.FAILURE.name();
                    return finish(MessageConstants.AGENT_LLM_NETWORK_FAILED, state, messages);
                }
            }
            // 成功后重置失败计数
            state.llmFailureCount = 0;

            LlmTurn llmTurn = LlmTurn.classify(response);
            if (llmTurn.status() == LlmTurn.Status.REFUSED
                    || llmTurn.status() == LlmTurn.Status.EMPTY) {
                Optional<String> projected = ToolResultProjector.project(messages);
                if (projected.isPresent()) {
                    return finishProjected(
                            projected.get(), state, messages, streamSink, turn, sessionId);
                }
                if (llmTurn.status() == LlmTurn.Status.REFUSED) {
                    return finishUnusableTurn(
                            ErrorCode.AGENT_LLM_REFUSED,
                            MessageConstants.AGENT_LLM_REFUSED,
                            llmTurn, state, messages, streamSink);
                }
            }

            AiMessage aiMessage = response.aiMessage();
            if (aiMessage == null) {
                aiMessage = AiMessage.from("");
            }
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
                        state.loopEndReason = RunStatus.SUCCESS.name();
                        return finish("内容较长、多次写入后仍超出单轮输出上限。已写入的部分见 workspace/，建议把需求拆成更小的文件分别生成。",
                                state, messages);
                    }
                    state.loopEndReason = RunStatus.FAILURE.name();
                    return finish("要生成的内容超出了单轮输出上限，且多次分块续写仍未完成。建议把任务拆小（例如把 3D 仿真拆成 HTML 骨架、JS 逻辑、样式分别生成）后再试。",
                            state, messages);
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
                // 封存本轮已流出正文为过程说明（前端进时间线，不清空历史）
                if (Objects.nonNull(streamSink)) {
                    streamSink.onAnswerReset();
                }
                var toolCalls = aiMessage.toolExecutionRequests();
                log.info("模型请求 {} 个工具调用,tools:{}", toolCalls.size(),toolCalls);
                if (Objects.nonNull(traceRecorder)) {
                    for (var tc : toolCalls) {
                        traceRecorder.recordToolCall(sessionId, turn, toolNameOf(tc), argumentsOf(tc));
                    }
                }

                executeToolCalls(toolCalls, messages, progressCallback, state);
                if (state.unknownOutcome) {
                    state.loopEndReason = OUTCOME_UNKNOWN;
                    return finish("工具调用可能已产生副作用，但终态无法确认。"
                            + state.unknownOutcomeMessage + " 请先核验实际状态，再决定是否重试或补偿。",
                            state, messages);
                }
                if (state.permissionAskTool != null) {
                    log.info("等待用户批准工具 {}", state.permissionAskTool);
                    state.loopEndReason = PERM_ASK;
                    return finish(String.format(
                            MessageConstants.AGENT_PERM_ASK_WAIT, state.permissionAskTool),
                            state, messages);
                }
                if (state.userQuestionText != null) {
                    log.info("等待用户回答问题");
                    state.loopEndReason = USER_QUESTION;
                    return finish(state.userQuestionText, state, messages);
                }
                if (focusTodosCompleted(sessionId)
                        && taskTodoStore.hasIncomplete(sessionId)) {
                    log.info("提案 focus todo 已完成，结束本段");
                    state.loopEndReason = RunStatus.SUCCESS.name();
                    return finish(STEP_SEGMENT_DONE, state, messages);
                }
                if (state.allFailedRepeated(toolCalls)) {
                    List<String> blocked = state.blockedTools();
                    log.warn("死循环检测：失败工具重复 >= {} 次，终止（闸门锁死: {}）",
                            FAIL_REPEAT_ABORT, blocked);
                    state.loopEndReason = "DUP_TOOLS";
                    String why = blocked.isEmpty()
                            ? "我连续多次用相同参数调用同样的工具但没拿到新结果，停止避免空转。"
                            : "工具 " + blocked + " 不在本步骤允许的工具面内，重试多次仍被闸门拒绝，"
                                    + "停止避免空转；这类操作需要由具备对应能力的步骤来做。";
                    return finish(why + "已调用：" + state.toolsInvoked, state, messages);
                }

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
                            state.loopEndReason = "MEDIA_ABORT";
                            return finish(Optional.ofNullable(state.lastMediaResult)
                                    .orElse("图片已生成。"), state, messages);
                        }
                        messages.add(new SystemMessage(
                                "图片已经生成完毕，不要再调用任何工具，直接输出 markdown 图片链接给用户。"));
                    }
                }

                int msgCountBefore = messages.size();
                messages = contextCompressor.maybeCompress(messages, maxContextTokens, sessionId);
                if (Objects.nonNull(traceRecorder) && messages.size() < msgCountBefore) {
                    traceRecorder.recordCompression(sessionId, turn, msgCountBefore, messages.size(), 0, 0);
                }
                continue;
            }

            // 3) 模型返回文本（无工具调用）→ 模型主动收尾，作为最终回复
            String result = tryReturnFinalText(aiMessage, messages, state, explicitlyNeedsFile,
                    turn, iterations, sessionId);
            if (Objects.nonNull(result)) {
                if (Objects.nonNull(traceRecorder)) {
                    traceRecorder.recordAnswer(sessionId, turn, result);
                }
                state.loopEndReason = RunStatus.SUCCESS.name();
                return finish(result, state, messages);
            }
            // tryReturnFinalText 注入了提醒，继续循环
        }

        log.warn("Agent 循环达到最大迭代次数 {}", iterations);
        state.loopEndReason = LOOP_MAX_ITERATIONS;
        return finish(buildMaxIterationFallback(state, iterations), state, messages);
        } finally {
            if (Objects.nonNull(traceRecorder)) {
                if (SubagentContext.isActive()) {
                    traceRecorder.exitChildScope();
                    traceRecorder.recordNode(sessionId, 0, "SUBAGENT_LOOP_END", "{}", RunStatus.SUCCESS.name(), 0);
                } else {
                    String reason = state.loopEndReason == null ? "DONE" : state.loopEndReason;
                    traceRecorder.recordAgentLoopEnd(sessionId, Math.max(0, state.currentTurn), reason);
                    if (LOOP_MAX_ITERATIONS.equalsIgnoreCase(reason)
                            || "MEDIA_ABORT".equalsIgnoreCase(reason)) {
                        traceRecorder.recordAborted(
                                sessionId, Math.max(0, state.currentTurn), reason);
                    }
                }
            }
            if (ownsExecution && Objects.nonNull(traceRecorder)) {
                traceRecorder.endExecution(state.loopEndReason == null ? RunStatus.SUCCESS.name() : state.loopEndReason);
            }
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
        if (Objects.nonNull(sg) && sg.text().equals(state.lastSubGoalText) && Objects.nonNull(state.currentSubGoalMsg)
                && messages.contains(state.currentSubGoalMsg)) {
            return;
        }

        // 先移除上一条指针消息（如果还在 messages 里）
        if (Objects.nonNull(state.currentSubGoalMsg)) {
            messages.remove(state.currentSubGoalMsg);
            state.currentSubGoalMsg = null;
        }
        if (Objects.isNull(sg)) return; // 无可执行子目标：不注入

        String pointer = "当前子目标 (#" + sg.position() + "/" + sg.total() + ")：" + sg.text()
                + "\n聚焦完成这一步即可，完成后用 todo 工具把它标记为 completed 再进入下一步。";
        SystemMessage msg = new SystemMessage(pointer);
        messages.add(msg);
        state.currentSubGoalMsg = msg;

        // 子目标文字变化才推前端，避免重复事件
        // 子目标是状态指针：只更新 LoopState + 前端，不落独立 Trace 节点（并入 PLAN 元数据）
        if (!sg.text().equals(state.lastSubGoalText)) {
            state.lastSubGoalText = sg.text();
            state.lastSubGoalDone = sg.done();
            state.lastSubGoalTotal = sg.total();
            if (Objects.nonNull(streamSink)) {
                try { streamSink.onSubGoal(sg.text(), sg.done(), sg.total()); }
                catch (Exception ignored) {}
            }
        } else {
            state.lastSubGoalDone = sg.done();
            state.lastSubGoalTotal = sg.total();
        }
    }


    private ToolSurface.Resolved resolveBaseSurface(TaskPlan plan) {
        LoopTurnPolicy policy = LoopTurnContext.current();
        if (toolSurface == null) {
            return new ToolSurface.Resolved(true, Set.of());
        }
        return toolSurface.resolveTurn(plan, policy.allowedTools(), policy.forceToolsOnly());
    }

    private static String surfaceLabel(ToolSurface.Resolved surface) {
        if (surface == null || surface.unrestricted()) {
            return "ALL";
        }
        return String.valueOf(surface.names().size());
    }

    /**
     * 安全过滤后的规格，并写入与规格同一份工具名单到 messages。
     */
    private List<?> specsAndBindTurnTools(ToolSurface.Resolved base, LoopState state,
                                          List<ChatMessage> messages) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (base.unrestricted()) {
            names.addAll(toolRegistry.getToolNames());
        } else {
            names.addAll(base.names());
        }
        // exec-enabled=false 时仍留在工具表，执行时 needsSessionGrant 弹批准（同 http_post）。
        if (state.imageGenerateUnavailable) {
            names.remove("image_generate");
        }
        if (!LoopTurnContext.current().hardGate()
                && state.explorationCount >= MAX_EXPLORATION_CALLS) {
            names.remove("read_file");
            names.remove("list_files");
            names.remove("exec_command");
            names.remove("read_package");
        }
        PermissionMode mode = PermissionContext.mode();
        boolean planOk = PermissionContext.planApproved();
        names.removeIf(n -> !PermissionPolicy.allowInSpecs(mode, planOk, n));
        List<?> specs = toolRegistry.getSpecifications(names);
        specs = capBrowserProbes(specs, messages);
        bindTurnTools(messages, specNames(specs));
        return specs;
    }

    private static Set<String> specNames(List<?> specs) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (specs == null) {
            return names;
        }
        for (Object s : specs) {
            if (s instanceof ToolSpecification spec) {
                names.add(spec.name());
            }
        }
        return names;
    }

    private static void bindTurnTools(List<ChatMessage> messages, Set<String> names) {
        String body = MessageConstants.TURN_TOOLS_HEADER + "\n"
                + ContextContributorConfiguration.toolGuidance(
                        names, LoopTurnContext.current().hardGate());
        messages.removeIf(m -> m instanceof SystemMessage sm
                && sm.text() != null
                && sm.text().startsWith(MessageConstants.TURN_TOOLS_HEADER));
        int at = 0;
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) {
            at = 1;
        }
        messages.add(at, new SystemMessage(body));
    }

    private List<?> capBrowserProbes(List<?> specs, List<ChatMessage> messages) {
        PermissionMode mode = PermissionContext.mode();
        boolean planOk = PermissionContext.planApproved();
        specs = specs.stream()
                .filter(s -> PermissionPolicy.allowInSpecs(
                        mode, planOk,
                        ((dev.langchain4j.agent.tool.ToolSpecification) s).name()))
                .toList();
        if (!shouldDropBrowserProbes(consecutiveProbeTurns(messages)))
            return specs;
        List<?> kept = specs.stream()
                .filter(s -> !BROWSER_PROBE_TOOLS.contains(
                        ((dev.langchain4j.agent.tool.ToolSpecification) s).name()))
                .toList();
        if (kept.isEmpty()) {
            return specs;
        }
        log.info("浏览器探测已过量，本轮去掉 {}", BROWSER_PROBE_TOOLS);
        return kept;
    }

    static boolean shouldDropBrowserProbes(int consecutive) {
        return consecutive >= BROWSER_PROBE_CAP;
    }

    /** 探测已过量时拒绝 evaluate/scroll；null 表示放行。 */
    public static String denyCappedBrowserProbe(String toolName) {
        if (toolName == null || !BROWSER_PROBE_TOOLS.contains(toolName)) {
            return null;
        }
        return "{\"error\":\"" + BROWSER_PROBE_DENIED.replace("\"", "\\\"") + "\"}";
    }

    static int consecutiveProbeTurns(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m instanceof SystemMessage) {
                continue;
            }
            if (m instanceof UserMessage) {
                break;
            }
            if (m instanceof ToolExecutionResultMessage tr) {
                if (WRITE_TOOLS.contains(tr.toolName())) {
                    break;
                }
                if (!BROWSER_PROBE_TOOLS.contains(tr.toolName())) {
                    break;
                }
                continue;
            }
            if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                boolean probe = true;
                for (var tc : ai.toolExecutionRequests()) {
                    if (!BROWSER_PROBE_TOOLS.contains(toolNameOf(tc))) {
                        probe = false;
                        break;
                    }
                }
                if (!probe) {
                    break;
                }
                n++;
            }
        }
        return n;
    }

    static String maybeBrowserWriteNudge(String toolName, String result, int consecutiveProbes) {
        if (toolName == null || !BROWSER_PROBE_TOOLS.contains(toolName)) {
            return null;
        }
        if (TraceRecorder.isFailedResult(result)) {
            return null;
        }
        if (result != null && result.length() >= BROWSER_EXTRACT_WRITE_CHARS)
            return BROWSER_WRITE_NOW_HINT;
        if (consecutiveProbes >= BROWSER_PROBE_CAP)
            return BROWSER_PROBE_CAP_HINT;
        return null;
    }

    private static String applyBrowserNudge(String name, String result,
                                            String resultForContext, List<ChatMessage> messages) {
        String nudge = maybeBrowserWriteNudge(name, result, consecutiveProbeTurns(messages));
        if (nudge == null) {
            return resultForContext;
        }
        return resultForContext + "\n\n" + nudge;
    }

    /** 循环侧入口：管道执行，再把副作用写回本轮状态。 */
    private String executeToolWithHooks(String name, String args, int turn,
                                        boolean denyProbes, LoopState state, RunScope scope) {
        ToolRequest request = ToolRequest.of(scope, name, args, turn, state.runId);
        if (denyProbes) {
            request = request.withProbeDeny(denyCappedBrowserProbe(name));
        }
        return applyInvocation(toolPipeline.invoke(request), state);
    }

    private static String applyInvocation(ToolInvocation invocation, LoopState state) {
        if (invocation == null) {
            return "";
        }
        switch (invocation.outcome()) {
            case GATE_DENIED -> state.noteGateDenied(invocation.toolName());
            case PERMISSION_ASK -> state.permissionAskTool = invocation.toolName();
            case USER_QUESTION -> state.userQuestionText = invocation.loopSignal();
            case EXECUTED -> state.noteStructuredResult(
                    invocation.toolName(), invocation.result());
            case POLICY_DENIED, CONTROL_STOP, FENCE_REJECTED -> {
            }
        }
        return invocation.text();
    }

    private static String controlEndReason(ExecutionControl.StopReason reason) {
        return switch (reason) {
            case CANCELLED -> CANCELLED;
            case DEADLINE_EXCEEDED -> DEADLINE_EXCEEDED;
            case TOOL_BUDGET_EXCEEDED, TOKEN_BUDGET_EXCEEDED, TENANT_QUOTA_EXCEEDED -> RESOURCE_QUOTA_EXCEEDED;
            case NONE -> "DONE";
        };
    }

    private static String controlMessage(ExecutionControl.StopReason reason) {
        return switch (reason) {
            case CANCELLED -> "任务已取消";
            case DEADLINE_EXCEEDED -> "任务已超过执行 deadline";
            case TOOL_BUDGET_EXCEEDED -> "任务已耗尽工具调用配额";
            case TOKEN_BUDGET_EXCEEDED -> "任务已耗尽 token 预算";
            case TENANT_QUOTA_EXCEEDED -> "租户 token 配额已耗尽";
            case NONE -> "";
        };
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
                ChatResponse response = Objects.isNull(streamSink)
                        ? chatModel.chat(request)
                        : callLlmStreaming(request, streamSink);
                if (Objects.nonNull(response) && Objects.nonNull(response.tokenUsage())) {
                    String sid = currentSessionId.get();
                    if (Objects.nonNull(sid)) {
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
        for (Throwable cur = t; Objects.nonNull(cur) && cur != cur.getCause(); cur = cur.getCause()) {
            if (cur instanceof java.io.IOException
                    || cur instanceof java.util.concurrent.TimeoutException
                    || cur instanceof dev.langchain4j.exception.TimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (Objects.nonNull(msg)) {
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

        StreamingChatModel model = Optional.ofNullable(getCurrentStreamingModel()).orElse(streamingChatModel);
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                if (Objects.isNull(partialThinking)) {
                    return;
                }
                receivedAny.set(true);
                String t = partialThinking.text();
                if (Objects.nonNull(t) && !t.isEmpty()) {
                    try { streamSink.onThinking(t); } catch (Exception ignored) {}
                }
            }

            @Override
            public void onPartialResponse(String token) {
                if (Objects.nonNull(token) && !token.isEmpty()) {
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
        if (Objects.nonNull(err)) {
            if (err instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(err);
        }
        return responseRef.get();
    }

    /** 按请求体大小估计首包等待：思考模型 + 超长 schema 需要更久 */
    private static long estimateFirstTokenTimeoutSec(ChatRequest request) {
        long chars = 0;
        if (Objects.nonNull(request) && Objects.nonNull(request.messages())) {
            for (ChatMessage m : request.messages()) {
                if (Objects.isNull(m)) {
                    continue;
                }
                String s = m.toString();
                chars += Objects.isNull(s) ? 0 : s.length();
            }
        }
        int toolCount = Objects.nonNull(request) && Objects.nonNull(request.toolSpecifications())
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
        if (Objects.nonNull(progressCallback)) {
            String names = toolCalls.stream()
                    .map(tc -> formatProgressMsg(toolNameOf(tc), argumentsOf(tc)))
                    .reduce((a, b) -> a + " | " + b).orElse("");
            progressCallback.accept(names);
        }

        // 必须把参数一起交出去：exec_command 的只读性取决于命令行内容（git status 只读、
        // mvnw package 不是），只看工具名会把整批含 exec_command 的调用一律降级成串行。
        if (toolExecutionGuards.canRunBatchInParallel(
                toolCalls, AgentLoop::toolNameOf, AgentLoop::argumentsOf)) {
            executeToolCallsParallel(toolCalls, messages, progressCallback, state);
        } else {
            for (var tc : toolCalls) {
                executeToolCallSingle(tc, messages, progressCallback, state);
            }
        }
    }

    private static boolean hasBrowserTool(List<?> toolCalls) {
        for (var tc : toolCalls)
            if (toolNameOf(tc).startsWith("browser_")) {
                return true;
            }
        return false;
    }

    /** 并行执行多个工具 */
    private void executeToolCallsParallel(List<?> toolCalls, List<ChatMessage> messages,
                                           Consumer<String> progressCallback, LoopState state) {
        Map<String, String> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        // 整批等待时长必须跟着各调用自己的闸门走：写死 300s 会让一条声明了
        // timeout=600 的只读命令在读结果时被当成超时，而进程其实还在跑。
        long batchWaitSeconds = 0L;

        RunScope scope = RunScope.capture();
        final int turn = state.currentTurn;
        final boolean denyProbes = shouldDropBrowserProbes(
                consecutiveProbeTurns(messages));

        for (var tc : toolCalls) {
            String name = toolNameOf(tc);
            String args = argumentsOf(tc);
            String cacheKey = name + ":" + args;

            // 缓存命中
            if (isCacheableTool(name) && state.toolResultCache.containsKey(cacheKey)) {
                log.info("  [并行缓存] {}", name);
                results.put(toolIdOf(tc) + "|" + name, state.toolResultCache.get(cacheKey));
                futures.add(CompletableFuture.completedFuture(null));
                continue;
            }

            log.info("  [并行] {}({})", name, truncate(redactSensitive(args), 150));
            if (Objects.nonNull(progressCallback)) {
                progressCallback.accept(formatProgressMsg(name, args));
            }

            long timeout = resolveToolTimeout(name, args);
            batchWaitSeconds = Math.max(batchWaitSeconds, timeout);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try (var ignored = scope.bind()) {
                    String r = executeToolWithHooks(
                            name, args, turn, denyProbes, state, scope);
                    results.put(toolIdOf(tc) + "|" + name, Optional.ofNullable(r).orElse(""));
                }
            }, VIRTUAL_EXECUTOR).orTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(Math.max(60L, batchWaitSeconds + 30L), java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("并行工具异常: {}，等待剩余 future 完成", e.getMessage());
            for (CompletableFuture<Void> f : futures) {
                try { f.get(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
        }

        // 按顺序收集结果
        for (var tc : toolCalls) {
            String name = toolNameOf(tc);
            String args = argumentsOf(tc);
            String result = results.getOrDefault(toolIdOf(tc) + "|" + name,
                    timeoutToolResult(name, args, resolveToolTimeout(name, args)).legacyText());
            state.noteStructuredResult(name, ToolResult.fromLegacy(result));
            String cacheKey = name + ":" + argumentsOf(tc);
            if (isCacheableTool(name) && ToolResult.fromLegacy(result).isSuccess() && !result.isEmpty()) {
                state.toolResultCache.put(cacheKey, result);
            }
            trackResult(name, result, state);

            // 反思机制（并行）：失败时把反思提示并入工具结果，不伪装成用户消息。
            // 这里不再为 ask_user_question 单开特判 —— 它和「等用户批准」现在都返回
            // {"status":"awaiting_user"}，isFailedResult 会短路，两者天然不产生反思提示。
            // 原先只给 ask_user_question 开特判、漏了授权路径，正是那个漏口把「等人」读成了「失败」。
            String parallelReflection = buildReflectionHint(name, argumentsOf(tc), result);
            String resultForContext = clampForContext(result, name);
            resultForContext = applyBrowserNudge(name, result, resultForContext, messages);
            if (Objects.nonNull(parallelReflection)) {
                state.consecutiveFailures++;
                // 每次失败都注入反思提示，不再限制为 one-shot
                if (state.consecutiveFailures >= 2) {
                    resultForContext = resultForContext + "\n\n" + parallelReflection;
                    log.info("  [反思-并行] 工具结果附加失败反思提示: {}, 连续失败={}", name, state.consecutiveFailures);
                }
            } else {
                state.consecutiveFailures = 0;
            }

            log.info("  [并行] {}: {}", name, truncate(redactSensitive(result), 200));
            recordToolOutcome(state, name, argumentsOf(tc), result, progressCallback);
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
            recordToolOutcome(state, name, args, truncErr, progressCallback);
            messages.add(ToolExecutionResultMessage.from(toolIdOf(tc), name, truncErr));
            return;
        }

        String cacheKey = name + ":" + args;

        log.info("  {}({})", name, truncate(redactSensitive(args), 150));
        if (Objects.nonNull(progressCallback)) {
            progressCallback.accept(formatProgressMsg(name, args));
        }

        String result;
        if (isCacheableTool(name) && state.toolResultCache.containsKey(cacheKey)) {
            result = state.toolResultCache.get(cacheKey);
            log.info("  [缓存命中] {}", name);
        } else {
            // 外层超时兜底：即使某工具内部超时逻辑失灵（如常驻进程把读流挂死），
            // 也能在此处被强制中断，绝不让单个工具拖死整个 Agent 循环。
            // 与并行路径共用 TOOL_TIMEOUT_SECONDS 预算；给读流/清理留 10s 余量。
            long timeout = resolveToolTimeout(name, args);
            final String fName = name, fArgs = args;
            final int turn = state.currentTurn;
            final RunScope scope = RunScope.capture();
            final boolean denyProbes = shouldDropBrowserProbes(
                    consecutiveProbeTurns(messages));
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try (var ignored = scope.bind()) {
                    return executeToolWithHooks(
                            fName, fArgs, turn, denyProbes, state, scope);
                }
            }, VIRTUAL_EXECUTOR);
            try {
                result = future.get(timeout, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                future.cancel(true);
                log.warn("  工具 {} 执行超时（{}s），请求取消", name, timeout);
                ToolResult timeoutResult = timeoutToolResult(name, args, timeout);
                state.noteStructuredResult(name, timeoutResult);
                result = timeoutResult.legacyText();
            } catch (Exception e) {
                future.cancel(true);
                log.warn("  工具 {} 执行异常: {}", name, e.getMessage());
                result = "{\"error\":\"工具执行异常: " + e.getMessage() + "\"}";
            }
            if (isCacheableTool(name) && ToolResult.fromLegacy(result).isSuccess()) {
                state.toolResultCache.put(cacheKey, result);
            }
        }

        trackResult(name, result, state);

        // 反思机制：工具失败时把反思提示并入工具结果，不伪装成用户消息。
        // 不特判 ask_user_question 与授权等待：「未执行」的结果带 status=awaiting_user，
        // isFailedResult 短路，不需要按工具名逐个开白名单（那种写法漏一个就是一个 bug）。
        String reflectionHint = buildReflectionHint(name, args, result);
        String resultForContext = clampForContext(result, name);
        resultForContext = applyBrowserNudge(name, result, resultForContext, messages);
        if (Objects.nonNull(reflectionHint)) {
            state.consecutiveFailures++;
            state.lastFailedTool = name;
            // 每次失败都注入反思提示，不再限制为 one-shot
            if (state.consecutiveFailures >= 1) {
                resultForContext = resultForContext + "\n\n" + reflectionHint;
                log.info("  [反思] 工具结果附加失败反思提示: {}, 连续失败={}", name, state.consecutiveFailures);
            }
        } else {
            state.consecutiveFailures = 0;
            state.lastFailedTool = null;
        }

        log.info("  {}: {}", name, truncate(redactSensitive(result), 300));
        recordToolOutcome(state, name, args, result, progressCallback);
        messages.add(ToolExecutionResultMessage.from(
                toolIdOf(tc), name, resultForContext));
    }

    /**
     * 外层闸门秒数：工具的执行预算，plan 模式还能进一步压缩。
     *
     * <p>必须吃参数。{@code exec_command} 的预算是调用方在参数里声明的
     * （{@code git status} 与 {@code mvnw package} 不该共用同一个数），
     * 按名字取到的只是注册期的兜底常量。取到窄值时外层会先于工具内部超时触发，
     * 而外层超时在写类工具上被判成「终态未知」→ 整轮中止。</p>
     *
     * <p>{@code actionTimeoutSeconds} 只做<b>收紧</b>（0 = 不封顶）：它表达的是
     * 「 planner 认为这一步不该超过这么久」，不能反过来放宽工具自身的预算。</p>
     */
    private long resolveToolTimeout(String name, String argumentsJson) {
        long tool = toolExecutionGuards.timeoutSeconds(name, argumentsJson);
        int cap = LoopTurnContext.current().actionTimeoutSeconds();
        if (cap > 0 && cap < tool) {
            return cap;
        }
        return tool;
    }

    private ToolResult timeoutToolResult(String name, String argumentsJson, long timeoutSeconds) {
        if (AskUserQuestionTool.TOOL_NAME.equals(name)) {
            return ToolResult.failure(ToolErrorCode.CANCELLED,
                    "等待用户回答已结束，请根据用户下一条消息继续", false);
        }
        ToolDescriptor descriptor = toolExecutionGuards.descriptor(name, argumentsJson);
        String message = "工具执行超时" + (timeoutSeconds > 0 ? "（" + timeoutSeconds + "s）" : "");
        if (descriptor.sideEffect() == ToolSideEffect.READ_ONLY
                || descriptor.idempotent()
                || ToolConcurrencyPolicy.isOutcomeVerifiable(name)) {
            String extra = ToolConcurrencyPolicy.isOutcomeVerifiable(name)
                    ? "，页面终态未知；先 browser_snapshot 核验当前页面，再决定重试还是换策略"
                    : "，可安全重试";
            return ToolResult.failure(ToolErrorCode.TIMEOUT, message + extra, true);
        }
        if (ToolConcurrencyPolicy.EXEC_TOOL.equals(name)) {
            // 不判「终态未知」：那会直接中止整轮（state.unknownOutcome → loopEndReason=OUTCOME_UNKNOWN），
            // 一条卡住的构建命令不该把整条多步任务打死。这里改为可自愈的失败：
            // 明确告诉模型进程可能还在、怎么核验、以及下次怎么调大预算。
            return ToolResult.failure(ToolErrorCode.TIMEOUT,
                    message + "，命令进程可能仍在后台运行。先跑一条只读命令核验"
                            + "（如 tasklist / pgrep -a java），确认没有残留进程再重试，"
                            + "并把 timeout 参数调大（上限 "
                            + com.miniagent.agent.tool.impl.ExecCommandParams.MAX_TIMEOUT_SECONDS + "s）", true);
        }
        if (SongGenerateParams.TOOL_NAME.equals(name)) {
            // 生歌是异步任务：超时并不意味着「副作用去向不明」——任务还在云端，
            // 拿同一个 taskId 查一次就能确定终态。判成 OUTCOME_UNKNOWN 会因为一次慢查询
            // 把整条多步任务打死，这里降级成可自愈的失败。
            return ToolResult.failure(ToolErrorCode.TIMEOUT,
                    message + "，歌曲任务仍在云端生成中。先告诉用户还在生成，"
                            + "下一轮用响应里那个 taskId 再调一次 song_generate 续查，不要重新提交", true);
        }
        return ToolResult.unknown(message + "，调用可能已产生副作用；必须先核验", null);
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
                text = ChatMessageTexts.userForTrace(um);
            }
            else if (msg instanceof AiMessage am) {
                role = "assistant";
                if (Objects.nonNull(am.text())) {
                    text = am.text();
                }
                if (am.hasToolExecutionRequests()) {
                    text += " [tool_calls: " + am.toolExecutionRequests().size() + "]";
                }
            }
            else if (msg instanceof ToolExecutionResultMessage tr) { role = "tool:" + tr.toolName(); text = tr.text(); }
            sb.append("\n[").append(role).append("] ").append(truncate(text, 300));
            shown++;
        }
        return sb.toString().trim();
    }

    private void recordToolOutcome(LoopState state, String name, String args, String result,
                                   Consumer<String> progressCallback) {
        state.noteToolFinished(name, args, result);
        boolean failed = TraceRecorder.isFailedResult(result);
        String st = failed ? RunStatus.FAILURE.name() : RunStatus.SUCCESS.name();
        if (Objects.nonNull(traceRecorder))
            traceRecorder.recordToolResult(
                    currentSessionId.get(), state.currentTurn, name, result, 0, st);
        if (Objects.nonNull(progressCallback))
            progressCallback.accept((failed ? "✗ " : "✓ ") + name + ": "
                    + truncate(redactSensitive(result), 120));
        notifyToolExecution(state, name, result);
    }

    /**
     * 把工具执行事实交给记忆事件流。
     *
     * <p>这是 {@code AgentEvent.EventType.TOOL_EXECUTION} 的唯一生产者。没有它，
     * 那个枚举值就只剩下消费者：按它分类的 {@code RuleBasedMemoryClassifier}、
     * 按它加权的 {@code RuleBasedImportanceEvaluator}、按它取内容摘要的
     * {@code DefaultEventProcessor}、以及巩固阶段数它条数的 Episode 统计，
     * 全都走不到 —— 日志上表现为每轮都报「执行了 0 个工具调用，0 个失败」。</p>
     *
     * <p>{@code AWAITING_USER} 不上报：那种情况下工具压根没执行，报成一次执行是假事实。</p>
     */
    private void notifyToolExecution(LoopState state, String name, String result) {
        ToolExecutionSink sink = this.toolExecutionSink;
        if (Objects.isNull(sink) || Objects.isNull(currentSessionId.get())) {
            return;
        }
        ToolResult parsed = ToolResult.fromLegacy(result);
        if (parsed.status() == ToolStatus.AWAITING_USER) {
            return;
        }
        try {
            sink.onToolExecuted(currentSessionId.get(), state.currentTurn, name,
                    parsed.status(), truncate(redactSensitive(result), 400));
        } catch (Exception e) {
            log.debug("工具执行事件上报失败: {} {}", name, e.getMessage());
        }
    }

    /** 工具失败时注入反思上下文，引导模型换策略 */
    private String buildReflectionHint(String toolName, String args, String result) {
        if (StringUtils.isBlank(result)) {
            return null;
        }
        if ("read_file".equals(toolName) || "search_code".equals(toolName)
                || "read_package".equals(toolName)) {
            if (!result.contains("\"error\"")) {
                return null;
            }
        }
        if (!TraceRecorder.isFailedResult(result)) {
            return null;
        }

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
        } else if ("image_generate".equals(toolName)) {
            hint.append("- 图片生成服务不可用或已禁用\n");
            hint.append("- 降级方案：使用 write_file 生成 SVG/HTML/Mermaid 代码\n");
            hint.append("- 示例：write_file('diagram.svg', '<svg>...</svg>')\n");
            hint.append("- 或使用 web_search 搜索公开图片并下载\n");
        } else if ("browser_click".equals(toolName)) {
            hint.append("- SPA 按钮常点超时：改 by=css / by=text，不要盲重点同一 ref\n");
            hint.append("- 密码页用 browser_type + browser_press，不要点侧栏目录\n");
        } else if (result.contains("未知工具")) {
            hint.append("- 该工具名不存在。浏览器用 browser_navigate/snapshot/click/type/press\n");
            hint.append("- 写文件用 write_file，不要编造未注册工具名\n");
        }

        hint.append("\n不要用相同参数重试。换一个完全不同的方法。");
        return hint.toString();
    }

    /** 从路径中猜测程序名 */
    private static String guessProgramName(String path) {
        if (Objects.isNull(path)) {
            return "program";
        }
        String name = path.replace("\\", "/").trim();
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        name = name.replace(".lnk", "").replace(".exe", "").replace(".app", "");
        return name.isEmpty() ? "program" : name;
    }

    /** 更新循环状态 */
    private void trackResult(String toolName, String result, LoopState state) {
        state.toolsInvoked.add(toolName);
        state.lastErrorCode = ToolResult.fromLegacy(result).errorCode();
        if ("read_file".equals(toolName) || "list_files".equals(toolName)
                || "exec_command".equals(toolName)) state.explorationCount++;
        if ("read_package".equals(toolName)) {
            state.explorationCount += 3;
        }
        if (WRITE_TOOLS.contains(toolName) && Objects.nonNull(result)
                && (result.contains("\"success\":true") || result.startsWith("写入成功"))) {
            state.writeFileSucceeded = true;
            // 写入成功后失效文件相关的缓存，避免后续读到过期内容
            state.toolResultCache.entrySet().removeIf(e ->
                    e.getKey().startsWith("read_file:") || e.getKey().startsWith("exec_command:")
                            || e.getKey().startsWith("list_files:") || e.getKey().startsWith("read_package:"));
        }
        if ("exec_command".equals(toolName) && Objects.nonNull(result)
                && !result.contains("\"error\"")) {
            // exec_command 可能修改文件系统，失效 read_file 缓存
            state.toolResultCache.entrySet().removeIf(e ->
                    e.getKey().startsWith("read_file:") || e.getKey().startsWith("list_files:")
                            || e.getKey().startsWith("read_package:"));
        }
        // 使用结构化 ToolResult 判断 image_generate 是否可用
        if ("image_generate".equals(toolName)) {
            ToolResult toolResult = ToolResult.fromLegacy(result);
            if (toolResult.status() == ToolStatus.FAILED && !toolResult.retriable()) {
                state.imageGenerateUnavailable = true;
                log.warn("image_generate 标记为不可用: {}", toolResult.message());
            } else if (isImageGenerateUnavailable(result)) {
                // 备用判断
                state.imageGenerateUnavailable = true;
            }
        }
        if (MEDIA_TOOLS.contains(toolName) && Objects.nonNull(result) && looksLikeMediaSuccess(result)) {
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
        String finalText = sanitizeFinalAnswer(aiMessage.text());
        log.info("Agent收尾 {}/{}: 生成最终回复", turn + 1, iterations);

        LoopTurnPolicy turnPolicy = LoopTurnContext.current();
        StopDecision stop = stopHookChain.evaluate(buildStopContext(
                sessionId, finalText, state, turn, iterations, turnPolicy));
        if (Objects.nonNull(stop) && !stop.isProceed()) {
            if (stop.action() == StopDecision.Action.BLOCK_RETRY) {
                noteStopNudge(state, stop.reason());
                messages.add(new SystemMessage(Optional.ofNullable(stop.message())
                        .orElse("【StopHook】收尾被拦截，请继续执行。")));
                return null;
            }
            if (stop.action() == StopDecision.Action.PREVENT_CONTINUATION) {
                return Optional.ofNullable(stop.message())
                        .orElse(Optional.ofNullable(finalText).orElse(""));
            }
        }

        if (StringUtils.isBlank(finalText)) {
            if (state.mediaDelivered) {
                return ensureMarkdownImage(state.lastMediaResult);
            }
            if (state.writeFileSucceeded && !hasBlockingTodos(sessionId)) {
                return "（文件已生成，见 workspace/）";
            }
            if (!state.lightQa
                    && (hasBlockingTodos(sessionId)
                    || (state.requiresStructuredPlan && !taskTodoStore.hasPlan(sessionId)))) {
                finalText = "";
            } else {
                return "（模型未返回内容）";
            }
        }

        if (!state.lightQa
                && Objects.nonNull(sessionId)
                && taskTodoStore.hasPlan(sessionId)) {
            boolean runnable = taskTodoStore.hasRunnableIncomplete(sessionId);
            if (!turnPolicy.hardGate() && runnable
                    && state.incompleteTodoReminders >= 2) {
                log.warn("todo 未完成但已提醒多次，附带未完成清单后放行。轮次 {}",
                        turn + 1);
                if (Objects.isNull(finalText)) {
                    finalText = "";
                }
                finalText = finalText.stripTrailing()
                        + "\n\n⚠️ 以下子任务尚未完成（可发送「继续」接着做）：\n"
                        + taskTodoStore.render(sessionId);
            }
        }

        if (StringUtils.isBlank(finalText)) {
            if (state.mediaDelivered) {
                return ensureMarkdownImage(state.lastMediaResult);
            }
            if (state.writeFileSucceeded) {
                return "（文件已生成，见 workspace/）";
            }
            return "（模型未返回内容）";
        }

        if (taskTodoStore.hasAwaitingConfirm(sessionId)
                && !taskTodoStore.hasRunnableIncomplete(sessionId)) {
            return finalText;
        }

        if (state.mediaDelivered) {
            if (Objects.nonNull(state.lastMediaResult)
                    && !MARKDOWN_IMAGE.matcher(finalText).find()) {
                finalText = finalText.stripTrailing()
                        + "\n\n" + ensureMarkdownImage(state.lastMediaResult);
            }
            return finalText;
        }

        if (MARKDOWN_IMAGE.matcher(finalText).find()) {
            return finalText;
        }

        if (!explicitlyNeedsFile) {
            return finalText;
        }

        if (state.writeFileSucceeded) {
            return finalText;
        }

        if (turnPolicy.hardGate()) {
            return finalText;
        }

        if (state.fileReminderSent) {
            log.warn("已提醒 write_file 仍未落盘，放行。轮次 {}", turn + 1);
            return finalText;
        }

        log.info("文件任务未落盘，注入提醒。轮次 {}", turn + 1);
        state.fileReminderSent = true;
        messages.add(new SystemMessage(
                "用户要求输出文件但你还没调用 write_file，请写入后再给出产出物清单。"));
        return null;
    }

    private StopContext buildStopContext(String sessionId, String finalText, LoopState state,
                                         int turn, int iterations, LoopTurnPolicy turnPolicy) {
        boolean hasPlan = sessionId != null && taskTodoStore.hasPlan(sessionId);
        boolean runnable = sessionId != null
                && taskTodoStore.hasRunnableIncomplete(sessionId);
        String render = sessionId == null ? "" : taskTodoStore.render(sessionId);
        return new StopContext(
                sessionId,
                Optional.ofNullable(finalText).orElse(""),
                turn,
                iterations,
                Set.copyOf(state.toolsInvoked),
                state.writeFileSucceeded,
                state.mediaDelivered,
                state.requiresStructuredPlan,
                SubagentContext.isActive(),
                PermissionContext.mode(),
                PermissionContext.planApproved(),
                state.lightQa,
                turnPolicy.hardGate(),
                hasPlan,
                runnable,
                state.planReminderCount,
                state.incompleteTodoReminders,
                render);
    }

    private static void noteStopNudge(LoopState state, String reason) {
        if (ErrorCode.TODO_STOP_MISSING_PLAN.getCode().equals(reason)) {
            state.planReminderCount++;
        } else if (ErrorCode.TODO_STOP_INCOMPLETE.getCode().equals(reason)) {
            state.incompleteTodoReminders++;
        }
    }

    private boolean hasBlockingTodos(String sessionId) {
        return Objects.nonNull(sessionId)
                && taskTodoStore.hasPlan(sessionId)
                && taskTodoStore.hasRunnableIncomplete(sessionId);
    }

    private boolean focusTodosCompleted(String sessionId) {
        LoopTurnPolicy pc = LoopTurnContext.current();
        if (sessionId == null || !pc.hardGate() || pc.focusTodoIds().isEmpty()) {
            return false;
        }
        boolean any = false;
        for (TaskTodoStore.TodoItem it : taskTodoStore.get(sessionId)) {
            if (!pc.focusTodoIds().contains(it.id())) {
                continue;
            }
            any = true;
            if (it.status() != TaskTodoStore.Status.completed) {
                return false;
            }
        }
        return any;
    }

    /** 构建达到最大迭代次数时的兜底回复 */
    private String buildMaxIterationFallback(LoopState state, int iterations) {
        if (state.writeFileSucceeded || state.mediaDelivered)
            return MessageConstants.AGENT_TASK_UNFINISHED;
        return MessageConstants.AGENT_MAX_ITERATIONS_REACHED
                + " 回复「继续」可接着上次进度做。";
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


    /** 多模态消息中取文字，供「显式要文件」正则使用；无文字则视为非文件类意图。 */
    private static String firstUserTextForFileIntent(UserMessage um) {
        return ChatMessageTexts.userPlain(um);
    }

    // ==================== 工具方法 ====================

    private LoopOutcome finishProjected(String text, LoopState state, List<ChatMessage> messages,
            AgentStreamSink streamSink, int turn, String sessionId) {
        if (Objects.nonNull(streamSink)) {
            streamSink.onAnswerReset();
            streamSink.onAnswerToken(text);
        }
        if (Objects.nonNull(traceRecorder)) {
            traceRecorder.recordAnswer(sessionId, turn, text);
        }
        state.loopEndReason = RunStatus.SUCCESS.name();
        messages.add(AiMessage.from(text));
        return finish(text, state, messages);
    }

    private LoopOutcome finishUnusableTurn(ErrorCode code, String text, LlmTurn llmTurn,
            LoopState state, List<ChatMessage> messages, AgentStreamSink streamSink) {
        if (Objects.nonNull(streamSink)) {
            streamSink.onAnswerReset();
            streamSink.onAnswerToken(text);
        }
        state.loopEndReason = code.getCode();
        log.warn("模型回合不可用: status={} refuseKind={} code={}",
                llmTurn.status(), llmTurn.refuseKind(), code.getCode());
        messages.add(AiMessage.from(text));
        return finish(text, state, messages);
    }

    /** 去掉模型偶发泄漏的工具标记，避免直接展示给用户。 */
    private static String sanitizeFinalAnswer(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        return text
                .replaceAll("(?s)</?tool_call[^>]*>", "")
                .replaceAll("(?s)<\\|tool[^|]*\\|>", "")
                .trim();
    }

    /**
     * 兜底格式转换：确保工具返回的图片结果是 markdown 格式。
     * 即使工具已统一返回 markdown，仍需兜底处理历史遗留的 JSON/纯文本格式。
     */
    private static String ensureMarkdownImage(String result) {
        if (StringUtils.isBlank(result)) {
            return result;
        }
        // 已是 markdown
        if (result.startsWith("![") && result.contains("](")) return result;
        // JSON 中提取 markdown_images 字段（ComfyUI 旧格式兼容）
        if (result.contains("\"markdown_images\"")) {
            String extracted = extractJsonField(result, "markdown_images");
            if (Objects.nonNull(extracted) && extracted.startsWith("![")) {
                return extracted;
            }
        }
        // JSON 中提取 image URL（image_generate 旧格式兼容）
        if (result.contains("\"image\"") && result.contains("\"success\"")) {
            String url = extractJsonField(result, "image");
            if (StringUtils.isNotBlank(url)) {
                return "![生成的图片](" + url + ")";
            }
        }
        // 纯文本（如截图路径）— 无法转换，原样返回
        return result;
    }

    /** 判断 image_generate / browser_screenshot 的返回值是否指示成功并附带了图片/文件。 */
    private boolean looksLikeMediaSuccess(String result) {
        if (StringUtils.isBlank(result)) {
            return false;
        }
        // BuiltinTools.formatImageResult 会把成功结果包成 "![图片](url)"
        if (result.startsWith("![") && result.contains("](")) return true;
        // browser_screenshot 直接返回类似 "截图已保存: ..." 的字样
        if (result.contains("截图已保存") || result.contains("saved screenshot")) {
            return true;
        }
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
        if (Objects.isNull(result)) {
            return false;
        }
        // 使用结构化 ToolResult 字段判断
        ToolResult toolResult = ToolResult.fromLegacy(result);
        if (toolResult.status() == ToolStatus.FAILED && !toolResult.retriable()) {
            return true;
        }
        // 备用：检查特定错误码
        if (toolResult.errorCode() == ToolErrorCode.DEPENDENCY_UNAVAILABLE
                || toolResult.errorCode() == ToolErrorCode.PERMISSION_DENIED) {
            return true;
        }
        // 后备：字符串匹配（兼容旧格式）
        return result.contains("图片生成失败")
                || result.contains("API Key 无效")
                || result.contains("Invalid token")
                || result.contains("不要再重试")
                || result.contains("所有后端均失败");
    }

    /** 把工具结果压到可接受长度再塞进上下文（按工具类型取不同上限）。 */
    private String clampForContext(String result, String toolName) {
        if (StringUtils.isBlank(result)) {
            return "{\"success\":true,\"message\":\"工具执行完成\"}";
        }
        int limit = TOOL_RESULT_LIMITS.getOrDefault(toolName, DEFAULT_TOOL_RESULT_MAX);
        if (result.length() <= limit) {
            return result;
        }
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
            case "exec_command"      -> {
                // 有 description 就用它：用户看到「编译并运行测试」比看到一长串命令行清楚得多。
                // 审批弹窗也要这句话（ToolPipeline 发布 permission_ask 时带上同一份文本）。
                String desc = extractJsonField(arguments, "description");
                yield desc.isBlank()
                        ? "⚙️ 执行命令：" + truncate(extractJsonField(arguments, "command"), 50)
                        : "⚙️ " + truncate(desc, 40);
            }
            case "browser_navigate"  -> "🌍 打开网页：" + extractJsonField(arguments, "url");
            case "browser_extract_text" -> "📄 抽取页面正文…";
            case "browser_screenshot"-> "📸 截图中...";
            case "browser_click"     -> "🖱️ 点击：" + extractJsonField(arguments, "ref");
            case "browser_type"      -> "⌨️ 输入：" + truncate(extractJsonField(arguments, "text"), 30);
            case "memory"            -> "🧠 更新记忆...";
            case "http_get"          -> "📡 请求：" + extractJsonField(arguments, "url");
            case "http_post"         -> "📡 POST：" + extractJsonField(arguments, "url");
            default                  -> "🔧 调用工具：" + toolName;
        };
    }

    /** 从 JSON 字符串里粗提取指定字段值（不引入 Jackson 依赖，轻量实现） */
    public static String extractJsonField(String json, String field) {
        if (StringUtils.isBlank(json)) {
            return "";
        }
        String key = "\"" + field + "\"";
        int ki = json.indexOf(key);
        if (ki < 0) {
            return "";
        }
        int colon = json.indexOf(':', ki + key.length());
        if (colon < 0) {
            return "";
        }
        int vs = colon + 1;
        while (vs < json.length() && Character.isWhitespace(json.charAt(vs))) {
            vs++;
        }
        if (vs >= json.length()) {
            return "";
        }
        if (json.charAt(vs) == '"') {
            int end = json.indexOf('"', vs + 1);
            return end < 0 ? "" : json.substring(vs + 1, end);
        }
        int end = vs;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(vs, end).trim();
    }

    /** 纯日志/匹配用的截断。 */
    private static String truncate(String s, int max) {
        return com.miniagent.common.StringUtils.truncate(s, max);
    }

    /** 提取一行可读任务目标用于日志，避免长 prompt/密钥污染日志。 */
    private static String summarizeTaskGoal(String userText) {
        if (StringUtils.isBlank(userText)) {
            return "(empty)";
        }
        String oneLine = userText.replaceAll("[\\r\\n]+", " ").trim();
        return truncate(redactSensitive(oneLine), 120);
    }

    /** 日志脱敏：避免 access_token、secret、api-key 等敏感信息进入控制台。 */
    private static String redactSensitive(String s) {
        return com.miniagent.common.SecurityUtils.redactSensitive(s);
    }
}
