package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.execution.ToolInvocation;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.todo.HumanYield;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.RunStatus;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * User → Compiler → Validator → Scheduler → Executor → ToolRouter → Tool
 * → StepEvaluator → GraphEval → Continue | Retry/Replan。
 */
@Component
public class PlanningLoop {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.miniagent.agent.core.ModelCallControl modelCallControl;

    private static final Logger log = LoggerFactory.getLogger(PlanningLoop.class);
    private static final int PRIOR_OUTPUT_CHARS = 6000;
    private static final int AWAITING_HINT_CHARS = 500;
    static final String EXECUTION_HUMAN_WAIT = "humanWait";
    static final String RECOVERY_CLARIFICATION = "recoveryClarification";

    /** Completed receipts stay outside the single-node clarification graph while it waits. */
    private record RecoveryContinuation(String executionId, TaskGraph completed) {}

    private final PlannerProperties properties;
    private final GoalCompiler goalCompiler;
    private final PlanValidator planValidator;
    private final IntentGraphValidator intentGraphValidator = new IntentGraphValidator();
    private final PlannerStateStore stateStore;
    private final GraphScheduler graphScheduler;
    private final ToolRouter toolRouter;
    private final StepEvaluator stepEvaluator;
    private final RecoveryEngine recoveryEngine;
    private final TodoStateProjector todoProjector;
    private final NodeExecutor nodeExecutor;
    private final PlannerMetrics metrics;
    private final SessionLock sessionLock;
    private final ToolSuccessStats toolSuccessStats;
    private TraceRecorder traceRecorder;
    private final ThreadLocal<List<ChatMessage>> currentTurnAgentMessages = new ThreadLocal<>();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NodeTerminalCommitPort nodeTerminalCommitPort;

    public PlanningLoop(PlannerProperties properties,
                        GoalCompiler goalCompiler,
                        PlanValidator planValidator,
                        PlannerStateStore stateStore,
                        GraphScheduler graphScheduler,
                        ToolRouter toolRouter,
                        StepEvaluator stepEvaluator,
                        RecoveryEngine recoveryEngine,
                        TodoStateProjector todoProjector,
                        NodeExecutor nodeExecutor,
                        PlannerMetrics metrics,
                        SessionLock sessionLock,
                        ToolSuccessStats toolSuccessStats) {
        this.properties = properties;
        this.goalCompiler = goalCompiler;
        this.planValidator = planValidator;
        this.stateStore = stateStore;
        this.graphScheduler = graphScheduler;
        this.toolRouter = toolRouter;
        this.stepEvaluator = stepEvaluator;
        this.recoveryEngine = recoveryEngine;
        this.todoProjector = todoProjector;
        this.nodeExecutor = nodeExecutor;
        this.metrics = metrics;
        this.sessionLock = sessionLock;
        this.toolSuccessStats = toolSuccessStats;
    }

    public void setTraceRecorder(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
        this.recoveryEngine.setTraceRecorder(traceRecorder);
    }

    public boolean shouldHandle(TaskPlan plan) {
        return shouldHandle(plan, null);
    }

    public boolean shouldHandle(TaskPlan plan, String sessionId) {
        return shouldHandle(plan, sessionId, null);
    }

    /** Cheap forced/resume gate. New requests require {@link #selectRoute} for semantic routing. */
    public boolean shouldHandle(TaskPlan plan, String sessionId, String requestText) {
        if (!properties.isEnabled() || plan == null) return false;
        if (PermissionContext.mode() == PermissionMode.PLAN) return true;
        return canResume(sessionId, requestText);
    }

    private boolean canResume(String sessionId, String requestText) {
        if (StringUtils.isBlank(sessionId)) return false;
        return stateStore.get(sessionId).map(snapshot -> {
            TaskGraph graph = snapshot.graph();
            boolean incomplete = graph != null && !graph.isEmpty() && !graph.allTerminalSuccess();
            boolean pending = PlannerStateStore.PLAN_APPROVAL_PENDING.equals(
                    snapshot.execution().get(PlannerStateStore.EXECUTION_PLAN_APPROVAL));
            return incomplete && (PlannerResumePolicy.shouldResume(requestText,
                    graph.hasAwaitingConfirm(), true, pending)
                    || shouldRecompileClarify(snapshot.goal(), graph, requestText)
                    || (pending && StringUtils.isNotBlank(requestText)
                    && !PlannerResumePolicy.isClearlyNewTopic(requestText.trim())));
        }).orElse(false);
    }

    /** Resolve once per turn. The decision is passed explicitly to execution, never stored in a ThreadLocal. */
    public PlanningRoute selectRoute(ChatModel chat, TaskPlan plan, String sessionId,
                                     String requestText, UserMessage multimodal, List<ChatMessage> history) {
        PlanningRoute route;
        if (!properties.isEnabled() || plan == null) {
            route = PlanningRoute.direct("configuration", "PLANNER_DISABLED_OR_NO_TASK");
        } else if (shouldHandle(plan, sessionId, requestText)) {
            route = PlanningRoute.dag("state", PermissionContext.mode() == PermissionMode.PLAN
                    ? "EXPLICIT_PLAN_MODE" : "RESUME_CURRENT_GRAPH");
            if (PermissionContext.mode() == PermissionMode.PLAN && !canResume(sessionId, requestText)) {
                PlanningRoute semantic = new SemanticPlanningRouter(modelCallControl).decide(goalCompiler.resolvePlannerChat(chat),
                        requestText == null ? plan.taskGoal() : requestText, multimodal, history);
                route = new PlanningRoute(true, "explicit", "EXPLICIT_PLAN_MODE; " + semantic.reason(),
                        semantic.artifactDeliveryRequested());
            }
        } else if (!properties.isAutoPlanning()) {
            route = PlanningRoute.direct("configuration", "AUTO_PLANNING_DISABLED");
        } else {
            route = new SemanticPlanningRouter(modelCallControl).decide(goalCompiler.resolvePlannerChat(chat),
                    requestText == null ? plan.taskGoal() : requestText, multimodal, history);
        }
        trace(sessionId, AgentStepNode.PLANNING_ROUTE,
                "{\"route\":\"" + route.routeName()
                        + "\",\"source\":\"" + jsonEscape(route.source())
                        + "\",\"reason\":\"" + jsonEscape(route.reason()) + "\"}",
                route.resolved() ? RunStatus.SUCCESS : RunStatus.FAILURE);
        log.info("任务策略 session={} route={} source={} reason={}", sessionId,
                route.routeName(), route.source(), route.reason());
        return route;
    }

    /**
     * 判断外层调度循环是否因达到硬上限而退出。
     *
     * <p>循环使用 {@code rounds++ < max}，因此达到上限时计数器恰好等于
     * {@code max}；把这里单独抽成纯函数，避免边界条件再次退化成不可达分支。</p>
     */
    static boolean outerRoundsExhausted(int rounds, int maxRounds) {
        return maxRounds > 0 && rounds >= maxRounds;
    }

    static boolean shouldRecompileClarify(Goal goal, TaskGraph graph, String userMessage) {
        if (StringUtils.isBlank(userMessage)
                || HumanYield.looksLikeBareContinue(userMessage)
                || PlannerResumePolicy.isClearlyNewTopic(userMessage.trim())) {
            return false;
        }
        if (goal != null && goal.isClarify()) {
            return true;
        }
        return GoalCompiler.isClarifyGraph(graph);
    }

    static String mergeClarifyObjective(String previous, String reply) {
        String prev = previous == null ? "" : previous.trim();
        String next = reply == null ? "" : reply.trim();
        if (prev.isBlank()) {
            return next;
        }
        if (next.isBlank()) {
            return prev;
        }
        return prev + "\n" + next;
    }

    public boolean isAwaitingConfirm(String sessionId) {
        return hasAwaitingGraph(sessionId) || stateStore.hasPendingPlanApproval(sessionId);
    }

    private boolean hasAwaitingGraph(String sessionId) {
        return stateStore.get(sessionId)
                .map(s -> s.graph() != null && s.graph().hasAwaitingConfirm())
                .orElse(false);
    }

    /**
     * 规划运行的结局（对外可判定的语义）。
     *
     * <p><b>为什么需要它</b>：此前 {@code run} 只返回一个 String，于是
     * "图死锁 / 恢复耗尽 / 外圈跑完 / 会话锁丢失" 与 "图全部验收通过" 在调用方看来完全一样 ——
     * 上层一律 {@code markCompleted}，用户看到"已按规划图推进任务（version=…）"这种
     * 听起来成功的句子。任务没达成却被记为完成，是生产环境里最贵的一类 bug：
     * 既骗了用户，也让"完成率/失败率"这类指标彻底失去意义。</p>
     *
     * <p>调用方必须按结局决定状态：只有 {@link #COMPLETED} 才能算任务完成。</p>
     */
    public enum Outcome {
        /** 图全部节点通过终验（{@code evaluateGraph} 通过）。 */
        COMPLETED,
        /** 卡住了：图死锁、恢复次数用尽、外圈轮次用尽、步骤未完成。 */
        UNFINISHED,
        /** 在等人：关键步骤需要用户确认 / 需要用户回答。这不是失败，也不能算完成。 */
        BLOCKED_ON_HUMAN,
        /** 运行本身出错：会话锁丢失、任务图无法验收。 */
        FAILED
    }

    /** {@code run} 的结构化返回：答复文本 + 结局 + 机器可读的原因码 + 是否模板降级。 */
    public record RunOutcome(String answer, Outcome outcome, String reason, boolean degraded,
                             List<ChatMessage> agentMessages) {
        public RunOutcome {
            answer = answer == null ? "" : answer;
            reason = reason == null ? "" : reason;
            agentMessages = agentMessages == null ? List.of() : List.copyOf(agentMessages);
        }

        public RunOutcome(String answer, Outcome outcome, String reason, boolean degraded) {
            this(answer, outcome, reason, degraded, List.of());
        }

        public RunOutcome(String answer, Outcome outcome, String reason) {
            this(answer, outcome, reason, false, List.of());
        }

        public boolean goalSatisfied() {
            return outcome == Outcome.COMPLETED;
        }

        public RunOutcome withAgentMessages(List<ChatMessage> messages) {
            return new RunOutcome(answer, outcome, reason, degraded, messages);
        }

        static RunOutcome satisfied(String answer) {
            return satisfied(answer, false);
        }

        static RunOutcome satisfied(String answer, boolean degraded) {
            return new RunOutcome(answer, Outcome.COMPLETED,
                    degraded ? "GRAPH_COMPLETED_DEGRADED" : "GRAPH_COMPLETED", degraded);
        }

        static RunOutcome direct(AgentLoop.LoopOutcome result) {
            if (result == null) {
                return failed("智能体未返回执行结果。", "MISSING_LOOP_OUTCOME");
            }
            String reason = StringUtils.defaultIfBlank(result.endReason(), "UNKNOWN_LOOP_OUTCOME");
            if (AgentLoop.OUTCOME_UNKNOWN.equals(reason)
                    || (result.lastToolResult() != null
                    && result.lastToolResult().status() == com.miniagent.agent.tool.ToolStatus.UNKNOWN)) {
                return new RunOutcome(result.text(), Outcome.FAILED, AgentLoop.OUTCOME_UNKNOWN,
                        false, result.messages());
            }
            if (result.awaitingHuman()) {
                return new RunOutcome(result.text(), Outcome.BLOCKED_ON_HUMAN, reason,
                        false, result.messages());
            }
            if (result.successful()) {
                return new RunOutcome(result.text(), Outcome.COMPLETED, reason, false, result.messages());
            }
            if (RunStatus.SUCCESS.name().equals(reason)) {
                reason = StringUtils.isBlank(result.text()) ? "EMPTY_FINAL_ANSWER" : "TOOL_EXECUTION_FAILED";
            }
            return new RunOutcome(result.text(), Outcome.FAILED, reason, false, result.messages());
        }

        static RunOutcome unfinished(String answer, String reason) {
            return new RunOutcome(answer, Outcome.UNFINISHED, reason);
        }

        static RunOutcome blockedOnHuman(String answer, String reason) {
            return new RunOutcome(answer, Outcome.BLOCKED_ON_HUMAN, reason);
        }

        static RunOutcome failed(String answer, String reason) {
            return new RunOutcome(answer, Outcome.FAILED, reason);
        }
    }

    /**
     * 兼容入口：老的调用点只要文本。
     *
     * <p>新代码应当用 {@link #runWithOutcome} —— 只有拿到 {@link Outcome} 才能区分
     * "做完了"和"卡住了"，否则又会退回"没达成也报成功"。</p>
     */
    public String run(ChatModel chat,
                      String systemPrompt,
                      String userMessage,
                      UserMessage multimodalUser,
                      List<ChatMessage> history,
                      TaskPlan taskPlan,
                      String sessionId,
                      String fencingToken,
                      String executionId,
                      AgentStreamSink streamSink) {
        return runWithOutcome(chat, systemPrompt, userMessage, multimodalUser, history, taskPlan,
                sessionId, fencingToken, executionId, streamSink).answer();
    }

    public RunOutcome runWithOutcome(ChatModel chat,
                      String systemPrompt,
                      String userMessage,
                      UserMessage multimodalUser,
                      List<ChatMessage> history,
                      TaskPlan taskPlan,
                      String sessionId,
                      String fencingToken,
                      String executionId,
                      AgentStreamSink streamSink) {
        return runWithOutcome(chat, systemPrompt, userMessage, multimodalUser, history, taskPlan,
                sessionId, fencingToken, executionId, streamSink,
                selectRoute(chat, taskPlan, sessionId, userMessage, multimodalUser, history));
    }

