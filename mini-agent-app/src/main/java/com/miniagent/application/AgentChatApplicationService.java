package com.miniagent.application;

import com.miniagent.agent.context.SessionHistoryVectorStore;
import com.miniagent.agent.core.ContextCompressor;
import com.miniagent.agent.context.ContextLoader;
import com.miniagent.agent.context.LoadedContext;
import com.miniagent.agent.core.SessionEventCenter;
import jakarta.annotation.PostConstruct;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.intent.IntentPlanner;
import com.miniagent.agent.intent.IntentType;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.agent.planner.PlanningLoop;
import com.miniagent.memory.MemoryStore;
import com.miniagent.agent.todo.TaskTodoContext;
import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.web.dto.FileAttachment;
import com.miniagent.web.dto.MediaRef;
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
import com.miniagent.config.repository.ChatTaskRepository;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.service.TaskRunService;
import com.miniagent.agent.delegate.RoleContext;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.config.model.ModelClientFactory;
import java.util.Objects;
import java.util.Optional;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.miniagent.common.ChatMessageTexts;
import com.miniagent.common.ChatRole;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.RunStatus;
import com.miniagent.common.exception.BusinessException;
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

    @Value("${agent.multimodal.audio-max-bytes:36700160}")
    private long audioMaxBytes;
    @Value("${agent.multimodal.video-max-bytes:36700160}")
    private long videoMaxBytes;

    @Autowired
    private AgentLoop agentLoop;
    @Autowired
    private PlanningLoop planningLoop;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private com.miniagent.agent.trace.TraceRecorder traceRecorder;

    @Autowired
    private ChatMemoryConfig.ChatMemoryProvider chatMemoryProvider;
    @Autowired
    private MemoryStore memoryStore;
    @Autowired
    private DatabaseConversationStore conversationStore;
    @Autowired
    private IntentPlanner intentPlanner;
    @Autowired
    private ContextLoader contextLoader;
    @Autowired
    private ContextCompressor contextCompressor;
    @Autowired
    private FileContentExtractor fileContentExtractor;
    @Autowired
    private ChatTaskRepository chatTaskRepository;
    @Autowired
    private SessionEventCenter eventCenter;
    @Autowired
    private BuiltinTools builtinTools;
    @Autowired
    private TaskRunService taskRunService;
    @Autowired
    private ModelClientFactory modelClientFactory;
    @Autowired
    private MultimodalMessageBuilder multimodalBuilder;
    @Autowired
    private ChatStreamingService streamingService;
    @Autowired(required = false)
    private SessionHistoryVectorStore sessionHistoryVectorStore;
    @Autowired(required = false)
    private com.miniagent.memory.MemoryManager memoryManager;

    private static final int MAX_ITERATIONS = 90;

    @PostConstruct
    private void init() {
        agentLoop.setTraceRecorder(traceRecorder);
        agentLoop.setEventCenter(eventCenter);
        planningLoop.setTraceRecorder(traceRecorder);
    }

    // =========================================================================
    // 对话入口
    // =========================================================================

    public String chat(Long userId, String sessionId, String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_EMPTY);
        }
        String sid = (StringUtils.isBlank(sessionId))
                ? UUID.randomUUID().toString().substring(0, 8)
                : sessionId.trim();
        return executeAgentWithProgress(userId, sid, userMessage.trim(), null, List.of(), List.of());
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
                conversationStore.addMessageWithImages(sessionId, ChatRole.USER.getValue(), userMessage, userImagePaths);
            } else {
                conversationStore.addMessage(sessionId, ChatRole.USER.getValue(), userMessage);
            }
            conversationStore.addMessage(sessionId, ChatRole.ASSISTANT.getValue(), answer);
            if (sessionHistoryVectorStore != null && sessionHistoryVectorStore.isEnabled()) {
                sessionHistoryVectorStore.upsertTurn(sessionId, userMessage, answer);
            }
        } catch (Exception e) {
            log.warn("持久化会话历史失败: sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    private String executeAgentWithProgress(Long userId, String sessionId, String userMessage,
                                            org.springframework.web.servlet.mvc.method.annotation.SseEmitter progressEmitter,
                                            List<String> imageDataUrls,
                                            List<MediaRef> mediaRefs) {
        String acquireErr = taskRunService.tryStart(userId, sessionId);
        if (Objects.nonNull(acquireErr)) {
            throw new IllegalStateException(acquireErr);
        }
        streamingService.markRunning(sessionId);
        MemoryStore.setCurrentUser(userId);
        memoryStore.loadFromDisk();

        // 记录任务开始事件
        recordEvent(sessionId, null, com.miniagent.memory.model.AgentEvent.EventType.TASK_START,
            "user", Map.of("question", truncate(userMessage, 500)), null);

        try {
            String answer = doExecuteAgent(userId, sessionId, userMessage, progressEmitter,
                    imageDataUrls, mediaRefs);
            taskRunService.markCompleted(userId, sessionId);

            // 记录任务成功事件
            recordEvent(sessionId, null, com.miniagent.memory.model.AgentEvent.EventType.TASK_COMPLETE,
                "executor", Map.of("answer", truncate(answer, 500)),
                com.miniagent.memory.model.AgentEvent.EventStatus.SUCCESS);

            return answer;
        } catch (Exception e) {
            taskRunService.markFailed(userId, sessionId, e.getMessage());

            // 记录任务失败事件
            recordEvent(sessionId, null, com.miniagent.memory.model.AgentEvent.EventType.TASK_FAIL,
                "executor", Map.of("error", e.getMessage()),
                com.miniagent.memory.model.AgentEvent.EventStatus.FAILED);

            throw e;
        } finally {
            streamingService.markIdle(sessionId);
            MemoryStore.clearCurrentUser();
            TaskTodoContext.clear();

            // 巩固记忆
            if (memoryManager != null) {
                try {
                    memoryManager.consolidate(sessionId);
                } catch (Exception e) {
                    log.debug("记忆巩固失败: {}", e.getMessage());
                }
            }
        }
    }

    private void recordEvent(String sessionId, String taskId,
                             com.miniagent.memory.model.AgentEvent.EventType eventType,
                             String actor, Map<String, Object> payload,
                             com.miniagent.memory.model.AgentEvent.EventStatus status) {
        if (memoryManager == null) return;
        try {
            com.miniagent.memory.model.AgentEvent event = new com.miniagent.memory.model.AgentEvent();
            event.setTenantId("default");
            event.setSessionId(sessionId);
            event.setTaskId(taskId);
            event.setEventType(eventType);
            event.setActor(actor);
            event.setPayload(payload);
            event.setStatus(status);
            memoryManager.recordEvent(event);
        } catch (Exception e) {
            log.debug("事件记录失败: {}", e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private String doExecuteAgent(Long userId, String sessionId, String userMessage,
                                  org.springframework.web.servlet.mvc.method.annotation.SseEmitter progressEmitter,
                                  List<String> imageDataUrls,
                                  List<MediaRef> mediaRefs) {
        List<String> images = Optional.ofNullable(imageDataUrls).orElseGet(List::of).stream()
                .filter(StringUtils::isNotBlank).toList();
        List<MediaRef> media = Optional.ofNullable(mediaRefs).orElseGet(List::of).stream()
                .filter(r -> Objects.nonNull(r) && StringUtils.isNotBlank(r.getFilePath()))
                .toList();
        boolean hasImage = !images.isEmpty();
        boolean hasAv = !media.isEmpty();
        boolean hasMedia = hasImage || hasAv;
        String runStatus = RunStatus.SUCCESS.name();

        contextCompressor.setCurrentSession(sessionId);
        builtinTools.clearToolCache();

        if (Objects.nonNull(traceRecorder)) {
            traceRecorder.ensureExecution(sessionId, userMessage);
        }
        try {
        ModelClientFactory.ResolvedModels models = hasMedia
                ? modelClientFactory.resolveForMultimodal(userId)
                : modelClientFactory.resolve(userId);
        ChatModel effectiveChat = models.chat();
        log.info("本轮模型: userId={}, preset={}, model={}, multimodal={}",
                userId, models.settings().presetId(), models.settings().modelName(), hasMedia);

        ChatMemory memory = chatMemoryProvider.get(sessionId);
        List<ChatMessage> memMsgs = memory.messages();
        TaskPlan taskPlan = intentPlanner.plan(effectiveChat, userMessage, hasMedia, memMsgs);
        LoadedContext loaded = contextLoader.load(sessionId, userMessage, taskPlan, memMsgs);
        // 历史一律文本化：旧轮 image_url 会让文本端点直接 400
        List<ChatMessage> history = ChatMessageTexts.textOnlyHistory(loaded.history());

        List<String> savedImagePaths = hasImage ? multimodalBuilder.saveImagesToDisk(sessionId, images) : new ArrayList<>();
        List<String> savedMediaPaths = hasAv ? multimodalBuilder.copyMediaToConversation(userId, sessionId, media) : List.of();
        List<String> allSavedPaths = new ArrayList<>(savedImagePaths);
        allSavedPaths.addAll(savedMediaPaths);

        UserMessage multimodalMsg = hasMedia
                ? multimodalBuilder.buildMultimodalUserMessage(userId, userMessage, images, savedImagePaths, media, savedMediaPaths)
                : null;
        String displayQuestion = MultimodalMessageBuilder.buildDisplayQuestion(userMessage, images.size(), media.size());

        if (taskPlan.intent() == IntentType.REVIEW) {
            AgentLoop.setCurrentModels(effectiveChat, models.streaming());
            try {
                String answer = answerReview(userId, userMessage, images, media, history);
                if (hasMedia) memory.add(multimodalMsg);
                else memory.add(UserMessage.from(userMessage));
                memory.add(AiMessage.from(answer));
                ChatTask task = new ChatTask();
                task.setUserId(userId);
                task.setSessionId(sessionId);
                task.setQuestion(displayQuestion);
                task.setAnswer(answer);
                if (!allSavedPaths.isEmpty()) task.setImages(String.join(",", allSavedPaths));
                chatTaskRepository.save(task);
                persistTurn(userId, sessionId,
                        StringUtils.isNotBlank(userMessage) ? userMessage : displayQuestion,
                        answer,
                        allSavedPaths.isEmpty() ? null : allSavedPaths);
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
                : (hasMedia ? MessageConstants.CHAT_IMAGE_ANALYSIS : MessageConstants.CHAT_DEFAULT_SESSION);
        BuiltinTools.setCurrentTaskName(firstLine);
        TaskTodoContext.set(sessionId);
        String systemPrompt = loaded.systemPrompt();

        final boolean streaming = Objects.nonNull(progressEmitter);
        Consumer<String> progress = Optional.ofNullable(progressEmitter)
                .<Consumer<String>>map(em -> msg ->
                        eventCenter.publish(sessionId, "progress", Optional.ofNullable(msg).orElse("")))
                .orElse(null);
        com.miniagent.agent.core.AgentStreamSink streamSink = !streaming ? null
                : new com.miniagent.agent.core.AgentStreamSink() {
            @Override public void onThinking(String delta) {
                eventCenter.publish(sessionId, "thinking", delta);
            }
            @Override public void onAnswerToken(String delta) {
                eventCenter.publish(sessionId, "token", delta);
            }
            @Override public void onAnswerReset() {
                eventCenter.publish(sessionId, "seal", "");
            }
            @Override public void onSubGoal(String text, int done, int total) {
                eventCenter.publishSubGoal(sessionId, text, done, total);
            }
        };

        AgentLoop.setCurrentSession(sessionId);
        AgentLoop.setCurrentModels(effectiveChat, models.streaming());
        PermissionContext.setSession(sessionId);
        String answer;
        try {
            String executionId = Objects.nonNull(traceRecorder)
                    ? traceRecorder.currentExecutionId() : null;
            if (planningLoop.shouldHandle(taskPlan)) {
                answer = planningLoop.run(effectiveChat, systemPrompt, userMessage, multimodalMsg,
                        history, taskPlan, sessionId, executionId, progress, streamSink);
            } else if (hasMedia) {
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

        if (hasMedia) memory.add(multimodalMsg);
        else memory.add(UserMessage.from(userMessage));
        memory.add(AiMessage.from(answer));

        ChatTask task = new ChatTask();
        task.setUserId(userId);
        task.setSessionId(sessionId);
        task.setQuestion(displayQuestion);
        task.setAnswer(answer);
        if (!allSavedPaths.isEmpty()) task.setImages(String.join(",", allSavedPaths));
        chatTaskRepository.save(task);
        persistTurn(userId, sessionId,
                StringUtils.isNotBlank(userMessage) ? userMessage : displayQuestion,
                answer,
                allSavedPaths.isEmpty() ? null : allSavedPaths);
        updateMidtermMemoryAsync(userId, displayQuestion, answer);
        return answer;
        } catch (Exception e) {
            runStatus = RunStatus.FAILURE.name();
            if (Objects.nonNull(traceRecorder)) {
                traceRecorder.recordError(sessionId, 0, e.getMessage());
            }
            throw e;
        } finally {
            if (Objects.nonNull(traceRecorder) && traceRecorder.isActive()) {
                traceRecorder.endExecution(runStatus);
            }
        }
    }

    private String answerReview(Long userId, String userMessage, List<String> images,
                                List<MediaRef> media, List<ChatMessage> history) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(PromptTemplates.REVIEW_MODE_PROMPT));
            if (Objects.nonNull(history) && !history.isEmpty()) {
                int from = Math.max(0, history.size() - 4);
                messages.addAll(history.subList(from, history.size()));
            }
            String text = StringUtils.isBlank(userMessage)
                    ? MessageConstants.CHAT_REVIEW_ANALYZE_IMAGES
                    : userMessage;
            List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
            contents.add(TextContent.from(text));
            if (Objects.nonNull(images))
                for (String img : images) contents.add(ImageContent.from(img));
            if (Objects.nonNull(media)) {
                for (MediaRef ref : media) {
                    try {
                        multimodalBuilder.appendMediaContent(contents, userId, ref);
                    } catch (Exception e) {
                        contents.add(TextContent.from("[媒体读取失败: " + ref.getFilename() + "]"));
                    }
                }
            }
            messages.add(UserMessage.from(contents));
            ChatModel model = Optional.ofNullable(AgentLoop.getCurrentChatModel()).orElse(chatModel);
            var response = model.chat(ChatRequest.builder().messages(messages).build());
            String answer = response.aiMessage().text();
            return StringUtils.isBlank(answer)
                    ? MessageConstants.CHAT_REVIEW_QUALITY_FEEDBACK
                    : answer;
        } catch (Exception e) {
            return MessageConstants.CHAT_REVIEW_QUALITY_FEEDBACK + " 当前评审模式调用失败：" + e.getMessage();
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
                        StringUtils.isBlank(oldMemory) ? MessageConstants.MEMORY_EMPTY : oldMemory,
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
        return sanitized.length() <= maxChars ? sanitized : sanitized.substring(0, maxChars) + MessageConstants.CHAT_TRUNCATED;
    }

    // =========================================================================
    // SSE 流式对话
    // =========================================================================

    /**
     * 统一多模态 SSE 入口：文字 + 图片 + 已上传音视频。
     */
    public SseEmitter chatStreamMultimodal(Long userId, String sessionId, String userMessage,
                                           List<String> imageDataUrls, List<FileAttachment> files,
                                           List<MediaRef> mediaRefs, String role) {
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
        List<MediaRef> media = Optional.ofNullable(mediaRefs).orElseGet(List::of).stream()
                .filter(r -> Objects.nonNull(r) && StringUtils.isNotBlank(r.getFilePath()))
                .toList();
        if (StringUtils.isBlank(userMessage) && images.isEmpty() && media.isEmpty()) {
            return streamingService.createErrorEmitter("error", MessageConstants.CHAT_MULTIMODAL_EMPTY);
        }

        String sid = StringUtils.isBlank(sessionId)
                ? UUID.randomUUID().toString().substring(0, 8)
                : sessionId.trim();
        final String finalSid = sid;
        final String finalUserMessage = userMessage;
        final String finalRole = role;
        final List<String> finalImages = images;
        final List<MediaRef> finalMedia = media;

        SseEmitter emitter = streamingService.createStream(finalSid,
                Optional.ofNullable(finalUserMessage).orElse(""));

        CompletableFuture.runAsync(() -> {
            try {
                if (StringUtils.isNotBlank(finalRole)) RoleContext.setRole(finalRole);
                String answer = executeAgentWithProgress(
                        userId, finalSid, finalUserMessage, emitter, finalImages, finalMedia);
                streamingService.getEventCenter().complete(finalSid, Optional.ofNullable(answer).orElse(""));
            } catch (Exception e) {
                streamingService.getEventCenter().error(finalSid, MessageConstants.CHAT_PROCESSING_ERROR + e.getMessage());
            } finally {
                RoleContext.clear();
            }
        });

        return emitter;
    }
}