package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.agent.todo.HumanYield;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.RunStatus;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * User → Compiler → Validator → Scheduler → Executor → ToolRouter → Tool
 * → StepEvaluator → GraphEval → Continue | Retry/Replan。
 */
@Component
public class PlanningLoop {

    private static final Logger log = LoggerFactory.getLogger(PlanningLoop.class);
    private static final int PRIOR_OUTPUT_CHARS = 6000;
    private static final int AWAITING_HINT_CHARS = 500;

    private final PlannerProperties properties;
    private final GoalCompiler goalCompiler;
    private final PlanValidator planValidator;
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

    /**
     * 本轮要不要交给规划器（编译任务图 + 调度执行）。
     *
     * <p>判据只有三类，全部来自事实：</p>
     * <ol>
     *   <li>纯问答轮（问且无任何动手信号）直接不进；</li>
     *   <li>需要结构化计划，或消息里出现 URL / 文件名（可编成可调度图）；</li>
     *   <li>上一轮留下了未完成的图，且本轮文本确实是在接着做。</li>
     * </ol>
     *
     * <p>注意第 3 条必须能真正走到。旧实现先按意图白名单短路返回，
     * 而绝大多数意图都在该白名单里，于是「续跑」分支实际不可达 ——
     * 用户回一句「继续」永远进不来规划器。这里不再有白名单，
     * 恢复与否只由 {@link PlannerResumePolicy} 按文本与图当下的
     * {@code AWAITING_CONFIRM} 状态共同判定：用户明说继续，或系统此刻确实在等用户答复。
     * 「历史上曾经等过用户」不算理由，否则新任务会被旧图接管。</p>
     */
    public boolean shouldHandle(TaskPlan plan, String sessionId, String requestText) {
        if (!properties.isEnabled() || plan == null) {
            return false;
        }
        TaskSignals signals = plan.signals();
        if (signals.lightTurn()) {
            return false;
        }
        if (plan.requiresStructuredPlan()
                || DecompositionPolicy.hasGraphSignal(requestText, plan)) {
            return true;
        }
        return stateStore.hasIncompleteGraph(sessionId)
                && PlannerResumePolicy.shouldResume(
                signals, requestText, hasAwaitingGraph(sessionId), true);
    }