    /** Executes a previously resolved per-turn strategy without a second model routing call. */
    public RunOutcome runWithOutcome(ChatModel chat, String systemPrompt, String userMessage,
                      UserMessage multimodalUser, List<ChatMessage> history, TaskPlan taskPlan,
                      String sessionId, String fencingToken, String executionId,
                      AgentStreamSink streamSink, PlanningRoute route) {
        currentTurnAgentMessages.set(new ArrayList<>());
        try {
            RunOutcome outcome = runWithOutcomeInternal(chat, systemPrompt, userMessage, multimodalUser,
                    history, taskPlan, sessionId, fencingToken, executionId, streamSink, route);
            if (outcome == null) return null;
            List<ChatMessage> captured = currentTurnAgentMessages.get();
            if (captured == null || captured.isEmpty()) return outcome;
            List<ChatMessage> combined = new ArrayList<>(outcome.agentMessages());
            combined.addAll(captured);
            return outcome.withAgentMessages(combined);
        } finally {
            currentTurnAgentMessages.remove();
        }
    }

    private RunOutcome runWithOutcomeInternal(ChatModel chat, String systemPrompt, String userMessage,
                      UserMessage multimodalUser, List<ChatMessage> history, TaskPlan taskPlan,
                      String sessionId, String fencingToken, String executionId,
                      AgentStreamSink streamSink, PlanningRoute route) {
        if (route == null || !route.resolved()) {
            String reason = route == null ? "ROUTER_MISSING_DECISION" : route.reason();
            return RunOutcome.failed(RoutingModelFailure.userMessage(reason), reason);
        }
        if (!route.useDag()) {
            // 直跑路径：结局由 AgentLoop 自己判定，规划器不替它宣称完成
            if (multimodalUser != null) {
                return RunOutcome.direct(nodeExecutor.runDirectMultimodalOutcome(chat, systemPrompt,
                        multimodalUser, history, 90, taskPlan, streamSink));
            }
            return RunOutcome.direct(nodeExecutor.runDirectOutcome(chat, systemPrompt, userMessage, history,
                    90, taskPlan, streamSink));
        }
        if (!properties.isEnabled() || taskPlan == null) {
            return RunOutcome.failed("任务规划当前不可用，尚未开始操作。请重试。", "PLANNER_UNAVAILABLE");
        }

        if (route.artifactDeliveryRequested() != null) {
            taskPlan = taskPlan.withArtifactDeliveryRequested(route.artifactDeliveryRequested());
        }
        GoalCompiler.CompileResult compiled;
        StateSnapshot snap;
        var existing = stateStore.get(sessionId);
        boolean incompleteGraph = existing.isPresent()
                && !existing.get().graph().isEmpty()
                && !existing.get().graph().allTerminalSuccess();
        boolean awaitingGraph = existing.isPresent()
                && existing.get().graph().hasAwaitingConfirm();
        boolean awaitingPlanApproval = existing.isPresent()
                && PlannerStateStore.PLAN_APPROVAL_PENDING.equals(
                        existing.get().execution().get(
                                PlannerStateStore.EXECUTION_PLAN_APPROVAL));
        boolean resume = PlannerResumePolicy.shouldResume(
                userMessage,
                awaitingGraph,
                incompleteGraph,
                awaitingPlanApproval);
        boolean clarifyRecompile = existing.isPresent()
                && shouldRecompileClarify(
                        existing.get().goal(), existing.get().graph(), userMessage);
        // 计划还在等批准时，非批准答复视为对计划的调整要求：
        // 必须带着原目标重编译，不能只拿这句话当新任务，否则原任务直接丢失。
        boolean planRevision = awaitingPlanApproval
                && !isPlanApprovalReply(userMessage)
                && !PlannerResumePolicy.isClearlyNewTopic(
                        userMessage == null ? "" : userMessage.trim());
        if ((resume || clarifyRecompile || planRevision) && taskPlan.artifactDeliveryRequested() == null) {
            Object stored = existing.get().execution().get("artifactDeliveryRequested");
            if (stored instanceof Boolean requested) taskPlan = taskPlan.withArtifactDeliveryRequested(requested);
        }
        if (clarifyRecompile) {
            RecoveryContinuation continuation;
            try {
                continuation = recoveryContinuation(existing.get());
            } catch (IllegalStateException invalid) {
                return RunOutcome.failed("任务恢复信息无法验证，已保留原有进度，请先检查执行记录。",
                        "RECOVERY_CONTEXT_INVALID");
            }
            String merged = mergeClarifyObjective(
                    existing.get().goal().objective(), userMessage);
            TaskPlan nextPlan = taskPlan.withTaskGoal(merged);
            compiled = compileAndValidate(chat, merged, nextPlan, history,
                    continuation == null ? null : continuation.completed(),
                    existing.get().recoveryCount() + 1);
            if (!planValidator.accept(compiled.graph(), nextPlan, compiled.goal(), compiled.intent())) {
                return invalidPlanOutcome(sessionId, compiled);
            }
            if (continuation != null) {
                StateSnapshot previous = existing.get();
                if (!sessionLock.renewSessionLock(sessionId, fencingToken)) {
                    return RunOutcome.failed("任务执行租约已失效，已停止恢复任务。", "SESSION_LOCK_LOST");
                }
                Map<String, Object> metadata = new LinkedHashMap<>(previous.execution());
                if (!compiled.intent().needsClarification()) metadata.remove(RECOVERY_CLARIFICATION);
                StateSnapshot replacement = previous.revisePlan(compiled.graph(), compiled.goal(),
                        compiled.intent(), compiled.source(), compiled.degraded(), "recovery_clarification_reply")
                        .withExecution(metadata);
                try {
                    snap = stateStore.commit(sessionId, previous.version(), replacement);
                } catch (PlannerStateStore.VersionConflictException conflict) {
                    metrics.casConflict();
                    return RunOutcome.failed("任务进度已更新，请基于最新状态继续。", "STALE_EXECUTION");
                }
            } else {
                String execId = StringUtils.isNotBlank(executionId)
                        ? executionId : "exec_" + UUID.randomUUID().toString().substring(0, 8);
                snap = stateStore.init(sessionId, execId, compiled.goal(), compiled.graph(),
                        compiled.intent(), compiled.source(), compiled.degraded());
            }
            log.info("PlanningLoop 澄清后重编译 session={} nodes={} clarify={}",
                    sessionId, compiled.graph().nodes().size(),
                    compiled.goal().isClarify());
        } else if (planRevision) {
            String merged = mergeClarifyObjective(
                    existing.get().goal().objective(), userMessage);
            TaskPlan nextPlan = taskPlan.withTaskGoal(merged);
            compiled = compileAndValidate(chat, merged, nextPlan, history);
            if (!planValidator.accept(compiled.graph(), nextPlan, compiled.goal(), compiled.intent())) {
                // A revision is not approval to execute either the old or an invalid
                // replacement plan. Retain the pending snapshot for another revision.
                return RunOutcome.blockedOnHuman(
                        "调整后的任务计划未通过校验，尚未开始执行。请重新调整计划后重试。",
                        "PLAN_REVISION_INVALID");
            }
            String execId = StringUtils.isNotBlank(executionId)
                    ? executionId : "exec_" + UUID.randomUUID().toString().substring(0, 8);
            // 重编译后是全新快照，审批状态自动回到未批准，会再次停下等确认。
            snap = stateStore.init(sessionId, execId, compiled.goal(), compiled.graph(),
                    compiled.intent(), compiled.source(), compiled.degraded());
            log.info("PlanningLoop 按审批意见重编译 session={} nodes={}",
                    sessionId, compiled.graph().nodes().size());
        } else if (resume) {
            snap = consumeHumanWait(sessionId, existing.get());
            compiled = new GoalCompiler.CompileResult(snap.goal(), snap.graph(), snap.degraded(),
                    snap.compilationSource(), "", snap.intent());
            log.info("PlanningLoop 续跑已有图 session={} version={} nodes={}",
                    sessionId, snap.version(), snap.graph().nodes().size());

            // ===== 续跑已有图时的进度汇报 =====
            // 走到这里就说明本轮被判定为续跑，不再额外看「用户是否说了继续」。
            // 汇报只进日志与 trace：对话区不再展示执行过程，用户看 todo 条与最终答案。
            String summary = buildContinueTaskSummary(snap, userMessage);
            log.info("PlanningLoop 续跑汇报: {}", summary);
            // 检查上一个任务的状态
            String statusCheck = checkPreviousTaskStatus(snap);
            if (statusCheck != null) {
                log.info("PlanningLoop 上一个任务状态检查: {}", statusCheck);
            }
            // ===========================================

        } else {
            compiled = compileAndValidate(chat, userMessage, taskPlan, history);
            if (!planValidator.accept(compiled.graph(), taskPlan, compiled.goal(), compiled.intent())) {
                return invalidPlanOutcome(sessionId, compiled);
            }
            String execId = StringUtils.isNotBlank(executionId)
                    ? executionId : "exec_" + UUID.randomUUID().toString().substring(0, 8);
            snap = stateStore.init(sessionId, execId, compiled.goal(), compiled.graph(),
                    compiled.intent(), compiled.source(), compiled.degraded());
        }
        final String admittedExecutionId = snap.executionId();
        final String admittedScopeId = snap.sessionId();
        if (taskPlan.artifactDeliveryRequested() != null
                && !taskPlan.artifactDeliveryRequested().equals(snap.execution().get("artifactDeliveryRequested"))) {
            Map<String, Object> metadata = new LinkedHashMap<>(snap.execution());
            metadata.put("artifactDeliveryRequested", taskPlan.artifactDeliveryRequested());
            snap = stateStore.commit(sessionId, snap.version(), snap.withExecution(metadata));
        }
        todoProjector.project(sessionId, snap.graph());
        // A tool whose outcome was unknown may have applied a side effect even though
        // the caller never received the result.  Keep it terminal until an explicit
        // reconciliation/reset; normalization must never replay it automatically.
        TaskNode unknownNode = unknownOutcomeNode(snap.graph());
        if (unknownNode != null) {
            return stopUnknownOutcome(sessionId, snap, unknownNode);
        }
        trace(sessionId, AgentStepNode.GOAL_COMPILED,
                "{\"goalId\":\"" + compiled.goal().goalId()
                        + "\",\"nodes\":" + compiled.graph().nodes().size()
                        + ",\"degradedCompilation\":" + compiled.degradedCompilation()
                        + ",\"source\":\"" + jsonEscape(compiled.source().name()) + "\""
                        + ",\"fallbackReason\":\"" + jsonEscape(compiled.fallbackReason()) + "\""
                        + ",\"intent\":" + intentJson(compiled.intent()) + "}");

        if (compiled.intent().needsClarification()) {
            String question = StringUtils.isBlank(compiled.intent().clarificationQuestion())
                    ? "请补充完成任务所需的信息。"
                    : compiled.intent().clarificationQuestion();
            return RunOutcome.blockedOnHuman(question, "INTENT_NEEDS_CLARIFICATION");
        }

        // 规则图不是模型对本次请求的理解。降级时必须先让人确认整图，
        // 否则「结构合法」就被当成了「语义正确」。
        switch (planApprovalAction(compiled.degraded(),
                properties.isPlanApprovalOnDegraded(), isPlanApproved(snap), userMessage)) {
            case BLOCK -> {
                snap = markPlanApproval(sessionId, snap,
                        PlannerStateStore.PLAN_APPROVAL_PENDING);
                return RunOutcome.blockedOnHuman(planApprovalPrompt(compiled),
                        "PLAN_APPROVAL_REQUIRED");
            }
            case APPROVE -> {
                snap = markPlanApproval(sessionId, snap,
                        PlannerStateStore.PLAN_APPROVAL_APPROVED);
                log.info("计划审批通过 session={} source={}", sessionId, compiled.source());
            }
            case NONE -> { }
        }

        if (compiled.degraded()) {
            log.warn("PlanningLoop 使用降级编译结果执行 session={} source={} reason={}",
                    sessionId, compiled.source(), compiled.fallbackReason());
        }

        AcceptedNodeAnswer acceptedAnswer = null;
        int rounds = 0;
        // 只有"图全部通过终验"这一条路会把 completed 置真；其余出口都是"没做完"。
        String unfinishedReason = "NO_READY_NODES";
        while (rounds++ < properties.getMaxOuterRounds()) {
            if (!sessionLock.renewSessionLock(sessionId, fencingToken)) {
                log.warn("PlanningLoop 会话锁丢失，中止 session={} code={}",
                        sessionId, ErrorCode.AGENT_PLANNER_LOCK_LOST.getCode());
                metrics.outerTimeout();
                String msg = ErrorCode.AGENT_PLANNER_LOCK_LOST.getMessage();
                return RunOutcome.failed(msg, "SESSION_LOCK_LOST");
            }
            snap = stateStore.get(sessionId).orElse(snap);
            if (!java.util.Objects.equals(admittedExecutionId, snap.executionId())
                    || !java.util.Objects.equals(admittedScopeId, snap.sessionId())) {
                return RunOutcome.failed("任务执行已被新的任务接替，旧执行已停止。", "STALE_EXECUTION");
            }
            if (acceptedAnswer != null && !acceptedAnswer.matches(snap)) acceptedAnswer = null;
            unknownNode = unknownOutcomeNode(snap.graph());
            if (unknownNode != null) {
                return stopUnknownOutcome(sessionId, snap, unknownNode);
            }
            if (snap.intent().needsClarification()) {
                return RunOutcome.blockedOnHuman(snap.intent().clarificationQuestion(),
                        "INTENT_NEEDS_CLARIFICATION");
            }
            TaskGraph normalized = snap.graph().normalizeForScheduling();
            if (!sameNodeStatuses(normalized, snap.graph())) {
                try {
                    snap = stateStore.commit(sessionId, snap.version(), snap.withGraph(normalized));
                } catch (PlannerStateStore.VersionConflictException e) {
                    metrics.casConflict();
                    snap = stateStore.get(sessionId).orElse(snap);
                    continue;
                }
                todoProjector.project(sessionId, snap.graph());
            }
            unknownNode = unknownOutcomeNode(snap.graph());
            if (unknownNode != null) {
                return stopUnknownOutcome(sessionId, snap, unknownNode);
            }
            if (snap.graph().allTerminalSuccess()) {
                StepEvaluator.GraphEval acc = stepEvaluator.evaluateGraph(
                        snap.goal(), snap.graph(), snap);
                StateSnapshot evaluated = stateStore.get(sessionId).orElse(null);
                if (evaluated == null || !java.util.Objects.equals(snap.sessionId(), evaluated.sessionId())
                        || !java.util.Objects.equals(snap.executionId(), evaluated.executionId())) {
                    return RunOutcome.failed("任务执行已被新的任务接替，旧执行已停止。", "STALE_EXECUTION");
                }
                if (evaluated.version() != snap.version()) continue;
                if (!sessionLock.renewSessionLock(sessionId, fencingToken)) {
                    return RunOutcome.failed("任务执行租约已失效，已停止确认完成。", "SESSION_LOCK_LOST");
                }
                if (!acc.ok()) {
                    acceptedAnswer = null;
                    log.warn("图终验失败 session={} node={} reason={}",
                            sessionId, acc.nodeId(), acc.reason());
                    TaskNode bad = resolveAcceptFailNode(snap.graph(), acc.nodeId());
                    if (bad == null) {
                        unfinishedReason = "ACCEPTANCE_NODE_MISSING";
                        break;
                    }
                    TaskGraph g = snap.graph().replace(
                            bad.withStatus(TaskNodeStatus.FAILED)
                                    .withError(acc.reason()));
                    FailureDiagnosis dx = recoveryEngine.diagnose(
                            bad, diagnoseTool(bad, ""), acc.reason());
                    try {
                        snap = commitNodeOutcomes(sessionId, fencingToken, snap, snap.withGraph(g), List.of(
                                new NodeOutcome("acceptance-" + bad.id() + "-" + snap.planVersion(),
                                        bad, NodeTerminalCommitPort.Result.FAILURE,
                                        failureDetail(null, acc.reason(), userMessage))));
                    } catch (PlannerStateStore.VersionConflictException e) {
                        metrics.casConflict();
                        continue;
                    }
                    RecoveryAttempt recovery = applyRecoveryOrCancel(sessionId, fencingToken, snap, bad, dx, bad.id(),
                            chat, userMessage, taskPlan, history);
                    if (recovery.stop() != null) return recovery.stop();
                    snap = recovery.snapshot();
                    todoProjector.project(sessionId, snap.graph());
                    continue;
                }
                // Acceptance can run a slow model or read external state. Fence
                // its result with an actual CAS before returning task success.
                try {
                    Map<String, Object> accepted = new LinkedHashMap<>(snap.execution());
                    accepted.put("graphAcceptedPlanVersion", snap.planVersion());
                    accepted.put("graphAcceptedStateVersion", snap.version());
                    snap = stateStore.commit(sessionId, snap.version(), snap.withExecution(accepted));
                } catch (PlannerStateStore.VersionConflictException conflict) {
                    metrics.casConflict();
                    continue;
                }
                log.info("PlanningLoop 图完成 session={} version={} metrics={}",
                        sessionId, snap.version(), metrics.snapshot());
                metrics.graphCompleted();
                // Only a durably accepted node from this execution and plan can
                // supply delivery prose. Otherwise derive the response from the
                // persisted outputs that have just passed graph acceptance.
                return RunOutcome.satisfied(buildCompletedAnswer(
                        acceptedAnswer != null && acceptedAnswer.matches(snap) ? acceptedAnswer.text() : "", snap.graph()),
                        compiled.degraded());
            }
            List<TaskNode> ready = graphScheduler.select(snap.graph());
            if (ready.isEmpty()) {
                if (snap.graph().hasAwaitingConfirm()) {
                    log.info("PlanningLoop 等待人工 confirm session={}", sessionId);
                    String hint = firstAwaitingHint(snap.graph());
                    if (StringUtils.isNotBlank(hint)) {
                        return RunOutcome.blockedOnHuman(hint, "AWAITING_CONFIRM");
                    }
                    return RunOutcome.blockedOnHuman(
                            "关键步骤等待确认：请在页面点击「确认并继续」。", "AWAITING_CONFIRM");
                }
                log.warn("PlanningLoop 无 ready 节点且未全部成功，尝试 REWRITE session={}", sessionId);
                TaskNode stuck = firstNonSuccess(snap.graph());
                if (stuck == null) {
                    // 没有非成功节点却选不出 READY：图状态自相矛盾，不能当成完成
                    unfinishedReason = "NO_READY_AND_NO_STUCK_NODE";
                    break;
                }
                FailureDiagnosis dx = recoveryEngine.diagnose(
                        stuck, diagnoseTool(stuck, ""), "no ready nodes");
                TaskGraph before = snap.graph();
                acceptedAnswer = null;
                RecoveryAttempt recovery = applyRecoveryOrCancel(sessionId, fencingToken, snap, stuck, dx, stuck.id(),
                        chat, userMessage, taskPlan, history);
                if (recovery.stop() != null) return recovery.stop();
                snap = recovery.snapshot();
                if (sameNodeStatuses(before, snap.graph())) {
                    // 恢复动作没有改变任何节点状态 = 死锁（例如上游被写成 CANCELLED，
                    // 后继永远拿不到解锁条件）。这正是"卡住了却报成功"的典型现场。
                    unfinishedReason = "GRAPH_DEADLOCK";
                    break;
                }
                todoProjector.project(sessionId,
                        stateStore.get(sessionId).map(StateSnapshot::graph).orElse(snap.graph()));
                continue;
            }

            // StepExec carries one node's terminal result and evidence. Dispatch
            // exactly one graph node until results can be correlated per action;
            // a larger legacy batch setting must not certify unexecuted nodes.
            // Tool calls inside this node retain their normal concurrency policy.
            ActionProposal proposal = graphScheduler.propose(snap, ready, 1);
            if (proposal.actions().isEmpty()) {
                unfinishedReason = "EMPTY_PROPOSAL";
                break;
            }
            for (ActionSpec a : proposal.actions()) {
                TaskNode n = snap.graph().byId(a.taskId());
                if (n == null || n.status() != TaskNodeStatus.READY) {
                    log.warn("拒绝非 READY 节点进入提案: {}", a.taskId());
                    proposal = new ActionProposal(proposal.proposalId(), proposal.basedOnVersion(),
                            proposal.basedOnPlanVersion(), proposal.executionId(), List.of());
                    break;
                }
            }
            if (proposal.actions().isEmpty()) {
                continue;
            }

            snap = markRunning(sessionId, snap, proposal);
            if (!ownsRunningProposal(snap, proposal)) {
                metrics.casConflict();
                log.info("丢弃过期 ActionProposal session={} proposal={} stateVersion={} planVersion={}",
                        sessionId, proposal.proposalId(), snap.version(), snap.planVersion());
                continue;
            }
            todoProjector.project(sessionId, snap.graph());
            trace(sessionId, AgentStepNode.GRAPH_UPDATED,
                    "{\"version\":" + snap.version() + ",\"running\":"
                            + proposal.actions().size() + "}");

            String stepUser = userMessage;
            String prior = predecessorOutputs(snap.graph(), proposal);
            if (StringUtils.isNotBlank(prior)) {
                stepUser = userMessage + "\n\n# 前置节点产出\n" + prior;
            }
            StepExec step = executeProposal(chat, systemPrompt, stepUser,
                    multimodalUser, history, taskPlan, proposal, snap.graph(),
                    snap.version(), sessionId, streamSink);
            captureAgentTranscript(step.messages());
            boolean drifted = step.drifted();
            boolean quotaAbort = isResourceQuotaAbort(step.endReason())
                    || AgentLoop.LOOP_MAX_ITERATIONS.equals(step.endReason());
            boolean humanWait = AgentLoop.PERM_ASK.equals(step.endReason())
                    || AgentLoop.USER_QUESTION.equals(step.endReason());

            snap = stateStore.get(sessionId).orElse(snap);
            if (!ownsRunningProposal(snap, proposal)) {
                metrics.casConflict();
                log.warn("丢弃过期执行结果 session={} proposal={} planVersion={} observedPlanVersion={}",
                        sessionId, proposal.proposalId(), proposal.basedOnPlanVersion(), snap.planVersion());
                continue;
            }
            if (isExecutionStop(step.endReason())) {
                return stopExecution(sessionId, snap, proposal, step, userMessage);
            }
            if (AgentLoop.OUTCOME_UNKNOWN.equals(step.endReason())
                    || (step.structuredResult() != null
                    && step.structuredResult().status() == com.miniagent.agent.tool.ToolStatus.UNKNOWN)) {
                TaskGraph stopped = snap.graph();
                for (ActionSpec action : proposal.actions()) {
                    TaskNode node = stopped.byId(action.taskId());
                    if (node != null) {
                        String reason = "OUTCOME_UNKNOWN: " + clip(step.text(), 400);
                        stopped = stopped.replace(node.withStatus(TaskNodeStatus.OUTCOME_UNKNOWN)
                                .withToolStatus(com.miniagent.agent.tool.ToolStatus.UNKNOWN)
                                .withError(reason));
                    }
                }
                try {
                    snap = stateStore.commit(sessionId, snap.version(), snap.withGraph(stopped));
                } catch (PlannerStateStore.VersionConflictException e) {
                    // The dispatch receipt already persisted UNKNOWN before calling
                    // the executor, so even a conflicting completion cannot replay it.
                    metrics.casConflict();
                }
                todoProjector.project(sessionId,
                        stateStore.get(sessionId).map(StateSnapshot::graph).orElse(stopped));
                return RunOutcome.failed("本次工具执行结果未知，已停止自动重试，请先核验实际状态。",
                        "OUTCOME_UNKNOWN");
            }
            boolean anyFail = false;
            TaskGraph g = snap.graph();
            List<NodeOutcome> pendingOutcomes = new ArrayList<>();
            for (ActionSpec action : proposal.actions()) {
                TaskNode node = g.byId(action.taskId());
                if (node == null) {
                    continue;
                }
                if (humanWait) {
                    g = g.replace(node.withStatus(TaskNodeStatus.AWAITING_CONFIRM).withToolStatus(null)
                            .withError(clip(step.text(), AWAITING_HINT_CHARS)));
                    continue;
                }
                boolean awaiting = todoProjector.isTodoAwaiting(
                        sessionId, g, action.taskId());
                if (awaiting) {
                    g = g.replace(node.withStatus(TaskNodeStatus.AWAITING_CONFIRM)
                            .withToolStatus(null)
                            .withError(clip(step.text(), AWAITING_HINT_CHARS)));
                    continue;
                }
                if (drifted) {
                    acceptedAnswer = null;
                    anyFail = true;
                    String reason = "drift:偏离子目标 " + node.name();
                    g = g.replace(node.withStatus(TaskNodeStatus.FAILED).withToolStatus(null).withError(reason));
                    FailureDiagnosis dx = recoveryEngine.diagnose(
                            node, diagnoseTool(node, step.lastTool()), reason,
                            step.lastErrorCode());
                    try {
                        snap = commitNodeOutcomes(sessionId, fencingToken, snap, snap.withGraph(g), List.of(
                                new NodeOutcome(action.actionId(), node, NodeTerminalCommitPort.Result.FAILURE,
                                        failureDetail(step.lastErrorCode(), reason, userMessage))));
                    } catch (PlannerStateStore.VersionConflictException e) {
                        metrics.casConflict();
                        break;
                    }
                    RecoveryAttempt recovery = applyRecoveryOrCancel(sessionId, fencingToken, snap, node, dx, action.taskId(),
                            chat, userMessage, taskPlan, history);
                    if (recovery.stop() != null) return recovery.stop();
                    snap = recovery.snapshot();
                    g = snap.graph();
                    continue;
                }
                String judgeAnswer = node.doneWhen() != null && node.doneWhen().isJudge()
                        ? step.text() : "";
                NodeOutputBinder.Bound bound = NodeOutputBinder.bind(
                        node, "", step.messages(), judgeAnswer, step.structuredResult(),
                        predecessorOutputs(g, proposal));
                StepEvaluator.EvalResult ev;
                if (RunStatus.FAILURE.name().equals(step.endReason())) {
                    ev = StepEvaluator.EvalResult.fail("tool execution failed: " + clip(step.text(), 400));
                } else if (!bound.complete(node)) {
                    ev = StepEvaluator.EvalResult.fail("declared outputs 未绑定");
                } else {
                    if (node.doneWhen() != null && node.doneWhen().isCommand()) {
                        StepEvaluator.CommandReceipt receipt = StepEvaluator.matchingCommandReceipt(
                                step.commandReceipts(), node.doneWhen().criteria());
                        String commandEvidence = receipt == null ? bound.evidence()
                                : receipt.result().legacyText();
                        ev = stepEvaluator.evaluate(node, step.structuredResult(), commandEvidence,
                                step.commandReceipts());
                    } else {
                        ev = step.structuredResult() == null
                                ? stepEvaluator.evaluateAfterLoop(node, bound.evidence())
                                : stepEvaluator.evaluate(node, step.structuredResult(), bound.evidence());
                    }
                }
                TaskNodeStatus next = statusAfterChunk(ev.ok(), quotaAbort);
                if (next == TaskNodeStatus.SUCCESS) {
                    TaskNode completed = node.withStatus(TaskNodeStatus.SUCCESS).withError("")
                            .withToolStatus(step.structuredResult() == null
                                    ? null : step.structuredResult().status())
                            .withOutput(clip(node.doneWhen() != null && node.doneWhen().isCommand()
                                            ? commandEvidence(step, node) : bound.evidence(), 12000),
                                    clipBindings(bound.bindings()));
                    if (node.doneWhen() != null && node.doneWhen().isCommand()) {
                        StepEvaluator.CommandReceipt receipt = StepEvaluator.matchingCommandReceipt(
                                step.commandReceipts(), node.doneWhen().criteria());
                        if (receipt != null) {
                            Map<String, String> bindings = new LinkedHashMap<>(completed.outputBindings());
                            bindings.put("__command_receipt", PlannerStateJson.MAPPER.valueToTree(
                                    Map.of("tool", receipt.toolName(),
                                            "command", commandFromArguments(receipt.arguments()))).toString());
                            completed = completed.withOutput(completed.output(), bindings);
                        }
                    }
                    g = g.replace(completed);
                    pendingOutcomes.add(new NodeOutcome(action.actionId(), node,
                            NodeTerminalCommitPort.Result.SUCCESS,
                            new NodeTerminalCommitPort.Detail(null, null, userMessage), ev.acceptanceProof()));
                } else if (next == TaskNodeStatus.READY) {
                    g = g.replace(node.withStatus(TaskNodeStatus.READY).withToolStatus(null).withError(""));
                } else {
                    acceptedAnswer = null;
                    anyFail = true;
                    g = g.replace(node.withStatus(TaskNodeStatus.FAILED)
                            .withToolStatus(step.structuredResult() == null
                                    ? null : step.structuredResult().status()).withError(ev.reason()));
                    FailureDiagnosis dx = recoveryEngine.diagnose(
                            node, diagnoseTool(node, step.lastTool()), ev.reason(),
                            step.lastErrorCode());
                    StateSnapshot failedSnap = snap.withGraph(g);
                    try {
                        snap = commitNodeOutcomes(sessionId, fencingToken, snap, failedSnap, List.of(
                                new NodeOutcome(action.actionId(), node, NodeTerminalCommitPort.Result.FAILURE,
                                        failureDetail(step.lastErrorCode(), ev.reason(), userMessage))));
                    } catch (PlannerStateStore.VersionConflictException e) {
                        metrics.casConflict();
                        log.warn("状态冲突，触发 replan: {}", e.getMessage());
                        break;
                    }
                    RecoveryAttempt recovery = applyRecoveryOrCancel(sessionId, fencingToken, snap, node, dx, action.taskId(),
                            chat, userMessage, taskPlan, history);
                    if (recovery.stop() != null) return recovery.stop();
                    snap = recovery.snapshot();
                    g = snap.graph();
                }
            }
            if (!anyFail) {
                try {
                    StateSnapshot next = snap.withGraph(g);
                    if (humanWait) {
                        Map<String, Object> metadata = new LinkedHashMap<>(snap.execution());
                        metadata.put(EXECUTION_HUMAN_WAIT, Map.of("reason", step.endReason(), "taskIds",
                                proposal.actions().stream().map(ActionSpec::taskId).toList()));
                        next = next.withExecution(metadata);
                    }
                    snap = commitNodeOutcomes(sessionId, fencingToken, snap, next, pendingOutcomes);
                    // A model draft is eligible for delivery only after its own node's
                    // accepted output and verdict have been durably committed. Never
                    // retain failed, stale or CAS-rejected node prose as a fallback.
                    if (!humanWait && !isStubStepAnswer(step.text()) && StringUtils.isNotBlank(step.text())) {
                        for (NodeOutcome outcome : pendingOutcomes) {
                            if (outcome.result() == NodeTerminalCommitPort.Result.SUCCESS) {
                                acceptedAnswer = AcceptedNodeAnswer.fromCommitted(snap, outcome.node().id(), step.text());
                            }
                        }
                    }
                    trace(sessionId, AgentStepNode.STATE_COMMIT,
                            "{\"version\":" + snap.version() + "}");
                } catch (PlannerStateStore.VersionConflictException e) {
                    metrics.casConflict();
                    log.warn("成功提交冲突，replan: {}", e.getMessage());
                    continue;
                }
            }
            todoProjector.project(sessionId,
                    stateStore.get(sessionId).map(StateSnapshot::graph).orElse(g));
            if (snap.intent().needsClarification()) {
                return RunOutcome.blockedOnHuman(snap.intent().clarificationQuestion(),
                        "INTENT_NEEDS_CLARIFICATION");
            }
            if (humanWait) {
                log.info("PlanningLoop 等待用户 session={} reason={}",
                        sessionId, step.endReason());
                if (StringUtils.isNotBlank(step.text())) {
                    return RunOutcome.blockedOnHuman(step.text(), step.endReason());
                }
                return RunOutcome.blockedOnHuman("需要你确认后才能继续。", step.endReason());
            }
            TaskGraph latest = stateStore.get(sessionId).map(StateSnapshot::graph).orElse(g);
            if (latest.hasAwaitingConfirm() || g.hasAwaitingConfirm()) {
                log.info("PlanningLoop 等待人工输入 session={}", sessionId);
                String hint = firstAwaitingHint(latest);
                if (StringUtils.isNotBlank(hint)) return RunOutcome.blockedOnHuman(hint, "AWAITING_CONFIRM");
                return RunOutcome.blockedOnHuman(
                        "关键步骤等待确认：请在页面点击「确认并继续」。", "AWAITING_CONFIRM");
            }
            if (quotaAbort && hasUnfinishedProposalNode(g, proposal)) {
                log.info("本段未完成，节点保持 READY session={}", sessionId);
                unfinishedReason = "QUOTA_ABORT";
                break;
            }
        }
        // `rounds++ < max` leaves rounds exactly equal to max when the loop
        // exits because the hard limit was reached.  Using `>` made this
        // branch unreachable, which hid the real terminal reason and metric.
        if (outerRoundsExhausted(rounds, properties.getMaxOuterRounds())) {
            metrics.outerTimeout();
            unfinishedReason = "OUTER_ROUNDS_EXHAUSTED";
        }

        String unfinishedAnswer = "任务尚未完成，已保留执行进度。";
        // 走到这里说明是"没做完"退出（死锁/恢复耗尽/轮次用尽/配额中止）。
        // 绝不能把这种结果报成完成：上层会据此决定任务状态与"完成率"口径，
        // 附带未完成节点，不将失败或尚未验收的节点散文当成交付结果。
        String pending = pendingSummary(sessionId);
        if (StringUtils.isNotBlank(pending)) {
            unfinishedAnswer = unfinishedAnswer + "\n\n仍待处理：\n" + pending;
        }
        return RunOutcome.unfinished(unfinishedAnswer, unfinishedReason);
    }

