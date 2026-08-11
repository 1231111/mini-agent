package com.miniagent.application;

import com.miniagent.agent.core.ContextCompressor;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.ConcurrentHashMap;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.TokenUsageTracker;
import com.miniagent.agent.intent.IntentPlanner;
import com.miniagent.agent.intent.IntentType;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.memory.MemoryStore;
import com.miniagent.config.storage.MediaStorage;
import com.miniagent.agent.todo.TaskTodoContext;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.skill.SkillStore;
import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.service.DatabaseConversationStore.Conversation;
import com.miniagent.config.service.DatabaseConversationStore.ConversationSummary;
import com.miniagent.web.dto.FileAttachment;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.miniagent.agent.web.FileContentExtractor;
import com.miniagent.web.MiniAgentChatPageController;
import com.miniagent.config.repository.ChatTaskRepository;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.service.TaskRunService;
import com.miniagent.agent.delegate.RoleContext;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.config.model.ModelClientFactory;
import java.util.Objects;
import java.util.Optional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.miniagent.application.PromptTemplates;
import org.apache.commons.lang3.StringUtils;


/**
 * 直接 Agent 模式：分层系统提示词 + Agent 循环 + 冻结快照记忆
 *
 * 记忆设计完全参考 hermes-agent：
 * - MEMORY.md + USER.md 两个文件
 * - § 分隔条目，字符数硬上限
 * - 冻结快照：session 开始时加载，session 中写盘但不刷新快照（保持 prefix cache 稳定）
 * - 自动用户画像：从日常提问中提取用户偏好
 *
 * 会话管理参考 ChatGPT WebUI：
 * - ConversationStore 持久化会话历史到 JSON 文件
 * - 侧边栏显示历史会话列表，支持切换/删除/重命名
 */
@Service
@Slf4j
public class AgentChatApplicationService {

    /** SSE 流式连接超时（毫秒）。可配置，默认 30 分钟，避免长任务被 10 分钟硬上限掐断。0 表示永不超时（不建议，有连接泄漏风险）。 */
    @Value("${agent.sse.timeout-ms:1800000}")
    private long sseTimeoutMs;

    @Autowired
    private AgentLoop agentLoop;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private com.miniagent.agent.trace.TraceRecorder traceRecorder;

    @Autowired
    private ChatMemoryConfig.ChatMemoryProvider chatMemoryProvider;
    @Autowired
    private MemoryStore memoryStore;
    @Autowired
    private ToolRegistry toolRegistry;
    @Autowired
    private DatabaseConversationStore conversationStore;
    @Autowired
    private SkillStore skillStore;
    @Autowired
    private IntentPlanner intentPlanner;
    @Autowired
    private TaskTodoStore taskTodoStore;
    @Autowired
    private ContextCompressor contextCompressor;
    @Autowired
    private FileContentExtractor fileContentExtractor;
    @Autowired
    private ChatTaskRepository chatTaskRepository;
    @Autowired
    private com.miniagent.agent.core.SessionStreamHub streamHub;
    @Autowired
    private BuiltinTools builtinTools;
    @Autowired
    private TaskRunService taskRunService;
    @Autowired
    private MediaStorage mediaStorage;
    @Autowired
    private ModelClientFactory modelClientFactory;

    private static final int MAX_ITERATIONS = 90;

    /** 正在运行的任务：sessionId -> true。用于前端查询任务状态（内存快路径）。 */
    private final ConcurrentHashMap<String, Boolean> runningTasks = new ConcurrentHashMap<>();

    @PostConstruct
    private void init() {
        agentLoop.setTraceRecorder(traceRecorder);
        agentLoop.setStreamHub(streamHub);
    }

    /** 查询会话是否有正在运行的任务 */
    public boolean isTaskRunning(String sessionId) {
        return runningTasks.containsKey(sessionId) || taskRunService.isRunning(sessionId);
    }

