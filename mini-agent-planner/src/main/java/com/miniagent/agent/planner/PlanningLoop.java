package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.intent.IntentType;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.agent.todo.HumanYield;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.RunStatus;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * User → Compiler → Validator → Scheduler → Executor → ToolRouter → Tool
 * → StepEvaluator → Continue | Retry/Replan。
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
        if (!properties.isEnabled() || plan == null || plan.intent() == null) {
            return false;
        }
        String intent = plan.intent().name();
        for (String skip : properties.getSkipIntents()) {
            if (intent.equalsIgnoreCase(skip)) {
                return false;
            }
        }
        if (plan.requiresStructuredPlan()) {
            return true;
        }
        boolean awaiting = hasAwaitingGraph(sessionId);
        return stateStore.hasIncompleteGraph(sessionId)
                && shouldResumeExisting(plan.intent(),
                stateStore.peekResume(sessionId), awaiting, true);
    }

    static boolean shouldResumeExisting(IntentType intent, boolean peekResume,
                                        boolean awaitingConfirm, boolean incomplete) {
        if (!incomplete) {
            return false;
        }
        return intent == IntentType.CONTINUE_TASK || peekResume || awaitingConfirm;
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
        if (!shouldHandle(taskPlan, sessionId)) {
            if (multimodalUser != null)
                return nodeExecutor.runDirectMultimodal(chat, systemPrompt, multimodalUser, history,
                        90, progress, taskPlan, streamSink);
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
        boolean resume = shouldResumeExisting(
                taskPlan.intent(),
                stateStore.peekResume(sessionId),
                awaitingGraph,
                incompleteGraph);
        if (resume) {
            stateStore.clearResume(sessionId);
            snap = existing.get();
            compiled = new GoalCompiler.CompileResult(snap.goal(), snap.graph(), false);
            log.info("PlanningLoop 续跑已有图 session={} version={} nodes={}",
                    sessionId, snap.version(), snap.graph().nodes().size());
            if (snap.graph().hasAwaitingConfirm()
                    && !HumanYield.looksLikeBareContinue(userMessage)) {
                todoProjector.confirmAwaiting(sessionId, userMessage);
            }
        } else {
            compiled = compileAndValidate(chat, userMessage, taskPlan);
            if (!planValidator.accept(compiled.graph(), taskPlan)) {
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
            TaskGraph normalized = todoProjector.syncConfirmFromTodo(sessionId, snap.graph())
                    .normalizeForScheduling();
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
                if (stuck == null) break;
                FailureDiagnosis dx = recoveryEngine.diagnose(stuck, stuck.toolHint(), "no ready nodes");
                if (recoveryEngine.recover(sessionId, dx).isEmpty()) break;
                metrics.recovery();
                todoProjector.project(sessionId,
                        stateStore.get(sessionId).map(StateSnapshot::graph).orElse(snap.graph()));
                continue;
            }

            // 只提案 READY 节点
            ActionProposal proposal = graphScheduler.propose(snap, ready,
                    properties.getProposalBatchSize());
            if (proposal.actions().isEmpty()) break;
            for (ActionSpec a : proposal.actions()) {
                TaskNode n = snap.graph().byId(a.taskId());
                if (n == null || n.status() != TaskNodeStatus.READY) {
                    log.warn("拒绝非 READY 节点进入提案: {}", a.taskId());
                    proposal = new ActionProposal(proposal.proposalId(), proposal.basedOnVersion(),
                            proposal.basedOnPlanVersion(), proposal.executionId(), List.of());
                    break;
                }
            }
            if (proposal.actions().isEmpty()) continue;

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
            if (StringUtils.isBlank(prior) && StringUtils.isNotBlank(lastAnswer)
                    && !StepEvaluator.looksLikeLoopAbort(lastAnswer))
                prior = clip(lastAnswer, PRIOR_OUTPUT_CHARS);
            if (StringUtils.isNotBlank(prior))
                stepUser = userMessage + "\n\n# 前置节点产出\n" + prior;
            StepExec step = executeProposal(chat, systemPrompt, stepUser,
                    multimodalUser, history, taskPlan, proposal, snap.graph(),
                    snap.version(), sessionId, progress, streamSink);
            lastAnswer = step.text();
            boolean drifted = step.drifted();
            boolean quotaAbort = AgentLoop.LOOP_MAX_ITERATIONS.equals(step.endReason());
            boolean permAsk = AgentLoop.PERM_ASK.equals(step.endReason());

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
                if (permAsk) {
                    g = g.replace(node.withStatus(TaskNodeStatus.READY).withError(""));
                    continue;
                }
                boolean todoDone = todoProjector.isTodoCompleted(sessionId, g, action.taskId());
                if (!todoDone) {
                    if (HumanYield.looksLikeNeedHuman(lastAnswer)
                            && !todoProjector.isTodoAwaiting(
                                    sessionId, g, action.taskId())) {
                        todoProjector.yieldNodeToHuman(
                                sessionId, g, action.taskId(), lastAnswer);
                    }
                    if (todoProjector.isTodoAwaiting(sessionId, g, action.taskId())) {
                        g = g.replace(node.withStatus(TaskNodeStatus.AWAITING_CONFIRM)
                                .withError(clip(lastAnswer, AWAITING_HINT_CHARS)));
                        continue;
                    }
                }
                if (drifted) {
                    anyFail = true;
                    metrics.nodeFailed();
                    recordToolOutcome(node, false);
                    String reason = "drift:偏离子目标 " + node.name();
                    g = g.replace(node.withStatus(TaskNodeStatus.FAILED).withError(reason));
                    FailureDiagnosis dx = recoveryEngine.diagnose(node, routeKey(node), reason);
                    try {
                        snap = stateStore.commit(sessionId, snap.version(), snap.withGraph(g));
                    } catch (PlannerStateStore.VersionConflictException e) {
                        metrics.casConflict();
                        break;
                    }
                    snap = applyRecoveryOrCancel(sessionId, snap, node, dx, action.taskId());
                    g = snap.graph();
                    continue;
                }
                String evidence = todoProjector.todoEvidence(sessionId, g, action.taskId());
                if (StringUtils.isBlank(evidence)) {
                    evidence = lastAnswer;
                }
                StepEvaluator.EvalResult ev = stepEvaluator.evaluateAfterLoop(
                        node, todoDone, evidence);
                TaskNodeStatus next = statusAfterChunk(ev.ok(), quotaAbort);
                if (next == TaskNodeStatus.SUCCESS) {
                    g = g.replace(node.withStatus(TaskNodeStatus.SUCCESS).withError("")
                            .withOutput(clip(evidence, PRIOR_OUTPUT_CHARS)));
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
                    FailureDiagnosis dx = recoveryEngine.diagnose(node, routeKey(node), ev.reason());
                    StateSnapshot failedSnap = snap.withGraph(g);
                    try {
                        snap = stateStore.commit(sessionId, snap.version(), failedSnap);
                    } catch (PlannerStateStore.VersionConflictException e) {
                        metrics.casConflict();
                        log.warn("状态冲突，触发 replan: {}", e.getMessage());
                        break;
                    }
                    snap = applyRecoveryOrCancel(sessionId, snap, node, dx, action.taskId());
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
            if (permAsk) {
                stateStore.markResume(sessionId);
                log.info("PlanningLoop 等待工具授权 session={}", sessionId);
                if (StringUtils.isNotBlank(lastAnswer)) {
                    return lastAnswer;
                }
                return "需要你批准危险工具后才能继续。请点击弹窗中的「批准并继续」。";
            }
            TaskGraph latest = stateStore.get(sessionId).map(StateSnapshot::graph).orElse(g);
            if (latest.hasAwaitingConfirm() || g.hasAwaitingConfirm()) {
                stateStore.markResume(sessionId);
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

    private record StepExec(String text, String endReason, boolean drifted) {}

    /** 轮次用尽就续跑。file_exists 可能只是写了个开头，不能当「做完了」。 */
    static boolean shouldResumeChunk(String endReason, int chunk, int maxChunks) {
        return chunk < maxChunks
                && AgentLoop.LOOP_MAX_ITERATIONS.equals(endReason);
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
        if (graph == null || proposal == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ActionSpec a : proposal.actions()) {
            TaskNode node = graph.byId(a.taskId());
            if (node == null) continue;
            if (!node.inputs().isEmpty()) {
                for (String in : node.inputs()) {
                    TaskNode prod = producerOf(graph, node, in);
                    if (prod == null || StringUtils.isBlank(prod.output())) continue;
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append("## ").append(in).append(" (").append(prod.id())
                            .append(' ').append(prod.name()).append(")\n")
                            .append(clip(prod.output(), PRIOR_OUTPUT_CHARS)).append('\n');
                }
                continue;
            }
            for (String d : node.dependsOn()) {
                TaskNode dep = graph.byId(d);
                if (dep == null || StringUtils.isBlank(dep.output())) continue;
                if (!sb.isEmpty()) sb.append('\n');
                sb.append("## ").append(dep.id()).append(' ')
                        .append(dep.name()).append('\n')
                        .append(clip(dep.output(), PRIOR_OUTPUT_CHARS)).append('\n');
            }
        }
        return sb.toString();
    }

    static TaskNode producerOf(TaskGraph graph, TaskNode node, String input) {
        if (graph == null || node == null || input == null) return null;
        for (String d : node.dependsOn()) {
            TaskNode dep = graph.byId(d);
            if (dep != null && dep.outputs().contains(input)) return dep;
        }
        return null;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
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
            if (fn != null) focusName = fn.name();
        }
        Set<Integer> focusTodos = new LinkedHashSet<>();
        for (ActionSpec a : proposal.actions()) {
            int id = todoProjector.todoIdFor(graph, a.taskId());
            if (id > 0) focusTodos.add(id);
        }
        boolean hard = properties.isHardProposal();
        String label = StringUtils.isNotBlank(focusName) ? focusName : focusTask;
        ProposalTurnPolicy policy = new ProposalTurnPolicy(allowed, hard, label, focusTodos);
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
            return new StepExec(out.text(), out.endReason(), policy.consumeDrift());
        } finally {
            ExecutionTurnContext.clear();
            LoopTurnContext.clear();
        }
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
            String hint = n != null ? n.toolHint() : "";
            sb.append("- taskId=").append(a.taskId())
                    .append(" capability=").append(cap)
                    .append(" tool=").append(a.tool())
                    .append(" arguments=").append(a.arguments())
                    .append(" acceptance=").append(a.acceptance().wire())
                    .append(" idempotencyKey=").append(a.idempotencyKey())
                    .append(" timeoutSeconds=").append(a.timeoutSeconds());
            if (StringUtils.isNotBlank(hint))
                sb.append(" hint=").append(hint);
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
     * CAS 冲突等瞬时失败不取消。
     */
    private StateSnapshot applyRecoveryOrCancel(String sessionId, StateSnapshot snap,
                                                TaskNode node, FailureDiagnosis dx,
                                                String taskId) {
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
        if (latest == null) latest = node;
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

    private StateSnapshot markRunning(String sessionId, StateSnapshot snap, ActionProposal proposal) {
        StateSnapshot current = stateStore.get(sessionId).orElse(snap);
        if (current.version() != snap.version() || current.planVersion() != proposal.basedOnPlanVersion()
                || proposal.basedOnVersion() != current.version()) return current;
        TaskGraph g = current.graph();
        for (ActionSpec a : proposal.actions()) {
            TaskNode n = g.byId(a.taskId());
            if (n == null || n.status() != TaskNodeStatus.READY) return current;
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
        if (a == null || b == null) return a == b;
        if (a.nodes().size() != b.nodes().size()) return false;
        for (int i = 0; i < a.nodes().size(); i++) {
            TaskNode x = a.nodes().get(i);
            TaskNode y = b.nodes().get(i);
            if (!x.id().equals(y.id()) || x.status() != y.status()) return false;
        }
        return true;
    }

    private static TaskNode firstNonSuccess(TaskGraph graph) {
        for (TaskNode n : graph.nodes())
            if (n.status() != TaskNodeStatus.SUCCESS && n.status() != TaskNodeStatus.CANCELLED)
                return n;
        return null;
    }

    private GoalCompiler.CompileResult compileAndValidate(ChatModel chat,
                                                         String userMessage,
                                                         TaskPlan taskPlan) {
        GoalCompiler.CompileResult compiled = goalCompiler.compile(chat, userMessage, taskPlan);
        if (planValidator.accept(compiled.graph(), taskPlan)) return compiled;
        log.info("PlanValidator 拒绝，改用模板 nodes={}", compiled.graph().nodes().size());
        return goalCompiler.fallback(compiled.goal(), userMessage, taskPlan);
    }

    private static String routeKey(TaskNode node) {
        if (node == null) return "";
        if (StringUtils.isNotBlank(node.toolHint())) return node.toolHint();
        return node.capability();
    }

    private void recordToolOutcome(TaskNode node, boolean ok) {
        if (toolSuccessStats == null || node == null) return;
        String t = node.toolHint();
        if (StringUtils.isBlank(t)) t = node.capability();
        if (StringUtils.isNotBlank(t)) toolSuccessStats.record(t, ok);
    }

    private void trace(String sessionId, AgentStepNode node, String content) {
        if (traceRecorder == null) return;
        traceRecorder.recordNode(sessionId, 0, node.name(), content, RunStatus.SUCCESS.name(), 0);
    }
}