    /** 未达成目标时列出还没成功的节点，避免用户只看到一句"已推进"。 */
    private String pendingSummary(String sessionId) {
        try {
            TaskGraph g = stateStore.get(sessionId).map(StateSnapshot::graph).orElse(null);
            if (g == null || g.nodes() == null || g.nodes().isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (TaskNode n : g.nodes()) {
                if (n.status() == TaskNodeStatus.SUCCESS) {
                    continue;
                }
                if (shown++ >= 8) {
                    sb.append("- …\n");
                    break;
                }
                sb.append("- ").append(StringUtils.isBlank(n.name()) ? n.id() : n.name());
                if (StringUtils.isNotBlank(n.lastError())) {
                    sb.append("（详情见执行日志）");
                }
                sb.append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.debug("汇总未完成节点失败: {}", e.getMessage());
            return "";
        }
    }

    static String keepBetterAnswer(String previous, String next) {
        if (isStubStepAnswer(next) && StringUtils.isNotBlank(previous)) {
            return previous;
        }
        if (StringUtils.isBlank(next)) {
            return previous == null ? "" : previous;
        }
        return next;
    }

    /** A reply candidate is local to a successfully committed node, never a model draft. */
    record AcceptedNodeAnswer(String sessionId, String executionId, long planVersion,
                              long committedVersion, TaskNode source, String text) {
        static AcceptedNodeAnswer fromCommitted(StateSnapshot committed, String nodeId, String text) {
            if (committed == null || StringUtils.isBlank(committed.sessionId())
                    || StringUtils.isBlank(committed.executionId()) || committed.graph() == null
                    || StringUtils.isBlank(text) || isStubStepAnswer(text)) return null;
            TaskNode node = committed.graph().byId(nodeId);
            if (node == null || node.status() != TaskNodeStatus.SUCCESS || !NodeOutputBinder.ofNode(node).complete(node))
                return null;
            // Accepting a tool observation or a file does not certify unrelated
            // model prose. File/media delivery uses the accepted artifact summary;
            // text delivery must be the very output evaluated for this node.
            if (node.doneWhen().isFile() || node.doneWhen().isMedia()
                    || !text.trim().equals(node.output().trim())) return null;
            List<TaskNode> terminalNodes = committed.graph().nodes().stream()
                    .filter(candidate -> committed.graph().nodes().stream()
                            .noneMatch(other -> other.dependsOn().contains(candidate.id())))
                    .toList();
            // A middle step or one of several independent terminal outputs is not
            // an answer for the whole graph. Those graphs use accepted-output summaries.
            if (terminalNodes.size() != 1 || !node.id().equals(terminalNodes.getFirst().id())) return null;
            return new AcceptedNodeAnswer(committed.sessionId(), committed.executionId(), committed.planVersion(),
                    committed.version(), node, text.trim());
        }

        boolean matches(StateSnapshot snapshot) {
            return snapshot != null && sessionId.equals(snapshot.sessionId())
                    && executionId.equals(snapshot.executionId()) && planVersion == snapshot.planVersion()
                    && snapshot.version() >= committedVersion && snapshot.graph() != null
                    && source.equals(snapshot.graph().byId(source.id()));
        }
    }

    /** Node drafts remain internal until their verdict and graph acceptance are durable. */
    static AgentStreamSink nodeEvidenceStream(AgentStreamSink userStream) {
        if (userStream == null) return null;
        return new AgentStreamSink() {
            @Override public void onThinking(String delta) { userStream.onThinking(delta); }
            @Override public void onAnswerToken(String delta) { /* unaccepted node draft */ }
            @Override public void onAnswerReset() { /* cannot reset the user's answer */ }
            @Override public void onSubGoal(String text, int done, int total) { userStream.onSubGoal(text, done, total); }
            @Override public void onContext(int used, int limit) { userStream.onContext(used, limit); }
        };
    }

    static boolean isStubStepAnswer(String text) {
        if (text == null) {
            return true;
        }
        String t = text.trim();
        return t.equals(AgentLoop.STEP_SEGMENT_DONE)
                || t.startsWith("本步已完成")
                || t.equals("本步已完成")
                || t.startsWith("已按规划图推进任务");
    }

    /**
     * Turn a graph-level success into text that can safely be shown to the user.
     *
     * <p>{@link AgentLoop#STEP_SEGMENT_DONE} is an execution marker, not a final
     * answer.  Usually a later model turn supplies a prose answer, but a plan made
     * entirely of direct/bound actions has no such turn.  In that case the graph's
     * accepted node outputs are the only durable evidence we have, so expose a
     * bounded, labelled summary instead of returning an empty string.</p>
     */
    static String buildCompletedAnswer(String lastAnswer, TaskGraph graph) {
        if (StringUtils.isNotBlank(lastAnswer) && !isStubStepAnswer(lastAnswer)) {
            return lastAnswer.trim();
        }

        StringBuilder summary = new StringBuilder("任务已完成。");
        boolean hasEvidence = false;
        if (graph != null && graph.nodes() != null) {
            for (TaskNode node : graph.nodes()) {
                if (node == null || node.status() != TaskNodeStatus.SUCCESS) {
                    continue;
                }
                String name = StringUtils.isBlank(node.name()) ? node.id() : node.name();
                DoneWhen doneWhen = node.doneWhen() == null ? DoneWhen.note() : node.doneWhen();
                if (doneWhen.isFile() && StringUtils.isNotBlank(doneWhen.path())) {
                    summary.append("\n- ").append(name).append("：已生成 ")
                            .append(doneWhen.path());
                    hasEvidence = true;
                    continue;
                }
                String output = node.output() == null ? "" : node.output().trim();
                if (StringUtils.isBlank(output) || StepEvaluator.isHollowEvidence(output)) {
                    summary.append("\n- ").append(name).append("：已完成");
                    hasEvidence = true;
                    continue;
                }
                summary.append("\n- ").append(name).append("：")
                        .append(clip(output, PRIOR_OUTPUT_CHARS));
                hasEvidence = true;
            }
        }
        if (!hasEvidence) {
            summary.append("\n已完成所有规划步骤，但步骤没有返回可展示的摘要。");
        }
        return summary.toString();
    }

    private record StepExec(String text, String endReason, boolean drifted,
                            String lastTool, ToolErrorCode lastErrorCode,
                            List<ChatMessage> messages, ToolResult structuredResult, boolean toolAttempted,
                            List<StepEvaluator.CommandReceipt> commandReceipts) {
        StepExec {
            messages = messages == null ? List.of() : List.copyOf(messages);
            commandReceipts = commandReceipts == null ? List.of() : List.copyOf(commandReceipts);
        }
    }

    /** Only executor waits are consumed by a reply; explicit Todo confirmations stay gated. */
    private StateSnapshot consumeHumanWait(String sessionId, StateSnapshot snapshot) {
        Object pending = snapshot.execution().get(EXECUTION_HUMAN_WAIT);
        if (!(pending instanceof Map<?, ?> wait)
                || !(wait.get("taskIds") instanceof List<?> taskIds)) return snapshot;
        TaskGraph graph = snapshot.graph();
        for (Object taskId : taskIds) {
            TaskNode node = taskId instanceof String id ? graph.byId(id) : null;
            if (node != null && node.status() == TaskNodeStatus.AWAITING_CONFIRM)
                graph = graph.replace(node.withStatus(TaskNodeStatus.PENDING).withToolStatus(null).withError(""));
        }
        Map<String, Object> metadata = new LinkedHashMap<>(snapshot.execution());
        metadata.remove(EXECUTION_HUMAN_WAIT);
        return stateStore.commit(sessionId, snapshot.version(), snapshot.withGraph(graph).withExecution(metadata));
    }

    private static boolean isExecutionStop(String reason) {
        return AgentLoop.CANCELLED.equals(reason) || RunStatus.CANCELED.name().equals(reason)
                || AgentLoop.DEADLINE_EXCEEDED.equals(reason) || isResourceQuotaAbort(reason);
    }

    /** A control stop outranks old successful tool evidence and must never enter acceptance or recovery. */
    private RunOutcome stopExecution(String sessionId, StateSnapshot snapshot, ActionProposal proposal,
                                     StepExec step, String userMessage) {
        TaskGraph graph = snapshot.graph();
        boolean unknown = step.toolAttempted();
        for (ActionSpec action : proposal.actions()) {
            TaskNode node = graph.byId(action.taskId());
            if (node == null) continue;
            TaskNode stopped = node.withStatus(unknown ? TaskNodeStatus.OUTCOME_UNKNOWN : TaskNodeStatus.PENDING)
                    .withToolStatus(unknown ? com.miniagent.agent.tool.ToolStatus.UNKNOWN : null)
                    .withError(step.endReason() + ": " + clip(step.text(), AWAITING_HINT_CHARS));
            if (unknown) {
                NodeOutputBinder.Bound evidence = NodeOutputBinder.bind(
                        node, "", step.messages(), "", step.structuredResult());
                stopped = stopped.withOutput(clip(evidence.evidence(), 12000), clipBindings(evidence.bindings()));
            }
            graph = graph.replace(stopped);
            // A control stop or unknown external effect is not a verified
            // procedure failure. Preserve evidence without training a verdict.
        }
        Map<String, Object> metadata = new LinkedHashMap<>(snapshot.execution());
        metadata.put("stopReason", step.endReason());
        metadata.put("stoppedTaskIds", proposal.actions().stream().map(ActionSpec::taskId).toList());
        StateSnapshot stopped = stateStore.commit(sessionId, snapshot.version(), snapshot.withGraph(graph).withExecution(metadata));
        todoProjector.project(sessionId, stopped.graph());
        String answer = StringUtils.defaultIfBlank(step.text(), "任务已停止，尚未完成。");
        if (unknown) answer += "\n当前节点已保留执行证据，请先核验实际状态；系统不会自动重放该节点。";
        return RunOutcome.failed(answer, step.endReason());
    }

    /** 轮次用尽就续跑。file_exists 可能只是写了个开头，不能当「做完了」。 */
    static boolean shouldResumeChunk(String endReason, int chunk, int maxChunks) {
        return chunk < maxChunks
                && isLoopLimit(endReason);
    }

    private static boolean isLoopLimit(String endReason) {
        return endReason != null && AgentLoop.LOOP_MAX_ITERATIONS.equalsIgnoreCase(endReason);
    }

    private static TaskNode unknownOutcomeNode(TaskGraph graph) {
        if (graph == null) {
            return null;
        }
        for (TaskNode node : graph.nodes()) {
            if (node.toolStatus() == com.miniagent.agent.tool.ToolStatus.UNKNOWN
                    || node.status() == TaskNodeStatus.RUNNING
                    || node.status() == TaskNodeStatus.OUTCOME_UNKNOWN) {
                return node;
            }
        }
        return null;
    }

    private RunOutcome stopUnknownOutcome(String sessionId, StateSnapshot snapshot, TaskNode unknown) {
        TaskGraph stopped = snapshot.graph();
        boolean changed = false;
        for (TaskNode node : stopped.nodes()) {
            if ((node.toolStatus() == com.miniagent.agent.tool.ToolStatus.UNKNOWN
                    || node.status() == TaskNodeStatus.RUNNING)
                    && node.status() != TaskNodeStatus.OUTCOME_UNKNOWN) {
                stopped = stopped.replace(node.withStatus(TaskNodeStatus.OUTCOME_UNKNOWN)
                        .withError("OUTCOME_UNKNOWN: 执行中断，实际副作用尚未核验"));
                changed = true;
            }
        }
        if (changed) {
            try {
                snapshot = stateStore.commit(sessionId, snapshot.version(), snapshot.withGraph(stopped));
            } catch (PlannerStateStore.VersionConflictException e) {
                metrics.casConflict();
                snapshot = stateStore.get(sessionId).orElse(snapshot);
            }
        }
        todoProjector.project(sessionId, snapshot.graph());
        return RunOutcome.failed("节点「" + unknown.name()
                + "」执行结果未知，已停止自动重试，请先核验实际状态。", "OUTCOME_UNKNOWN");
    }

    static TaskNodeStatus statusAfterChunk(boolean evalOk, boolean quotaAbort) {
        if (evalOk) {
            return TaskNodeStatus.SUCCESS;
        }
        if (quotaAbort) {
            return TaskNodeStatus.READY;
        }
        return TaskNodeStatus.FAILED;
    }

    static boolean isResourceQuotaAbort(String endReason) {
        return AgentLoop.RESOURCE_QUOTA_EXCEEDED.equals(endReason)
                || "RESOURCE_QUOTA_EXCEEDED".equals(endReason)
                || "TENANT_QUOTA_EXCEEDED".equals(endReason)
                || "TOOL_BUDGET_EXCEEDED".equals(endReason);
    }

    static Map<String, String> clipBindings(Map<String, String> in) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : in.entrySet()) {
            out.put(e.getKey(), clip(e.getValue(), PRIOR_OUTPUT_CHARS));
        }
        return Map.copyOf(out);
    }

