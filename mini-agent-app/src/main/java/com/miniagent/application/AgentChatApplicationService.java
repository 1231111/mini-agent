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
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.web.dto.FileAttachment;
import com.miniagent.web.dto.FileRef;
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
import com.miniagent.agent.web.SearchDateContext;
import com.miniagent.agent.desktop.DesktopInteractionService;
import com.miniagent.config.repository.ChatTaskRepository;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.service.TaskRunService;
import com.miniagent.config.service.TaskRunLeaseRenewer;
import com.miniagent.agent.delegate.ClientMultiAgent;
import com.miniagent.agent.delegate.RoleContext;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.config.model.ModelClientFactory;
import com.miniagent.config.model.UserModelBinder;
import java.util.Objects;
import java.util.Optional;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
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

    @Value("${web.search.time-zone:Asia/Shanghai}")
    private String searchTimeZone = "Asia/Shanghai";
    private Clock searchClock = Clock.systemUTC();

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
    private TaskTodoStore taskTodoStore;
    @Autowired
    private BuiltinTools builtinTools;
    /**
     * 代码诊断待检查队列。与 {@code builtinTools.clearToolCache()} 同生命周期清空：
     * 新用户消息开始时，上一轮没来得及检查的文件不该再作为「刚改过」注入。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.miniagent.agent.coding.DiagnosticCollector diagnosticCollector;
    @Autowired
    private TaskRunService taskRunService;
    @Autowired(required = false)
    private TaskRunLeaseRenewer taskRunLeaseRenewer;
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
    private DesktopInteractionService desktopInteractionService;
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
        return executeAgentWithProgress(
                userId, sid, userMessage.trim(), userMessage.trim(), null,
                List.of(), List.of(), List.of(), null, null).answer();
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

    private record PreparedRun(Long tenantId, String projectId, TaskRunService.RunHandle handle, Instant requestedAt) {}

    private PreparedRun prepareRun(Long userId, String sessionId) {
        Instant requestedAt = searchClock.instant();
        Long tenantId = userRepository.findById(userId)
                .filter(com.miniagent.config.entity.User::isEnabled)
                .map(com.miniagent.config.entity.User::getTenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
        String projectId = conversationProjectService.resolveAuthorized(
                userId, String.valueOf(tenantId), sessionId);
        TaskRunService.StartResult start = taskRunService.tryStart(userId, sessionId);
        if (!start.accepted()) {
            throw new IllegalStateException(start.error());
        }
        return new PreparedRun(tenantId, projectId, start.handle(), requestedAt);
    }

    private AgentResult executeAgentWithProgress(
            Long userId,
            String sessionId,
            String userMessage,
            String displayMessage,
            SseEmitter progressEmitter,
            List<String> imageDataUrls,
            List<MediaRef> mediaRefs,
            List<FileRef> fileRefs,
            PreparedRun acceptedRun,
            String streamToken) {
        PreparedRun prepared = acceptedRun == null ? prepareRun(userId, sessionId) : acceptedRun;
        Long tenantId = prepared.tenantId();
        String authorizedProjectId = prepared.projectId();
        TaskRunService.RunHandle run = prepared.handle();
        OwnerContext owner = new OwnerContext(userId, String.valueOf(tenantId));
        boolean executionStarted = false;
        boolean workerOpenAttempted = false;
        AutoCloseable leaseRenewal = null;

        long[] taskId = {0L};
        try (var ignoredOwner = OwnerContext.bind(owner);
             var ignoredRun = TaskExecutionContext.bindRun(run.fencingToken());
             var ignoredDate = SearchDateContext.bind(SearchDateContext.fromRequest(
                     displayMessage, prepared.requestedAt(), SearchDateContext.resolveZone(searchTimeZone)))) {
            leaseRenewal = taskRunLeaseRenewer == null
                    ? null : taskRunLeaseRenewer.start(run);
            OwnerContext.setCurrentProjectId(authorizedProjectId);
            if (!taskRunService.renewSessionLock(sessionId, run.fencingToken())) {
                throw new IllegalStateException("Task run lease is no longer owned");
            }
            executionControl.start(sessionId, tenantId, run.fencingToken());
            executionStarted = true;
            if (executionControl.isCancelled(sessionId, run.fencingToken())) {
                stopLeaseRenewal(leaseRenewal);
                leaseRenewal = null;
                taskRunService.markCancelled(run, "Cancelled before worker start");
                return new AgentResult("任务已取消。", RunStatus.CANCELED, "CANCELLED", 0L);
            }
            // Route changes only detach SSE clients; explicit controls own cancellation.
            streamingService.markRunning(sessionId);
            workerOpenAttempted = true;
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
                    displayMessage,
                    progressEmitter,
                    imageDataUrls,
                    mediaRefs,
                    fileRefs,
                    taskId,
                    streamToken);
            taskId[0] = agentResult.taskId();
            // Stop renewal before selecting a terminal state. close() waits for
            // any in-flight renewal tick, which may have discovered a lost run
            // lease and canceled this fencing token while doExecuteAgent returned.
            stopLeaseRenewal(leaseRenewal);
            leaseRenewal = null;
            String answer = agentResult.answer();
            if (executionControl.isCancelled(sessionId, run.fencingToken()) || agentResult.status() == RunStatus.CANCELED) {
                taskRunService.markCancelled(run, "Cancelled by user");
                recordEvent(
                        sessionId,
                        Long.toString(taskId[0]),
                        com.miniagent.memory.model.AgentEvent.EventType.TASK_FAIL,
                        "executor",
                        Map.of("reason", "cancelled"),
                        com.miniagent.memory.model.AgentEvent.EventStatus.FAILED);
                return new AgentResult(answer, RunStatus.CANCELED, "CANCELLED", taskId[0]);
            }
            if (agentResult.status() == RunStatus.WAITING) {
                taskRunService.markWaiting(run, agentResult.reason());
                return agentResult;
            }
            if (agentResult.status() != RunStatus.SUCCESS) {
                String reason = agentResult.reason();
                taskRunService.markFailed(run, "任务未达成目标: " + reason);
                recordEvent(sessionId, Long.toString(taskId[0]),
                        com.miniagent.memory.model.AgentEvent.EventType.TASK_FAIL,
                        "executor", Map.of("reason", reason),
                        com.miniagent.memory.model.AgentEvent.EventStatus.FAILED);
                log.warn("任务未达成目标，标记为失败: session={}, reason={}", sessionId, reason);
                return agentResult;
            }
            taskRunService.markCompleted(run);

            // 记录任务成功事件
            recordEvent(sessionId, Long.toString(taskId[0]), com.miniagent.memory.model.AgentEvent.EventType.TASK_COMPLETE,
                "executor", Map.of("answer", truncate(answer, 500)),
                com.miniagent.memory.model.AgentEvent.EventStatus.SUCCESS);

            return agentResult;
        } catch (Exception e) {
            stopLeaseRenewal(leaseRenewal);
            leaseRenewal = null;
            boolean cancelled = false;
            if (executionStarted) {
                try {
                    // isCancelled observes the local flag before querying Redis.
                    // A confirmed local cancel still wins during a Redis outage;
                    // an unavailable remote status must not skip terminal cleanup.
                    cancelled = executionControl.isCancelled(sessionId, run.fencingToken());
                } catch (RuntimeException controlFailure) {
                    if (controlFailure != e) e.addSuppressed(controlFailure);
                    log.warn("任务失败后无法查询取消状态，按执行失败收口: session={}, runId={}",
                            sessionId, run.runId(), controlFailure);
                }
            }
            if (cancelled) {
                taskRunService.markCancelled(run, "Cancelled by user");
            } else {
                taskRunService.markFailed(run, exceptionDetail(e));
            }

            // 记录任务失败事件
            recordEvent(sessionId, Long.toString(taskId[0]), com.miniagent.memory.model.AgentEvent.EventType.TASK_FAIL,
                "executor", Map.of("error", exceptionDetail(e)),
                com.miniagent.memory.model.AgentEvent.EventStatus.FAILED);

            throw e;
        } finally {
            stopLeaseRenewal(leaseRenewal);
            if (desktopInteractionService != null) {
                try { desktopInteractionService.releaseFor(userId, owner.tenantId(), sessionId); }
                catch (RuntimeException e) { log.warn("释放桌面输入失败: session={}", sessionId, e); }
            }
            try {
                if (workerOpenAttempted) {
                    clientMultiAgent.close(sessionId);
                }
            } catch (RuntimeException e) {
                log.warn("关闭会话 worker 失败: session={}", sessionId, e);
            }
            try {
                executionControl.finish(sessionId, run.fencingToken());
            } catch (RuntimeException e) {
                log.warn("释放执行控制状态失败: session={}", sessionId, e);
            } finally {
                TaskTodoContext.clear();
                // Always clear thread-local overrides before any further I/O.
                WorkspaceContext.clearRootOverride();
                WorkspaceContext.clearSessionOverride();
            }
            // For SSE runs the outer async wrapper publishes the terminal event
            // before releasing the ChatStreamingService reservation. Releasing it
            // here would let a rapid second request replace the channel between
            // this finally block and eventCenter.complete/error.
            if (progressEmitter == null) {
                streamingService.markIdle(sessionId);
            }

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

    private static void stopLeaseRenewal(AutoCloseable renewal) {
        if (renewal == null) return;
        try {
            renewal.close();
        } catch (Exception e) {
            log.warn("停止任务运行锁续租失败", e);
        }
    }

    public void cancel(Long userId, String sessionId) {
        requestExecutionStop(sessionId, false);
    }

    public void pause(String sessionId) {
        requestExecutionStop(sessionId, true);
    }

    private void requestExecutionStop(String sessionId, boolean pause) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId required");
        }
        RuntimeException controlFailure = null;
        try {
            taskRunService.activeRunToken(sessionId).ifPresent(token -> {
                if (pause) executionControl.pause(sessionId, token);
                else executionControl.cancel(sessionId, token);
            });
        } catch (RuntimeException failure) {
            controlFailure = failure;
            throw failure;
        } finally {
            if (desktopInteractionService != null) {
                try { desktopInteractionService.revokeSession(sessionId); }
                catch (RuntimeException cleanupFailure) {
                    if (controlFailure == null) throw cleanupFailure;
                    if (controlFailure != cleanupFailure) controlFailure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    public void resume(String sessionId) {
        if (StringUtils.isBlank(sessionId)) throw new IllegalArgumentException("sessionId required");
        taskRunService.activeRunToken(sessionId).ifPresent(token -> executionControl.resume(sessionId, token));
    }

    public boolean isPauseRequested(String sessionId) {
        return taskRunService.activeRunToken(sessionId)
                .map(token -> executionControl.isPauseRequested(sessionId, token)).orElse(false);
    }

    public boolean isPausedAtCheckpoint(String sessionId) {
        return taskRunService.activeRunToken(sessionId)
                .map(token -> executionControl.isPausedAtCheckpoint(sessionId, token)).orElse(false);
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
     * <p>默认循环与规划器共用显式终态，持久任务、轨迹及 SSE 不从答复文本推断成功。</p>
     */
    record AgentResult(String answer, RunStatus status, String reason, long taskId) {}

    static RunStatus loopStatus(AgentLoop.LoopOutcome outcome) {
        if (outcome == null) return RunStatus.FAILURE;
        if (outcome.cancelled()) return RunStatus.CANCELED;
        if (outcome.awaitingHuman()) return RunStatus.WAITING;
        return outcome.successful() ? RunStatus.SUCCESS : RunStatus.FAILURE;
    }

    private static String exceptionDetail(Exception error) {
        return Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
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
            String displayMessage,
            SseEmitter progressEmitter,
            List<String> imageDataUrls,
            List<MediaRef> mediaRefs,
            List<FileRef> fileRefs,
            long[] taskId,
            String streamToken) {
        List<String> images = Optional.ofNullable(imageDataUrls).orElseGet(List::of).stream()
                .filter(StringUtils::isNotBlank).toList();
        List<MediaRef> media = Optional.ofNullable(mediaRefs).orElseGet(List::of).stream()
                .filter(r -> Objects.nonNull(r) && StringUtils.isNotBlank(r.getFilePath()))
                .toList();
        List<FileRef> uploadedFiles = Optional.ofNullable(fileRefs).orElseGet(List::of).stream()
                .filter(r -> Objects.nonNull(r) && StringUtils.isNotBlank(r.getFilePath()))
                .toList();
        boolean hasImage = !images.isEmpty();
        boolean hasAv = !media.isEmpty();
        boolean hasMedia = hasImage || hasAv;
        RunStatus runStatus = RunStatus.SUCCESS;

        contextCompressor.setCurrentSession(sessionId);
        builtinTools.clearToolCache();
        if (Objects.nonNull(diagnosticCollector)) {
            diagnosticCollector.clear();
        }

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
        // 不从用户文本匹配任务词表；加载上下文后由模型按依赖关系选择 direct / DAG。
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
        List<String> savedFilePaths = multimodalBuilder.referenceFilesForConversation(userId, uploadedFiles);
        List<String> memoryMediaPaths = new ArrayList<>(savedImagePaths);
        memoryMediaPaths.addAll(savedMediaPaths);
        List<String> allSavedPaths = new ArrayList<>(memoryMediaPaths);
        allSavedPaths.addAll(savedFilePaths);

        UserMessage multimodalMsg = hasMedia
                ? multimodalBuilder.buildMultimodalUserMessage(userId, userMessage, images, savedImagePaths, media, savedMediaPaths)
                : null;
        String displayQuestion = MultimodalMessageBuilder.buildDisplayQuestion(
                Objects.requireNonNullElse(displayMessage, ""), images.size(), media.size());

        String firstLine = StringUtils.isNotBlank(userMessage)
                ? userMessage.split("[\r\n]", 2)[0]
                : (hasMedia ? MessageConstants.CHAT_IMAGE_ANALYSIS : MessageConstants.CHAT_DEFAULT_SESSION);
        BuiltinTools.setCurrentTaskName(firstLine);
        TaskTodoContext.set(sessionId);
        // 新一轮聊天流需要主动回放当前任务清单：清单可能从上一轮沿用，
        // 本轮模型不一定再次调用 todo 工具，但聊天气泡仍应显示任务进度卡片。
        taskTodoStore.publishCurrentState(sessionId);
        String systemPrompt = loaded.systemPrompt();

        final boolean streaming = Objects.nonNull(progressEmitter);
        // 工具执行过程不再推给前端：对话区只有模型自己的话与最终答案。
        // 工具名/参数/结果原文（内部 errorCode、文件路径）留在日志与 agent_trace_steps。
        // 前端也没有 progress 的消费点了，这里不 publish 就不会进 EventStream。
        com.miniagent.agent.core.AgentStreamSink streamSink = !streaming ? null
                : new com.miniagent.agent.core.AgentStreamSink() {
            @Override public void onThinking(String delta) {
                eventCenter.publish(sessionId, "thinking", delta, streamToken);
            }
            @Override public void onAnswerToken(String delta) {
                eventCenter.publish(sessionId, "token", delta, streamToken);
            }
            @Override public void onAnswerReset() {
                eventCenter.publish(sessionId, "seal", "", streamToken);
            }
            @Override public void onSubGoal(String text, int done, int total) {
                eventCenter.publish(sessionId, "subgoal",
                        Map.of("text", Objects.requireNonNullElse(text, ""), "done", done, "total", total), streamToken);
            }
            @Override public void onContext(int used, int limit) {
                eventCenter.publish(sessionId, "context",
                        "{\"used\":" + used + ",\"limit\":" + limit + "}", streamToken);
            }
        };

        AgentLoop.setCurrentSession(sessionId);
        // 连窗口一起绑定：循环按"配置上限 vs 模型厂商窗口"的较小值决定何时压缩。
        // 不带窗口时，128k 的模型也会按 512k 估算，压缩永远不触发 → 上游 400 → 误报成网络故障。
        com.miniagent.common.model.EffectiveModelContext.set(
                effectiveChat, models.streaming(), models.settings().contextWindowTokens());
        String answer;
        String endReason = "SUCCESS";
        boolean planApprovalRequired = false;
        try {
            String executionId = Objects.nonNull(traceRecorder)
                    ? traceRecorder.currentExecutionId() : null;
            var planningRoute = planningLoop.selectRoute(effectiveChat, taskPlan, sessionId,
                    userMessage, multimodalMsg, history);
            if (planningRoute != null && planningRoute.useDag()) {
                PlanningLoop.RunOutcome outcome = planningLoop.runWithOutcome(
                        effectiveChat, systemPrompt, userMessage, multimodalMsg,
                        history, taskPlan, sessionId, run.fencingToken(), executionId,
                        streamSink, planningRoute);
                answer = outcome.answer();
                endReason = outcome.reason();
                planApprovalRequired = "PLAN_APPROVAL_REQUIRED".equals(outcome.reason());
                if (planApprovalRequired && progressEmitter != null) {
                    eventCenter.publish(sessionId, "plan_approval",
                            Map.of("required", true, "reason", outcome.reason()), streamToken);
                }
                if (meansUnfinished(outcome)) {
                    runStatus = RunStatus.FAILURE;
                } else if (outcome.outcome() == PlanningLoop.Outcome.BLOCKED_ON_HUMAN) {
                    runStatus = RunStatus.WAITING;
                }
            } else {
                AgentLoop.LoopOutcome outcome = hasMedia
                        ? agentLoop.runWithMultimodalOutcome(effectiveChat, systemPrompt, multimodalMsg,
                                history, maxIterations(), taskPlan, streamSink)
                        : agentLoop.runOutcome(effectiveChat, systemPrompt, userMessage, history,
                                maxIterations(), taskPlan, streamSink);
                answer = outcome == null ? "" : outcome.text();
                endReason = outcome == null ? "MISSING_LOOP_OUTCOME"
                        : StringUtils.defaultIfBlank(outcome.endReason(), "UNKNOWN_LOOP_OUTCOME");
                runStatus = loopStatus(outcome);
            }
            if (executionControl.isCancelled(sessionId)) {
                runStatus = RunStatus.CANCELED;
                endReason = "CANCELLED";
            }
        } finally {
            PermissionContext.clear();
            AgentLoop.clearCurrentModels();
            AgentLoop.clearCurrentSession();
        }

        // A blank `end` event is rendered as a successful completion by the chat UI.
        // Preserve a visible, persisted assistant response instead of silently ending
        // the turn with an empty bubble. A waiting/approval turn is handled separately.
        if (StringUtils.isBlank(answer)
                && runStatus != RunStatus.WAITING
                && runStatus != RunStatus.CANCELED
                && !planApprovalRequired) {
            answer = ensureFinalAnswer(answer);
            runStatus = RunStatus.FAILURE;
            endReason = "EMPTY_FINAL_ANSWER";
            log.warn("Agent 返回空最终答复，已写入兜底提示并标记未完成: session={}", sessionId);
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
        task.setTaskProgressJson(TaskProgressSnapshot.capture(
                traceRecorder == null ? null : traceRecorder.currentExecutionId(), loaded.taskId(),
                runStatus.name(), endReason, taskTodoStore == null ? List.of() : taskTodoStore.get(sessionId))
                .toJson());
        if (!allSavedPaths.isEmpty()) {
            task.setImages(String.join(",", allSavedPaths));
        }
        chatTaskRepository.save(task);
        persistTurn(userId, sessionId,
                StringUtils.isNotBlank(userMessage) ? userMessage : displayQuestion,
                answer,
                memoryMediaPaths.isEmpty() ? null : memoryMediaPaths);
        return new AgentResult(answer, runStatus, endReason, loaded.taskId());
        }
        } catch (Exception e) {
            runStatus = RunStatus.FAILURE;
            if (Objects.nonNull(traceRecorder)) {
                traceRecorder.recordError(sessionId, 0, e.getMessage());
            }
            throw e;
        } finally {
            // 权限上下文在这里提前绑定过，任何在进入内层 try 之前抛出的异常都要清掉，
            // 否则线程复用时会带着上一个会话的模式去找下一个会话要授权。
            PermissionContext.clear();
            if (Objects.nonNull(traceRecorder) && traceRecorder.isActive()) {
                traceRecorder.endExecution(runStatus.name());
            }
        }
    }

    static String ensureFinalAnswer(String answer) {
        return StringUtils.isNotBlank(answer)
                ? answer
                : "任务已结束，但智能体没有生成最终回复。请查看右侧任务详情中的执行记录和已生成文件。";
    }

    // =========================================================================
    // SSE 流式对话
    // =========================================================================

    /**
     * 统一多模态 SSE 入口：文字 + 图片 + 已上传音视频。
     */
    public SseEmitter chatStreamMultimodal(Long userId, String sessionId, String userMessage,
                                           String displayMessage,
                                           List<String> imageDataUrls, List<FileAttachment> files,
                                           List<MediaRef> mediaRefs, List<FileRef> fileRefs,
                                           String role) {
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
        List<FileRef> uploadedFiles = Optional.ofNullable(fileRefs).orElseGet(List::of).stream()
                .filter(r -> Objects.nonNull(r) && StringUtils.isNotBlank(r.getFilePath()))
                .toList();
        if (StringUtils.isBlank(userMessage) && images.isEmpty() && media.isEmpty()
                && uploadedFiles.isEmpty()) {
            return streamingService.createErrorEmitter("error", MessageConstants.CHAT_MULTIMODAL_EMPTY);
        }

        String sid = StringUtils.isBlank(sessionId)
                ? UUID.randomUUID().toString().substring(0, 8)
                : sessionId.trim();
        final String finalSid = sid;
        final String finalUserMessage = userMessage;
        final String finalDisplayMessage = Objects.requireNonNullElse(displayMessage, "");
        final String finalRole = role;
        final List<String> finalImages = images;
        final List<MediaRef> finalMedia = media;
        final List<FileRef> finalFileRefs = uploadedFiles;

        // The distributed task lock must be acquired before opening/broadcasting
        // a channel. A losing replica must not replace the accepted run's stream.
        final PreparedRun prepared;
        try {
            prepared = prepareRun(userId, finalSid);
        } catch (Exception e) {
            return streamingService.createErrorEmitter("error", exceptionDetail(e));
        }
        final ChatStreamingService.StreamStart stream;
        try {
            stream = streamingService.tryCreateStreamForAcceptedRun(finalSid,
                    Optional.ofNullable(finalUserMessage).orElse(""), userId);
        } catch (Exception e) {
            taskRunService.markFailed(prepared.handle(), exceptionDetail(e));
            executionControl.finish(finalSid, prepared.handle().fencingToken());
            return streamingService.createErrorEmitter("error", exceptionDetail(e));
        }
        if (!stream.accepted()) {
            taskRunService.markFailed(prepared.handle(), "SSE channel already reserved");
            executionControl.finish(finalSid, prepared.handle().fencingToken());
            return stream.emitter();
        }
        SseEmitter emitter = stream.emitter();

        try {
        CompletableFuture.runAsync(() -> {
            try {
                if (StringUtils.isNotBlank(finalRole)) {
                    RoleContext.setRole(finalRole);
                }
                AgentResult result = executeAgentWithProgress(
                        userId, finalSid, finalUserMessage, finalDisplayMessage,
                        emitter, finalImages, finalMedia,
                        finalFileRefs, prepared, stream.runToken());
                streamingService.markCompleting(finalSid);
                streamingService.getEventCenter().publish(finalSid, "task_outcome",
                        Map.of("status", result.status().name(), "reason", result.reason()), stream.runToken());
                if (result.status() == RunStatus.FAILURE) {
                    streamingService.getEventCenter().error(finalSid, result.answer(), stream.runToken());
                } else {
                    streamingService.getEventCenter().complete(finalSid, result.answer(), stream.runToken());
                }
            } catch (Exception e) {
                streamingService.markCompleting(finalSid);
                streamingService.getEventCenter().error(finalSid,
                        MessageConstants.CHAT_PROCESSING_ERROR + exceptionDetail(e), stream.runToken());
            } finally {
                try {
                    streamingService.markIdle(finalSid);
                } finally {
                    RoleContext.clear();
                }
            }
        });
        } catch (RuntimeException e) {
            try {
                taskRunService.markFailed(prepared.handle(), exceptionDetail(e));
                streamingService.markCompleting(finalSid);
                streamingService.getEventCenter().error(finalSid, exceptionDetail(e), stream.runToken());
            } finally {
                try {
                    executionControl.finish(finalSid, prepared.handle().fencingToken());
                } finally {
                    streamingService.markIdle(finalSid);
                }
            }
        }

        return emitter;
    }
}
