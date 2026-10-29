package com.miniagent.application;

import com.miniagent.common.context.OwnerContext;

import com.miniagent.agent.context.SessionHistoryVectorStore;
import com.miniagent.agent.core.ContextCompressor;
import com.miniagent.agent.context.ContextLoader;
import com.miniagent.agent.context.LoadedContext;
import com.miniagent.agent.core.SessionEventCenter;
import jakarta.annotation.PostConstruct;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.TaskExecutionContext;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionProperties;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskPlanFactory;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.planner.PlanningLoop;
import com.miniagent.memory.MemoryService;
import com.miniagent.memory.MemoryStore;
import com.miniagent.agent.todo.TaskTodoContext;
import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.web.dto.FileAttachment;
import com.miniagent.web.dto.MediaRef;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.miniagent.agent.web.FileContentExtractor;
import com.miniagent.config.repository.ChatTaskRepository;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.service.TaskRunService;
import com.miniagent.agent.delegate.ClientMultiAgent;
import com.miniagent.agent.delegate.RoleContext;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.config.model.ModelClientFactory;
import com.miniagent.config.model.UserModelBinder;
import java.util.Objects;
import java.util.Optional;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.miniagent.common.ChatMessageTexts;
import com.miniagent.common.ChatRole;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.RunStatus;
import com.miniagent.common.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;