    static boolean hasUnfinishedProposalNode(TaskGraph g, ActionProposal proposal) {
        if (g == null || proposal == null) {
            return false;
        }
        for (ActionSpec action : proposal.actions()) {
            TaskNode n = g.byId(action.taskId());
            if (n != null && n.status() != TaskNodeStatus.SUCCESS) {
                return true;
            }
        }
        return false;
    }

    private static String firstAwaitingHint(TaskGraph graph) {
        if (graph == null) {
            return "";
        }
        for (TaskNode n : graph.nodes()) {
            if (n.status() == TaskNodeStatus.AWAITING_CONFIRM
                    && StringUtils.isNotBlank(n.lastError())) {
                return n.lastError();
            }
        }
        return "";
    }

    static String predecessorOutputs(TaskGraph graph, ActionProposal proposal) {
        if (graph == null || proposal == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ActionSpec a : proposal.actions()) {
            TaskNode node = graph.byId(a.taskId());
            if (node == null) {
                continue;
            }
            if (!node.inputs().isEmpty()) {
                for (String in : node.inputs()) {
                    TaskNode prod = producerOf(graph, node, in);
                    if (prod == null || prod.status() != TaskNodeStatus.SUCCESS
                            || (StringUtils.isBlank(prod.output())
                            && StringUtils.isBlank(prod.outputBindings().get(in)))) {
                        continue;
                    }
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    String named = prod.outputBindings().get(in);
                    sb.append("## ").append(in).append(" (").append(prod.id())
                            .append(' ').append(prod.name()).append(")\n")
                            .append(clip(StringUtils.isNotBlank(named) ? named : prod.output(), PRIOR_OUTPUT_CHARS))
                            .append('\n');
                }
                continue;
            }
            for (String d : node.dependsOn()) {
                TaskNode dep = graph.byId(d);
                if (dep == null || StringUtils.isBlank(dep.output())) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append("## ").append(dep.id()).append(' ')
                        .append(dep.name()).append('\n')
                        .append(clip(dep.output(), PRIOR_OUTPUT_CHARS)).append('\n');
            }
        }
        return sb.toString();
    }

