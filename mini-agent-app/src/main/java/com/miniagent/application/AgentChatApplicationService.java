package com.miniagent.application;

import com.miniagent.agent.core.ContextCompressor;
import com.miniagent.agent.context.ContextLoader;
import com.miniagent.agent.context.LoadedContext;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.ConcurrentHashMap;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.TokenUsageTracker;
import com.miniagent.agent.intent.IntentPlanner;
import com.miniagent.agent.intent.IntentType;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.agent.planner.PlanningLoop;
import com.miniagent.memory.MemoryStore;
import com.miniagent.config.storage.MediaStorage;
import com.miniagent.agent.todo.TaskTodoContext;
import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.service.DatabaseConversationStore.Conversation;
import com.miniagent.config.service.DatabaseConversationStore.ConversationSummary;
import com.miniagent.web.dto.FileAttachment;
import com.miniagent.web.dto.MediaRef;
import com.miniagent.agent.web.MultimodalMedia;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Base64;
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

    /** SSE 流式连接超时（毫秒）。可配置，默认 30 分钟，避免长任务被 10 分钟硬上限掐断。0 表示永不超时（不建议，有连接泄漏风险）。 */
    @Value("${agent.sse.timeout-ms:1800000}")
    private long sseTimeoutMs;
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
    private com.miniagent.agent.core.SessionEventCenter eventCenter;
    @Autowired
    private BuiltinTools builtinTools;
    @Autowired
    private TaskRunService taskRunService;
    @Autowired
    private MediaStorage mediaStorage;
    @Autowired
    private ModelClientFactory modelClientFactory;
    @Autowired(required = false)
    private com.miniagent.agent.context.SessionHistoryVectorStore sessionHistoryVectorStore;

    private static final int MAX_ITERATIONS = 90;

    /** 正在运行的任务：sessionId -> true。用于前端查询任务状态（内存快路径）。 */
    private final ConcurrentHashMap<String, Boolean> runningTasks = new ConcurrentHashMap<>();

    @PostConstruct
    private void init() {
        agentLoop.setTraceRecorder(traceRecorder);
        agentLoop.setEventCenter(eventCenter);
        planningLoop.setTraceRecorder(traceRecorder);
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
        boolean ok = eventCenter.attachClient(sessionId, emitter);
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
    // 对话入口
    // =========================================================================

    public String chat(Long userId, String sessionId, String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_EMPTY);
        }
        String sid = (StringUtils.isBlank(sessionId))
                ? UUID.randomUUID().toString().substring(0, 8)
                : sessionId.trim();
        return executeAgent(userId, sid, userMessage.trim());
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
                                            org.springframework.web.servlet.mvc.method.annotation.SseEmitter progressEmitter) {
        return executeAgentWithProgress(userId, sessionId, userMessage, progressEmitter, List.of(), List.of());
    }

    /** 文本 / 多模态统一入口：并发闸门 +（可选）SSE sink */
    private String executeAgentWithProgress(Long userId, String sessionId, String userMessage,
                                            org.springframework.web.servlet.mvc.method.annotation.SseEmitter progressEmitter,
                                            List<String> imageDataUrls) {
        return executeAgentWithProgress(userId, sessionId, userMessage, progressEmitter, imageDataUrls, List.of());
    }

    private String executeAgentWithProgress(Long userId, String sessionId, String userMessage,
                                            org.springframework.web.servlet.mvc.method.annotation.SseEmitter progressEmitter,
                                            List<String> imageDataUrls,
                                            List<MediaRef> mediaRefs) {
        String acquireErr = taskRunService.tryStart(userId, sessionId);
        if (Objects.nonNull(acquireErr)) {
            throw new IllegalStateException(acquireErr);
        }
        runningTasks.put(sessionId, Boolean.TRUE);
        MemoryStore.setCurrentUser(userId);
        memoryStore.loadFromDisk();
        try {
            String answer = doExecuteAgent(userId, sessionId, userMessage, progressEmitter,
                    imageDataUrls, mediaRefs);
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

        List<String> savedImagePaths = hasImage ? saveImagesToDisk(sessionId, images) : new ArrayList<>();
        List<String> savedMediaPaths = hasAv ? copyMediaToConversation(userId, sessionId, media) : List.of();
        List<String> allSavedPaths = new ArrayList<>(savedImagePaths);
        allSavedPaths.addAll(savedMediaPaths);

        UserMessage multimodalMsg = hasMedia
                ? buildMultimodalUserMessage(userId, userMessage, images, savedImagePaths, media, savedMediaPaths)
                : null;
        String displayQuestion = buildDisplayQuestion(userMessage, images.size(), media.size());

        if (taskPlan.intent() == IntentType.REVIEW) {
            AgentLoop.setCurrentModels(effectiveChat, models.streaming());
            try {
                String answer;
                if (hasMedia) {
                    answer = answerReviewMultimodal(userId, userMessage, images, media, history);
                } else {
                    answer = answerReviewQuestion(userMessage, null, history);
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

    private static String buildDisplayQuestion(String userMessage, int imageCount, int mediaCount) {
        String base = StringUtils.isNotBlank(userMessage)
                ? userMessage
                : (imageCount + mediaCount > 0
                ? MessageConstants.CHAT_MEDIA_PLACEHOLDER
                : "");
        StringBuilder sb = new StringBuilder(base);
        if (imageCount > 0) sb.append(" 📷x").append(imageCount);
        if (mediaCount > 0) sb.append(" 🎙x").append(mediaCount);
        return sb.toString();
    }

    private List<String> copyMediaToConversation(Long userId, String sessionId, List<MediaRef> media) {
        List<String> keys = new ArrayList<>();
        for (MediaRef ref : media) {
            try {
                Path src = resolveOwnedUpload(userId, ref.getFilePath());
                String key = mediaStorage.copyUploadToConversation(
                        sessionId, src, ref.getFilename());
                if (StringUtils.isNotBlank(key)) keys.add(key);
            } catch (Exception e) {
                log.warn("复制音视频到会话失败: {}", e.getMessage());
            }
        }
        return keys;
    }

    private Path resolveOwnedUpload(Long userId, String filePath) throws IOException {
        Path p = mediaStorage.resolve(filePath).normalize();
        if (!Files.isRegularFile(p))
            throw new IOException("媒体文件不存在: " + filePath);
        Path userRoot = mediaStorage.uploadsDir().resolve(String.valueOf(userId)).normalize();
        Path convRoot = mediaStorage.conversationsDir().normalize();
        if (!p.startsWith(userRoot) && !p.startsWith(convRoot))
            throw new IOException("非法媒体路径");
        return p;
    }

    private UserMessage buildMultimodalUserMessage(Long userId, String userMessage, List<String> images,
                                                   List<String> savedImagePaths,
                                                   List<MediaRef> mediaRefs,
                                                   List<String> savedMediaPaths) {
        List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        if (StringUtils.isNotBlank(userMessage)) text.append(userMessage);
        Optional.ofNullable(savedImagePaths).filter(paths -> !paths.isEmpty()).ifPresent(paths -> {
            text.append(String.format(MessageConstants.CHAT_USER_UPLOADED_IMAGES, paths.size()));
            for (int i = 0; i < paths.size(); i++) {
                text.append(String.format(MessageConstants.CHAT_IMAGE_LOCAL_PATH, i + 1, paths.get(i)))
                        .append('\n');
            }
        });
        if (Objects.nonNull(mediaRefs) && !mediaRefs.isEmpty()) {
            text.append(MessageConstants.CHAT_USER_UPLOADED_MEDIA);
            for (int i = 0; i < mediaRefs.size(); i++) {
                MediaRef r = mediaRefs.get(i);
                String pathNote = (Objects.nonNull(savedMediaPaths) && i < savedMediaPaths.size())
                        ? savedMediaPaths.get(i)
                        : r.getFilePath();
                String label = MultimodalMedia.KIND_VIDEO.equals(r.getKind()) ? "视频" : "音频";
                text.append(String.format(MessageConstants.CHAT_MEDIA_LOCAL_PATH, label, pathNote))
                        .append('\n');
            }
        }
        contents.add(TextContent.from(text.toString()));
        if (Objects.nonNull(images))
            images.forEach(img -> contents.add(ImageContent.from(img)));
        if (Objects.nonNull(mediaRefs)) {
            for (MediaRef ref : mediaRefs) {
                try {
                    appendMediaContent(contents, userId, ref);
                } catch (Exception e) {
                    log.warn("加载音视频失败 {}: {}", ref.getFilename(), e.getMessage());
                    contents.add(TextContent.from("[媒体读取失败: " + ref.getFilename() + "]"));
                }
            }
        }
        return UserMessage.from(contents);
    }

    private void appendMediaContent(List<dev.langchain4j.data.message.Content> contents,
                                    Long userId, MediaRef ref) throws IOException {
        Path path = Objects.nonNull(userId)
                ? resolveOwnedUpload(userId, ref.getFilePath())
                : mediaStorage.resolve(ref.getFilePath()).normalize();
        if (!Files.isRegularFile(path))
            throw new IOException("not found");
        long size = Files.size(path);
        String kind = Optional.ofNullable(ref.getKind())
                .orElseGet(() -> MultimodalMedia.kindOf(ref.getFilename(), ref.getMimeType()));
        long limit = MultimodalMedia.KIND_VIDEO.equals(kind) ? videoMaxBytes : audioMaxBytes;
        if (size > limit)
            throw new IOException("媒体超过上限 " + (limit / 1024 / 1024) + "MB");
        byte[] bytes = Files.readAllBytes(path);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        String mime = MultimodalMedia.mimeOf(ref.getFilename(), ref.getMimeType(), kind);
        if (MultimodalMedia.KIND_VIDEO.equals(kind))
            contents.add(VideoContent.from(b64, mime));
        else
            contents.add(AudioContent.from(b64, mime));
    }

    private String answerReviewMultimodal(Long userId, String userMessage, List<String> images,
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
                        appendMediaContent(contents, userId, ref);
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

    private UserMessage buildMultimodalUserMessage(String userMessage, List<String> images,
                                                   List<String> savedImagePaths) {
        return buildMultimodalUserMessage(null, userMessage, images, savedImagePaths, List.of(), List.of());
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
                    ? MessageConstants.CHAT_REVIEW_ANALYZE_IMAGES
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
                    ? MessageConstants.CHAT_REVIEW_QUALITY_FEEDBACK
                    : answer;
        } catch (Exception e) {
            return MessageConstants.CHAT_REVIEW_QUALITY_FEEDBACK + " 当前评审模式调用失败：" + e.getMessage();
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
        return sanitized.length() <= maxChars ? sanitized : sanitized.substring(0, maxChars) + MessageConstants.CHAT_TRUNCATED;
    }

    // =========================================================================
    // 会话管理（供 Controller 调用）
    // =========================================================================

    /** 删除会话 */
    public void resetConversation(Long userId, String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            String sid = sessionId.trim();
            conversationStore.delete(sid);
            deleteSessionHistoryVectors(sid);
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
        boolean ok = conversationStore.delete(sessionId);
        if (ok) deleteSessionHistoryVectors(sessionId);
        return ok;
    }

    public boolean deleteConversationForUser(Long userId, String sessionId) {
        boolean ok = conversationStore.deleteForUser(userId, sessionId);
        if (ok) deleteSessionHistoryVectors(sessionId);
        return ok;
    }

    private void deleteSessionHistoryVectors(String sessionId) {
        if (sessionHistoryVectorStore != null && StringUtils.isNotBlank(sessionId)) {
            try {
                sessionHistoryVectorStore.deleteSession(sessionId.trim());
            } catch (Exception e) {
                log.warn("删除会话历史向量失败 sessionId={}: {}", sessionId, e.getMessage());
            }
        }
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
                emitter.send(SseEmitter.event().name("error").data(MessageConstants.CHAT_INPUT_EMPTY));
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
        eventCenter.start(finalSid, finalMsg);
        eventCenter.attachClient(finalSid, emitter);

        // 异步执行 Agent；思考/工具/答案增量经中枢实时扇出，完成/异常由中枢统一收尾
        CompletableFuture.runAsync(() -> {
            try {
                String answer = executeAgentWithProgress(userId, finalSid, finalMsg, emitter);
                eventCenter.complete(finalSid, Optional.ofNullable(answer).orElse(""));
            } catch (Exception e) {
                eventCenter.error(finalSid, MessageConstants.CHAT_PROCESSING_ERROR + e.getMessage());
            }
        });

        return emitter;
    }

    public SseEmitter chatStreamWithImage(Long userId, String sessionId, String userMessage, String imageDataUrl) {
        return chatStreamMultimodal(userId, sessionId, userMessage,
                (StringUtils.isBlank(imageDataUrl)) ? List.of() : List.of(imageDataUrl), null, null, null);
    }

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
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data(MessageConstants.CHAT_MULTIMODAL_EMPTY));
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
        final List<MediaRef> finalMedia = media;

        eventCenter.start(finalSid, Optional.ofNullable(finalUserMessage).orElse(""));
        eventCenter.attachClient(finalSid, emitter);

        CompletableFuture.runAsync(() -> {
            try {
                if (StringUtils.isNotBlank(finalRole)) RoleContext.setRole(finalRole);
                String answer = executeAgentWithProgress(
                        userId, finalSid, finalUserMessage, emitter, finalImages, finalMedia);
                eventCenter.complete(finalSid, Optional.ofNullable(answer).orElse(""));
            } catch (Exception e) {
                eventCenter.error(finalSid, MessageConstants.CHAT_PROCESSING_ERROR + e.getMessage());
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