/**
 * 直接 Agent 模式：身份/记忆/信号拼 system prompt，Loop 只吃字符串 + 历史。
 * 记忆写入走 memory 工具 / 事件 / 回合结束巩固，不经过 Loader。
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
    private ExecutionProperties executionProperties;
    @Autowired
    private ExecutionControl executionControl;
    @Autowired
    private ClientMultiAgent clientMultiAgent;
    @Autowired
    private PlanningLoop planningLoop;
    @Autowired
    private com.miniagent.agent.trace.TraceRecorder traceRecorder;

    @Autowired
    private ChatMemoryConfig.ChatMemoryProvider chatMemoryProvider;
    @Autowired
    private MemoryStore memoryStore;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private DatabaseConversationStore conversationStore;
    @Autowired
    private TaskPlanFactory taskPlanFactory;
    @Autowired
    private ContextLoader contextLoader;
    @Autowired
    private ContextCompressor contextCompressor;
    @Autowired
    private FileContentExtractor fileContentExtractor;
    @Autowired
    private ChatTaskRepository chatTaskRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SessionEventCenter eventCenter;
    @Autowired
    private BuiltinTools builtinTools;
    @Autowired
    private TaskRunService taskRunService;
    @Autowired
    private ModelClientFactory modelClientFactory;
    /** 后台异步任务（记忆巩固）在新线程上跑，不继承 ThreadLocal，需要显式绑定用户模型。 */
    @Autowired
    private UserModelBinder userModelBinder;
    @Autowired
    private MultimodalMessageBuilder multimodalBuilder;
    @Autowired
    private ChatStreamingService streamingService;
    @Autowired(required = false)
    private SessionHistoryVectorStore sessionHistoryVectorStore;
    @Autowired(required = false)
    private com.miniagent.memory.MemoryManager memoryManager;
    @Autowired
    private com.miniagent.agent.memory.writer.MemoryToolExecutionSink toolExecutionSink;
    @Autowired
    private ConversationWorkspaceService conversationWorkspaceService;
    @Autowired
    private ConversationProjectService conversationProjectService;

    private int maxIterations() {
        return executionProperties.getMaxIterations();
    }

    @PostConstruct
    private void init() {
        agentLoop.setTraceRecorder(traceRecorder);
        agentLoop.setEventCenter(eventCenter);
        agentLoop.setToolExecutionSink(toolExecutionSink);
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

    /**
     * 把会话绑定的工作目录装到当前线程。
     *
     * <p>只设 ThreadLocal，不碰 {@code RunScope}：{@code AgentLoop} 派发工具前会
     * {@code RunScope.capture()}，捕获到的就是这里的值，随后在工具线程上
     * {@code bind()} 还原。这条链路意味着<b>必须在这里设、在 {@code finally} 里清</b> ——
     * 线程是复用的，漏清会让下一个会话在别人的目录里干活。</p>
     *
     * <p>未绑定、或绑定目录已失效时什么都不做：默认工作区由
     * {@code BuiltinTools.effectiveWorkspaceRoot()} 自己兜底，这里覆盖成默认值反而会
     * 盖掉子 Agent 的隔离目录。</p>
     */
    private void applySessionWorkspace(Long userId, String sessionId) {
        conversationWorkspaceService.sessionRoot(sessionId, userId).ifPresent(root -> {
            WorkspaceContext.setRootOverride(root);
            WorkspaceContext.setSessionOverride(sessionId);
            log.info("会话工作目录生效: session={}, path={}", sessionId, root);
        });
    }

    private String executeAgentWithProgress(
            Long userId,
            String sessionId,
            String userMessage,
            SseEmitter progressEmitter,
            List<String> imageDataUrls,
            List<MediaRef> mediaRefs) {
        Long tenantId = userRepository.findById(userId)
                .filter(com.miniagent.config.entity.User::isEnabled)
                .map(com.miniagent.config.entity.User::getTenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
        TaskRunService.StartResult start = taskRunService.tryStart(userId, sessionId);
        if (!start.accepted()) {
            throw new IllegalStateException(start.error());
        }
        TaskRunService.RunHandle run = start.handle();
        String authorizedProjectId = conversationProjectService.resolveAuthorized(
                userId, String.valueOf(tenantId), sessionId);
        OwnerContext owner = new OwnerContext(userId, String.valueOf(tenantId));
        boolean executionStarted = false;

        long[] taskId = {0L};
        try (var ignoredOwner = OwnerContext.bind(owner)) {
            OwnerContext.setCurrentProjectId(authorizedProjectId);
            executionControl.start(sessionId, tenantId);
            executionStarted = true;
            // Install the execution lease before publishing the running state.
            // A client can disconnect between taskRunService.tryStart and this
            // point; ChatStreamingService then delivers the deferred cancellation
            // to the real lease instead of losing it when start() replaces a
            // fallback lease.
            streamingService.markRunning(sessionId);
            clientMultiAgent.open(sessionId);
            memoryStore.loadFromDisk();
            try {
                memoryService.promoteUserBlob();
            } catch (Exception e) {
                log.debug("用户画像晋升跳过: {}", e.getMessage());
            }
            applySessionWorkspace(userId, sessionId);
            AgentResult agentResult = doExecuteAgent(
                    userId,
                    sessionId,
                    run,
                    userMessage,
                    progressEmitter,
                    imageDataUrls,
                    mediaRefs,
                    taskId);
            taskId[0] = agentResult.taskId();
            String answer = agentResult.answer();
            if (executionControl.isCancelled(sessionId)) {
                taskRunService.markCancelled(run, "Cancelled by user");
                recordEvent(
                        sessionId,
                        Long.toString(taskId[0]),
                        com.miniagent.memory.model.AgentEvent.EventType.TASK_FAIL,
                        "executor",
                        Map.of("reason", "cancelled"),
                        com.miniagent.memory.model.AgentEvent.EventStatus.FAILED);
                return answer;
            }
            PlanningLoop.RunOutcome unfinished = agentResult.unfinishedOutcome();
            if (unfinished != null) {
                // 「没做完」不能记为完成。
                // 此前规划器的死锁/恢复耗尽/轮次用尽都只返回一句"已按规划图推进任务"，
                // 这里照样 markCompleted —— 任务状态、完成率口径、以及用户对"做完了"的判断
                // 同时失真。现在按结局分流：UNFINISHED/FAILED 记为失败并留下原因码。
                String reason = unfinished.reason();
                taskRunService.markFailed(run, "任务未达成目标: " + reason);
                recordEvent(sessionId, Long.toString(taskId[0]),
                        com.miniagent.memory.model.AgentEvent.EventType.TASK_FAIL,
                        "executor", Map.of("reason", reason),
                        com.miniagent.memory.model.AgentEvent.EventStatus.FAILED);
                log.warn("任务未达成目标，标记为失败: session={}, outcome={}, reason={}",
                        sessionId, unfinished.outcome(), reason);
                return answer;
            }
            taskRunService.markCompleted(run);

            // 记录任务成功事件
            recordEvent(sessionId, Long.toString(taskId[0]), com.miniagent.memory.model.AgentEvent.EventType.TASK_COMPLETE,
                "executor", Map.of("answer", truncate(answer, 500)),
                com.miniagent.memory.model.AgentEvent.EventStatus.SUCCESS);

            return answer;
        } catch (Exception e) {
            if (executionStarted && executionControl.isCancelled(sessionId)) {
                taskRunService.markCancelled(run, "Cancelled by user");
            } else {
                taskRunService.markFailed(run, e.getMessage());
            }

            // 记录任务失败事件
            recordEvent(sessionId, Long.toString(taskId[0]), com.miniagent.memory.model.AgentEvent.EventType.TASK_FAIL,
                "executor", Map.of("error", e.getMessage()),
                com.miniagent.memory.model.AgentEvent.EventStatus.FAILED);

            throw e;
        } finally {
            if (executionStarted) {
                clientMultiAgent.close(sessionId);
                executionControl.finish(sessionId);
            }
            // For SSE runs the outer async wrapper publishes the terminal event
            // before releasing the ChatStreamingService reservation. Releasing it
            // here would let a rapid second request replace the channel between
            // this finally block and eventCenter.complete/error.
            if (progressEmitter == null) {
                streamingService.markIdle(sessionId);
            }
            TaskTodoContext.clear();
            // 工作区覆盖是线程级的，必须在本轮结束清掉：这个线程会被后续请求复用，
            // 留着会让下一个会话莫名其妙地在别人的项目目录里写文件。
            WorkspaceContext.clearRootOverride();
            WorkspaceContext.clearSessionOverride();

            // 巩固走后台，避免挡住 SSE end（PERM_ASK 批准后续跑会等流结束）
            if (memoryManager != null) {
                final String consolidateSid = sessionId;
                final Long consolidateUserId = userId;
                CompletableFuture.runAsync(() -> {
                    // 异步线程不继承 ThreadLocal：显式绑定归属与该用户配置的模型，
                    // 否则巩固的 LLM 提炼会回退到全局 Bean（全局 key 失效即稳定 401）。
                    try (var ignoredOwner = OwnerContext.bind(owner);
                         var ignoredProject = bindProject(authorizedProjectId);
                         var ignoredModel = userModelBinder.bindModel(consolidateUserId)) {
                        memoryManager.consolidate(owner.tenantId(), consolidateSid);
                    } catch (Exception e) {
                        log.debug("记忆巩固失败: {}", e.getMessage());
                    }
                });
            }
        }
    }

    public void cancel(Long userId, String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId required");
        }
        executionControl.cancel(sessionId);
    }

    private void recordEvent(String sessionId, String taskId,
                             com.miniagent.memory.model.AgentEvent.EventType eventType,
                             String actor, Map<String, Object> payload,
                             com.miniagent.memory.model.AgentEvent.EventStatus status) {
        if (memoryManager == null) {
            return;
        }
        try {
            com.miniagent.memory.model.AgentEvent event = new com.miniagent.memory.model.AgentEvent();
            event.setTenantId(OwnerContext.effectiveTenantId());
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

    private OwnerContext.Scope bindProject(String projectId) {
        String previous = OwnerContext.currentProjectId();
        OwnerContext.setCurrentProjectId(projectId);
        return () -> OwnerContext.setCurrentProjectId(previous);
    }

    private String truncate(String s, int maxLen) {
        return com.miniagent.common.StringUtils.truncate(s, maxLen);
    }

    /**
     * 一次 agent 执行的结果。
     *
     * <p>{@code unfinishedOutcome} 非空表示"这一轮没达成目标"（规划器死锁/恢复耗尽/轮次用尽/
     * 任务图无法验收）—— 调用方必须据此把 run 记为失败，而不是完成。</p>
     */
    record AgentResult(String answer, PlanningLoop.RunOutcome unfinishedOutcome,
                       boolean planApprovalRequired, long taskId) {
        AgentResult(String answer, PlanningLoop.RunOutcome unfinishedOutcome) {
            this(answer, unfinishedOutcome, false, 0L);
        }
    }

    /**
     * 结局是否意味着"没达成目标"（必须记为失败）。
     *
     * <p>抽成独立方法是为了让这条不变式能被单测锁住：它决定任务状态与完成率口径，
     * 一旦有人把 UNFINISHED 当成正常结束，"没做完也报成功"就会悄悄回来。
     * {@code BLOCKED_ON_HUMAN} 不算失败 —— 它在等人，既不是完成也不是失败。</p>
     */
    static boolean meansUnfinished(PlanningLoop.RunOutcome outcome) {
        return outcome != null
                && (outcome.outcome() == PlanningLoop.Outcome.UNFINISHED
                || outcome.outcome() == PlanningLoop.Outcome.FAILED);
    }

    static void captureTaskId(long[] taskIdHolder, long taskId) {
        taskIdHolder[0] = taskId;
    }

    private AgentResult doExecuteAgent(
            Long userId,
            String sessionId,
            TaskRunService.RunHandle run,
            String userMessage,
            SseEmitter progressEmitter,
            List<String> imageDataUrls,
            List<MediaRef> mediaRefs,
            long[] taskId) {
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
        // 本轮不再从用户文本里提取信号。是否拆解、是否建 todo、调哪个工具，
        // 全部由模型在 AgentLoop 内自行决定；hasMedia 只作为多模态输入传给上下文。
        TaskPlan taskPlan = taskPlanFactory.build(userMessage);
        // 权限必须在拼上下文之前绑定：system prompt 的确认口径、todo 块的执行原则
        // 都要读当前会话的「模式 + 确认策略」。晚绑一步，全自动会话拿到的仍是默认值，
        // 于是闸门已经放开、提示词还在要求模型向用户讨确认。
        PermissionContext.setSession(sessionId);
        LoadedContext loaded =
                contextLoader.load(sessionId, userMessage, hasMedia, memMsgs);
        captureTaskId(taskId, loaded.taskId());
        try (TaskExecutionContext.Scope taskScope = TaskExecutionContext.bind(loaded.taskId())) {
        // 记录的 taskId 与本轮 LoadedContext 一致，并由 RunScope 传播到工具线程。
        recordEvent(sessionId, Long.toString(loaded.taskId()),
                com.miniagent.memory.model.AgentEvent.EventType.TASK_START,
                "user", Map.of("question", truncate(userMessage, 500)), null);
        // 任务级隔离不在这里做.曾经的做法是在换任务时往会话里落一条 system 边界标记，
        // 下一轮重建历史时只取标记之后的消息 —— 那是拿「跨轮追问答不出来」换「不污染」，方向错了。
        // 现在：对话历史整段保留（追问要看得见上文），任务级状态（规划图、压缩摘要）
        // 按 loaded.scopeKey() 隔离，换任务后天然读不到旧状态。边界判定见 TaskScopeRegistry。
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

        String firstLine = StringUtils.isNotBlank(userMessage)
                ? userMessage.split("[\r\n]", 2)[0]
                : (hasMedia ? MessageConstants.CHAT_IMAGE_ANALYSIS : MessageConstants.CHAT_DEFAULT_SESSION);
        BuiltinTools.setCurrentTaskName(firstLine);
        TaskTodoContext.set(sessionId);
        String systemPrompt = loaded.systemPrompt();

        final boolean streaming = Objects.nonNull(progressEmitter);
        // 工具执行过程不再推给前端：对话区只有模型自己的话与最终答案。
        // 工具名/参数/结果原文（内部 errorCode、文件路径）留在日志与 agent_trace_steps。
        // 前端也没有 progress 的消费点了，这里不 publish 就不会进 EventStream。
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
            @Override public void onContext(int used, int limit) {
                eventCenter.publish(sessionId, "context",
                        "{\"used\":" + used + ",\"limit\":" + limit + "}");
            }
        };

        AgentLoop.setCurrentSession(sessionId);
        // 连窗口一起绑定：循环按"配置上限 vs 模型厂商窗口"的较小值决定何时压缩。
        // 不带窗口时，128k 的模型也会按 512k 估算，压缩永远不触发 → 上游 400 → 误报成网络故障。
        com.miniagent.common.model.EffectiveModelContext.set(
                effectiveChat, models.streaming(), models.settings().contextWindowTokens());
        String answer;
        // 规划器的结局：只有"没做完"会被带出去让调用方记为失败；完成与人等状态保持原语义。
        PlanningLoop.RunOutcome unfinishedOutcome = null;
        boolean planApprovalRequired = false;
        try {
            String executionId = Objects.nonNull(traceRecorder)
                    ? traceRecorder.currentExecutionId() : null;
            if (planningLoop.shouldHandle(taskPlan, sessionId, userMessage)) {
                PlanningLoop.RunOutcome outcome = planningLoop.runWithOutcome(
                        effectiveChat, systemPrompt, userMessage, multimodalMsg,
                        history, taskPlan, sessionId, run.fencingToken(), executionId,
                        streamSink);
                answer = outcome.answer();
                planApprovalRequired = "PLAN_APPROVAL_REQUIRED".equals(outcome.reason());
                if (planApprovalRequired && progressEmitter != null) {
                    eventCenter.publish(sessionId, "plan_approval",
                            Map.of("required", true, "reason", outcome.reason()));
                }
                if (meansUnfinished(outcome)) {
                    unfinishedOutcome = outcome;
                    runStatus = RunStatus.FAILURE.name();
                }
            } else if (hasMedia) {
                answer = agentLoop.runWithMultimodal(effectiveChat, systemPrompt, multimodalMsg, history,
                        maxIterations(), taskPlan, streamSink);
            } else {
                answer = agentLoop.run(effectiveChat, systemPrompt, userMessage, history,
                        maxIterations(), taskPlan, streamSink);
            }
            if (executionControl.isCancelled(sessionId)) {
                runStatus = RunStatus.CANCELED.name();
            } else if (planningLoop.isAwaitingConfirm(sessionId)) {
                runStatus = RunStatus.WAITING.name();
            }
        } finally {
            PermissionContext.clear();
            AgentLoop.clearCurrentModels();
            AgentLoop.clearCurrentSession();
        }

        if (hasMedia) {
            memory.add(multimodalMsg);
        } else {
            memory.add(UserMessage.from(userMessage));
        }
        memory.add(AiMessage.from(answer));

        ChatTask task = new ChatTask();
        task.setUserId(userId);
        task.setSessionId(sessionId);
        task.setQuestion(displayQuestion);
        task.setAnswer(answer);
        if (!allSavedPaths.isEmpty()) {
            task.setImages(String.join(",", allSavedPaths));
        }
        chatTaskRepository.save(task);
        persistTurn(userId, sessionId,
                StringUtils.isNotBlank(userMessage) ? userMessage : displayQuestion,
                answer,
                allSavedPaths.isEmpty() ? null : allSavedPaths);
        return new AgentResult(answer, unfinishedOutcome, planApprovalRequired, loaded.taskId());
        }
        } catch (Exception e) {
            runStatus = RunStatus.FAILURE.name();
            if (Objects.nonNull(traceRecorder)) {
                traceRecorder.recordError(sessionId, 0, e.getMessage());
            }
            throw e;
        } finally {
            // 权限上下文在这里提前绑定过，任何在进入内层 try 之前抛出的异常都要清掉，
            // 否则线程复用时会带着上一个会话的模式去找下一个会话要授权。
            PermissionContext.clear();
            TaskExecutionContext.clear();
            if (Objects.nonNull(traceRecorder) && traceRecorder.isActive()) {
                traceRecorder.endExecution(runStatus);
            }
        }
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
            if (StringUtils.isNotBlank(userMessage)) {
                sb.append(userMessage);
            }
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
                Optional.ofNullable(finalUserMessage).orElse(""), userId);

        CompletableFuture.runAsync(() -> {
            try {
                if (StringUtils.isNotBlank(finalRole)) {
                    RoleContext.setRole(finalRole);
                }
                String answer = executeAgentWithProgress(
                        userId, finalSid, finalUserMessage, emitter, finalImages, finalMedia);
                streamingService.markCompleting(finalSid);
                streamingService.getEventCenter().complete(finalSid, Optional.ofNullable(answer).orElse(""));
            } catch (Exception e) {
                streamingService.markCompleting(finalSid);
                streamingService.getEventCenter().error(finalSid, MessageConstants.CHAT_PROCESSING_ERROR + e.getMessage());
            } finally {
                streamingService.markIdle(finalSid);
                RoleContext.clear();
            }
        });

        return emitter;
    }
}
