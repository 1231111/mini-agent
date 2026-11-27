package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.LoopTurnPolicy;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.execution.ToolInvocation;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.todo.HumanYield;
import com.miniagent.agent.todo.AcceptanceFeedback;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.trace.TraceDataSanitizer;
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CodeDiffReviewService codeDiffReviewService;

    private static final Logger log = LoggerFactory.getLogger(PlanningLoop.class);
    private static final int PRIOR_OUTPUT_CHARS = 6000;
    private static final int AWAITING_HINT_CHARS = 500;
    public static final String EXECUTION_HUMAN_WAIT = "humanWait";
    static final String RECOVERY_CLARIFICATION = "recoveryClarification";
    static final String FAILURE_RECOVERY = "failureRecovery";
    static final String ACCEPTANCE_FAILURES = "acceptanceFailures";

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

    /** Indicates whether this turn should reach the planner/router rather than short-circuiting direct. */
    public boolean shouldHandle(TaskPlan plan, String sessionId, String requestText) {
        if (!properties.isEnabled() || plan == null) return false;
        return PermissionContext.mode() == PermissionMode.PLAN
                || properties.isAutoPlanning()
                || !activeTaskRoutingContext(sessionId).isBlank()
                || !todoProjector.resumableTaskContext(sessionId).isBlank();
    }

    /** Resolve once per turn. The decision is passed explicitly to execution, never stored in a ThreadLocal. */
    public PlanningRoute selectRoute(ChatModel chat, TaskPlan plan, String sessionId,
                                     String requestText, UserMessage multimodal, List<ChatMessage> history) {
        PlanningRoute route;
        if (!properties.isEnabled() || plan == null) {
            route = PlanningRoute.direct("configuration", "PLANNER_DISABLED_OR_NO_TASK");
        } else if (!shouldHandle(plan, sessionId, requestText)) {
            route = PlanningRoute.direct("configuration", "AUTO_PLANNING_DISABLED");
        } else {
            String activeContext = activeTaskRoutingContext(sessionId);
            String pausedContext = todoProjector.resumableTaskContext(sessionId);
            PlanningRoute semantic = new SemanticPlanningRouter(modelCallControl).decide(
                    goalCompiler.resolvePlannerChat(chat),
                    requestText == null ? plan.taskGoal() : requestText, multimodal, history,
                    activeContext, pausedContext);
            if (PermissionContext.mode() == PermissionMode.PLAN && semantic.resolved()) {
                route = new PlanningRoute(true, "explicit", "EXPLICIT_PLAN_MODE; " + semantic.reason(),
                        semantic.artifactDeliveryRequested(), semantic.taskBoundary(), semantic.dagAction());
            } else {
                route = semantic;
            }
        }
        trace(sessionId, AgentStepNode.PLANNING_ROUTE,
                "{\"route\":\"" + route.routeName()
                        + "\",\"source\":\"" + jsonEscape(route.source())
                        + "\",\"reason\":\"" + jsonEscape(route.reason())
                        + "\",\"taskBoundary\":\"" + route.taskBoundary().name()
                        + "\",\"dagAction\":\"" + route.dagAction().name() + "\"}",
                route.resolved() ? RunStatus.SUCCESS : RunStatus.FAILURE);
        log.info("任务策略 session={} route={} source={} boundary={} dagAction={} reason={}", sessionId,
                route.routeName(), route.source(), route.taskBoundary(), route.dagAction(), route.reason());
        return route;
    }

    private String activeTaskRoutingContext(String sessionId) {
        if (StringUtils.isBlank(sessionId)) return "";
        String graphContext = stateStore.get(sessionId).filter(snapshot -> snapshot.graph() != null
                        && !snapshot.graph().isEmpty())
                .map(snapshot -> {
                    StringBuilder context = new StringBuilder("objective: ")
                            .append(snapshot.goal() == null ? "" : snapshot.goal().objective());
                    for (TaskNode node : snapshot.graph().nodes()) {
                        if (context.length() >= 3_500) break;
                        context.append("\n- [").append(node.status().name()).append("] ")
                                .append(node.name());
                    }
                    return context.toString();
                }).orElse("");
        String checklistContext = StringUtils.defaultString(todoProjector.currentTaskContext(sessionId));
        if (graphContext.isBlank()) return checklistContext;
        if (checklistContext.isBlank()) return graphContext;
        return graphContext + "\n" + checklistContext;
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

    static boolean shouldRecompileClarify(Goal goal, TaskGraph graph, PlanningRoute.DagAction action) {
        return action == PlanningRoute.DagAction.PLAN
                && ((goal != null && goal.isClarify()) || GoalCompiler.isClarifyGraph(graph));
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

        boolean codeReviewRequired = CodeValidationPolicy.isCodeChangeGoal(taskPlan.taskGoal())
                || CodeValidationPolicy.isCodeChangeGoal(userMessage);
        CodeDiffReviewService.Baseline codeReviewBaseline = null;

        if (route.artifactDeliveryRequested() != null) {
            taskPlan = taskPlan.withArtifactDeliveryRequested(route.artifactDeliveryRequested());
        }
        GoalCompiler.CompileResult compiled;
        StateSnapshot snap;
        var existing = stateStore.get(sessionId);
        boolean incompleteGraph = existing.isPresent()
                && !existing.get().graph().isEmpty()
                && !existing.get().graph().allTerminalSuccess();
        if (com.miniagent.agent.core.GoalContinuationContext.active()
                && (!incompleteGraph || !java.util.Objects.equals(existing.get().executionId(),
                    com.miniagent.agent.core.GoalContinuationContext.current().executionId())))
            return RunOutcome.failed("原任务已完成或已变化，请刷新当前任务状态。", "STALE_EXECUTION");
        boolean awaitingPlanApproval = existing.isPresent()
                && PlannerStateStore.PLAN_APPROVAL_PENDING.equals(
                        existing.get().execution().get(
                                PlannerStateStore.EXECUTION_PLAN_APPROVAL));
        boolean clarifyRecompile = !com.miniagent.agent.core.GoalContinuationContext.active() && existing.isPresent()
                && shouldRecompileClarify(
                        existing.get().goal(), existing.get().graph(), route.dagAction());
        // The semantic router chooses whether to reuse the accepted graph or compile a revision.
        // The executor still enforces scope, incompleteness, unknown-outcome and approval gates.
        boolean resume = route.dagAction() == PlanningRoute.DagAction.RESUME_EXISTING
                && route.taskBoundary() != com.miniagent.agent.scope.TaskBoundary.NEW
                && incompleteGraph;
        // 计划还在等批准时，非批准答复视为对计划的调整要求：
        // 必须带着原目标重编译，不能只拿这句话当新任务，否则原任务直接丢失。
        boolean planRevision = !com.miniagent.agent.core.GoalContinuationContext.active() && awaitingPlanApproval
                && !isPlanApprovalReply(userMessage)
                && route.taskBoundary() != com.miniagent.agent.scope.TaskBoundary.NEW;
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
            snap = existing.get();
            boolean budgetReplanned = false;
            TaskNode exhaustedNode = awaitingPlanApproval || snap.graph().hasAwaitingConfirm()
                    ? null : exhaustedToolBudgetNode(snap);
            if (exhaustedNode != null) {
                TaskNode unknown = unknownOutcomeNode(snap.graph());
                if (unknown != null) return stopUnknownOutcome(sessionId, snap, unknown);

                if (chat == null) {
                    String reason = "规划模型不可用，未消耗预算恢复次数；请在模型恢复后继续";
                    trace(sessionId, AgentStepNode.ERROR, toolBudgetRecoveryTrace(
                            exhaustedNode, stateStore.nodeToolCallsUsed(snap, exhaustedNode.id()),
                            properties.getProposalMaxToolCalls(), reason), RunStatus.FAILURE);
                    return RunOutcome.failed(toolBudgetRecoveryBlockedMessage(exhaustedNode,
                                    stateStore.nodeToolCallsUsed(snap, exhaustedNode.id()),
                                    properties.getProposalMaxToolCalls(), reason),
                            "TOOL_BUDGET_RECOVERY_UNAVAILABLE");
                }
                if (!sessionLock.renewSessionLock(sessionId, fencingToken)) {
                    return RunOutcome.failed("任务执行租约已失效，未消耗预算恢复次数，请基于最新状态继续。",
                            "SESSION_LOCK_LOST");
                }

                PlannerStateStore.NodeToolBudgetRecoveryReservation reservation =
                        stateStore.reserveNodeToolBudgetRecovery(sessionId, snap.executionId(),
                                snap.planVersion(), exhaustedNode.id(), properties.getProposalMaxToolCalls(),
                                properties.getMaxToolBudgetRecoveryReplansPerNode(),
                                properties.getMaxToolBudgetRecoveryReplansPerTask());
                if (!reservation.reserved()) {
                    // Do not ask the node model to propose another tool call that the durable
                    // budget gate will reject again. The original graph and successful receipts
                    // remain intact for a user-directed revision.
                    trace(sessionId, AgentStepNode.ERROR, toolBudgetRecoveryTrace(
                            exhaustedNode, reservation.callsUsed(), properties.getProposalMaxToolCalls(),
                            reservation.reason()), RunStatus.FAILURE);
                    return RunOutcome.failed(toolBudgetRecoveryBlockedMessage(
                                    exhaustedNode, reservation.callsUsed(), properties.getProposalMaxToolCalls(),
                                    reservation.reason()),
                            "TOOL_BUDGET_RECOVERY_EXHAUSTED");
                }
                snap = reservation.snapshot();
                FailureDiagnosis diagnosis = new FailureDiagnosis(
                        FailureClass.REWRITE_GRAPH, FailureKind.TOOL_BUDGET_EXHAUSTED,
                        exhaustedNode.id(), "", exhaustedNode.lastError(),
                        "只重规划这个已耗尽预算的失败节点并保留成功节点；先用 search_code/已知工作目录定位所需逻辑，"
                                + "不要猜嵌套路径或重读重叠片段；分段读取必须依据工具返回的真实行数和非重叠范围。"
                                + "新节点必须缩小职责并给出可独立验收的进展；无法可靠拆解时先请求用户澄清。");
                log.warn("续跑发现节点工具预算耗尽，预留一次有界重规划 session={} node={} calls={}/{} attempt={}/{}",
                        sessionId, exhaustedNode.id(), reservation.callsUsed(), properties.getProposalMaxToolCalls(),
                        reservation.attemptsUsed(), properties.getMaxToolBudgetRecoveryReplansPerNode());
                RecoveryAttempt budgetRecovery = tryLlmReplan(sessionId, fencingToken, snap, exhaustedNode,
                        diagnosis, chat, userMessage, taskPlan, history, true);
                if (budgetRecovery.stop() != null) return budgetRecovery.stop();
                if (budgetRecovery.snapshot() == null) {
                    trace(sessionId, AgentStepNode.ERROR, toolBudgetRecoveryTrace(
                            exhaustedNode, reservation.callsUsed(), properties.getProposalMaxToolCalls(),
                            "未生成通过校验的替代计划"), RunStatus.FAILURE);
                    return RunOutcome.failed(toolBudgetRecoveryBlockedMessage(
                                    exhaustedNode, reservation.callsUsed(), properties.getProposalMaxToolCalls(),
                                    "未生成通过校验的替代计划"),
                            "TOOL_BUDGET_RECOVERY_FAILED");
                }
                snap = budgetRecovery.snapshot();
                existing = Optional.of(snap);
                metrics.recovery();
                budgetReplanned = true;
            } else {
                snap = consumeHumanWait(sessionId, snap);
                existing = Optional.of(snap);
            }
            compiled = new GoalCompiler.CompileResult(snap.goal(), snap.graph(), snap.degraded(),
                    snap.compilationSource(), "", snap.intent());
            snap = resetRoundRecoveryBudget(sessionId, snap);
            log.info("PlanningLoop 续跑已有图 session={} version={} nodes={} budgetReplanned={}",
                    sessionId, snap.version(), snap.graph().nodes().size(), budgetReplanned);

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
        codeReviewRequired = codeReviewRequired || hasReviewableCodeDeliverable(snap);
        if (codeReviewRequired && codeDiffReviewService != null) {
            // Capture only task-declared code outputs in non-Git workspaces. A large
            // workspace baseline is an infrastructure problem, never a reason for the
            // model to archive or move unrelated user files.
            codeReviewBaseline = codeDiffReviewService.captureForSession(
                    sessionId, codeReviewScopePaths(snap));
        }
        if (taskPlan.artifactDeliveryRequested() != null
                && !taskPlan.artifactDeliveryRequested().equals(snap.execution().get("artifactDeliveryRequested"))) {
            Map<String, Object> metadata = new LinkedHashMap<>(snap.execution());
            metadata.put("artifactDeliveryRequested", taskPlan.artifactDeliveryRequested());
            snap = stateStore.commit(sessionId, snap.version(), snap.withExecution(metadata));
        }
        todoProjector.project(sessionId, snap);
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
        boolean attemptedCancelledDependencyResume = false;
        Map<String, ProposalTurnPolicy.ProgressGuard> nodeProgressGuards = new HashMap<>();
        String acceptedCodeReviewHash = "";
        String unfinishedDetail = "";
        // 只有"图全部通过终验"这一条路会把 completed 置真；其余出口都是"没做完"。
        String unfinishedReason = "NO_READY_NODES";
        executionLoop: while (rounds++ < properties.getMaxOuterRounds()) {
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
                todoProjector.project(sessionId, snap);
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
                    FailureDiagnosis dx = recoveryEngine.diagnoseSemanticFailure(
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
                    todoProjector.project(sessionId, snap);
                    continue;
                }
                if (codeReviewRequired) {
                    CodeDiffReviewService.Review review = codeDiffReviewService == null
                            ? new CodeDiffReviewService.Review("", false, true,
                                    "独立代码审查服务不可用，按安全策略阻止完成",
                                    CodeDiffReviewService.ReviewFailureKind.INFRASTRUCTURE)
                            : codeDiffReviewService.review(codeReviewBaseline, chat);
                    if (review.passed()) acceptedCodeReviewHash = review.diffHash();
                    if (!review.passed()) {
                        String reviewReason = review.feedback();
                        if (!review.actionable()) {
                            return RunOutcome.unfinished(
                                    "任务内容已完成，但独立审查基础设施不可用，尚未通过最终验收。\n"
                                            + reviewReason
                                            + "\n系统不会要求智能体修改工作区来绕过此门禁。",
                                    "CODE_REVIEW_INFRASTRUCTURE_UNAVAILABLE");
                        }
                        TaskNode bad = resolveAcceptFailNode(snap.graph(), "");
                        if (bad == null) {
                            return RunOutcome.unfinished(
                                    "代码修改尚未通过独立审查，任务未标记完成。\n" + reviewReason,
                                    "CODE_REVIEW_BLOCKED");
                        }
                        TaskGraph reopened = snap.graph().replace(bad.withStatus(TaskNodeStatus.FAILED)
                                .withError("独立代码审查未通过：" + reviewReason));
                        try {
                            snap = commitNodeOutcomes(sessionId, fencingToken, snap, snap.withGraph(reopened), List.of(
                                    new NodeOutcome("code-review-" + bad.id() + "-" + snap.planVersion(), bad,
                                            NodeTerminalCommitPort.Result.FAILURE,
                                            failureDetail(null, reviewReason, userMessage))));
                        } catch (PlannerStateStore.VersionConflictException conflict) {
                            metrics.casConflict();
                            continue;
                        }
                        FailureDiagnosis reviewDiagnosis = recoveryEngine.diagnoseSemanticFailure(
                                bad, diagnoseTool(bad, ""), reviewReason);
                        RecoveryAttempt recovery = applyRecoveryOrCancel(sessionId, fencingToken, snap, bad,
                                reviewDiagnosis, "code-review-" + bad.id(), chat, userMessage, taskPlan, history);
                        if (recovery.stop() != null) return recovery.stop();
                        snap = recovery.snapshot();
                        todoProjector.project(sessionId, snap);
                        continue;
                    }
                }
                if (codeReviewRequired && (codeDiffReviewService == null
                        || !codeDiffReviewService.isCurrent(codeReviewBaseline, acceptedCodeReviewHash))) {
                    acceptedCodeReviewHash = "";
                    continue;
                }
                // Acceptance can run a slow model or read external state. Fence
                // its result with an actual CAS before returning task success.
                try {
                    Map<String, Object> accepted = new LinkedHashMap<>(snap.execution());
                    accepted.put("graphAcceptedPlanVersion", snap.planVersion());
                    accepted.put("graphAcceptedStateVersion", snap.version());
                    if (codeReviewRequired) accepted.put("graphAcceptedCodeReviewHash", acceptedCodeReviewHash);
                    snap = stateStore.commit(sessionId, snap.version(), snap.withExecution(accepted));
                } catch (PlannerStateStore.VersionConflictException conflict) {
                    metrics.casConflict();
                    continue;
                }
                if (codeReviewRequired && codeDiffReviewService != null)
                    codeDiffReviewService.completeSessionReview(sessionId);
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
            if (com.miniagent.agent.core.GoalContinuationContext.active()) {
                var continuation = com.miniagent.agent.core.GoalContinuationContext.current();
                if (!java.util.Objects.equals(continuation.executionId(), snap.executionId()))
                    return RunOutcome.failed("待续跑目标已经变化，旧执行已停止。", "STALE_EXECUTION");
            }
            if (com.miniagent.agent.core.GoalContinuationContext.unattended()) {
                // snap 在主循环里被反复重赋值，不能直接被 lambda 捕获（编译期要求
                // effectively final）。这里固定的是「本轮 ready 选择时看到的那一版快照」，
                // 与 stream 的立即求值语义一致：filter 执行期间不会有人再改 snap。
                final StateSnapshot readyView = snap;
                ready = ready.stream().filter(n -> GoalContinuationPolicy.readOnlyNode(readyView, n)).toList();
                if (ready.isEmpty() && !snap.graph().allTerminalSuccess())
                    return RunOutcome.blockedOnHuman("已完成可自动执行的读取和核验；剩余动作需要你确认后继续。",
                            "BACKGROUND_ACTION_REQUIRES_CONFIRM");
            }
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
                DependencyBlock blocked = firstPendingBlockedByCancelledDependency(snap.graph());
                if (blocked != null) {
                    if (shouldAttemptCancelledDependencyResume(
                            resume, attemptedCancelledDependencyResume, snap.graph())) {
                        attemptedCancelledDependencyResume = true;
                        TaskNode cancelled = blocked.dependency();
                        String reason = "用户明确要求继续，但未完成节点「" + blocked.node().name()
                                + "」依赖的节点「" + cancelled.name() + "」此前因恢复耗尽而取消";
                        FailureDiagnosis diagnosis = new FailureDiagnosis(
                                FailureClass.REWRITE_GRAPH, FailureKind.DEPENDENCY_FAILED,
                                cancelled.id(), "", reason,
                                "只重规划未完成子图，保留已成功节点；对取消节点先只读核验可能已发生的结果，禁止盲目重复副作用操作");
                        String continuationRequest = cancelledDependencyResumeRequest(snap, userMessage);
                        log.warn("续跑发现已取消的前置节点，尝试一次有界重规划 session={} node={} blockedNode={} recoveryCount={}",
                                sessionId, cancelled.id(), blocked.node().id(), snap.recoveryCount());
                        RecoveryAttempt recovery = tryLlmReplan(sessionId, fencingToken, snap, cancelled,
                                diagnosis, chat, continuationRequest, taskPlan, history, true);
                        if (recovery.stop() != null) return recovery.stop();
                        if (recovery.snapshot() != null) {
                            metrics.recovery();
                            snap = recovery.snapshot();
                            todoProjector.project(sessionId, snap);
                            continue;
                        }
                        unfinishedReason = "CANCELLED_DEPENDENCY_RESUME_UNAVAILABLE";
                        unfinishedDetail = "未完成步骤依赖一个已取消的前置步骤，且本次无法生成可验证的恢复计划。";
                        log.warn("取消依赖续跑没有可用的恢复计划 session={} node={} dependency={}",
                                sessionId, blocked.node().id(), cancelled.id());
                        break;
                    }
                    unfinishedReason = "DEPENDENCY_FAILED";
                    unfinishedDetail = "后续步骤「" + blocked.node().name() + "」因前置步骤「"
                            + blocked.dependency().name() + "」未通过验收而保留待处理。";
                    log.warn("PlanningLoop 后续节点被取消依赖阻塞 session={} node={} dependency={} dependencyStatus={}",
                            sessionId, blocked.node().id(), blocked.dependency().id(),
                            blocked.dependency().status());
                    break;
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

            boolean boundCodeWrite = proposal.actions().stream().anyMatch(PlanningLoop::isCodeFileWriteAction);
            boolean boundShellAction = proposal.actions().stream()
                    .anyMatch(action -> "exec_command".equals(action.tool()));
            if ((boundCodeWrite || boundShellAction) && codeReviewBaseline == null
                    && codeDiffReviewService != null) {
                // Capture before any direct bound action can write files.
                codeReviewBaseline = codeDiffReviewService.captureForSession(
                        sessionId, codeReviewScopePaths(snap));
            }
            if (boundCodeWrite) codeReviewRequired = true;

            snap = markRunning(sessionId, snap, proposal);
            if (!ownsRunningProposal(snap, proposal)) {
                metrics.casConflict();
                log.info("丢弃过期 ActionProposal session={} proposal={} stateVersion={} planVersion={}",
                        sessionId, proposal.proposalId(), snap.version(), snap.planVersion());
                continue;
            }
            todoProjector.project(sessionId, snap);
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
                    snap.version(), sessionId, streamSink, nodeProgressGuards);
            if (boundShellAction && !codeReviewRequired) {
                if (codeDiffReviewService == null
                        || codeDiffReviewService.hasChangedCodeArtifact(codeReviewBaseline)) {
                    codeReviewRequired = true;
                } else {
                    codeDiffReviewService.discardSessionBaseline(sessionId, codeReviewBaseline);
                    codeReviewBaseline = null;
                }
            }
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
            boolean noProgressAbort = false;
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
                boolean semanticAcceptance = node.doneWhen() != null
                        && (node.doneWhen().isJudge()
                            || !node.doneWhen().semanticChecks().isEmpty());
                int expectedTodoId = semanticAcceptance
                        ? todoProjector.todoIdFor(g, node.id()) : 0;
                // The persisted todo record is the authority. The conversation
                // copy of a large submit receipt may have been clamped to the
                // context budget, so grading must not depend on re-parsing it.
                String submittedEvidence = expectedTodoId <= 0 ? ""
                        : todoProjector.submittedEvidence(sessionId, expectedTodoId);
                NodeOutputBinder.Bound bound = NodeOutputBinder.bind(
                        node, "", step.messages(), judgeAnswer, step.structuredResult(),
                        predecessorOutputs(g, proposal), expectedTodoId, submittedEvidence);
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
                com.miniagent.agent.todo.AcceptanceFeedback feedback = ev.feedback();
                boolean repairingEvidence = EvidenceRepairPolicy.active(snap, node.id());
                if (!ev.ok() && feedback != null
                        && (feedback.repairableEvidence() || feedback.infrastructureFailure())) {
                    Map<String, Object> admission = feedback.repairableEvidence()
                            ? EvidenceRepairPolicy.admission(snap, node, feedback, bound.evidence(),
                                    evidenceRepairPaths(snap, node)) : Map.of();
                    if (!admission.isEmpty()) {
                        // The same node enters a restricted collection phase. Its product action,
                        // completed dependencies, bindings, and original receipt remain intact.
                        TaskNode pendingRepair = node.withStatus(TaskNodeStatus.PENDING).withToolStatus(null)
                                .withError(feedback.legacyError()).withOutput(bound.evidence(), bound.bindings());
                        StateSnapshot repairing = EvidenceRepairPolicy.put(snap.withGraph(g.replace(pendingRepair)),
                                node.id(), admission);
                        snap = stateStore.commit(sessionId, snap.version(), repairing);
                        g = snap.graph();
                        anyFail = true;
                        acceptedAnswer = null;
                        nodeProgressGuards.remove(progressGuardKey(proposal.basedOnPlanVersion(), node.id()));
                        trace(sessionId, AgentStepNode.GRAPH_UPDATED,
                                "{\"acceptanceRepair\":true,\"nodeId\":\"" + jsonEscape(node.id())
                                        + "\",\"attempt\":" + admission.get("attempts")
                                        + ",\"criterionId\":\"" + feedback.criterionId() + "\"}");
                        continue;
                    }
                    Map<String, Object> blocked = new LinkedHashMap<>(EvidenceRepairPolicy.state(snap, node.id()));
                    blocked.put("phase", feedback.infrastructureFailure() ? "JUDGE_BLOCKED" : "EXHAUSTED");
                    blocked.put("feedback", PlannerStateJson.MAPPER.convertValue(feedback, Map.class));
                    String reason = feedback.infrastructureFailure() ? "验收服务尚不可用，已保留产物和执行回执，等待恢复后重新验收。"
                            : "只读补证据未产生可验收进展或预算已用尽，已保留产物和缺失项，需要处理后继续。";
                    if (feedback.infrastructureFailure()) {
                        TaskNode waiting = node.withStatus(TaskNodeStatus.AWAITING_CONFIRM).withToolStatus(null)
                                .withError(reason + " " + feedback.reason()).withOutput(bound.evidence(), bound.bindings());
                        snap = stateStore.commit(sessionId, snap.version(), EvidenceRepairPolicy.put(
                                snap.withGraph(g.replace(waiting)), node.id(), blocked));
                        todoProjector.project(sessionId, snap);
                        return RunOutcome.blockedOnHuman(reason + "\n" + feedback.legacyError(),
                                "ACCEPTANCE_JUDGE_UNAVAILABLE");
                    }
                    // Exhausting a collection strategy is input for a new strategy,
                    // not an instruction to ask the user to press Continue forever.
                    snap = EvidenceRepairPolicy.put(snap, node.id(), blocked);
                }
                if (repairingEvidence && !ev.ok()) {
                    // A collection failure never replays the original write action through generic recovery.
                    Map<String, Object> blocked = new LinkedHashMap<>(EvidenceRepairPolicy.state(snap, node.id()));
                    blocked.put("phase", "BLOCKED");
                    snap = EvidenceRepairPolicy.put(snap, node.id(), blocked);
                }
                if (repairingEvidence && ev.ok()) {
                    Map<String, Object> complete = new LinkedHashMap<>(EvidenceRepairPolicy.state(snap, node.id()));
                    complete.put("phase", "VERIFIED");
                    complete.put("verifiedEvidence", clip(bound.evidence(), 12000));
                    snap = EvidenceRepairPolicy.put(snap, node.id(), complete);
                }
                boolean noProgress = AgentLoop.NO_PROGRESS.equals(step.endReason());
                TaskNodeStatus next = noProgress ? TaskNodeStatus.FAILED
                        : statusAfterChunk(ev.ok(), quotaAbort);
                if (next == TaskNodeStatus.SUCCESS) {
                    nodeProgressGuards.remove(progressGuardKey(proposal.basedOnPlanVersion(), node.id()));
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
                    String failureReason = AgentLoop.NO_PROGRESS.equals(step.endReason())
                            ? clip(step.text(), AWAITING_HINT_CHARS) : ev.reason();
                    g = g.replace(node.withStatus(TaskNodeStatus.FAILED)
                            .withToolStatus(step.structuredResult() == null
                                    ? null : step.structuredResult().status()).withError(failureReason)
                            .withOutput(bound.evidence(), bound.bindings()));
                    FailureDiagnosis dx = feedback != null
                            ? recoveryEngine.diagnoseAcceptance(node, diagnoseTool(node, step.lastTool()), feedback)
                            : semanticAcceptance && !noProgress
                            ? recoveryEngine.diagnoseSemanticFailure(node, diagnoseTool(node, step.lastTool()), failureReason)
                            : recoveryEngine.diagnose(node, diagnoseTool(node, step.lastTool()), failureReason,
                                    step.lastErrorCode());
                    StateSnapshot failedSnap = rememberAcceptanceFeedback(snap.withGraph(g), node, feedback);
                    try {
                        snap = commitNodeOutcomes(sessionId, fencingToken, snap, failedSnap, List.of(
                                new NodeOutcome(action.actionId(), node, NodeTerminalCommitPort.Result.FAILURE,
                                        failureDetail(step.lastErrorCode(), failureReason, userMessage))));
                    } catch (PlannerStateStore.VersionConflictException e) {
                        metrics.casConflict();
                        log.warn("状态冲突，触发 replan: {}", e.getMessage());
                        break;
                    }
                    if (AgentLoop.NO_PROGRESS.equals(step.endReason())) {
                        // A repeated tool/result fingerprint is not a normal execution
                        // failure to feed back into the same model loop. Persist it as a
                        // failed node and stop this graph run; a later explicit user retry
                        // starts a fresh, inspectable attempt.
                        unfinishedReason = AgentLoop.NO_PROGRESS;
                        unfinishedDetail = failureReason;
                        noProgressAbort = true;
                        continue;
                    }
                    RecoveryAttempt recovery = applyRecoveryOrCancel(sessionId, fencingToken, snap, node, dx, action.taskId(),
                            chat, userMessage, taskPlan, history);
                    if (recovery.stop() != null) return recovery.stop();
                    snap = recovery.snapshot();
                    g = snap.graph();
                }
            }
            if (noProgressAbort) {
                todoProjector.project(sessionId,
                        stateStore.get(sessionId).map(StateSnapshot::graph).orElse(g));
                break executionLoop;
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
        if (StringUtils.isNotBlank(unfinishedDetail)) {
            unfinishedAnswer += "\n" + unfinishedDetail;
        }
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
                            com.miniagent.agent.core.ExecutionControl.StopReason controlStopReason,
                            boolean unknownOutcome,
                            List<StepEvaluator.CommandReceipt> commandReceipts) {
        StepExec {
            messages = messages == null ? List.of() : List.copyOf(messages);
            controlStopReason = controlStopReason == null
                    ? com.miniagent.agent.core.ExecutionControl.StopReason.NONE : controlStopReason;
            commandReceipts = commandReceipts == null ? List.of() : List.copyOf(commandReceipts);
        }
    }

    /** Only executor waits are consumed by a reply; explicit Todo confirmations stay gated. */
    private StateSnapshot consumeHumanWait(String sessionId, StateSnapshot snapshot) {
        // A typed confirmation already released its exact node under CAS. It is
        // not a blanket approval for other nodes waiting in the same graph.
        if (com.miniagent.agent.core.GoalContinuationContext.active()
                && !com.miniagent.agent.core.GoalContinuationContext.unattended()) return snapshot;
        Object pending = snapshot.execution().get(EXECUTION_HUMAN_WAIT);
        if (!(pending instanceof Map<?, ?> wait)
                || !(wait.get("taskIds") instanceof List<?> taskIds)) return snapshot;
        if (RecoveryBudget.EXHAUSTED_REASON.equalsIgnoreCase(String.valueOf(wait.get("reason")))
                && !RecoveryBudget.isNewUserRound(snapshot)) return snapshot;
        TaskGraph graph = snapshot.graph();
        for (Object taskId : taskIds) {
            TaskNode node = taskId instanceof String id ? graph.byId(id) : null;
            if (node != null && node.status() == TaskNodeStatus.AWAITING_CONFIRM)
                graph = graph.replace(node.withStatus(TaskNodeStatus.PENDING).withToolStatus(null));
        }
        Map<String, Object> metadata = new LinkedHashMap<>(snapshot.execution());
        metadata.remove(EXECUTION_HUMAN_WAIT);
        metadata.remove("recoveryBudgetBlocked");
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
        boolean unknown = hasUnknownToolOutcome(step.structuredResult(), step.toolAttempted(),
                step.controlStopReason(), step.unknownOutcome());
        for (ActionSpec action : proposal.actions()) {
            TaskNode node = graph.byId(action.taskId());
            if (node == null) continue;
            NodeOutputBinder.Bound evidence = NodeOutputBinder.bind(
                    node, "", step.messages(), "", step.structuredResult());
            TaskNode stopped = node.withStatus(unknown ? TaskNodeStatus.OUTCOME_UNKNOWN : TaskNodeStatus.PENDING)
                    .withToolStatus(unknown ? com.miniagent.agent.tool.ToolStatus.UNKNOWN : null)
                    .withError(step.endReason() + ": " + clip(step.text(), AWAITING_HINT_CHARS))
                    .withOutput(clip(evidence.evidence(), 12000), clipBindings(evidence.bindings()));
            graph = graph.replace(stopped);
            // A control stop or unknown external effect is not a verified
            // procedure failure. Preserve evidence without training a verdict.
        }
        Map<String, Object> metadata = new LinkedHashMap<>(snapshot.execution());
        metadata.put("stopReason", step.endReason());
        metadata.put("controlStopReason", step.controlStopReason().name());
        metadata.put("stoppedTaskIds", proposal.actions().stream().map(ActionSpec::taskId).toList());
        StateSnapshot stopped = stateStore.commit(sessionId, snapshot.version(), snapshot.withGraph(graph).withExecution(metadata));
        todoProjector.project(sessionId, stopped);
        trace(sessionId, AgentStepNode.ERROR,
                "{\"reason\":\"EXECUTION_CONTROL_STOP\",\"controlStopReason\":\""
                        + step.controlStopReason().name() + "\",\"outcomeUnknown\":" + unknown
                        + ",\"stoppedTaskIds\":[" + proposal.actions().stream()
                        .map(ActionSpec::taskId).map(id -> "\"" + jsonEscape(id) + "\"")
                        .collect(Collectors.joining(",")) + "]}", RunStatus.FAILURE);
        String answer = StringUtils.defaultIfBlank(step.text(), "任务已停止，尚未完成。");
        if (unknown) answer += "\n当前节点已保留执行证据，请先核验实际状态；系统不会自动重放该节点。";
        return RunOutcome.failed(answer, step.endReason());
    }

    static boolean hasUnknownToolOutcome(ToolResult result, boolean toolAttempted,
                                         com.miniagent.agent.core.ExecutionControl.StopReason stopReason,
                                         boolean stickyUnknownOutcome) {
        if (stickyUnknownOutcome) return true;
        if (result != null && result.status() == com.miniagent.agent.tool.ToolStatus.UNKNOWN) return true;
        // A typed control-plane stop is returned before its requested handler starts.
        // Earlier completed calls have receipts; only UNKNOWN means an external side effect
        // may still be in flight or unconfirmed.
        if (stopReason != null && stopReason != com.miniagent.agent.core.ExecutionControl.StopReason.NONE)
            return false;
        return toolAttempted;
    }

    /** 轮次用尽就续跑。file_exists 可能只是写了个开头，不能当「做完了」。 */
    static boolean shouldResumeChunk(String endReason, int chunk, int maxChunks) {
        return (maxChunks <= 0 || chunk < maxChunks)
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
        todoProjector.project(sessionId, snapshot);
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
                                     AgentStreamSink streamSink,
                                     Map<String, ProposalTurnPolicy.ProgressGuard> nodeProgressGuards) {
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
        Set<String> declaredFilePaths = Set.of();
        String requiredCommand = "";
        if (!proposal.actions().isEmpty()) {
            ActionSpec action = proposal.actions().get(0);
            timeoutSec = action.timeoutSeconds();
            TaskNode focusNode = graph == null ? null : graph.byId(action.taskId());
            declaredFilePaths = declaredFilePaths(focusNode);
            if (focusNode != null && focusNode.doneWhen() != null
                    && focusNode.doneWhen().isCommand()) {
                requiredCommand = focusNode.doneWhen().criteria();
            }
        }
        String progressKey = progressGuardKey(proposal.basedOnPlanVersion(), focusTask);
        ProposalTurnPolicy.ProgressGuard progressGuard = nodeProgressGuards.computeIfAbsent(
                progressKey, ignored -> new ProposalTurnPolicy.ProgressGuard(() -> {
                    PlannerStateStore.NodeToolCallReservation reservation = stateStore.reserveNodeToolCall(
                            sessionId, proposal.executionId(), proposal.basedOnPlanVersion(), focusTask,
                            properties.getProposalMaxToolCalls());
                    if (reservation.reserved()) return ProposalTurnPolicy.ProgressGuard.BudgetDecision.allowed();
                    LoopTurnPolicy.StopKind kind = properties.getProposalMaxToolCalls() > 0
                            && reservation.callsUsed() >= properties.getProposalMaxToolCalls()
                            ? LoopTurnPolicy.StopKind.TOOL_BUDGET_EXHAUSTED
                            : LoopTurnPolicy.StopKind.NO_PROGRESS;
                    return ProposalTurnPolicy.ProgressGuard.BudgetDecision.denied(kind, reservation.reason());
                }));
        ProposalTurnPolicy policy = new ProposalTurnPolicy(
                allowed, hard, label, focusTodos, timeoutSec,
                declaredFilePaths, requiredCommand, progressGuard, true);
        StateSnapshot repairSnapshot = stateStore.get(sessionId).orElse(null);
        boolean evidenceRepair = repairSnapshot != null
                && java.util.Objects.equals(proposal.executionId(), repairSnapshot.executionId())
                && proposal.basedOnPlanVersion() == repairSnapshot.planVersion()
                && EvidenceRepairPolicy.active(repairSnapshot, focusTask);
        LoopTurnPolicy effectivePolicy = evidenceRepair ? new EvidenceRepairPolicy(policy,
                EvidenceRepairPolicy.allowedPaths(repairSnapshot, focusTask),
                () -> EvidenceRepairPolicy.reserve(stateStore, sessionId, proposal.executionId(),
                        proposal.basedOnPlanVersion(), focusTask)) : policy;
        if (evidenceRepair) allowed = effectivePolicy.allowedTools();
        LoopTurnContext.set(effectivePolicy);
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
            if (!evidenceRepair && ActionBinder.canDirect(proposal)) {
                StepExec bound = executeBoundAction(proposal.actions().get(0));
                if (bound != null) {
                    return bound;
                }
            }
            String focus = systemPrompt + "\n\n" + focusBlock(proposal, graph, focusTodos, hard);
            if (evidenceRepair) focus += "\n\n" + EvidenceRepairPolicy.prompt(
                    EvidenceRepairPolicy.state(repairSnapshot, focusTask));
            int maxIter = Math.max(2, properties.getProposalMaxIterations());
            if (evidenceRepair) maxIter = Math.min(4, maxIter);
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
                int maxChunks = evidenceRepair ? 1 : properties.getProposalMaxChunks();
                while (shouldResumeChunk(out.endReason(), chunks, maxChunks)) {
                    log.info("循环保护后续跑 chunk={}/{} task={}", chunks + 1,
                            maxChunks <= 0 ? "∞" : maxChunks, focusTask);
                    ArrayList<ChatMessage> live = new ArrayList<>(out.messages());
                    live.add(new SystemMessage(MessageConstants.PLANNER_CONTINUE_HINT));
                    out = nodeExecutor.continueNode(chat, live, userMessage,
                            maxIter, taskPlan, evidenceStream);
                    if (out.lastToolResult() != null) lastToolResult = out.lastToolResult();
                    toolAttempted |= !out.toolsInvoked().isEmpty() || out.lastToolResult() != null;
                    chunks++;
                }
                return new StepExec(out.text(), out.endReason(), effectivePolicy.consumeDrift(),
                        lastInvokedTool(out), lastToolResult == null ? out.lastErrorCode()
                        : lastToolResult.errorCode(), out.messages(), lastToolResult, toolAttempted,
                        out.controlStopReason(),
                        out.unknownOutcome(),
                        toolExecutionReceipts(out.messages()));
            } catch (RuntimeException ex) {
                com.miniagent.agent.core.ModelCallControl.rethrowIfStopped(ex);
                // A ReAct turn may already have dispatched tools before the caller
                // failed. Without a complete result, replay cannot be assumed safe.
                log.warn("节点执行异常，停止自动重试 task={}", focusTask, ex);
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                return new StepExec("节点执行结果未知：" + message, AgentLoop.OUTCOME_UNKNOWN,
                        effectivePolicy.consumeDrift(), "", ToolErrorCode.OUTCOME_UNKNOWN, List.of(), null, true,
                        com.miniagent.agent.core.ExecutionControl.StopReason.NONE, true, List.of());
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
        String policyStop = LoopTurnContext.current().stopReason();
        if (StringUtils.isNotBlank(policyStop)) {
            text = policyStop;
            end = AgentLoop.NO_PROGRESS;
            ok = false;
        }
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
                invocation == null ? com.miniagent.agent.core.ExecutionControl.StopReason.NONE
                        : invocation.controlStopReason(),
                result.status() == com.miniagent.agent.tool.ToolStatus.UNKNOWN,
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

    static String focusBlock(ActionProposal proposal, TaskGraph graph,
                              Set<Integer> focusTodos, boolean hard) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ActionProposal（硬闸门=").append(hard).append("）\n");
        sb.append("- proposalId: ").append(proposal.proposalId()).append('\n');
        sb.append("- basedOnVersion: ").append(proposal.basedOnVersion()).append('\n');
        sb.append("- planVersion: ").append(proposal.basedOnPlanVersion()).append('\n');
        sb.append("- focusTodoIds: ").append(focusTodos).append('\n');
        sb.append(MessageConstants.PLANNER_FOCUS_RULES).append('\n');
        if (hard) {
            sb.append("【本节点范围】只完成下方 ActionProposal 的当前节点和已声明交付物。"
                    + "不得清理、归档、迁移、删除或重命名工作区中其他文件；"
                    + "不得为了绕过验收/审查门禁改变文件基线。"
                    + "文件写入和编辑会在运行时按当前节点声明路径逐次校验；shell 只允许运行本节点声明的验收命令。\n");
        }
        sb.append("本步动作：\n");
        for (ActionSpec a : proposal.actions()) {
            TaskNode n = graph == null ? null : graph.byId(a.taskId());
            String cap = n != null ? n.capability() : a.tool();
            List<String> blocked = n == null ? List.of() : n.blockedTools();
            if (n != null && StringUtils.isNotBlank(n.lastError())) {
                sb.append("上一轮失败上下文（必须先处理，不要盲重试）：")
                        .append(n.lastError()).append('\n');
            }
            String previousEvidence = previousNodeExecutionEvidence(n);
            if (!previousEvidence.isBlank()) {
                sb.append("【此前执行回执（不可视为未执行）】\n")
                        .append(previousEvidence)
                        .append("\n以上记录只用于了解已发生的操作。继续前先用只读方式核验外部状态；"
                                + "无法确认写入、提交或桌面操作结果时必须停下并请求用户核验，"
                                + "不得盲目重放可能已成功的副作用。\n");
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
            Set<String> declaredPaths = declaredFilePaths(n);
            if (!declaredPaths.isEmpty()) sb.append(" declaredFilePaths=").append(declaredPaths);
            if (StringUtils.isNotBlank(a.compensation())) {
                sb.append(" compensation=").append(a.compensation());
            }
            if (!blocked.isEmpty()) {
                sb.append(" blockedTools=").append(blocked);
            }
            sb.append(" expected=").append(a.expectedResult()).append('\n');
            if (a.acceptance().isJudge() || a.acceptance().isValidation()) {
                sb.append("【本轮验收取证】本节点的语义/验证结论必须由本轮实际工具回执支持。"
                        + "验收条件涉及源文件时，请在本节点内重新读取并检查对应文件；"
                        + "此前尝试的记录、对话摘要或 todo.submit 自述不能替代当前回执。"
                        + "每项条件都要在证据中对应到实际观察，读不到时明确说明未验证。\n");
            }
        }
        sb.append(MessageConstants.DAG_EVIDENCE_SUBMISSION).append('\n');
        return sb.toString();
    }

    static String previousNodeExecutionEvidence(TaskNode node) {
        if (node == null || StringUtils.isBlank(node.output())) return "";
        return clip(node.output(), 6_000);
    }

    private static Set<String> declaredFilePaths(TaskNode node) {
        if (node == null) return Set.of();
        Set<String> paths = new LinkedHashSet<>();
        if (node.doneWhen() != null && node.doneWhen().isFile()) {
            addDeclaredFilePath(paths, node.doneWhen().path(), true);
        }
        for (String output : node.outputs()) addDeclaredFilePath(paths, output, false);
        Object boundPath = node.toolArguments().get("path");
        if (boundPath instanceof String path) addDeclaredFilePath(paths, path, true);
        return Set.copyOf(paths);
    }

    /** Evidence collection can observe only the current node's declared files and dependency artifacts. */
    private static Set<String> evidenceRepairPaths(StateSnapshot snapshot, TaskNode node) {
        if (snapshot == null || node == null) return Set.of();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        pending.add(node.id());
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!visited.add(id)) continue;
            TaskNode candidate = snapshot.graph().byId(id);
            if (candidate == null) continue;
            paths.addAll(declaredFilePaths(candidate));
            pending.addAll(candidate.dependsOn());
        }
        return Set.copyOf(paths);
    }

    /** Persist the exact structured acceptance failure alongside the failed node.
     *
     * The feedback is an input to the next recovery decision, not a success
     * receipt and not a new user instruction.  Keep it under the exact plan
     * version/node key so a later task cannot accidentally reuse an old judge
     * verdict.
     */
    private static StateSnapshot rememberAcceptanceFeedback(StateSnapshot snapshot,
                                                             TaskNode node,
                                                             AcceptanceFeedback feedback) {
        if (snapshot == null || node == null || feedback == null) return snapshot;
        Map<String, Object> failures = new LinkedHashMap<>();
        Object existing = snapshot.execution().get(ACCEPTANCE_FAILURES);
        if (existing instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key instanceof String text) failures.put(text, value);
            });
        }
        String key = snapshot.planVersion() + ":" + node.id();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("planVersion", snapshot.planVersion());
        record.put("nodeId", node.id());
        record.put("criterionId", feedback.criterionId());
        record.put("verdict", feedback.verdict().name());
        record.put("kind", feedback.kind().name());
        record.put("nextAction", feedback.nextAction().name());
        record.put("reason", clip(feedback.reason(), 1800));
        record.put("missingEvidence", feedback.missingEvidence().stream().limit(16).map(v -> clip(v, 512)).toList());
        record.put("feedback", PlannerStateJson.MAPPER.convertValue(feedback, Map.class));
        failures.put(key, Map.copyOf(record));
        Map<String, Object> execution = new LinkedHashMap<>(snapshot.execution());
        execution.put(ACCEPTANCE_FAILURES, failures);
        return snapshot.withExecution(execution);
    }

    private static AcceptanceFeedback acceptanceFeedback(StateSnapshot snapshot, String nodeId) {
        Object failures = snapshot.execution().get(ACCEPTANCE_FAILURES);
        Object raw = failures instanceof Map<?, ?> map ? map.get(snapshot.planVersion() + ":" + nodeId) : null;
        Object feedback = raw instanceof Map<?, ?> entry ? entry.get("feedback") : null;
        if (feedback == null) feedback = EvidenceRepairPolicy.state(snapshot, nodeId).get("feedback");
        if (feedback == null) return null;
        try { return PlannerStateJson.MAPPER.convertValue(feedback, AcceptanceFeedback.class); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static void addDeclaredFilePath(Set<String> paths, String candidate, boolean explicitPath) {
        if (candidate == null || candidate.isBlank()) return;
        String path = candidate.trim().replace('\\', '/');
        if (!explicitPath && !path.matches("(?i).*(?:/|\\.[a-z0-9]{1,12})[^/]*$")) return;
        String normalized = ProposalTurnPolicy.normalizeWorkspacePath(path);
        if (!normalized.isBlank()) paths.add(normalized);
    }

    private static boolean hasReviewableCodeDeliverable(StateSnapshot snapshot) {
        return !codeReviewScopePaths(snapshot).isEmpty();
    }

    private static Set<String> codeReviewScopePaths(StateSnapshot snapshot) {
        if (snapshot == null) return Set.of();
        Set<String> paths = new LinkedHashSet<>();
        if (snapshot.intent() != null) {
            snapshot.intent().deliverables().stream().map(TaskIntent.Deliverable::path)
                    .filter(CodeValidationPolicy::isReviewableCodeArtifact).forEach(paths::add);
        }
        if (snapshot.graph() != null) {
            for (TaskNode node : snapshot.graph().nodes()) {
                if (node.doneWhen() != null && node.doneWhen().isFile()
                        && CodeValidationPolicy.isReviewableCodeArtifact(node.doneWhen().path())) {
                    paths.add(node.doneWhen().path());
                }
                node.outputs().stream().filter(CodeValidationPolicy::isReviewableCodeArtifact).forEach(paths::add);
                Object path = node.toolArguments().get("path");
                if (path instanceof String value && CodeValidationPolicy.isReviewableCodeArtifact(value)) {
                    paths.add(value);
                }
            }
        }
        return Set.copyOf(paths);
    }

    private static String progressGuardKey(long planVersion, String taskId) {
        return planVersion + ":" + StringUtils.defaultString(taskId);
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
        todoProjector.project(sessionId, latest);
        log.warn("任务恢复已停止 session={} reason={}", sessionId, reason);
        return new RecoveryAttempt(latest, RunOutcome.failed(
                "任务恢复未通过校验，已保留执行进度并停止自动重试。请查看执行记录后继续。", reason));
    }

    /** Normalize the trusted request round; automatic wakes never mint a new allowance. */
    private StateSnapshot resetRoundRecoveryBudget(String sessionId, StateSnapshot snap) {
        Map<String, Object> exec = RecoveryBudget.prepare(snap);
        if (exec.equals(snap.execution())) return snap;
        try {
            StateSnapshot reset = stateStore.commit(sessionId, snap.version(), snap.withExecution(exec));
            log.info("恢复预算轮次已规范化 session={} round={} used={} total={}", sessionId,
                    exec.get(RecoveryBudget.ROUND_ID),
                    exec.get(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT),
                    exec.get(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL));
            return reset;
        } catch (PlannerStateStore.VersionConflictException conflict) {
            metrics.casConflict();
            return stateStore.get(sessionId).orElse(snap);
        }
    }

    private RecoveryAttempt applyRecoveryOrCancel(String sessionId, String fencingToken, StateSnapshot snap,                                                TaskNode node, FailureDiagnosis dx,
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
        boolean atLimit = RecoveryBudget.roundUsed(snap) >= properties.getMaxRecoveries()
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
        log.warn("Recovery 耗尽，保留节点等待用户继续 task={}", taskId);
        TaskNode latest = snap.graph().byId(node.id());
        if (latest == null) {
            latest = node;
        }
        TaskGraph g = snap.graph().replace(
                latest.withStatus(TaskNodeStatus.AWAITING_CONFIRM).withError(RecoveryBudget.EXHAUSTED_REASON + ": "
                        + "本轮自动恢复额度已耗尽，已保留执行进度；请检查失败原因后明确继续。"));
        Map<String, Object> blockedExecution = RecoveryBudget.prepare(snap);
        blockedExecution.put("recoveryBudgetBlocked", Map.of("reason", RecoveryBudget.EXHAUSTED_REASON,
                "taskId", latest.id(), "roundId", blockedExecution.get(RecoveryBudget.ROUND_ID),
                "roundUsed", RecoveryBudget.roundUsed(snap), "failureClass", dx.failureClass().name()));
        blockedExecution.put(EXECUTION_HUMAN_WAIT,
                Map.of("reason", RecoveryBudget.EXHAUSTED_REASON, "taskIds", List.of(latest.id())));
        try {
            return RecoveryAttempt.applied(stateStore.commit(sessionId, snap.version(),
                    snap.withGraph(g).withExecution(blockedExecution)));
        } catch (PlannerStateStore.VersionConflictException e) {
            metrics.casConflict();
            log.warn("保存恢复等待状态冲突 task={}: {}", taskId, e.getMessage());
            return RecoveryAttempt.applied(stateStore.get(sessionId).orElse(snap));
        }
    }

    private RecoveryAttempt tryLlmReplan(String sessionId, String fencingToken, StateSnapshot snap,
                                                 TaskNode node, FailureDiagnosis dx,
                                                 ChatModel chat, String userMessage,
                                                 TaskPlan taskPlan, List<ChatMessage> history) {
        return tryLlmReplan(sessionId, fencingToken, snap, node, dx, chat,
                userMessage, taskPlan, history, false);
    }

    private RecoveryAttempt tryLlmReplan(String sessionId, String fencingToken, StateSnapshot snap,
                                         TaskNode node, FailureDiagnosis dx,
                                         ChatModel chat, String userMessage,
                                         TaskPlan taskPlan, List<ChatMessage> history,
                                         boolean explicitUserResume) {
        if (chat == null || snap == null || node == null || dx == null) {
            return RecoveryAttempt.notApplicable();
        }
        if (dx.failureClass() != FailureClass.REWRITE_GRAPH
                && dx.failureClass() != FailureClass.REVISE_GOAL) {
            return RecoveryAttempt.notApplicable();
        }
        TaskNode currentFailedNode = snap.graph().byId(node.id());
        if (currentFailedNode == null || currentFailedNode.status() == TaskNodeStatus.OUTCOME_UNKNOWN
                || currentFailedNode.status() == TaskNodeStatus.RUNNING
                || currentFailedNode.toolStatus() == com.miniagent.agent.tool.ToolStatus.UNKNOWN)
            return RecoveryAttempt.notApplicable();
        int used = RecoveryBudget.classUsed(snap, dx.failureClass());
        if (!replanBudgetAvailable(RecoveryBudget.roundUsed(snap), properties.getMaxRecoveries(),
                used, recoveryEngine.classLimit(dx.failureClass()), dx.kind(), explicitUserResume)) {
            return RecoveryAttempt.notApplicable();
        }
        // Semantic failures must change the execution strategy. A structured
        // model decision separates absent observations from actual defects;
        // no language-specific error matching decides the next business action.
        FailureRecoveryPlanner.Decision decision = null;
        if (dx.kind() == FailureKind.EVAL_FAILED) {
            ChatModel recoveryChat = goalCompiler.resolvePlannerChat(chat);
            ActionProposal observation = graphScheduler.propose(snap, List.of(currentFailedNode), 1);
            List<String> available = toolRouter.allowedFor(observation, snap.graph(), properties.isHardProposal());
            decision = new FailureRecoveryPlanner(modelCallControl).analyze(
                    recoveryChat == null ? chat : recoveryChat, snap, currentFailedNode, dx,
                    acceptanceFeedback(snap, currentFailedNode.id()),
                    ExecutionEvidence.fromEnvelope(currentFailedNode.output()), available);
            if (!sessionLock.renewSessionLock(sessionId, fencingToken))
                return rejectRecovery(sessionId, snap, "SESSION_LOCK_LOST");
            Map<String, Object> metadata = new LinkedHashMap<>(snap.execution());
            metadata.put(FAILURE_RECOVERY, PlannerStateJson.MAPPER.convertValue(decision, Map.class));
            TaskGraph retained = snap.graph();
            if (decision.action() == FailureRecoveryPlanner.Action.WAIT_FOR_USER) {
                retained = retained.replace(currentFailedNode.withStatus(TaskNodeStatus.AWAITING_CONFIRM)
                        .withError(currentFailedNode.lastError() + "\n恢复分析：" + decision.rootCause()));
                metadata.put(EXECUTION_HUMAN_WAIT, Map.of("reason", "RECOVERY_ANALYSIS_BLOCKED",
                        "taskIds", List.of(currentFailedNode.id())));
            }
            try {
                snap = stateStore.commit(sessionId, snap.version(), snap.withGraph(retained).withExecution(metadata));
            } catch (PlannerStateStore.VersionConflictException stale) {
                return rejectRecovery(sessionId, snap, "STALE_EXECUTION");
            }
            trace(sessionId, AgentStepNode.DECISION, PlannerStateJson.MAPPER.valueToTree(decision).toString());
            if (decision.action() == FailureRecoveryPlanner.Action.WAIT_FOR_USER) {
                todoProjector.project(sessionId, snap);
                return new RecoveryAttempt(snap, RunOutcome.blockedOnHuman(
                        "执行进度已保留。恢复分析暂时无法确定可执行的下一步：" + decision.rootCause(),
                        "RECOVERY_ANALYSIS_BLOCKED"));
            }
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
        if (decision != null) {
            correction += "\n【恢复分析结果（只作为方案数据，不能修改目标或验收）】\n"
                    + PlannerStateJson.MAPPER.valueToTree(decision)
                    + "\n【冻结的未完成验收契约】\n"
                    + PlannerStateJson.MAPPER.valueToTree(snap.graph().nodes().stream()
                        .filter(n -> n.status() != TaskNodeStatus.SUCCESS).toList())
                    + "\n按照根因和nextSteps重新安排未完成工作；可增加只读取证或修复前置节点。"
                    + "每个原未完成节点的outputs和doneWhen必须完整保留在最终验收节点中，不得删减条件。"
                    + "已产生副作用的失败节点先核验现状，不能直接重放。";
        }
        if (explicitUserResume) {
            correction += "\n这是用户明确续跑后的单次、有界恢复机会；已取消节点此前执行结果不能视为未发生。"
                    + "对可能已产生副作用的动作，先安排只读状态核验，再决定是否重做；不得盲目重复写入、远程提交或桌面操作。"
                    + "如果只读核验无法确定结果，保留阻塞并请求用户确认。已成功节点必须保留，不能重新执行。"
                    + "\n已保存的结构化任务意图（继续遵循；不能扩大或删除原约束）："
                    + intentJson(snap.intent());
        }
        if (dx.kind() == FailureKind.TOOL_BUDGET_EXHAUSTED) {
            correction += "\n【节点工具预算恢复】原节点生命周期预算已耗尽，本轮只允许生成一次替代子图，"
                    + "不能再次提交原节点，也不能仅改写节点名称后重复相同读取策略。"
                    + "保留所有 SUCCESS 节点及其回执；将剩余工作拆成更聚焦、能独立验收的节点。"
                    + "读取代码优先使用 search_code 定位所需逻辑，再读取准确路径；不得拼接 workspace 子目录猜路径。"
                    + "大文件按实际返回的行数使用不重叠范围分段，不能猜测文件偏移或越过文件末尾。"
                    + "新子图仍须通过完整 PlanValidator 和意图校验，不能降低原验收要求。";
        }
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
        PlanValidationReport validation = mergeValidationReports(
                planValidator.validate(merged, recoveryPlan, compiled.goal(), compiled.intent()),
                intentGraphValidator.validate(compiled.intent(), merged));
        if (decision != null && !waiting) validation = mergeValidationReports(validation,
                mergeValidationReports(RecoveryPlanContract.validate(snap, merged),
                        intentGraphValidator.validate(snap.intent(), merged)));
        TaskGraph rejectedGraph = compiled.graph();
        int maxReplanRetries = Math.max(0, properties.getMaxReplanRetries());
        int correctionRetries = 0;
        for (int retry = 1; !validation.valid() && retry <= maxReplanRetries; retry++) {
            correctionRetries = retry;
            PlanValidationReport previousValidation = validation;
            log.warn("运行时 LLM replan 图验收失败，提交校正重试 session={} retry={}/{} errors={}",
                    sessionId, retry, maxReplanRetries, clip(validation.summary(), 1800));
            String correctionPrompt = correction + "\n\n" + validation.toCorrectionPrompt();
            try {
                compiled = goalCompiler.compileWithCorrection(chat, userMessage, recoveryPlan, correctionPrompt,
                        history == null ? List.of() : history, rejectedGraph);
            } catch (Exception e) {
                com.miniagent.agent.core.ModelCallControl.rethrowIfStopped(e);
                log.warn("运行时 LLM replan 校正编译失败 session={} retry={}/{}: {}",
                        sessionId, retry, maxReplanRetries, e.getMessage());
                return rejectRecovery(sessionId, snap, "REPLAN_COMPILATION_FAILED");
            }
            if (compiled == null || compiled.degradedCompilation()
                    || compiled.graph() == null || compiled.graph().isEmpty()) {
                return rejectRecovery(sessionId, snap, "REPLAN_COMPILATION_INVALID");
            }
            waiting = compiled.intent().needsClarification();
            merged = waiting ? compiled.graph()
                    : mergeKeepSuccess(snap.graph(), compiled.graph(), snap.recoveryCount() + 1);
            if (merged == null || merged.isEmpty()) {
                return rejectRecovery(sessionId, snap, "REPLAN_IDENTITY_CONFLICT");
            }
            validation = mergeValidationReports(
                    planValidator.validate(merged, recoveryPlan, compiled.goal(), compiled.intent()),
                    intentGraphValidator.validate(compiled.intent(), merged));
            if (decision != null && !waiting) validation = mergeValidationReports(validation,
                    mergeValidationReports(RecoveryPlanContract.validate(snap, merged),
                            intentGraphValidator.validate(snap.intent(), merged)));
            if (!validation.valid()) {
                rejectedGraph = compiled.graph();
                if (!hasStrictValidationProgress(previousValidation, validation,
                        rejectedGraph != null && !rejectedGraph.isEmpty())) {
                    log.warn("运行时 LLM replan 无校验进展，停止继续重试 session={} previousErrors={} currentErrors={} graphUsable={}",
                            sessionId, previousValidation.errors().size(), validation.errors().size(),
                            rejectedGraph != null && !rejectedGraph.isEmpty());
                    break;
                }
            }
        }
        if (!validation.valid()) {
            trace(sessionId, AgentStepNode.GOAL_COMPILED,
                    recoveryValidationTrace(validation, correctionRetries), RunStatus.FAILURE);
            log.warn("运行时 LLM replan 校正停止，保留原执行图 session={} retries={} configuredLimit={} errors={}",
                    sessionId, correctionRetries, maxReplanRetries, clip(validation.summary(), 1800));
            return rejectRecovery(sessionId, snap, "REPLAN_VALIDATION_FAILED");
        }
        Goal nextGoal = decision != null && !waiting ? snap.goal()
                : compiled.goal() != null ? compiled.goal() : snap.goal();
        boolean reservedExplicitResume = explicitUserResume && (dx.kind() == FailureKind.DEPENDENCY_FAILED
                || dx.kind() == FailureKind.TOOL_BUDGET_EXHAUSTED);
        Map<String, Object> exec = RecoveryBudget.consume(snap, dx.failureClass(), properties.getMaxRecoveries(),
                recoveryEngine.classLimit(dx.failureClass()), reservedExplicitResume);
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
        exec.put("lastFailureKind", dx.kind().name());
        StateSnapshot patched = snap.revisePlan(merged, nextGoal,
                        decision != null && !waiting ? snap.intent() : compiled.intent(),
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
                            "tool", dx.tool(), "llmReplan", true,
                            "explicitUserResume", explicitUserResume),
                    null);
            StateSnapshot committed = stateStore.commit(sessionId, snap.version(), patched,
                    List.of(recoveryEvent));
            // 规则恢复走 RecoveryEngine.traceRecovery 打点；模型重规划这条路原来只写日志，
            // 页面上就和"普通一次编译"没区别。这里补同一种事件，前端才能标出"重规划"。
            AgentStepNode replanNode = dx.failureClass() == FailureClass.REVISE_GOAL
                    ? AgentStepNode.RECOVERY_REVISE_GOAL : AgentStepNode.RECOVERY_REWRITE_GRAPH;
            String replanTrace = "{\"taskId\":\"" + dx.taskId()
                    + "\",\"taskName\":\"" + jsonEscape(node.name()) + "\""
                    + ",\"kind\":\"" + dx.kind().name()
                    + "\",\"tool\":\"" + jsonEscape(dx.tool())
                    + "\",\"reason\":\"" + jsonEscape(dx.reason()) + "\""
                    + ",\"llmReplan\":true,\"explicitUserResume\":" + explicitUserResume
                    + ",\"generation\":" + (snap.recoveryCount() + 1) + "}";
            trace(sessionId, replanNode, replanTrace);
            log.info("运行时 LLM replan 已提交 session={} nodes={}",
                    sessionId, committed.graph().nodes().size());
            return RecoveryAttempt.applied(committed);
        } catch (PlannerStateStore.VersionConflictException e) {
            metrics.casConflict();
            return rejectRecovery(sessionId, snap, "STALE_EXECUTION");
        }
    }

    static String recoveryValidationTrace(PlanValidationReport validation, int retries) {
        var detail = PlannerStateJson.MAPPER.createObjectNode();
        detail.put("accepted", false);
        detail.put("reason", "REPLAN_VALIDATION_FAILED");
        detail.put("correctionRetries", retries);
        detail.put("summary", clip(validation == null ? "missing validation report" : validation.summary(), 1800));
        if (validation != null) {
            var errors = detail.putArray("errors");
            validation.errors().stream().limit(6).forEach(error -> {
                var item = errors.addObject();
                item.put("type", error.type().name());
                item.put("nodeId", clip(error.nodeId(), 120));
                item.put("message", clip(error.message(), 240));
                item.put("suggestion", clip(error.suggestion(), 200));
            });
            detail.put("omittedErrors", Math.max(0, validation.errors().size() - 6));
            var warnings = detail.putArray("warnings");
            validation.warnings().stream().limit(6).forEach(warning -> {
                var item = warnings.addObject();
                item.put("type", warning.type().name());
                item.put("nodeId", clip(warning.nodeId(), 120));
                item.put("message", clip(warning.message(), 240));
            });
            detail.put("omittedWarnings", Math.max(0, validation.warnings().size() - 6));
        }
        return detail.toString();
    }

    static boolean shouldAttemptCancelledDependencyResume(boolean explicitResume,
                                                          boolean alreadyAttempted,
                                                          TaskGraph graph) {
        return explicitResume && !alreadyAttempted
                && firstPendingBlockedByCancelledDependency(graph) != null;
    }

    static boolean replanBudgetAvailable(int roundRecoveries, int maxRecoveries,
                                         int classRecoveries, int classLimit,
                                         FailureKind failureKind,
                                         boolean explicitUserResume) {
        // Explicit continuation recovery has its own durable, task-scoped budget.
        // Do not let automatic-recovery counters consume that separately reserved allowance.
        if (explicitUserResume && (failureKind == FailureKind.DEPENDENCY_FAILED
                || failureKind == FailureKind.TOOL_BUDGET_EXHAUSTED)) return true;
        return RecoveryBudget.available(roundRecoveries, maxRecoveries, classRecoveries, classLimit);
    }

    static String cancelledDependencyResumeRequest(StateSnapshot snapshot, String userMessage) {
        String objective = snapshot == null || snapshot.goal() == null
                ? "" : StringUtils.trimToEmpty(snapshot.goal().objective());
        String current = StringUtils.trimToEmpty(userMessage);
        StringBuilder request = new StringBuilder();
        if (!objective.isBlank()) request.append("原始任务目标：\n").append(objective).append('\n');
        request.append("用户本次明确要求继续未完成任务");
        if (!current.isBlank() && !current.equals(objective)) {
            request.append("；本次补充指示：").append(current);
        }
        request.append("。只恢复未完成部分，遵循已保存意图与原始约束；已成功节点不得重做。");
        return request.toString();
    }

    /**
     * Extra validator replans are useful only while the candidate graph is validly
     * parsed and strictly reduces the outstanding error count. A plateau, regression,
     * or empty graph stops early instead of spending the configured budget blindly.
     */
    static boolean hasStrictValidationProgress(PlanValidationReport previous,
                                               PlanValidationReport current,
                                               boolean candidateGraphUsable) {
        return candidateGraphUsable && previous != null && current != null && !current.valid()
                && current.errors().size() < previous.errors().size();
    }

    static String validationFailureReason(int retries, int retryLimit,
                                          String initialValidation, String finalValidation) {
        return "PLAN_VALIDATION_FAILED: retries=" + retries + "/" + retryLimit
                + "; initial=" + clip(initialValidation, 1800)
                + "; finalReplan=" + clip(finalValidation, 1800);
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

    private record DependencyBlock(TaskNode node, TaskNode dependency) {}

    /** Do not spend recovery attempts cancelling descendants of an already-cancelled prerequisite. */
    private static DependencyBlock firstPendingBlockedByCancelledDependency(TaskGraph graph) {
        if (graph == null) return null;
        for (TaskNode node : graph.nodes()) {
            if (node.status() != TaskNodeStatus.PENDING) continue;
            for (String dependencyId : node.dependsOn()) {
                TaskNode dependency = graph.byId(dependencyId);
                if (dependency != null && dependency.status() == TaskNodeStatus.CANCELLED) {
                    return new DependencyBlock(node, dependency);
                }
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

    private TaskNode exhaustedToolBudgetNode(StateSnapshot snapshot) {
        if (snapshot == null || snapshot.graph() == null) return null;
        int maximum = properties.getProposalMaxToolCalls();
        if (maximum <= 0) return null;
        for (TaskNode node : snapshot.graph().nodes()) {
            if (node == null || (node.status() != TaskNodeStatus.FAILED
                    && node.status() != TaskNodeStatus.READY
                    && node.status() != TaskNodeStatus.PENDING)) continue;
            if (stateStore.nodeToolCallsUsed(snapshot, node.id()) >= maximum) return node;
        }
        return null;
    }

    private static String toolBudgetRecoveryBlockedMessage(TaskNode node, int callsUsed,
                                                            int maximum, String detail) {
        String name = node == null || StringUtils.isBlank(node.name()) ? "失败节点" : node.name();
        return "节点「" + name + "」已用完工具预算（" + callsUsed + "/" + maximum
                + "）。系统没有继续派发旧节点，以免重复空转；" + StringUtils.defaultIfBlank(detail, "安全重规划未完成")
                + "。原有已验收进度已保留，请调整该步骤目标或验收要求后再继续。";
    }

    private static String toolBudgetRecoveryTrace(TaskNode node, int callsUsed,
                                                  int maximum, String reason) {
        return "{\"reason\":\"TOOL_BUDGET_RECOVERY_STOPPED\",\"nodeId\":\""
                + jsonEscape(node == null ? "" : node.id()) + "\",\"nodeName\":\""
                + jsonEscape(node == null ? "" : node.name()) + "\",\"callsUsed\":"
                + Math.max(0, callsUsed) + ",\"maxCalls\":" + Math.max(1, maximum)
                + ",\"detail\":\"" + jsonEscape(reason) + "\"}";
    }

    /** A selected DAG cannot execute until compilation and validation succeed. */
    private RunOutcome invalidPlanOutcome(String sessionId, GoalCompiler.CompileResult compiled) {
        trace(sessionId, AgentStepNode.GOAL_COMPILED, invalidPlanTrace(compiled), RunStatus.FAILURE);
        log.warn("PlanningLoop 任务图未通过校验，停止本轮执行 session={} source={} code={}",
                sessionId, compiled.source(), ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getCode());
        return RunOutcome.failed("任务计划生成失败，尚未开始操作。请重试；原有任务进度已保留。",
                "PLAN_VALIDATION_FAILED");
    }

    static String invalidPlanTrace(GoalCompiler.CompileResult compiled) {
        var detail = PlannerStateJson.MAPPER.createObjectNode();
        detail.put("nodes", compiled == null || compiled.graph() == null ? 0 : compiled.graph().nodes().size());
        detail.put("accepted", false);
        detail.put("source", compiled == null ? "UNKNOWN" : compiled.source().name());
        detail.put("reason", "PLAN_VALIDATION_FAILED");
        String diagnostic = compiled == null ? "missing compile result"
                : TraceDataSanitizer.sanitizeText(
                        com.miniagent.common.SecurityUtils.redactSensitive(compiled.fallbackReason()));
        detail.put("validatorDiagnostic", clip(diagnostic, 6_000));
        return detail.toString();
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
        log.warn("PlanValidator 拒绝，进入 replan 闭环，错误数={} summary={}",
                report.errors().size(), clip(initialValidation, 1800));
        TaskGraph rejectedGraph = compiled.graph();

        // 有界 replan：只有校验错误数严格下降才继续消耗下一次预算。
        int maxReplanRetries = Math.max(0, properties.getMaxReplanRetries());
        int replanAttempts = 0;
        for (int attempt = 1; attempt <= maxReplanRetries; attempt++) {
            replanAttempts = attempt;
            PlanValidationReport previousReport = report;
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

            log.warn("Replan 尝试 {} 仍然失败，错误数={} summary={}",
                    attempt, report.errors().size(), clip(report.summary(), 1800));
            if (replanned.graph() != null && !replanned.graph().isEmpty()) {
                rejectedGraph = replanned.graph();
            }
            if (!hasStrictValidationProgress(previousReport, report,
                    replanned.graph() != null && !replanned.graph().isEmpty())) {
                log.warn("Replan 无校验进展，停止继续重试 errors={} -> {} graphUsable={}",
                        previousReport.errors().size(), report.errors().size(),
                        replanned.graph() != null && !replanned.graph().isEmpty());
                break;
            }
        }

        // 到达重试上限或校验进展停止。不再构造规则猜测图：
        // 不是模型对本次请求的理解，继续执行等于把「结构合法」当成「语义正确」，
        // 正是分项任务出错的主要来源。这里返回空图结果，调用方据此拒绝调度。
        String fallbackReason = validationFailureReason(
                replanAttempts, maxReplanRetries, initialValidation, report.summary());
        log.warn("Replan 停止，拒绝调度（不降级模板）code={} retries={}/{} errors={}",
                ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getCode(), replanAttempts, maxReplanRetries,
                report.errors().size());
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

    private static boolean isCodeFileWriteAction(ActionSpec action) {
        if (action == null || !("write_file".equals(action.tool()) || "edit_file".equals(action.tool())))
            return false;
        Object path = action.arguments().get("path");
        return path != null && CodeValidationPolicy.isReviewableCodeArtifact(String.valueOf(path));
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
    static String buildContinueTaskSummary(StateSnapshot snap, String userMessage) {
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
            long cancelledNodes = snap.graph().nodes().stream()
                    .filter(n -> n.status() == TaskNodeStatus.CANCELLED
                            && n.toolStatus() != com.miniagent.agent.tool.ToolStatus.UNKNOWN)
                    .count();
            long unknownNodes = snap.graph().nodes().stream()
                    .filter(n -> n.status() == TaskNodeStatus.OUTCOME_UNKNOWN
                            || n.toolStatus() == com.miniagent.agent.tool.ToolStatus.UNKNOWN)
                    .count();
            long pendingNodes = totalNodes - completedNodes - failedNodes - cancelledNodes - unknownNodes;

            sb.append("任务进度：").append(completedNodes).append("/").append(totalNodes).append(" 已完成");
            if (failedNodes > 0) {
                sb.append("，").append(failedNodes).append(" 个失败");
            }
            if (cancelledNodes > 0) {
                sb.append("，").append(cancelledNodes).append(" 个因恢复耗尽取消（继续时需重新规划）");
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
        boolean hasCancelled = snap.graph().nodes().stream()
                .anyMatch(n -> n.status() == TaskNodeStatus.CANCELLED);
        boolean hasRunning = snap.graph().nodes().stream()
                .anyMatch(n -> n.status() == TaskNodeStatus.RUNNING);

        if (unknownOutcomeNode(snap.graph()) != null) {
            return "上一个任务存在执行结果未知的步骤，核验实际状态前不会自动重试";
        } else if (allSuccess) {
            return "上一个任务已全部完成";
        } else if (hasFailed) {
            return "上一个任务存在失败步骤，将重新尝试执行";
        } else if (hasCancelled) {
            return "上一个任务有步骤因恢复耗尽被取消；继续时先重规划受阻部分，不会直接跳过依赖";
        } else if (hasRunning) {
            return "上一个任务正在执行中，将继续执行";
        } else {
            return "上一个任务未完成，将继续执行";
        }
    }
}