    static boolean shouldRecompileClarify(Goal goal, TaskGraph graph, String userMessage) {
        if (StringUtils.isBlank(userMessage)
                || HumanYield.looksLikeBareContinue(userMessage)) {
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
        return hasAwaitingGraph(sessionId);
    }

    private boolean hasAwaitingGraph(String sessionId) {
        return stateStore.get(sessionId)
                .map(s -> s.graph() != null && s.graph().hasAwaitingConfirm())
                .orElse(false);
    }

    public String run(ChatModel chat,
                      String systemPrompt,
                      String userMessage,
                      UserMessage multimodalUser,
                      List<ChatMessage> history,
                      TaskPlan taskPlan,
                      String sessionId,
                      String executionId,
                      Consumer<String> progress,
                      AgentStreamSink streamSink) {
        if (!shouldHandle(taskPlan, sessionId, userMessage)) {
            if (multimodalUser != null) {
                return nodeExecutor.runDirectMultimodal(chat, systemPrompt, multimodalUser, history,
                        90, progress, taskPlan, streamSink);
            }
            return nodeExecutor.runDirect(chat, systemPrompt, userMessage, history,
                    90, progress, taskPlan, streamSink);
        }

        GoalCompiler.CompileResult compiled;
        StateSnapshot snap;
        var existing = stateStore.get(sessionId);
        boolean incompleteGraph = existing.isPresent()
                && !existing.get().graph().isEmpty()
                && !existing.get().graph().allTerminalSuccess();
        boolean awaitingGraph = existing.isPresent()
                && existing.get().graph().hasAwaitingConfirm();
        boolean resume = PlannerResumePolicy.shouldResume(
                taskPlan.signals(), userMessage,
                awaitingGraph,
                incompleteGraph);
        boolean clarifyRecompile = existing.isPresent()
                && shouldRecompileClarify(
                        existing.get().goal(), existing.get().graph(), userMessage);
        if (clarifyRecompile) {
            String merged = mergeClarifyObjective(
                    existing.get().goal().objective(), userMessage);
            TaskPlan nextPlan = taskPlan.withTaskGoal(merged);
            compiled = compileAndValidate(chat, merged, nextPlan);
            if (!planValidator.accept(compiled.graph(), nextPlan, compiled.goal())) {
                log.warn("PlanningLoop 澄清后任务图仍无法验收 session={} code={}",
                        sessionId, ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getCode());
                return ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getMessage();
            }
            String execId = StringUtils.isNotBlank(executionId)
                    ? executionId : "exec_" + UUID.randomUUID().toString().substring(0, 8);
            snap = stateStore.init(sessionId, execId, compiled.goal(), compiled.graph());
            log.info("PlanningLoop 澄清后重编译 session={} nodes={} clarify={}",
                    sessionId, compiled.graph().nodes().size(),
                    compiled.goal().isClarify());
        } else if (resume) {
            snap = existing.get();
            compiled = new GoalCompiler.CompileResult(snap.goal(), snap.graph(), false);
            log.info("PlanningLoop 续跑已有图 session={} version={} nodes={}",
                    sessionId, snap.version(), snap.graph().nodes().size());

            // ===== 用户明确说「接着做」时的进度汇报 =====
            if (taskPlan.signals().continueTask()) {
                String summary = buildContinueTaskSummary(snap, userMessage);
                if (progress != null) {
                    progress.accept(summary);
                }
                // 检查上一个任务的状态
                String statusCheck = checkPreviousTaskStatus(snap);
                if (statusCheck != null) {
                    log.info("PlanningLoop 上一个任务状态检查: {}", statusCheck);
                    if (progress != null) {
                        progress.accept(statusCheck);
                    }
                }
            }
            // ===========================================

            if (snap.graph().hasAwaitingConfirm()
                    && !HumanYield.looksLikeBareContinue(userMessage)) {
                TaskGraph confirmed = todoProjector.confirmFirst(snap.graph());
                if (!sameNodeStatuses(confirmed, snap.graph())) {
                    try {
                        snap = stateStore.commit(
                                sessionId, snap.version(), snap.withGraph(confirmed));
                    } catch (PlannerStateStore.VersionConflictException e) {
                        metrics.casConflict();
                        snap = stateStore.get(sessionId).orElse(snap);
                    }
                }
            }
        } else {
            compiled = compileAndValidate(chat, userMessage, taskPlan);
            if (!planValidator.accept(compiled.graph(), taskPlan, compiled.goal())) {
                log.warn("PlanningLoop 任务图验收失败 session={} code={}",
                        sessionId, ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getCode());
                return ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getMessage();
            }
            String execId = StringUtils.isNotBlank(executionId)
                    ? executionId : "exec_" + UUID.randomUUID().toString().substring(0, 8);
            snap = stateStore.init(sessionId, execId, compiled.goal(), compiled.graph());
        }
        todoProjector.project(sessionId, snap.graph());
        trace(sessionId, AgentStepNode.GOAL_COMPILED,
                "{\"goalId\":\"" + compiled.goal().goalId()
                        + "\",\"nodes\":" + compiled.graph().nodes().size()
                        + ",\"template\":" + compiled.fromTemplate() + "}");

        String lastAnswer = "";
        int rounds = 0;
        while (rounds++ < properties.getMaxOuterRounds()) {
            if (!sessionLock.renewSessionLock(sessionId)) {
                log.warn("PlanningLoop 会话锁丢失，中止 session={} code={}",
                        sessionId, ErrorCode.AGENT_PLANNER_LOCK_LOST.getCode());
                metrics.outerTimeout();
                String msg = ErrorCode.AGENT_PLANNER_LOCK_LOST.getMessage();
                return StringUtils.isBlank(lastAnswer) ? msg : lastAnswer + "\n（" + msg + "）";
            }
            snap = stateStore.get(sessionId).orElse(snap);
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
            if (snap.graph().allTerminalSuccess()) {
                StepEvaluator.GraphEval acc = stepEvaluator.evaluateGraph(
                        snap.goal(), snap.graph());
                if (!acc.ok()) {
                    log.warn("图终验失败 session={} node={} reason={}",
                            sessionId, acc.nodeId(), acc.reason());
                    TaskNode bad = resolveAcceptFailNode(snap.graph(), acc.nodeId());
                    if (bad == null) {
                        break;
                    }
                    TaskGraph g = snap.graph().replace(
                            bad.withStatus(TaskNodeStatus.FAILED)
                                    .withError(acc.reason()));
                    FailureDiagnosis dx = recoveryEngine.diagnose(
                            bad, diagnoseTool(bad, ""), acc.reason());
                    try {
                        snap = stateStore.commit(
                                sessionId, snap.version(), snap.withGraph(g));
                    } catch (PlannerStateStore.VersionConflictException e) {
                        metrics.casConflict();
                        continue;
                    }
                    snap = applyRecoveryOrCancel(sessionId, snap, bad, dx, bad.id(),
                            chat, userMessage, taskPlan);
                    todoProjector.project(sessionId, snap.graph());
                    continue;
                }
                log.info("PlanningLoop 图完成 session={} version={} metrics={}",
                        sessionId, snap.version(), metrics.snapshot());
                metrics.graphCompleted();
                break;
            }
            List<TaskNode> ready = graphScheduler.select(snap.graph());
            if (ready.isEmpty()) {
                if (snap.graph().hasAwaitingConfirm()) {
                    log.info("PlanningLoop 等待人工 confirm session={}", sessionId);
                    String hint = firstAwaitingHint(snap.graph());
                    if (StringUtils.isNotBlank(lastAnswer)) {
                        return lastAnswer;
                    }
                    if (StringUtils.isNotBlank(hint)) {
                        return hint;
                    }
                    return "关键步骤等待确认：请在页面点击「确认并继续」。";
                }
                log.warn("PlanningLoop 无 ready 节点且未全部成功，尝试 REWRITE session={}", sessionId);
                TaskNode stuck = firstNonSuccess(snap.graph());
                if (stuck == null) {
                    break;
                }
                FailureDiagnosis dx = recoveryEngine.diagnose(
                        stuck, diagnoseTool(stuck, ""), "no ready nodes");
                TaskGraph before = snap.graph();
                snap = applyRecoveryOrCancel(sessionId, snap, stuck, dx, stuck.id(),
                        chat, userMessage, taskPlan);
                if (sameNodeStatuses(before, snap.graph())) {
                    break;
                }
                todoProjector.project(sessionId,
                        stateStore.get(sessionId).map(StateSnapshot::graph).orElse(snap.graph()));
                continue;
            }

            // 只提案 READY 节点
            ActionProposal proposal = graphScheduler.propose(snap, ready,
                    properties.getProposalBatchSize());
            if (proposal.actions().isEmpty()) {
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
                    snap.version(), sessionId, progress, streamSink);
            lastAnswer = keepBetterAnswer(lastAnswer, step.text());
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
            boolean anyFail = false;
            TaskGraph g = snap.graph();
            for (ActionSpec action : proposal.actions()) {
                TaskNode node = g.byId(action.taskId());
                if (node == null) {
                    continue;
                }
                if (humanWait) {
                    g = g.replace(node.withStatus(TaskNodeStatus.READY).withError(""));
                    continue;
                }
                boolean awaiting = todoProjector.isTodoAwaiting(
                        sessionId, g, action.taskId());
                if (awaiting) {
                    g = g.replace(node.withStatus(TaskNodeStatus.AWAITING_CONFIRM)
                            .withError(clip(lastAnswer, AWAITING_HINT_CHARS)));
                    continue;
                }
                if (drifted) {
                    anyFail = true;
                    metrics.nodeFailed();
                    recordToolOutcome(node, false);
                    String reason = "drift:偏离子目标 " + node.name();
                    g = g.replace(node.withStatus(TaskNodeStatus.FAILED).withError(reason));
                    FailureDiagnosis dx = recoveryEngine.diagnose(
                            node, diagnoseTool(node, step.lastTool()), reason,
                            step.lastErrorCode());
                    try {
                        snap = stateStore.commit(sessionId, snap.version(), snap.withGraph(g));
                    } catch (PlannerStateStore.VersionConflictException e) {
                        metrics.casConflict();
                        break;
                    }
                    snap = applyRecoveryOrCancel(sessionId, snap, node, dx, action.taskId(),
                            chat, userMessage, taskPlan);
                    g = snap.graph();
                    continue;
                }
                String judgeAnswer = node.doneWhen() != null && node.doneWhen().isJudge()
                        ? step.text() : "";
                NodeOutputBinder.Bound bound = NodeOutputBinder.bind(
                        node, "", step.messages(), judgeAnswer);
                StepEvaluator.EvalResult ev;
                if (!bound.complete(node)) {
                    ev = StepEvaluator.EvalResult.fail("declared outputs 未绑定");
                } else {
                    ev = stepEvaluator.evaluateAfterLoop(node, bound.evidence());
                }
                TaskNodeStatus next = statusAfterChunk(ev.ok(), quotaAbort);
                if (next == TaskNodeStatus.SUCCESS) {
                    g = g.replace(node.withStatus(TaskNodeStatus.SUCCESS).withError("")
                            .withOutput(clip(bound.evidence(), PRIOR_OUTPUT_CHARS),
                                    clipBindings(bound.bindings())));
                    metrics.nodeSuccess();
                    recordToolOutcome(node, true);
                    stateStore.appendEvent(sessionId, new DomainEvent(
                            "ev_" + UUID.randomUUID().toString().substring(0, 8),
                            DomainEventType.NODE_SUCCESS, action.actionId(), action.taskId(),
                            Map.of(), null));
                } else if (next == TaskNodeStatus.READY) {
                    g = g.replace(node.withStatus(TaskNodeStatus.READY).withError(""));
                } else {
                    anyFail = true;
                    metrics.nodeFailed();
                    recordToolOutcome(node, false);
                    g = g.replace(node.withStatus(TaskNodeStatus.FAILED).withError(ev.reason()));
                    stateStore.appendEvent(sessionId, new DomainEvent(
                            "ev_" + UUID.randomUUID().toString().substring(0, 8),
                            DomainEventType.NODE_FAILED, action.actionId(), action.taskId(),
                            Map.of("reason", ev.reason()), null));
                    FailureDiagnosis dx = recoveryEngine.diagnose(
                            node, diagnoseTool(node, step.lastTool()), ev.reason(),
                            step.lastErrorCode());
                    StateSnapshot failedSnap = snap.withGraph(g);
                    try {
                        snap = stateStore.commit(sessionId, snap.version(), failedSnap);
                    } catch (PlannerStateStore.VersionConflictException e) {
                        metrics.casConflict();
                        log.warn("状态冲突，触发 replan: {}", e.getMessage());
                        break;
                    }
                    snap = applyRecoveryOrCancel(sessionId, snap, node, dx, action.taskId(),
                            chat, userMessage, taskPlan);
                    g = snap.graph();
                }
            }
            if (!anyFail) {
                try {
                    snap = stateStore.commit(sessionId, snap.version(), snap.withGraph(g));
                    trace(sessionId, AgentStepNode.STATE_COMMIT,
                            "{\"version\":" + snap.version() + "}");
                } catch (PlannerStateStore.VersionConflictException e) {
                    metrics.casConflict();
                    log.warn("成功提交冲突，replan: {}", e.getMessage());
                }
            }
            todoProjector.project(sessionId,
                    stateStore.get(sessionId).map(StateSnapshot::graph).orElse(g));
            if (humanWait) {
                log.info("PlanningLoop 等待用户 session={} reason={}",
                        sessionId, step.endReason());
                if (StringUtils.isNotBlank(lastAnswer)) {
                    return lastAnswer;
                }
                return "需要你确认后才能继续。";
            }
            TaskGraph latest = stateStore.get(sessionId).map(StateSnapshot::graph).orElse(g);
            if (latest.hasAwaitingConfirm() || g.hasAwaitingConfirm()) {
                log.info("PlanningLoop 等待人工输入 session={}", sessionId);
                if (StringUtils.isNotBlank(lastAnswer)) {
                    return lastAnswer;
                }
                return "关键步骤等待确认：请在页面点击「确认并继续」。";
            }
            if (quotaAbort && hasUnfinishedProposalNode(g, proposal)) {
                log.info("本段未完成，节点保持 READY session={}", sessionId);
                break;
            }
        }
        if (rounds > properties.getMaxOuterRounds())
            metrics.outerTimeout();

        if (StringUtils.isBlank(lastAnswer))
            lastAnswer = "已按规划图推进任务（version="
                    + stateStore.get(sessionId).map(StateSnapshot::version).orElse(0L)
                    + "，metrics=" + metrics.snapshot() + "）。";
        return lastAnswer;
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

    static boolean isStubStepAnswer(String text) {
        if (text == null) {
            return true;
        }
        String t = text.trim();
        return t.equals(AgentLoop.STEP_SEGMENT_DONE)
                || t.equals("本步已完成")
                || t.startsWith("已按规划图推进任务");
    }

    private record StepExec(String text, String endReason, boolean drifted,
                            String lastTool, ToolErrorCode lastErrorCode,
                            List<ChatMessage> messages) {
        StepExec {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    /** 轮次用尽就续跑。file_exists 可能只是写了个开头，不能当「做完了」。 */
    static boolean shouldResumeChunk(String endReason, int chunk, int maxChunks) {
        return chunk < maxChunks
                && isLoopLimit(endReason);
    }

    private static boolean isLoopLimit(String endReason) {
        return endReason != null && AgentLoop.LOOP_MAX_ITERATIONS.equalsIgnoreCase(endReason);
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
                || "TENANT_QUOTA_EXCEEDED".equals(endReason)
                || "TOKEN_BUDGET_EXCEEDED".equals(endReason)
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
                    if (prod == null || (StringUtils.isBlank(prod.output())
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
        for (String d : node.dependsOn()) {
            TaskNode dep = graph.byId(d);
            if (dep != null && dep.outputs().contains(input)) {
                return dep;
            }
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
                                     Consumer<String> progress,
                                     AgentStreamSink streamSink) {
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
        ExecutionTurnContext.open(sessionId, proposal.basedOnPlanVersion(), bindings,
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
            AgentLoop.LoopOutcome out;
            out = nodeExecutor.execute(chat, focus, userMessage, multimodalUser,
                    history, maxIter, progress, taskPlan, streamSink);
            int chunks = 1;
            int maxChunks = Math.max(1, properties.getProposalMaxChunks());
            while (shouldResumeChunk(out.endReason(), chunks, maxChunks)) {
                log.info("配额续跑 chunk={}/{} task={}", chunks + 1, maxChunks, focusTask);
                ArrayList<ChatMessage> live = new ArrayList<>(out.messages());
                live.add(new SystemMessage(MessageConstants.PLANNER_CONTINUE_HINT));
                out = nodeExecutor.continueNode(chat, live, userMessage,
                        maxIter, progress, taskPlan, streamSink);
                chunks++;
            }
            return new StepExec(out.text(), out.endReason(), policy.consumeDrift(),
                    lastInvokedTool(out), out.lastErrorCode(), out.messages());
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
        ToolResult result = nodeExecutor.executeBound(action.tool(), json);
        String text = result == null ? "" : result.legacyText();
        boolean ok = result != null && result.isSuccess()
                && !StepEvaluator.looksLikeToolError(text);
        ToolExecutionResultMessage msg = ToolExecutionResultMessage.from(
                action.actionId(), action.tool(), text);
        ToolErrorCode code = result == null ? ToolErrorCode.INTERNAL_ERROR : result.errorCode();
        String end = ok ? AgentLoop.STEP_SEGMENT_DONE : RunStatus.FAILURE.name();
        return new StepExec(ok ? AgentLoop.STEP_SEGMENT_DONE : text, end, false,
                action.tool(), code, List.of(msg));
    }

    private boolean dispatchFenceValid(String sessionId, ActionProposal proposal) {
        return ownsRunningProposal(stateStore.get(sessionId).orElse(null), proposal);
    }

    static boolean ownsRunningProposal(StateSnapshot snapshot, ActionProposal proposal) {
        if (snapshot == null || proposal == null || proposal.actions().isEmpty()
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
        sb.append("完成后用 todo update（id∈focusTodoIds）标 completed 并附可校验 evidence。\n");
        return sb.toString();
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
    private StateSnapshot applyRecoveryOrCancel(String sessionId, StateSnapshot snap,
                                                TaskNode node, FailureDiagnosis dx,
                                                String taskId, ChatModel chat,
                                                String userMessage, TaskPlan taskPlan) {
        Optional<StateSnapshot> llm = tryLlmReplan(
                sessionId, snap, node, dx, chat, userMessage, taskPlan);
        if (llm.isPresent()) {
            metrics.recovery();
            return llm.get();
        }
        boolean atLimit = snap.recoveryCount() >= properties.getMaxRecoveries()
                || recoveryEngine.classCount(snap, dx.failureClass())
                >= recoveryEngine.classLimit(dx.failureClass());
        if (recoveryEngine.recover(sessionId, dx).isPresent()) {
            metrics.recovery();
            return stateStore.get(sessionId).orElse(snap);
        }
        if (!atLimit) {
            log.warn("Recovery 失败 task={}", taskId);
            return stateStore.get(sessionId).orElse(snap);
        }
        log.warn("Recovery 耗尽，取消节点 task={}", taskId);
        TaskNode latest = snap.graph().byId(node.id());
        if (latest == null) {
            latest = node;
        }
        TaskGraph g = snap.graph().replace(
                latest.withStatus(TaskNodeStatus.CANCELLED).withError("recovery_exhausted"));
        try {
            return stateStore.commit(sessionId, snap.version(), snap.withGraph(g));
        } catch (PlannerStateStore.VersionConflictException e) {
            metrics.casConflict();
            log.warn("取消耗尽节点冲突 task={}: {}", taskId, e.getMessage());
            return stateStore.get(sessionId).orElse(snap);
        }
    }

    private Optional<StateSnapshot> tryLlmReplan(String sessionId, StateSnapshot snap,
                                                 TaskNode node, FailureDiagnosis dx,
                                                 ChatModel chat, String userMessage,
                                                 TaskPlan taskPlan) {
        if (chat == null || snap == null || node == null || dx == null) {
            return Optional.empty();
        }
        if (dx.failureClass() != FailureClass.REWRITE_GRAPH
                && dx.failureClass() != FailureClass.REVISE_GOAL) {
            return Optional.empty();
        }
        if (snap.recoveryCount() >= properties.getMaxRecoveries()) {
            return Optional.empty();
        }
        int used = recoveryEngine.classCount(snap, dx.failureClass());
        if (used >= recoveryEngine.classLimit(dx.failureClass())) {
            return Optional.empty();
        }
        String correction = "失败节点 id=" + node.id()
                + " name=" + node.name()
                + " capability=" + node.capability()
                + " tool=" + dx.tool()
                + "\n失败原因: " + dx.reason()
                + "\n请重新规划未完成部分，不要重复已成功节点。"
                + " 产出文件的步骤必须 doneWhen.type=file_exists 且 path 非空。";
        GoalCompiler.CompileResult compiled;
        try {
            compiled = goalCompiler.compileWithCorrection(
                    chat, userMessage, taskPlan, correction);
        } catch (Exception e) {
            log.warn("运行时 LLM replan 失败: {}", e.getMessage());
            return Optional.empty();
        }
        if (compiled == null || compiled.fromTemplate()
                || compiled.graph() == null || compiled.graph().isEmpty()) {
            return Optional.empty();
        }
        TaskGraph merged = mergeKeepSuccess(
                snap.graph(), compiled.graph(), snap.recoveryCount() + 1);
        if (merged == null || merged.isEmpty()) {
            return Optional.empty();
        }
        if (!planValidator.accept(merged, taskPlan, compiled.goal())) {
            log.warn("运行时 LLM replan 图验收失败 session={}", sessionId);
            return Optional.empty();
        }
        Goal nextGoal = compiled.goal() != null ? compiled.goal() : snap.goal();
        Map<String, Object> exec = new HashMap<>(snap.execution());
        exec.put("recovery." + dx.failureClass().name(), used + 1);
        exec.put("lastFailureKind", dx.kind().name());
        StateSnapshot patched = snap.revisePlan(merged, nextGoal,
                        "llm_replan_" + dx.failureClass().name().toLowerCase())
                .withExecution(exec).withRecoveryInc();
        try {
            StateSnapshot committed = stateStore.commit(sessionId, snap.version(), patched);
            stateStore.appendEvent(sessionId, new DomainEvent(
                    "ev_" + UUID.randomUUID().toString().substring(0, 8),
                    DomainEventType.RECOVERY_APPLIED, null, dx.taskId(),
                    Map.of("class", dx.failureClass().name(), "kind", dx.kind().name(),
                            "tool", dx.tool(), "llmReplan", true),
                    null));
            log.info("运行时 LLM replan 已提交 session={} nodes={}",
                    sessionId, committed.graph().nodes().size());
            return Optional.of(committed);
        } catch (PlannerStateStore.VersionConflictException e) {
            metrics.casConflict();
            return Optional.empty();
        }
    }

    // ponytail: LLM 可能把已成功步骤再规划一遍；靠 prompt 约束，必要时再按 name 去重
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
        Set<String> newIds = new HashSet<>();
        for (TaskNode n : neu.nodes()) {
            newIds.add(prefix + n.id());
        }
        boolean first = true;
        for (TaskNode n : neu.nodes()) {
            String newId = prefix + n.id();
            List<String> deps = new ArrayList<>();
            if (first) {
                deps.addAll(successIds);
                first = false;
            } else {
                for (String d : n.dependsOn()) {
                    String mapped = prefix + d;
                    if (newIds.contains(mapped)) {
                        deps.add(mapped);
                    }
                }
                if (deps.isEmpty()) {
                    deps.addAll(successIds);
                }
            }
            out.add(new TaskNode(newId, n.name(), n.capability(), deps,
                    n.inputs(), n.outputs(), TaskNodeStatus.PENDING, n.priority(),
                    n.doneWhen(), n.toolHint(), "", 0, ""));
        }
        TaskGraph g = new TaskGraph(out);
        if (g.hasCycle()) {
            return null;
        }
        return g;
    }

    private StateSnapshot markRunning(String sessionId, StateSnapshot snap, ActionProposal proposal) {
        StateSnapshot current = stateStore.get(sessionId).orElse(snap);
        if (current.version() != snap.version() || current.planVersion() != proposal.basedOnPlanVersion()
                || proposal.basedOnVersion() != current.version()) return current;
        TaskGraph g = current.graph();
        for (ActionSpec a : proposal.actions()) {
            TaskNode n = g.byId(a.taskId());
            if (n == null || n.status() != TaskNodeStatus.READY) {
                return current;
            }
            g = g.replace(n.withStatus(TaskNodeStatus.RUNNING));
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

    private GoalCompiler.CompileResult compileAndValidate(ChatModel chat,
                                                         String userMessage,
                                                         TaskPlan taskPlan) {
        // 第一次编译
        GoalCompiler.CompileResult compiled = goalCompiler.compile(chat, userMessage, taskPlan);

        // 结构化验证
        PlanValidationReport report = planValidator.validate(
                compiled.graph(), taskPlan, compiled.goal());
        if (report.valid()) {
            return compiled;
        }

        // 验证失败，进入 replan 闭环
        log.info("PlanValidator 拒绝，进入 replan 闭环，错误数={}", report.errors().size());
        log.debug("验证报告: {}", report.summary());

        // 尝试 replan，最多重试 maxReplanRetries 次
        int maxReplanRetries = Math.max(0, properties.getMaxReplanRetries());
        for (int attempt = 1; attempt <= maxReplanRetries; attempt++) {
            log.info("Replan 尝试 {}/{}", attempt, maxReplanRetries);

            // 生成修正提示
            String correctionPrompt = report.toCorrectionPrompt();

            // 重新编译，注入修正提示
            GoalCompiler.CompileResult replanned = goalCompiler.compileWithCorrection(
                chat, userMessage, taskPlan, correctionPrompt);

            // 验证重新编译的结果
            report = planValidator.validate(
                    replanned.graph(), taskPlan, replanned.goal());
            if (report.valid()) {
                log.info("Replan 成功，尝试次数={}", attempt);
                return replanned;
            }

            log.warn("Replan 尝试 {} 仍然失败，错误数={}", attempt, report.errors().size());
        }

        // 所有 replan 尝试失败，模板图仍须过同一道验收，不能把脏图交给调度器
        log.warn("Replan 全部失败，改用 fallback 模板，最终错误数={}",
                report.errors().size());
        GoalCompiler.CompileResult fb = goalCompiler.fallback(
                compiled.goal(), userMessage, taskPlan);
        PlanValidationReport fbReport = planValidator.validate(
                fb.graph(), taskPlan, fb.goal());
        if (fbReport.valid()) {
            return fb;
        }
        log.warn("fallback 仍无法验收，拒绝调度 code={} errors={}",
                ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getCode(),
                fbReport.errors().size());
        return fb;
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
        if (traceRecorder == null) {
            return;
        }
        traceRecorder.recordNode(sessionId, 0, node.name(), content, RunStatus.SUCCESS.name(), 0);
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
            long pendingNodes = totalNodes - completedNodes - failedNodes;

            sb.append("任务进度：").append(completedNodes).append("/").append(totalNodes).append(" 已完成");
            if (failedNodes > 0) {
                sb.append("，").append(failedNodes).append(" 个失败");
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

        if (allSuccess) {
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