    /**
     * 重连到正在运行（或刚结束仍在缓冲期）的会话流。
     * 返回已挂载的 emitter；若无活动通道（任务早结束且缓冲已驱逐），返回 null，调用方应回退到数据库加载。
     */
    public SseEmitter attachStream(String sessionId) {
        SseEmitter emitter = new SseEmitter(3600_000L);
        boolean ok = streamHub.attach(sessionId, emitter);
        if (!ok) {
            try {
                emitter.send(SseEmitter.event().name("gone").data(""));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }
        return emitter;
    }

    // getConversation 已在会话管理区域定义

    // =========================================================================
    // 分层系统提示词 v2（普适性设计，不绑定任何特定任务类型）
    // =========================================================================

    // ========== 身份与能力 ==========
    private String buildSystemPrompt(String sessionId, String currentQuery) {
        List<String> parts = new ArrayList<>();
        Set<String> toolNames = getAvailableToolNames();

        // 身份 + 能力
        parts.add(PromptTemplates.identity());
        parts.add(PromptTemplates.AUTHORITY);

        // 记忆快照：长期记忆按当前对话语义召回（向量可用时），否则全量注入
        String combinedSnapshot = memoryStore.getSnapshotForQuery(currentQuery);
        if (!combinedSnapshot.isEmpty()) {
            parts.add(combinedSnapshot);
        }

        // Skill 列表（渐进式披露：只注入名字+描述，模型需要时调 skill_view 加载完整内容）
        String skillSummary = skillStore.getSkillListSummary();
        if (!skillSummary.isEmpty()) {
            parts.add(skillSummary);
        }

        // 推理与完成判断（始终注入，不只是工具场景）
        parts.add(PromptTemplates.REASONING);
        parts.add(PromptTemplates.COMPLETION);

        // 文件读取指南（有read_file工具时注入）
        if (toolNames.contains("read_file")) {
            parts.add(PromptTemplates.FILE_GUIDANCE);
        }

        // 代码检索与编辑指南（有 search_code / edit_file 时注入）
        if (toolNames.contains("search_code") || toolNames.contains("edit_file")) {
            parts.add(PromptTemplates.CODE_TOOLS_GUIDANCE);
        }

        // 推理策略（有 delegate_task 时启用 ToT 能力）
        if (toolNames.contains("delegate_task")) {
            parts.add(PromptTemplates.REASONING_STRATEGY);
        }


        // 执行规则（有工具时注入）
        if (!toolNames.isEmpty()) {
            parts.add(PromptTemplates.BEHAVIOR);
        }

        // 工具专属指南（条件注入）
        if (toolNames.contains("browser_navigate")) {
            parts.add(PromptTemplates.BROWSER_GUIDANCE);
        }
        if (toolNames.contains("web_search")) {
            parts.add(PromptTemplates.WEB_SEARCH_GUIDANCE);
        }

        if (toolNames.contains("comfyui_status")) {
            parts.add(PromptTemplates.COMFYUI_GUIDANCE);
        }
        if (toolNames.contains("image_generate") && !toolNames.contains("comfyui_status")) {
            parts.add(PromptTemplates.IMAGE_GENERATE_GUIDANCE);
        }
        if (toolNames.contains("memory")) {
            parts.add(PromptTemplates.MEMORY_GUIDANCE);
        }

        // 任务规划/拆解（todo + delegate_task）
        if (toolNames.contains("todo") || toolNames.contains("delegate_task")) {
            parts.add(PromptTemplates.PLANNING_GUIDANCE);
        }

        // 角色化子Agent指导
        if (toolNames.contains("delegate_task")) {
            parts.add(PromptTemplates.ROLE_DELEGATION_GUIDANCE);
        }

        // 产出验证（能写代码又能执行命令时注入）
        if (toolNames.contains("write_file") && toolNames.contains("exec_command")) {
            parts.add(PromptTemplates.VERIFICATION_GUIDANCE);
        }

        // 当前 todo 状态（每轮新鲜注入，不依赖工具调用）
        if (Objects.nonNull(sessionId)) {
            String todoBlock = taskTodoStore.render(sessionId);
            if (!StringUtils.isBlank(todoBlock)) {
                parts.add(todoBlock);
            }
        }

        // 输出规范（始终注入）
        parts.add(PromptTemplates.CONFIRMATION);
        parts.add(PromptTemplates.OUTPUT);

        // 时间戳
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        parts.add("当前时间：" + now);

        return String.join("\n\n", parts);
    }

    private Set<String> getAvailableToolNames() {
        try {
            return new HashSet<>(toolRegistry.getToolNames());
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    // =========================================================================
    // 对话入口
    // =========================================================================

    public String chat(Long userId, String sessionId, String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            return "请输入有效内容。";
        }
        String sid = (StringUtils.isBlank(sessionId))
                ? UUID.randomUUID().toString().substring(0, 8)
                : sessionId.trim();
        return executeAgent(userId, sid, userMessage.trim());
    }

    /**
     * 取最近若干条历史，但对齐到完整对话轮次：窗口从一条 UserMessage 开始，
     * 避免以半截 AiMessage/工具消息开头而切散 tool_call/result 配对。
     */
    private List<ChatMessage> recentHistoryByTurn(List<ChatMessage> all, int maxKeep) {
        if (Objects.isNull(all) || all.isEmpty()) return List.of();
        int keep = Math.min(maxKeep, all.size());
        int start = all.size() - keep;
        // 向后推进到第一条 UserMessage，对齐轮次边界
        while (start < all.size() && !(all.get(start) instanceof UserMessage)) {
            start++;
        }
        if (start >= all.size()) start = all.size() - keep; // 兜底：无 user 边界则用原起点
        return new ArrayList<>(all.subList(start, all.size()));
    }

    private String executeAgent(Long userId, String sessionId, String userMessage) {
        return executeAgentWithProgress(userId, sessionId, userMessage, null);
    }

    /**
     * 把一轮对话（用户消息 + 助手回答）持久化到 DatabaseConversationStore，
     * 供下次 {@link ChatMemoryConfig.ChatMemoryProvider} 重建滑动窗口历史。
     * 会话不存在时先创建（首次提问即建会话）。失败不影响主流程。
     */
    private void persistTurn(Long userId, String sessionId, String userMessage, String answer,
                             List<String> userImagePaths) {
        try {
            if (!conversationStore.exists(sessionId)) {
                conversationStore.create(userId, sessionId, userMessage);
            }
            if (Objects.nonNull(userImagePaths) && !userImagePaths.isEmpty()) {
                conversationStore.addMessageWithImages(sessionId, "user", userMessage, userImagePaths);
            } else {
                conversationStore.addMessage(sessionId, "user", userMessage);
            }
            conversationStore.addMessage(sessionId, "assistant", answer);
        } catch (Exception e) {
            log.warn("持久化会话历史失败: sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    private String executeAgentWithProgress(Long userId, String sessionId, String userMessage,
                                            org.springframework.web.servlet.mvc.method.annotation.SseEmitter progressEmitter) {
        return executeAgentWithProgress(userId, sessionId, userMessage, progressEmitter, List.of());
    }

    /** 文本 / 多模态统一入口：并发闸门 +（可选）SSE sink */
    private String executeAgentWithProgress(Long userId, String sessionId, String userMessage,
                                            org.springframework.web.servlet.mvc.method.annotation.SseEmitter progressEmitter,
                                            List<String> imageDataUrls) {
        String acquireErr = taskRunService.tryAcquire(userId, sessionId);
        if (Objects.nonNull(acquireErr)) {
            throw new IllegalStateException(acquireErr);
        }
        runningTasks.put(sessionId, Boolean.TRUE);
        MemoryStore.setCurrentUser(userId);
        try {
            String answer = doExecuteAgent(userId, sessionId, userMessage, progressEmitter, imageDataUrls);
            taskRunService.markCompleted(userId, sessionId);
            return answer;
        } catch (Exception e) {
            taskRunService.markFailed(userId, sessionId, e.getMessage());
            throw e;
        } finally {
            runningTasks.remove(sessionId);
            MemoryStore.clearCurrentUser();
            TaskTodoContext.clear();
        }
    }

    private String doExecuteAgent(Long userId, String sessionId, String userMessage,
                                  org.springframework.web.servlet.mvc.method.annotation.SseEmitter progressEmitter,
                                  List<String> imageDataUrls) {
        List<String> images = Optional.ofNullable(imageDataUrls).orElseGet(List::of).stream()
                .filter(StringUtils::isNotBlank).toList();
        boolean hasImage = !images.isEmpty();
        String runStatus = "SUCCESS";

        contextCompressor.setCurrentSession(sessionId);
        builtinTools.clearToolCache();

        // 意图漏斗与 AgentLoop 共用同一 executionId
        if (Objects.nonNull(traceRecorder)) {
            traceRecorder.ensureExecution(sessionId, userMessage);
        }
        try {
        ModelClientFactory.ResolvedModels models = modelClientFactory.resolve(userId);
        ChatModel effectiveChat = models.chat();
        log.info("本轮模型: userId={}, preset={}, model={}",
                userId, models.settings().presetId(), models.settings().modelName());

        ChatMemory memory = chatMemoryProvider.get(sessionId);
        List<ChatMessage> memMsgs = memory.messages();
        TaskPlan taskPlan = intentPlanner.plan(effectiveChat, userMessage, hasImage, memMsgs);
        List<ChatMessage> history = taskPlan.shouldUseHistory()
                ? memMsgs : recentHistoryByTurn(memMsgs, 4);

        List<String> savedImagePaths = hasImage ? saveImagesToDisk(sessionId, images) : List.of();
        UserMessage multimodalMsg = hasImage ? buildMultimodalUserMessage(userMessage, images, savedImagePaths) : null;
        String displayQuestion = hasImage
                ? (StringUtils.isNotBlank(userMessage) ? userMessage : "[图片]") + " 📷x" + images.size()
                : userMessage;

        if (taskPlan.intent() == IntentType.REVIEW) {
            AgentLoop.setCurrentModels(effectiveChat, models.streaming());
            try {
                if (Objects.nonNull(traceRecorder)) {
                    traceRecorder.recordNode("REVIEW_PATH", "{\"hasImage\":" + hasImage + "}", "RUNNING", 0);
                }
                String answer = hasImage
                        ? answerReviewWithImages(userMessage, images, history)
                        : answerReviewQuestion(userMessage, null, history);
                if (hasImage) memory.add(multimodalMsg);
                else memory.add(UserMessage.from(userMessage));
                memory.add(AiMessage.from(answer));
                ChatTask task = new ChatTask();
                task.setUserId(userId);
                task.setSessionId(sessionId);
                task.setQuestion(displayQuestion);
                task.setAnswer(answer);
                if (hasImage) task.setImages(String.join(",", savedImagePaths));
                chatTaskRepository.save(task);
                persistTurn(userId, sessionId, displayQuestion, answer, hasImage ? savedImagePaths : null);
                updateMidtermMemoryAsync(userId, displayQuestion, answer);
                if (Objects.nonNull(traceRecorder)) {
                    traceRecorder.recordAnswer(sessionId, 0, answer);
                }
                return answer;
            } finally {
                AgentLoop.clearCurrentModels();
            }
        }

        String firstLine = StringUtils.isNotBlank(userMessage)
                ? userMessage.split("[\r\n]", 2)[0]
                : (hasImage ? "分析图片" : "default");
        BuiltinTools.setCurrentTaskName(firstLine);
        TaskTodoContext.set(sessionId);
        String systemPrompt = buildSystemPrompt(sessionId, userMessage);

        final boolean streaming = Objects.nonNull(progressEmitter);
        Consumer<String> progress = Optional.ofNullable(progressEmitter)
                .<Consumer<String>>map(em -> msg ->
                        streamHub.publish(sessionId, "progress", Optional.ofNullable(msg).orElse("")))
                .orElse(null);
        com.miniagent.agent.core.AgentStreamSink streamSink = !streaming ? null
                : new com.miniagent.agent.core.AgentStreamSink() {
            @Override public void onThinking(String delta) {
                streamHub.publish(sessionId, "thinking", delta);
            }
            @Override public void onAnswerToken(String delta) {
                streamHub.publish(sessionId, "token", delta);
            }
            @Override public void onAnswerReset() {
                streamHub.publish(sessionId, "reset", "");
            }
            @Override public void onSubGoal(String text, int done, int total) {
                streamHub.publishSubGoal(sessionId, text, done, total);
            }
        };

        AgentLoop.setCurrentSession(sessionId);
        AgentLoop.setCurrentModels(effectiveChat, models.streaming());
        PermissionContext.setSession(sessionId);
        String answer;
        try {
            if (hasImage) {
                answer = agentLoop.runWithMultimodal(effectiveChat, systemPrompt, multimodalMsg, history,
                        MAX_ITERATIONS, progress, taskPlan, streamSink);
            } else {
                answer = agentLoop.run(effectiveChat, systemPrompt, userMessage, history,
                        MAX_ITERATIONS, progress, taskPlan, streamSink);
            }
        } finally {
            PermissionContext.clear();
            AgentLoop.clearCurrentModels();
            AgentLoop.clearCurrentSession();
        }

        if (hasImage) memory.add(multimodalMsg);
        else memory.add(UserMessage.from(userMessage));
        memory.add(AiMessage.from(answer));

        ChatTask task = new ChatTask();
        task.setUserId(userId);
        task.setSessionId(sessionId);
        task.setQuestion(displayQuestion);
        task.setAnswer(answer);
        if (hasImage) task.setImages(String.join(",", savedImagePaths));
        chatTaskRepository.save(task);
        persistTurn(userId, sessionId, displayQuestion, answer, hasImage ? savedImagePaths : null);
        updateMidtermMemoryAsync(userId, displayQuestion, answer);
        return answer;
        } catch (Exception e) {
            runStatus = "FAILURE";
            if (Objects.nonNull(traceRecorder)) {
                traceRecorder.recordError(sessionId, 0, e.getMessage());
            }
            throw e;
        } finally {
            // AgentLoop 正常路径会 end；REVIEW / 异常路径若仍 active 则在此收尾
            if (Objects.nonNull(traceRecorder) && traceRecorder.isActive()) {
                traceRecorder.endExecution(runStatus);
            }
        }
    }

    private UserMessage buildMultimodalUserMessage(String userMessage, List<String> images,
                                                   List<String> savedImagePaths) {
        List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        if (StringUtils.isNotBlank(userMessage)) text.append(userMessage);
        Optional.ofNullable(savedImagePaths).filter(paths -> !paths.isEmpty()).ifPresent(paths -> {
            text.append("\n\n[用户上传了 ").append(paths.size()).append(" 张图片]\n");
            for (int i = 0; i < paths.size(); i++) {
                text.append("图片").append(i + 1).append(" 本地路径: ").append(paths.get(i)).append('\n');
            }
        });
        contents.add(TextContent.from(text.toString()));
        images.forEach(img -> contents.add(ImageContent.from(img)));
        return UserMessage.from(contents);
    }

    /** 单图入口（兼容旧调用） */
    public String chatWithImage(Long userId, String sessionId, String userMessage, String imageDataUrl) {
        return chatWithImages(userId, sessionId, userMessage,
                (StringUtils.isBlank(imageDataUrl)) ? List.of() : List.of(imageDataUrl));
    }

    /**
     * 多图入口：文字 + 任意张图片。所有图片走同一个 UserMessage 多模态。
     */
    public String chatWithImages(Long userId, String sessionId, String userMessage, List<String> imageDataUrls) {
        List<String> images = Optional.ofNullable(imageDataUrls).orElseGet(List::of).stream()
                .filter(StringUtils::isNotBlank).toList();
        if (images.isEmpty()) {
            return chat(userId, sessionId, userMessage);
        }
        String sid = StringUtils.isBlank(sessionId) ? "default" : sessionId.trim();
        return executeAgentWithProgress(userId, sid, userMessage, null, images);
    }

    private List<String> saveImagesToDisk(String sessionId, List<String> imageDataUrls) {
        return mediaStorage.saveConversationImages(sessionId, imageDataUrls);
    }

    private String answerReviewWithImages(String userMessage, List<String> images, List<ChatMessage> history) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(PromptTemplates.REVIEW_MODE_PROMPT));
            log.info("评审模式: imageCount={}, totalImageDataLength={}, userMessage='{}'",
                    images.size(),
                    images.stream().mapToInt(String::length).sum(),
                    Objects.isNull(userMessage) ? "" : userMessage.replaceAll("[\\r\\n]+", " "));

            if (Objects.nonNull(history) && !history.isEmpty()) {
                int from = Math.max(0, history.size() - 4);
                messages.addAll(history.subList(from, history.size()));
            }
            String text = StringUtils.isBlank(userMessage)
                    ? "请分析这些截图反映的问题。"
                    : userMessage;
            List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
            contents.add(TextContent.from(text));
            for (String img : images) {
                contents.add(ImageContent.from(img));
            }
            messages.add(UserMessage.from(contents));
            ChatModel model = Optional.ofNullable(AgentLoop.getCurrentChatModel()).orElse(chatModel);
            var response = model.chat(ChatRequest.builder().messages(messages).build());
            String answer = response.aiMessage().text();
            return StringUtils.isBlank(answer)
                    ? "这是结果质量反馈，不应该继续执行旧任务。问题通常来自上下文被旧任务牵引、工具循环未及时停止或最终兜底回复过短。"
                    : answer;
        } catch (Exception e) {
            return "这是结果质量反馈，不应该继续执行旧任务。当前评审模式调用失败：" + e.getMessage();
        }
    }

    private void updateMidtermMemoryAsync(Long userId, String userMessage, String answer) {
        CompletableFuture.runAsync(() -> {
            // 异步线程不继承请求线程的 ThreadLocal，需显式设置当前用户后再访问记忆。
            MemoryStore.setCurrentUser(userId);
            try {
                String oldMemory = memoryStore.getRawMidtermMemory();
                String input = """
                        【旧中期记忆】
                        %s

                        【最新用户消息】
                        %s

                        【最新助手回答】
                        %s
                        """.formatted(
                        StringUtils.isBlank(oldMemory) ? "（空）" : oldMemory,
                        sanitizeForMemory(userMessage, 2500),
                        sanitizeForMemory(answer, 3500)
                );
                var response = chatModel.chat(ChatRequest.builder()
                        .messages(List.of(
                                new SystemMessage(PromptTemplates.MIDTERM_MEMORY_PROMPT),
                                new UserMessage(input)
                        ))
                        .build());
                String summary = response.aiMessage().text();
                if (StringUtils.isNotBlank(summary)) {
                    memoryStore.updateMidtermMemory(summary);
                }
            } catch (Exception ignored) {
                // 中期记忆是增强能力，失败不影响主对话。
            } finally {
                MemoryStore.clearCurrentUser();
            }
        });
    }

    private String answerReviewQuestion(String userMessage, String imageDataUrl, List<ChatMessage> history) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(PromptTemplates.REVIEW_MODE_PROMPT));
            log.info("评审模式: hasImage={}, imageDataLength={}, userMessage='{}'",
                    StringUtils.isNotBlank(imageDataUrl),
                    Objects.isNull(imageDataUrl) ? 0 : imageDataUrl.length(),
                    Objects.isNull(userMessage) ? "" : userMessage.replaceAll("[\\r\\n]+", " "));