    static TaskNode producerOf(TaskGraph graph, TaskNode node, String input) {
        if (graph == null || node == null || input == null) {
            return null;
        }
        Set<String> visited = new HashSet<>();
        List<String> pending = new ArrayList<>(node.dependsOn());
        for (int i = 0; i < pending.size(); i++) {
            String d = pending.get(i);
            if (d.equals(node.id()) || !visited.add(d)) continue;
            TaskNode dep = graph.byId(d);
            if (dep != null && dep.outputs().contains(input)) {
                return dep;
            }
            if (dep != null) pending.addAll(dep.dependsOn());
        }
        return null;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private StepExec executeProposal(ChatModel chat,
                                     String systemPrompt,
                                     String userMessage,
                                     UserMessage multimodalUser,
                                     List<ChatMessage> history,
                                     TaskPlan taskPlan,
                                     ActionProposal proposal,
                                     TaskGraph graph,
                                     long dispatchStateVersion,
                                     String sessionId,
                                     AgentStreamSink streamSink) {
        AgentStreamSink evidenceStream = nodeEvidenceStream(streamSink);
        List<String> allowed = toolRouter.allowedFor(
                proposal, graph, properties.isHardProposal());
        String focusTask = proposal.actions().isEmpty() ? "" : proposal.actions().get(0).taskId();
        String focusName = "";
        if (!focusTask.isEmpty() && graph != null) {
            TaskNode fn = graph.byId(focusTask);
            if (fn != null) {
                focusName = fn.name();
            }
        }
        Set<Integer> focusTodos = new LinkedHashSet<>();
        for (ActionSpec a : proposal.actions()) {
            int id = todoProjector.todoIdFor(graph, a.taskId());
            if (id > 0) {
                focusTodos.add(id);
            }
        }
        boolean hard = properties.isHardProposal();
        String label = StringUtils.isNotBlank(focusName) ? focusName : focusTask;
        int timeoutSec = properties.getActionTimeoutSeconds();
        if (!proposal.actions().isEmpty()) {
            timeoutSec = proposal.actions().get(0).timeoutSeconds();
        }
        ProposalTurnPolicy policy = new ProposalTurnPolicy(
                allowed, hard, label, focusTodos, timeoutSec);
        LoopTurnContext.set(policy);
        List<ExecutionTurnContext.ActionBinding> bindings = proposal.actions().stream()
                .map(a -> new ExecutionTurnContext.ActionBinding(
                        a.actionId(), a.taskId(), a.tool(), a.idempotencyKey()))
                .toList();
        ExecutionTurnContext.open(sessionId, proposal.executionId(), proposal.basedOnPlanVersion(), bindings,
                () -> dispatchFenceValid(sessionId, proposal));
        metrics.proposal();
        try {
            if (traceRecorder != null)
                traceRecorder.recordNode(sessionId, 0, AgentStepNode.PROPOSAL.name(),
                        "{\"proposalId\":\"" + proposal.proposalId()
                                + "\",\"basedOnVersion\":" + proposal.basedOnVersion()
                                + ",\"planVersion\":" + proposal.basedOnPlanVersion()
                                + ",\"hardGate\":" + hard
                                + ",\"focusTodoIds\":" + focusTodos
                                + ",\"tools\":" + toJsonArray(allowed) + "}",
                        RunStatus.SUCCESS.name(), 0);
            if (ActionBinder.canDirect(proposal)) {
                StepExec bound = executeBoundAction(proposal.actions().get(0));
                if (bound != null) {
                    return bound;
                }
            }
            String focus = systemPrompt + "\n\n" + focusBlock(proposal, graph, focusTodos, hard);
            int maxIter = Math.max(2, properties.getProposalMaxIterations());
            for (String t : allowed) {
                if (t.startsWith("browser_")) {
                    maxIter = Math.max(maxIter, properties.getProposalBrowserMaxIterations());
                    break;
                }
            }
            try {
                AgentLoop.LoopOutcome out = nodeExecutor.execute(chat, focus, userMessage, multimodalUser,
                        history, maxIter, taskPlan, evidenceStream);
                ToolResult lastToolResult = out.lastToolResult();
                boolean toolAttempted = !out.toolsInvoked().isEmpty() || lastToolResult != null;
                int chunks = 1;
                int maxChunks = Math.max(1, properties.getProposalMaxChunks());
                while (shouldResumeChunk(out.endReason(), chunks, maxChunks)) {
                    log.info("配额续跑 chunk={}/{} task={}", chunks + 1, maxChunks, focusTask);
                    ArrayList<ChatMessage> live = new ArrayList<>(out.messages());
                    live.add(new SystemMessage(MessageConstants.PLANNER_CONTINUE_HINT));
                    out = nodeExecutor.continueNode(chat, live, userMessage,
                            maxIter, taskPlan, evidenceStream);
                    if (out.lastToolResult() != null) lastToolResult = out.lastToolResult();
                    toolAttempted |= !out.toolsInvoked().isEmpty() || out.lastToolResult() != null;
                    chunks++;
                }
                return new StepExec(out.text(), out.endReason(), policy.consumeDrift(),
                        lastInvokedTool(out), lastToolResult == null ? out.lastErrorCode()
                        : lastToolResult.errorCode(), out.messages(), lastToolResult, toolAttempted,
                        toolExecutionReceipts(out.messages()));
            } catch (RuntimeException ex) {
                com.miniagent.agent.core.ModelCallControl.rethrowIfStopped(ex);
                // A ReAct turn may already have dispatched tools before the caller
                // failed. Without a complete result, replay cannot be assumed safe.
                log.warn("节点执行异常，停止自动重试 task={}", focusTask, ex);
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                return new StepExec("节点执行结果未知：" + message, AgentLoop.OUTCOME_UNKNOWN,
                        policy.consumeDrift(), "", ToolErrorCode.OUTCOME_UNKNOWN, List.of(), null, true, List.of());
            }
        } finally {
            ExecutionTurnContext.clear();
            LoopTurnContext.clear();
        }
    }

    private StepExec executeBoundAction(ActionSpec action) {
        String json;
        try {
            json = PlannerStateJson.MAPPER.writeValueAsString(action.arguments());
        } catch (Exception e) {
            log.warn("绑定参数序列化失败，回退 ReAct tool={}", action.tool());
            return null;
        }
        ToolResult result;
        ToolInvocation invocation = null;
        try {
            invocation = nodeExecutor.executeBoundInvocation(action.tool(), json);
            result = invocation == null ? null : invocation.result();
        } catch (RuntimeException ex) {
            com.miniagent.agent.core.ModelCallControl.rethrowIfStopped(ex);
            log.warn("绑定工具执行异常，停止自动重试 tool={} task={}", action.tool(), action.taskId(), ex);
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            result = ToolResult.unknown("工具执行结果未知：" + msg, null);
        }
        if (result == null) {
            result = ToolResult.unknown("工具执行器未返回结果", null);
        }
        String text = result.legacyText();
        boolean ok = result.isSuccess();
        ToolErrorCode code = result.errorCode();
        String end = ok ? AgentLoop.STEP_SEGMENT_DONE
                : result.status() == com.miniagent.agent.tool.ToolStatus.UNKNOWN
                ? AgentLoop.OUTCOME_UNKNOWN : RunStatus.FAILURE.name();
        if (invocation != null) {
            switch (invocation.outcome()) {
                case CONTROL_STOP -> {
                    ExecutionControl.StopReason stop = invocation.controlStopReason();
                    // An unspecified legacy control stop is also unsafe to replay automatically.
                    end = stop == ExecutionControl.StopReason.NONE ? AgentLoop.OUTCOME_UNKNOWN : stop.name();
                    ok = false;
                }
                case PERMISSION_ASK -> {
                    end = AgentLoop.PERM_ASK;
                    ok = false;
                }
                case USER_QUESTION -> {
                    end = AgentLoop.USER_QUESTION;
                    text = StringUtils.defaultIfBlank(invocation.loopSignal(), text);
                    ok = false;
                }
                default -> { }
            }
        }
        ToolExecutionResultMessage msg = ToolExecutionResultMessage.from(
                action.actionId(), action.tool(), result.legacyText());
        // A stopped external write is UNKNOWN in the pipeline once dispatched. Pre-dispatch
        // stops and approval waits can be resumed without claiming unconfirmed side effects.
        boolean attempted = invocation == null || invocation.outcome() == ToolInvocation.Outcome.EXECUTED
                || result.status() == com.miniagent.agent.tool.ToolStatus.UNKNOWN;
        List<ChatMessage> toolMessages = new ArrayList<>();
        if (invocation != null && invocation.outcome() == ToolInvocation.Outcome.EXECUTED
                && result.status() != com.miniagent.agent.tool.ToolStatus.UNKNOWN) {
            toolMessages.add(AiMessage.from(ToolExecutionRequest.builder()
                    .id(action.actionId()).name(action.tool()).arguments(json).build()));
        }
        toolMessages.add(msg);
        return new StepExec(ok ? AgentLoop.STEP_SEGMENT_DONE : text, end, false,
                action.tool(), code, List.copyOf(toolMessages), result, attempted,
                toolExecutionReceipts(toolMessages));
    }

    private void captureAgentTranscript(List<ChatMessage> messages) {
        List<ChatMessage> target = currentTurnAgentMessages.get();
        if (target == null || messages == null || messages.isEmpty()) return;
        target.addAll(toolTranscriptSinceLatestUser(messages));
    }

    static List<ChatMessage> toolTranscriptSinceLatestUser(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        int start = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) start = i + 1;
        }
        int from = Math.max(0, start);
        List<ChatMessage> transcript = new ArrayList<>();
        for (int i = from; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                transcript.add(message);
            } else if (message instanceof ToolExecutionResultMessage) {
                transcript.add(message);
            }
        }
        return List.copyOf(transcript);
    }

    private boolean dispatchFenceValid(String sessionId, ActionProposal proposal) {
        return stateStore.dispatchFenceValid(sessionId, proposal);
    }

    static boolean ownsRunningProposal(StateSnapshot snapshot, ActionProposal proposal) {
        if (snapshot == null || proposal == null || proposal.actions().isEmpty()
                || !java.util.Objects.equals(snapshot.executionId(), proposal.executionId())
                || snapshot.planVersion() != proposal.basedOnPlanVersion()) return false;
        return proposal.actions().stream().allMatch(action -> {
            TaskNode node = snapshot.graph().byId(action.taskId());
            return node != null && node.status() == TaskNodeStatus.RUNNING;
        });
    }

    private static String focusBlock(ActionProposal proposal, TaskGraph graph,
                                     Set<Integer> focusTodos, boolean hard) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ActionProposal（硬闸门=").append(hard).append("）\n");
        sb.append("- proposalId: ").append(proposal.proposalId()).append('\n');
        sb.append("- basedOnVersion: ").append(proposal.basedOnVersion()).append('\n');
        sb.append("- planVersion: ").append(proposal.basedOnPlanVersion()).append('\n');
        sb.append("- focusTodoIds: ").append(focusTodos).append('\n');
        sb.append(MessageConstants.PLANNER_FOCUS_RULES).append('\n');
        sb.append("本步动作：\n");
        for (ActionSpec a : proposal.actions()) {
            TaskNode n = graph == null ? null : graph.byId(a.taskId());
            String cap = n != null ? n.capability() : a.tool();
            List<String> blocked = n == null ? List.of() : n.blockedTools();
            if (n != null && StringUtils.isNotBlank(n.lastError())) {
                sb.append("上一轮失败上下文（必须先处理，不要盲重试）：")
                        .append(n.lastError()).append('\n');
            }
            if (n != null && StringUtils.isNotBlank(n.procedureSnapshotJson())) {
                try {
                    ProcedurePromptInput procedure = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(n.procedureSnapshotJson(), ProcedurePromptInput.class);
                    sb.append("本节点固定使用已授权 Procedure 快照：procedureId=")
                            .append(procedure.procedureId()).append(" version=").append(procedure.version())
                            .append(" variant=").append(n.procedureVariant())
                            .append(" proposalId=").append(n.evolutionProposalId())
                            .append(" candidateHash=").append(n.evolutionCandidateHash())
                            .append(" canaryTargetPercent=").append(n.canaryTargetPercent()).append('\n')
                            .append("preconditions=").append(procedure.preconditions()).append('\n')
                            .append("执行步骤（必须依序遵循）：").append(procedure.steps()).append('\n')
                            .append("successConditions=").append(procedure.successConditions()).append('\n');
                } catch (Exception e) {
                    throw new IllegalStateException("Persisted procedure snapshot is invalid for task " + n.id(), e);
                }
            }
            sb.append("- taskId=").append(a.taskId())
                    .append(" capability=").append(cap)
                    .append(" tool=").append(a.tool())
                    .append(" arguments=").append(a.arguments())
                    .append(" acceptance=").append(a.acceptance().wire())
                    .append(" idempotencyKey=").append(a.idempotencyKey())
                    .append(" timeoutSeconds=").append(a.timeoutSeconds())
                    .append(" retryMax=").append(a.retryPolicy().maxAttempts())
                    .append(" concurrencyKey=").append(a.concurrencyKey());
            if (StringUtils.isNotBlank(a.compensation())) {
                sb.append(" compensation=").append(a.compensation());
            }
            if (!blocked.isEmpty()) {
                sb.append(" blockedTools=").append(blocked);
            }
            sb.append(" expected=").append(a.expectedResult()).append('\n');
        }
        sb.append(MessageConstants.DAG_EVIDENCE_SUBMISSION).append('\n');
        return sb.toString();
    }

    /** 整图审批的状态机：不介入 / 拦下等人 / 放行。 */
    enum PlanApprovalAction { NONE, BLOCK, APPROVE }

    static PlanApprovalAction planApprovalAction(boolean degraded, boolean enabled,
                                                 boolean approved, String userMessage) {
        if (!needsPlanApproval(degraded, enabled) || approved) {
            return PlanApprovalAction.NONE;
        }
        return isPlanApprovalReply(userMessage)
                ? PlanApprovalAction.APPROVE : PlanApprovalAction.BLOCK;
    }

    static boolean needsPlanApproval(boolean degraded, boolean enabled) {
        return enabled && degraded;
    }

    static boolean isPlanApprovalReply(String userMessage) {
        return HumanYield.looksLikePlanApproval(userMessage);
    }

    private static boolean isPlanApproved(StateSnapshot snap) {
        return snap != null && PlannerStateStore.PLAN_APPROVAL_APPROVED.equals(
                snap.execution().get(PlannerStateStore.EXECUTION_PLAN_APPROVAL));
    }

    private StateSnapshot markPlanApproval(String sessionId, StateSnapshot snap, String state) {
        if (snap == null) {
            return null;
        }
        if (state.equals(snap.execution().get(PlannerStateStore.EXECUTION_PLAN_APPROVAL))) {
            return snap;
        }
        Map<String, Object> exec = new HashMap<>(snap.execution());
        exec.put(PlannerStateStore.EXECUTION_PLAN_APPROVAL, state);
        try {
            return stateStore.commit(sessionId, snap.version(), snap.withExecution(exec));
        } catch (PlannerStateStore.VersionConflictException e) {
            metrics.casConflict();
            // The approval applied to this exact plan. A concurrent revision
            // or replacement must be presented again before it can execute.
            throw e;
        }
    }

    private static String planApprovalPrompt(GoalCompiler.CompileResult compiled) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务图已生成，但本轮使用的是降级编译结果（规划模型未返回有效任务图），")
                .append("执行前需要你确认。\n\n");
        sb.append("目标：").append(compiled.goal().objective()).append('\n');
        List<TaskNode> nodes = compiled.graph().nodes();
        sb.append("步骤：\n");
        int limit = Math.min(nodes.size(), 8);
        for (int i = 0; i < limit; i++) {
            TaskNode n = nodes.get(i);
            sb.append(i + 1).append(". ").append(n.name());
            if (!n.dependsOn().isEmpty()) {
                sb.append("（依赖 ").append(String.join(",", n.dependsOn())).append("）");
            }
            sb.append('\n');
        }
        if (nodes.size() > limit) {
            sb.append("… 共 ").append(nodes.size()).append(" 个节点\n");
        }
        TaskIntent intent = compiled.intent();
        if (!intent.deliverables().isEmpty()) {
            sb.append("交付物：").append(intent.deliverables().stream()
                    .map(d -> StringUtils.isBlank(d.path()) ? d.name() : d.path())
                    .collect(Collectors.joining("、"))).append('\n');
        }
        if (!intent.exclusions().isEmpty()) {
            sb.append("不做：").append(String.join("、", intent.exclusions())).append('\n');
        }
        sb.append("\n回复「开始」按此计划执行；要调整请直接说明要求。");
        return sb.toString();
    }

    private static String intentJson(TaskIntent intent) {
        if (intent == null) {
            return "null";
        }
        String deliverables = intent.deliverables().stream()
                .map(d -> "{\"name\":\"" + jsonEscape(d.name()) + "\",\"type\":\""
                        + jsonEscape(d.type()) + "\",\"path\":\"" + jsonEscape(d.path())
                        + "\",\"required\":" + d.required() + "}")
                .collect(Collectors.joining(",", "[", "]"));
        String inputs = intent.inputs().stream()
                .map(i -> "{\"name\":\"" + jsonEscape(i.name()) + "\",\"source\":\""
                        + jsonEscape(i.source()) + "\",\"required\":" + i.required() + "}")
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"objective\":\"" + jsonEscape(intent.objective())
                + "\",\"deliverables\":" + deliverables
                + ",\"inputs\":" + inputs
                + ",\"constraints\":" + stringArrayJson(intent.constraints())
                + ",\"exclusions\":" + stringArrayJson(intent.exclusions())
                + ",\"acceptanceCriteria\":" + stringArrayJson(intent.acceptanceCriteria())
                + ",\"needsClarification\":" + intent.needsClarification()
                + ",\"clarificationQuestion\":\"" + jsonEscape(intent.clarificationQuestion()) + "\"}";
    }

    private static String stringArrayJson(List<String> values) {
        if (values == null) {
            return "[]";
        }
        return values.stream().map(value -> "\"" + jsonEscape(value) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String toJsonArray(List<String> tools) {
        return tools.stream()
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * Recovery 成功则升版；达总上限/分类熔断则 CANCELLED，避免 FAILED→READY 空转。
     * CAS 冲突等瞬时失败不取消。REWRITE/REVISE 优先 LLM 重编译并保留 SUCCESS 节点。
     */
    private record RecoveryAttempt(StateSnapshot snapshot, RunOutcome stop) {
        static RecoveryAttempt notApplicable() { return new RecoveryAttempt(null, null); }
        static RecoveryAttempt applied(StateSnapshot snapshot) { return new RecoveryAttempt(snapshot, null); }
    }

    private RecoveryAttempt rejectRecovery(String sessionId, StateSnapshot snap, String reason) {
        StateSnapshot latest = stateStore.get(sessionId).orElse(snap);
        todoProjector.project(sessionId, latest.graph());
        log.warn("任务恢复已停止 session={} reason={}", sessionId, reason);
        return new RecoveryAttempt(latest, RunOutcome.failed(
                "任务恢复未通过校验，已保留执行进度并停止自动重试。请查看执行记录后继续。", reason));
    }

    private RecoveryAttempt applyRecoveryOrCancel(String sessionId, String fencingToken, StateSnapshot snap,
                                                TaskNode node, FailureDiagnosis dx,
                                                String taskId, ChatModel chat,
                                                String userMessage, TaskPlan taskPlan,
                                                List<ChatMessage> history) {
        RecoveryAttempt llm = tryLlmReplan(
                sessionId, fencingToken, snap, node, dx, chat, userMessage, taskPlan, history);
        // A rejected plan is not permission to retry the previous graph. In
        // particular, FAILED nodes would otherwise normalize back to READY.
        if (llm.stop() != null) return llm;
        if (llm.snapshot() != null) {
            metrics.recovery();
            return llm;
        }
        boolean atLimit = snap.recoveryCount() >= properties.getMaxRecoveries()
                || recoveryEngine.classCount(snap, dx.failureClass())
                >= recoveryEngine.classLimit(dx.failureClass());
        if (recoveryEngine.recover(sessionId, snap, dx).isPresent()) {
            metrics.recovery();
            return RecoveryAttempt.applied(stateStore.get(sessionId).orElse(snap));
        }
        if (!atLimit) {
            log.warn("Recovery 失败 task={}", taskId);
            return RecoveryAttempt.applied(stateStore.get(sessionId).orElse(snap));
        }
        log.warn("Recovery 耗尽，取消节点 task={}", taskId);
        TaskNode latest = snap.graph().byId(node.id());
        if (latest == null) {
            latest = node;
        }
        TaskGraph g = snap.graph().replace(
                latest.withStatus(TaskNodeStatus.CANCELLED).withError("recovery_exhausted"));
        try {
            return RecoveryAttempt.applied(stateStore.commit(sessionId, snap.version(), snap.withGraph(g)));
        } catch (PlannerStateStore.VersionConflictException e) {
            metrics.casConflict();
            log.warn("取消耗尽节点冲突 task={}: {}", taskId, e.getMessage());
            return RecoveryAttempt.applied(stateStore.get(sessionId).orElse(snap));
        }
    }

    private RecoveryAttempt tryLlmReplan(String sessionId, String fencingToken, StateSnapshot snap,
                                                 TaskNode node, FailureDiagnosis dx,
                                                 ChatModel chat, String userMessage,
                                                 TaskPlan taskPlan, List<ChatMessage> history) {
        if (chat == null || snap == null || node == null || dx == null) {
            return RecoveryAttempt.notApplicable();
        }
        if (dx.failureClass() != FailureClass.REWRITE_GRAPH
                && dx.failureClass() != FailureClass.REVISE_GOAL) {
            return RecoveryAttempt.notApplicable();
        }
        if (snap.recoveryCount() >= properties.getMaxRecoveries()) {
            return RecoveryAttempt.notApplicable();
        }
        int used = recoveryEngine.classCount(snap, dx.failureClass());
        if (used >= recoveryEngine.classLimit(dx.failureClass())) {
            return RecoveryAttempt.notApplicable();
        }
        String correction = "失败节点 id=" + node.id()
                + " name=" + node.name()
                + " capability=" + node.capability()
                + " tool=" + dx.tool()
                + "\n失败原因: " + dx.reason()
                // 把诊断出的处置建议交给模型。此前只用于选分支（规则恢复 vs 重规划），
                // 模型这边只看到「失败原因」，得自己重推一遍该修参数、换工具还是改目标 ——
                // 而失败分类正是这里做的判断（RecoveryEngine.classify），模型重推纯属重复劳动且会跑偏。
                + "\n建议处置: " + dx.suggestedFix()
                + "\n请重新规划未完成部分，不要重复已成功节点。"
                + "\n已成功节点回执（只可引用，不得重新执行）："
                + PlannerStateJson.MAPPER.valueToTree(snap.graph().nodes().stream()
                        .filter(completed -> completed.status() == TaskNodeStatus.SUCCESS).toList())
                + " 产出文件的步骤必须 doneWhen.type=file_exists 且 path 非空。";
        TaskPlan recoveryPlan = taskPlan.withTaskGoal(snap.goal().objective());
        GoalCompiler.CompileResult compiled;
        try {
            compiled = goalCompiler.compileWithCorrection(chat, userMessage, recoveryPlan, correction,
                    history == null ? List.of() : history, snap.graph());
        } catch (Exception e) {
            com.miniagent.agent.core.ModelCallControl.rethrowIfStopped(e);
            log.warn("运行时 LLM replan 失败: {}", e.getMessage());
            return rejectRecovery(sessionId, snap, "REPLAN_COMPILATION_FAILED");
        }
        if (compiled == null || compiled.degradedCompilation()
                || compiled.graph() == null || compiled.graph().isEmpty()) {
            return rejectRecovery(sessionId, snap, "REPLAN_COMPILATION_INVALID");
        }
        boolean waiting = compiled.intent().needsClarification();
        TaskGraph merged = waiting ? compiled.graph()
                : mergeKeepSuccess(snap.graph(), compiled.graph(), snap.recoveryCount() + 1);
        if (merged == null || merged.isEmpty()) {
            return rejectRecovery(sessionId, snap, "REPLAN_IDENTITY_CONFLICT");
        }
        if (!mergeValidationReports(
                planValidator.validate(merged, recoveryPlan, compiled.goal(), compiled.intent()),
                intentGraphValidator.validate(compiled.intent(), merged)).valid()) {
            log.warn("运行时 LLM replan 图验收失败（结构或意图）session={}", sessionId);
            return rejectRecovery(sessionId, snap, "REPLAN_VALIDATION_FAILED");
        }
        Goal nextGoal = compiled.goal() != null ? compiled.goal() : snap.goal();
        Map<String, Object> exec = new HashMap<>(snap.execution());
        if (waiting) {
            try {
                TaskGraph completed = new TaskGraph(snap.graph().nodes().stream()
                        .filter(receipt -> receipt.status() == TaskNodeStatus.SUCCESS).toList());
                exec.put(RECOVERY_CLARIFICATION, PlannerStateJson.MAPPER.writeValueAsString(
                        new RecoveryContinuation(snap.executionId(), completed)));
            } catch (Exception invalidReceipt) {
                return rejectRecovery(sessionId, snap, "RECOVERY_CONTEXT_INVALID");
            }
        }
        exec.put("recovery." + dx.failureClass().name(), used + 1);
        exec.put("lastFailureKind", dx.kind().name());
        StateSnapshot patched = snap.revisePlan(merged, nextGoal, compiled.intent(),
                        "llm_replan_" + dx.failureClass().name().toLowerCase())
                .withExecution(exec).withRecoveryInc();
        if (!sessionLock.renewSessionLock(sessionId, fencingToken)) {
            return rejectRecovery(sessionId, snap, "SESSION_LOCK_LOST");
        }
        try {
            // Replan state and audit must be committed in the same CAS.  A worker
            // restart after the graph write must not produce a state whose recovery
            // history says nothing about why the plan changed.
            DomainEvent recoveryEvent = new DomainEvent(
                    "ev_" + UUID.randomUUID().toString().substring(0, 8),
                    DomainEventType.RECOVERY_APPLIED, null, dx.taskId(),
                    Map.of("class", dx.failureClass().name(), "kind", dx.kind().name(),
                            "tool", dx.tool(), "llmReplan", true),
                    null);
            StateSnapshot committed = stateStore.commit(sessionId, snap.version(), patched,
                    List.of(recoveryEvent));
            log.info("运行时 LLM replan 已提交 session={} nodes={}",
                    sessionId, committed.graph().nodes().size());
            return RecoveryAttempt.applied(committed);
        } catch (PlannerStateStore.VersionConflictException e) {
            metrics.casConflict();
            return rejectRecovery(sessionId, snap, "STALE_EXECUTION");
        }
    }

    private static RecoveryContinuation recoveryContinuation(StateSnapshot waiting) {
        Object value = waiting.execution().get(RECOVERY_CLARIFICATION);
        if (value == null) return null;
        try {
            if (!(value instanceof String json)) throw new IllegalStateException();
            RecoveryContinuation receipt = PlannerStateJson.MAPPER.readValue(json, RecoveryContinuation.class);
            if (!java.util.Objects.equals(waiting.executionId(), receipt.executionId())
                    || receipt.completed() == null || receipt.completed().nodes().stream()
                    .anyMatch(node -> node.status() != TaskNodeStatus.SUCCESS
                            || node.toolStatus() == com.miniagent.agent.tool.ToolStatus.UNKNOWN)) {
                throw new IllegalStateException();
            }
            return receipt;
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid recovery clarification receipt", invalid);
        }
    }

    /** Reuse matching completed producers; a model cannot replay their effects under a fresh ID. */
    static TaskGraph mergeKeepSuccess(TaskGraph oldGraph, TaskGraph neu, int gen) {
        if (oldGraph == null || neu == null || neu.isEmpty()) {
            return null;
        }
        List<TaskNode> out = new ArrayList<>();
        List<String> successIds = new ArrayList<>();
        for (TaskNode n : oldGraph.nodes()) {
            if (n.status() == TaskNodeStatus.SUCCESS) {
                out.add(n);
                successIds.add(n.id());
            }
        }
        String prefix = "rp" + gen + "_";
        Map<String, String> newIds = new LinkedHashMap<>();
        for (TaskNode n : neu.nodes()) {
            if (StringUtils.isBlank(n.id())) {
                return null;
            }
            String mapped = prefix + n.id();
            // A generated ID must never alias an existing receipt and silently skip a new action.
            if (successIds.contains(mapped)) return null;
            for (TaskNode completed : out) {
                boolean sameId = completed.id().equals(n.id());
                boolean sameOutput = !completed.outputs().isEmpty()
                        && completed.outputs().stream().anyMatch(n.outputs()::contains);
                boolean sameBoundAction = StringUtils.isNotBlank(completed.toolHint())
                        && !completed.toolArguments().isEmpty()
                        && java.util.Objects.equals(completed.capability(), n.capability())
                        && java.util.Objects.equals(completed.toolHint(), n.toolHint())
                        && java.util.Objects.equals(completed.toolArguments(), n.toolArguments());
                // Matching an output label is evidence of a collision, not proof that
                // two unbound actions are the same. Refuse the merge rather than replay
                // or silently skip a potentially different side effect.
                if (sameOutput && !sameId && !sameBoundAction) {
                    return null;
                }
                if (sameId || sameOutput || sameBoundAction) {
                    if (!java.util.Objects.equals(completed.capability(), n.capability())
                            || (!sameBoundAction && !java.util.Objects.equals(completed.name(), n.name()))
                            || !java.util.Objects.equals(completed.toolHint(), n.toolHint())
                            || !java.util.Objects.equals(completed.toolArguments(), n.toolArguments())
                            || !java.util.Objects.equals(completed.doneWhen(), n.doneWhen())
                            || !java.util.Objects.equals(completed.inputs(), n.inputs())
                            || (!n.outputBindings().isEmpty()
                                && !java.util.Objects.equals(completed.outputBindings(), n.outputBindings()))
                            || !java.util.Objects.equals(completed.outputs(), n.outputs())) return null;
                    mapped = completed.id();
                    break;
                }
            }
            if (newIds.putIfAbsent(n.id(), mapped) != null) {
                return null;
            }
        }
        for (TaskNode n : neu.nodes()) {
            if (successIds.contains(newIds.get(n.id()))) continue;
            Set<String> deps = new LinkedHashSet<>();
            for (String dependency : n.dependsOn()) {
                String mapped = newIds.get(dependency);
                if (mapped != null) {
                    deps.add(mapped);
                } else if (successIds.contains(dependency)) {
                    deps.add(dependency);
                } else {
                    // 无法解析的依赖不能静默删除，否则会提前执行有副作用的动作。
                    return null;
                }
            }
            if (deps.isEmpty()) {
                deps.addAll(successIds);
            }
            out.add(n.withIdentityAndDependencies(newIds.get(n.id()), List.copyOf(deps))
                    .withStatus(TaskNodeStatus.PENDING));
        }
        TaskGraph g = new TaskGraph(out);
        if (g.hasCycle()) {
            return null;
        }
        return g;
    }

    private StateSnapshot markRunning(String sessionId, StateSnapshot snap, ActionProposal proposal) {
        StateSnapshot current = stateStore.get(sessionId).orElse(snap);
        if (!java.util.Objects.equals(current.sessionId(), snap.sessionId())
                || !java.util.Objects.equals(current.executionId(), snap.executionId())
                || !java.util.Objects.equals(current.executionId(), proposal.executionId())
                || current.version() != snap.version() || current.planVersion() != proposal.basedOnPlanVersion()
                || proposal.basedOnVersion() != current.version()) return current;
        TaskGraph g = current.graph();
        for (ActionSpec a : proposal.actions()) {
            TaskNode n = g.byId(a.taskId());
            if (n == null || n.status() != TaskNodeStatus.READY) {
                return current;
            }
            // Persist an in-flight receipt before dispatch. If the process dies,
            // the next run must reconcile the outcome instead of replaying a side effect.
            g = g.replace(n.withStatus(TaskNodeStatus.RUNNING)
                    .withToolStatus(com.miniagent.agent.tool.ToolStatus.UNKNOWN));
        }
        try {
            return stateStore.commit(sessionId, current.version(), current.withGraph(g));
        } catch (PlannerStateStore.VersionConflictException e) {
            metrics.casConflict();
            log.warn("markRunning 版本冲突: {}", e.getMessage());
            return stateStore.get(sessionId).orElse(snap);
        }
    }

    private static boolean sameNodeStatuses(TaskGraph a, TaskGraph b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.nodes().size() != b.nodes().size()) {
            return false;
        }
        for (int i = 0; i < a.nodes().size(); i++) {
            TaskNode x = a.nodes().get(i);
            TaskNode y = b.nodes().get(i);
            if (!x.id().equals(y.id()) || x.status() != y.status()) {
                return false;
            }
        }
        return true;
    }

    private static TaskNode firstNonSuccess(TaskGraph graph) {
        for (TaskNode n : graph.nodes()) {
            if (n.status() != TaskNodeStatus.SUCCESS && n.status() != TaskNodeStatus.CANCELLED) {
                return n;
            }
        }
        return null;
    }

    static TaskNode resolveAcceptFailNode(TaskGraph graph, String nodeId) {
        if (graph == null) {
            return null;
        }
        TaskNode byId = graph.byId(nodeId);
        if (byId != null) {
            return byId;
        }
        List<TaskNode> nodes = graph.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            TaskNode n = nodes.get(i);
            if (DataflowNormalizer.needsFileAcceptance(n)) {
                return n;
            }
        }
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
    }

    /** A selected DAG cannot execute until compilation and validation succeed. */
    private RunOutcome invalidPlanOutcome(String sessionId, GoalCompiler.CompileResult compiled) {
        trace(sessionId, AgentStepNode.GOAL_COMPILED,
                "{\"nodes\":0,\"accepted\":false,\"source\":\"" + compiled.source().name()
                        + "\",\"reason\":\"PLAN_VALIDATION_FAILED\"}", RunStatus.FAILURE);
        log.warn("PlanningLoop 任务图未通过校验，停止本轮执行 session={} source={} code={}",
                sessionId, compiled.source(), ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getCode());
        return RunOutcome.failed("任务计划生成失败，尚未开始操作。请重试；原有任务进度已保留。",
                "PLAN_VALIDATION_FAILED");
    }

    private GoalCompiler.CompileResult compileAndValidate(ChatModel chat,
                                                         String userMessage,
                                                         TaskPlan taskPlan) {
        return compileAndValidate(chat, userMessage, taskPlan, List.of());
    }

    private GoalCompiler.CompileResult compileAndValidate(ChatModel chat,
                                                         String userMessage,
                                                         TaskPlan taskPlan,
                                                         List<ChatMessage> history) {
        return compileAndValidate(chat, userMessage, taskPlan, history, null, 0);
    }

    private GoalCompiler.CompileResult compileAndValidate(ChatModel chat, String userMessage,
                                                         TaskPlan taskPlan, List<ChatMessage> history,
                                                         TaskGraph completed, int generation) {
        // 第一次编译
        boolean hasHistory = history != null && !history.isEmpty();
        GoalCompiler.CompileResult compiled = hasHistory
                ? goalCompiler.compile(chat, userMessage, taskPlan, history)
                : goalCompiler.compile(chat, userMessage, taskPlan);
        compiled = mergeCompletedCompilation(compiled, completed, generation);

        // 结构化验证
        PlanValidationReport report = mergeValidationReports(
                planValidator.validate(compiled.graph(), taskPlan, compiled.goal(), compiled.intent()),
                intentGraphValidator.validate(compiled.intent(), compiled.graph()));
        if (report.valid()) {
            return compiled;
        }

        // 保留第一次拒绝原因。后面即使 replan 失败，也必须能从
        // GOAL_COMPILED 看出「模型产图但未通过验收」，不能退化成无原因 TEMPLATE。
        String initialValidation = report.summary();
        log.info("PlanValidator 拒绝，进入 replan 闭环，错误数={}", report.errors().size());
        log.debug("验证报告: {}", initialValidation);
        TaskGraph rejectedGraph = compiled.graph();

        // 尝试 replan，最多重试 maxReplanRetries 次
        int maxReplanRetries = Math.max(0, properties.getMaxReplanRetries());
        for (int attempt = 1; attempt <= maxReplanRetries; attempt++) {
            log.info("Replan 尝试 {}/{}", attempt, maxReplanRetries);

            // 生成修正提示
            String correctionPrompt = report.toCorrectionPrompt()
                    + GoalCompiler.rejectedGraphPrompt(rejectedGraph);

            // 重新编译，注入修正提示
            GoalCompiler.CompileResult replanned = goalCompiler.compileWithCorrection(chat, userMessage,
                    taskPlan, correctionPrompt, hasHistory ? history : List.of(), rejectedGraph);
            replanned = mergeCompletedCompilation(replanned, completed, generation);

            // 验证重新编译的结果
            report = mergeValidationReports(
                    planValidator.validate(replanned.graph(), taskPlan, replanned.goal(), replanned.intent()),
                    intentGraphValidator.validate(replanned.intent(), replanned.graph()));
            if (report.valid()) {
                log.info("Replan 成功，尝试次数={}", attempt);
                return replanned;
            }

            log.warn("Replan 尝试 {} 仍然失败，错误数={}", attempt, report.errors().size());
            if (replanned.graph() != null && !replanned.graph().isEmpty()) {
                rejectedGraph = replanned.graph();
            }
        }

        // 所有 replan 尝试失败。不再构造规则猜测图：
        // 不是模型对本次请求的理解，继续执行等于把「结构合法」当成「语义正确」，
        // 正是分项任务出错的主要来源。这里返回空图结果，调用方据此拒绝调度。
        String fallbackReason = "PLAN_VALIDATION_FAILED: initial=" + clip(initialValidation, 1800)
                + "; finalReplan=" + clip(report.summary(), 1800);
        log.warn("Replan 全部失败，拒绝调度（不降级模板）code={} errors={}",
                ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getCode(), report.errors().size());
        return new GoalCompiler.CompileResult(compiled.goal(), new TaskGraph(List.of()), true,
                GoalCompiler.CompilationSource.VALIDATION_ERROR, fallbackReason,
                compiled.intent());
    }

    private static GoalCompiler.CompileResult mergeCompletedCompilation(GoalCompiler.CompileResult compiled,
                                                                         TaskGraph completed, int generation) {
        if (completed == null || compiled.intent().needsClarification() || compiled.graph().isEmpty()) return compiled;
        TaskGraph merged = mergeKeepSuccess(completed, compiled.graph(), generation);
        return new GoalCompiler.CompileResult(compiled.goal(),
                merged == null ? new TaskGraph(List.of()) : merged,
                compiled.degradedCompilation(), compiled.source(), compiled.fallbackReason(), compiled.intent());
    }

    private static PlanValidationReport mergeValidationReports(PlanValidationReport first,
                                                                PlanValidationReport second) {
        List<PlanValidationReport.ValidationError> errors = new ArrayList<>();
        List<PlanValidationReport.ValidationWarning> warnings = new ArrayList<>();
        if (first != null) {
            errors.addAll(first.errors());
            warnings.addAll(first.warnings());
        }
        if (second != null) {
            errors.addAll(second.errors());
            warnings.addAll(second.warnings());
        }
        return new PlanValidationReport(errors, warnings);
    }

    static String lastInvokedTool(AgentLoop.LoopOutcome out) {
        if (out != null && out.toolsInvoked() != null && !out.toolsInvoked().isEmpty()) {
            List<String> tools = out.toolsInvoked();
            return tools.get(tools.size() - 1);
        }
        return lastInvokedTool(out == null ? null : out.messages());
    }

    static String lastInvokedTool(List<ChatMessage> messages) {
        String last = "";
        if (messages == null) {
            return last;
        }
        for (ChatMessage m : messages) {
            if (m instanceof ToolExecutionResultMessage tr
                    && StringUtils.isNotBlank(tr.toolName())) {
                last = tr.toolName();
            }
        }
        return last;
    }

    static List<StepEvaluator.CommandReceipt> toolExecutionReceipts(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        Map<String, ToolExecutionRequest> requests = new HashMap<>();
        List<StepEvaluator.CommandReceipt> receipts = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                    if (StringUtils.isNotBlank(request.id())) requests.put(request.id(), request);
                }
            } else if (message instanceof ToolExecutionResultMessage result) {
                ToolExecutionRequest request = requests.get(result.id());
                if (request != null && request.name().equals(result.toolName())) {
                    receipts.add(new StepEvaluator.CommandReceipt(request.name(), request.arguments(),
                            ToolResult.fromLegacy(result.text())));
                }
            }
        }
        return List.copyOf(receipts);
    }

    private static String commandEvidence(StepExec step, TaskNode node) {
        StepEvaluator.CommandReceipt receipt = StepEvaluator.matchingCommandReceipt(
                step.commandReceipts(), node.doneWhen().criteria());
        return receipt == null ? "" : receipt.result().legacyText();
    }

    private static String commandFromArguments(String arguments) {
        try {
            return PlannerStateJson.MAPPER.readTree(arguments).path("command").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    static String diagnoseTool(TaskNode node, String lastTool) {
        if (StringUtils.isNotBlank(lastTool)) {
            return lastTool;
        }
        if (node != null && StringUtils.isNotBlank(node.blockedTool())) {
            return node.blockedTool();
        }
        return routeKey(node);
    }

    private static String routeKey(TaskNode node) {
        if (node == null || node.capability() == null) {
            return "";
        }
        return node.capability();
    }

    /**
     * 节点终态结算给记忆侧。
     *
     * <p><b>为什么整体吞异常</b>：Procedure 统计是旁路观测，不是任务的一部分。
     * 数据库抖动、Procedure 被删、归属变更都会让结算失败；若让异常冒泡，
     * 一个已经通过验收的节点会被判成任务失败 —— 记忆的故障反向决定了任务的成败，
     * 这是不能接受的耦合。失败只降级为 warn，图状态照常提交。</p>
     */
    private record NodeOutcome(String actionId, TaskNode node, NodeTerminalCommitPort.Result result,
                               NodeTerminalCommitPort.Detail detail, SemanticAcceptanceReceipts.Proof acceptanceProof) {
        NodeOutcome(String actionId, TaskNode node, NodeTerminalCommitPort.Result result,
                    NodeTerminalCommitPort.Detail detail) { this(actionId, node, result, detail, null); }
    }

    /** The node and its audit verdict become durable together, before metrics or memory learn it. */
    private StateSnapshot commitNodeOutcomes(String sessionId, String fencingToken, StateSnapshot expected, StateSnapshot next,
                                             List<NodeOutcome> outcomes) {
        if (!sessionLock.renewSessionLock(sessionId, fencingToken)) {
            throw new IllegalStateException("SESSION_LOCK_LOST");
        }
        List<DomainEvent> events = outcomes.stream().map(outcome -> {
            boolean success = outcome.result() == NodeTerminalCommitPort.Result.SUCCESS;
            String reason = outcome.detail() == null ? "" :
                    java.util.Objects.requireNonNullElse(outcome.detail().failureReason(), "");
            return new DomainEvent("ev_" + UUID.randomUUID().toString().substring(0, 8),
                    success ? DomainEventType.NODE_SUCCESS : DomainEventType.NODE_FAILED,
                    outcome.actionId(), outcome.node().id(), success ? Map.of() : Map.of("reason", reason), null);
        }).toList();
        Map<String, SemanticAcceptanceReceipts.Proof> proofs = new LinkedHashMap<>();
        Set<String> settledIds = new LinkedHashSet<>();
        for (NodeOutcome outcome : outcomes) {
            settledIds.add(outcome.node().id());
            if (outcome.result() == NodeTerminalCommitPort.Result.SUCCESS && outcome.acceptanceProof() != null)
                proofs.put(outcome.node().id(), outcome.acceptanceProof());
        }
        next = SemanticAcceptanceReceipts.withSettledProofs(expected, next, settledIds, proofs);
        StateSnapshot committed = stateStore.commit(sessionId, expected.version(), next, events);
        for (NodeOutcome outcome : outcomes) {
            boolean success = outcome.result() == NodeTerminalCommitPort.Result.SUCCESS;
            if (success) metrics.nodeSuccess(); else metrics.nodeFailed();
            TaskNode persisted = committed.graph().byId(outcome.node().id());
            recordToolOutcome(persisted, success);
            settleNode(sessionId, outcome.actionId(), persisted, outcome.result(), outcome.detail());
        }
        return committed;
    }

    private void settleNode(String sessionId, String actionId, TaskNode node,
                            NodeTerminalCommitPort.Result result,
                            NodeTerminalCommitPort.Detail detail) {
        if (nodeTerminalCommitPort == null || node == null
                || node.procedureId() == null || node.procedureVersion() == null) {
            return;
        }
        try {
            nodeTerminalCommitPort.commit(sessionId,
                    com.miniagent.agent.core.TaskExecutionContext.currentTaskId(),
                    actionId, node, result, detail);
        } catch (Exception e) {
            log.warn("Procedure 终态结算失败，不影响任务结果: session={}, node={}, procedure={}, version={}, err={}",
                    sessionId, node.id(), node.procedureId(), node.procedureVersion(), e.getMessage());
        }
    }

    private static NodeTerminalCommitPort.Detail failureDetail(ToolErrorCode code, String reason,
                                                               String userMessage) {
        String errorCode = code == null || code == ToolErrorCode.NONE ? null : code.name();
        return new NodeTerminalCommitPort.Detail(errorCode, reason, userMessage);
    }

    private void recordToolOutcome(TaskNode node, boolean ok) {
        if (toolSuccessStats == null || node == null) {
            return;
        }
        String t = node.capability();
        if (StringUtils.isNotBlank(t)) {
            toolSuccessStats.record(t, ok);
        }
    }

    private void trace(String sessionId, AgentStepNode node, String content) {
        trace(sessionId, node, content, RunStatus.SUCCESS);
    }

    private void trace(String sessionId, AgentStepNode node, String content, RunStatus status) {
        if (traceRecorder == null) {
            return;
        }
        traceRecorder.recordNode(sessionId, 0, node.name(), content, status.name(), 0);
    }

    /**
     * 构建 CONTINUE_TASK 的总结信息
     */
    private String buildContinueTaskSummary(StateSnapshot snap, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("【上一个任务总结】\n");

        // 任务目标
        if (snap.goal() != null) {
            sb.append("任务目标：").append(snap.goal().objective()).append("\n");
        }

        // 任务图状态
        if (snap.graph() != null && !snap.graph().isEmpty()) {
            int totalNodes = snap.graph().nodes().size();
            long completedNodes = snap.graph().nodes().stream()
                    .filter(n -> n.status() == TaskNodeStatus.SUCCESS)
                    .count();
            long failedNodes = snap.graph().nodes().stream()
                    .filter(n -> n.status() == TaskNodeStatus.FAILED)
                    .count();
            long unknownNodes = snap.graph().nodes().stream()
                    .filter(n -> n.status() == TaskNodeStatus.OUTCOME_UNKNOWN
                            || n.toolStatus() == com.miniagent.agent.tool.ToolStatus.UNKNOWN)
                    .count();
            long pendingNodes = totalNodes - completedNodes - failedNodes - unknownNodes;

            sb.append("任务进度：").append(completedNodes).append("/").append(totalNodes).append(" 已完成");
            if (failedNodes > 0) {
                sb.append("，").append(failedNodes).append(" 个失败");
            }
            if (unknownNodes > 0) {
                sb.append("，").append(unknownNodes).append(" 个执行结果未知，需核验");
            }
            if (pendingNodes > 0) {
                sb.append("，").append(pendingNodes).append(" 个待执行");
            }
            sb.append("\n");

            // 列出已完成的节点
            if (completedNodes > 0) {
                sb.append("已完成步骤：\n");
                snap.graph().nodes().stream()
                        .filter(n -> n.status() == TaskNodeStatus.SUCCESS)
                        .forEach(n -> sb.append("  ✓ ").append(n.name()).append("\n"));
            }

            // 列出失败的节点
            if (failedNodes > 0) {
                sb.append("失败步骤：\n");
                snap.graph().nodes().stream()
                        .filter(n -> n.status() == TaskNodeStatus.FAILED)
                        .forEach(n -> sb.append("  ✗ ").append(n.name()).append("\n"));
            }
        }

        // 恢复次数
        if (snap.recoveryCount() > 0) {
            sb.append("已尝试恢复次数：").append(snap.recoveryCount()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 检查上一个任务的状态，返回状态说明
     */
    private String checkPreviousTaskStatus(StateSnapshot snap) {
        if (snap.graph() == null || snap.graph().isEmpty()) {
            return "上一个任务没有执行记录";
        }

        boolean allSuccess = snap.graph().allTerminalSuccess();
        boolean hasFailed = snap.graph().nodes().stream()
                .anyMatch(n -> n.status() == TaskNodeStatus.FAILED);
        boolean hasRunning = snap.graph().nodes().stream()
                .anyMatch(n -> n.status() == TaskNodeStatus.RUNNING);

        if (unknownOutcomeNode(snap.graph()) != null) {
            return "上一个任务存在执行结果未知的步骤，核验实际状态前不会自动重试";
        } else if (allSuccess) {
            return "上一个任务已全部完成";
        } else if (hasFailed) {
            return "上一个任务存在失败步骤，将重新尝试执行";
        } else if (hasRunning) {
            return "上一个任务正在执行中，将继续执行";
        } else {
            return "上一个任务未完成，将继续执行";
        }
    }
}