            // 只给少量近期上下文，避免旧任务把模型重新拉回执行链路。
            if (Objects.nonNull(history) && !history.isEmpty()) {
                int from = Math.max(0, history.size() - 4);
                messages.addAll(history.subList(from, history.size()));
            }

            String text = StringUtils.isBlank(userMessage) ? "请分析这张截图反映的问题。" : userMessage;
            if (StringUtils.isNotBlank(imageDataUrl)) {
                messages.add(UserMessage.from(TextContent.from(text), ImageContent.from(imageDataUrl)));
            } else {
                messages.add(UserMessage.from(text));
            }

            ChatModel model = Optional.ofNullable(AgentLoop.getCurrentChatModel()).orElse(chatModel);
            var response = model.chat(ChatRequest.builder().messages(messages).build());
            String answer = response.aiMessage().text();
            return StringUtils.isBlank(answer)
                    ? "这是结果质量反馈，不应该继续执行旧任务。问题通常来自上下文被旧任务牵引、工具循环未及时停止或最终兜底回复过短。"
                    : answer;
        } catch (Exception e) {
            return "这是结果质量反馈，不应该继续执行旧任务。当前评审模式调用失败：" + e.getMessage();
        }
    }

    /** 判断是否是简单问答：短消息 + 不涉及文件/工具操作 */
    private static String sanitizeForMemory(String text, int maxChars) {
        if (Objects.isNull(text)) return "";
        String sanitized = text
                .replaceAll("(?i)(access_token=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(secret=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(api[_-]?key=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(\"access_token\"\\s*:\\s*\")[^\"]+\"", "$1***\"")
                .replaceAll("(?i)(\"secret\"\\s*:\\s*\")[^\"]+\"", "$1***\"")
                .replaceAll("(?i)(\"api[_-]?key\"\\s*:\\s*\")[^\"]+\"", "$1***\"");
        return sanitized.length() <= maxChars ? sanitized : sanitized.substring(0, maxChars) + "\n...（已截断）";
    }

    // =========================================================================
    // 会话管理（供 Controller 调用）
    // =========================================================================

    /** 删除会话 */
    public void resetConversation(Long userId, String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            conversationStore.delete(sessionId.trim());
        }
    }

    public List<ConversationSummary> listConversations(Long userId) {
        return conversationStore.list(userId);
    }

    public Conversation getConversation(String sessionId) {
        return conversationStore.get(sessionId);
    }

    public Conversation getConversationForUser(Long userId, String sessionId) {
        return conversationStore.getForUser(userId, sessionId);
    }

    public boolean deleteConversation(String sessionId) {
        return conversationStore.delete(sessionId);
    }

    public boolean deleteConversationForUser(Long userId, String sessionId) {
        return conversationStore.deleteForUser(userId, sessionId);
    }

    public boolean renameConversation(String sessionId, String newTitle) {
        return conversationStore.rename(sessionId, newTitle);
    }

    // =========================================================================
    // SSE 流式对话
    // =========================================================================

    public SseEmitter chatStream(Long userId, String sessionId, String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error").data("请输入有效内容。"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        String sid = (StringUtils.isBlank(sessionId))
                ? UUID.randomUUID().toString().substring(0, 8)
                : sessionId.trim();
        SseEmitter emitter = new SseEmitter(sseTimeoutMs); // 可配置超时（agent.sse.timeout-ms，默认30分钟）
        final String finalSid = sid;
        final String finalMsg = userMessage.trim();

        // 开启会话中枢通道，并把本次 POST 连接挂为第一个订阅者
        streamHub.start(finalSid, finalMsg);
        streamHub.attach(finalSid, emitter);

        // 异步执行 Agent；思考/工具/答案增量经中枢实时扇出，完成/异常由中枢统一收尾
        CompletableFuture.runAsync(() -> {
            try {
                String answer = executeAgentWithProgress(userId, finalSid, finalMsg, emitter);
                streamHub.complete(finalSid, Optional.ofNullable(answer).orElse(""));
            } catch (Exception e) {
                streamHub.error(finalSid, "处理出错: " + e.getMessage());
            }
        });

        return emitter;
    }

    public SseEmitter chatStreamWithImage(Long userId, String sessionId, String userMessage, String imageDataUrl) {
        return chatStreamMultimodal(userId, sessionId, userMessage,
                (StringUtils.isBlank(imageDataUrl)) ? List.of() : List.of(imageDataUrl), null, null);
    }

    /**
     * 统一多模态 SSE 入口：支持文字 + 任意张图片。
     */
    public SseEmitter chatStreamMultimodal(Long userId, String sessionId, String userMessage, List<String> imageDataUrls, List<FileAttachment> files, String role) {
        List<FileAttachment> fileAttachments =
            Optional.ofNullable(files).orElseGet(List::of).stream()
                .filter(f -> Objects.nonNull(f) && StringUtils.isNotBlank(f.getBase64()))
                .toList();
        if (!fileAttachments.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (StringUtils.isNotBlank(userMessage)) sb.append(userMessage);
            for (var file : fileAttachments) {
                String extracted = fileContentExtractor.extract(file.getFilename(), file.getMimeType(), file.getBase64());
                sb.append("\n\n--- 文件: ").append(file.getFilename()).append(" ---\n");
                sb.append(extracted);
                sb.append("\n--- 文件结束 ---");
            }
            userMessage = sb.toString();
        }

        List<String> images = Optional.ofNullable(imageDataUrls).orElseGet(List::of).stream()
                .filter(StringUtils::isNotBlank).toList();
        if (StringUtils.isBlank(userMessage) && images.isEmpty()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data("请输入文字或上传图片。"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        String sid = StringUtils.isBlank(sessionId)
                ? UUID.randomUUID().toString().substring(0, 8)
                : sessionId.trim();
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        final String finalSid = sid;
        final String finalUserMessage = userMessage;
        final String finalRole = role;
        final List<String> finalImages = images;

        streamHub.start(finalSid, Optional.ofNullable(finalUserMessage).orElse(""));
        streamHub.attach(finalSid, emitter);

        CompletableFuture.runAsync(() -> {
            try {
                if (StringUtils.isNotBlank(finalRole)) RoleContext.setRole(finalRole);
                String answer = executeAgentWithProgress(userId, finalSid, finalUserMessage, emitter, finalImages);
                streamHub.complete(finalSid, Optional.ofNullable(answer).orElse(""));
            } catch (Exception e) {
                streamHub.error(finalSid, "处理出错: " + e.getMessage());
            } finally {
                RoleContext.clear();
            }
        });

        return emitter;
    }
    /**
     * 将 Agent 最终回复以 SSE 推出。
     *
     * 切段规则：
     *   1. 按行（\n）分割，保留行内容；
     *   2. 若某行含 Markdown 图片语法 ![...](... 就整行作为一个 segment 发送，绝不再拆；
     *   3. 过长的纯文字行再按中文句末标点细分，避免一次发太多。
     */
    private void streamAnswer(SseEmitter emitter, String answer) throws Exception {
        if (StringUtils.isEmpty(answer)) {
            emitter.send(SseEmitter.event().name("end").data(""));
            emitter.complete();
            return;
        }

        // 真正的 token-by-token 流式：按标点符号分批推送
        StringBuilder token = new StringBuilder();
        boolean isFirst = true;
        int len = answer.length();

        for (int i = 0; i < len; i++) {
            char c = answer.charAt(i);

            // 遇到换行符：立即推送当前 token + 换行
            if (c == '\n') {
                if (token.length() > 0) {
                    String eventType = isFirst ? "start" : "token";
                    emitter.send(SseEmitter.event().name(eventType).data(token.toString()));
                    token.setLength(0);
                    isFirst = false;
                }
                emitter.send(SseEmitter.event().name("token").data("\n"));
                continue;
            }

            token.append(c);

            // 标点符号后推送（句子边界）
            if ("。！？；：，、.!?,;:".indexOf(c) >= 0) {
                String eventType = isFirst ? "start" : "token";
                emitter.send(SseEmitter.event().name(eventType).data(token.toString()));
                token.setLength(0);
                isFirst = false;
            }
        }

        // 推送剩余内容
        if (token.length() > 0) {
            emitter.send(SseEmitter.event().name("end").data(token.toString()));
        } else {
            emitter.send(SseEmitter.event().name("end").data(""));
        }
        emitter.complete();
    }
}