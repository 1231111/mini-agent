package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.intent.IntentType;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.RunStatus;
import com.miniagent.config.service.TaskRunService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 滚动规划：ready → route → propose → execute → evaluate → recover。
 */
@Component
public class PlanningLoop {

    private static final Logger log = LoggerFactory.getLogger(PlanningLoop.class);

    private final PlannerProperties properties;
    private final GoalCompiler goalCompiler;
    private final PlannerStateStore stateStore;
    private final ReadyTaskSelector readySelector = new ReadyTaskSelector();
    private final ToolRouter toolRouter;
    private final ProposalExecutor proposalExecutor;
    private final StepEvaluator stepEvaluator;
    private final RecoveryEngine recoveryEngine;
    private final TodoStateProjector todoProjector;
    private final AgentLoop agentLoop;
    private final PlannerMetrics metrics;
    private final TaskRunService taskRunService;
    private final ToolSuccessStats toolSuccessStats;
    private TraceRecorder traceRecorder;

    public PlanningLoop(PlannerProperties properties,
                        GoalCompiler goalCompiler,
                        PlannerStateStore stateStore,
                        ToolRouter toolRouter,
                        ProposalExecutor proposalExecutor,
                        StepEvaluator stepEvaluator,
                        RecoveryEngine recoveryEngine,
                        TodoStateProjector todoProjector,
                        AgentLoop agentLoop,
                        PlannerMetrics metrics,
                        TaskRunService taskRunService,
                        ToolSuccessStats toolSuccessStats) {
        this.properties = properties;
        this.goalCompiler = goalCompiler;
        this.stateStore = stateStore;
        this.toolRouter = toolRouter;
        this.proposalExecutor = proposalExecutor;
        this.stepEvaluator = stepEvaluator;
        this.recoveryEngine = recoveryEngine;
        this.todoProjector = todoProjector;
        this.agentLoop = agentLoop;
        this.metrics = metrics;
        this.taskRunService = taskRunService;
        this.toolSuccessStats = toolSuccessStats;
    }

    public void setTraceRecorder(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
        this.proposalExecutor.setTraceRecorder(traceRecorder);
        this.recoveryEngine.setTraceRecorder(traceRecorder);
    }

    public boolean shouldHandle(TaskPlan plan) {
        if (!properties.isEnabled() || plan == null || plan.intent() == null) return false;
        String intent = plan.intent().name();
        for (String skip : properties.getSkipIntents())
            if (intent.equalsIgnoreCase(skip)) return false;
        if (plan.requiresStructuredPlan()) return true;
        for (String force : properties.getForceForIntents())
            if (intent.equalsIgnoreCase(force)) return true;
        return plan.intent() != IntentType.QUESTION && plan.intent() != IntentType.REVIEW;
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
        if (!shouldHandle(taskPlan)) {
            if (multimodalUser != null)
                return agentLoop.runWithMultimodal(chat, systemPrompt, multimodalUser, history,
                        90, progress, taskPlan, streamSink);
            return agentLoop.run(chat, systemPrompt, userMessage, history,
                    90, progress, taskPlan, streamSink);
        }

        GoalCompiler.CompileResult compiled;
        StateSnapshot snap;
        var existing = stateStore.get(sessionId);
        if (existing.isPresent()
                && taskPlan.intent() == IntentType.CONTINUE_TASK
                && !existing.get().graph().allTerminalSuccess()
                && !existing.get().graph().isEmpty()) {
            snap = existing.get();
            compiled = new GoalCompiler.CompileResult(snap.goal(), snap.graph(), false);
            log.info("PlanningLoop 续跑已有图 session={} version={} nodes={}",
                    sessionId, snap.version(), snap.graph().nodes().size());
        } else {
            compiled = goalCompiler.compile(chat, userMessage, taskPlan);
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
            if (!taskRunService.renewSessionLock(sessionId)) {
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
            List<TaskNode> ready = readySelector.select(snap.graph());
            if (ready.isEmpty()) {
                if (snap.graph().hasAwaitingConfirm()) {
                    log.info("PlanningLoop 等待人工 confirm session={}", sessionId);
                    return "关键步骤等待确认：请对 awaiting_confirm 的 todo 执行 action=confirm 后继续。";
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
            ActionProposal proposal = toolRouter.propose(snap, ready,
                    properties.getProposalBatchSize());
            if (proposal.actions().isEmpty()) break;
            for (ActionSpec a : proposal.actions()) {
                TaskNode n = snap.graph().byId(a.taskId());
                if (n == null || n.status() != TaskNodeStatus.READY) {
                    log.warn("拒绝非 READY 节点进入提案: {}", a.taskId());
                    proposal = new ActionProposal(proposal.proposalId(), proposal.basedOnVersion(),
                            proposal.executionId(), List.of());
                    break;
                }
            }
            if (proposal.actions().isEmpty()) continue;

            snap = markRunning(sessionId, snap, proposal);
            todoProjector.project(sessionId, snap.graph());
            trace(sessionId, AgentStepNode.GRAPH_UPDATED,
                    "{\"version\":" + snap.version() + ",\"running\":"
                            + proposal.actions().size() + "}");

            String stepUser = userMessage;
            if (StringUtils.isNotBlank(lastAnswer)
                    && !StepEvaluator.looksLikeLoopAbort(lastAnswer)) {
                String prior = lastAnswer.length() <= 6000
                        ? lastAnswer : lastAnswer.substring(0, 6000) + "…";
                stepUser = userMessage
                        + "\n\n# 上一步进展（页面可能仍开着，不要再 navigate 同一 wiki 首页）\n"
                        + prior;
            }
            lastAnswer = proposalExecutor.execute(chat, systemPrompt, stepUser, multimodalUser,
                    history, taskPlan, proposal, snap.graph(), sessionId, progress, streamSink);

            boolean drifted = ProposalExecutor.consumeLastDrift();

            snap = stateStore.get(sessionId).orElse(snap);
            boolean anyFail = false;
            TaskGraph g = snap.graph();
            for (ActionSpec action : proposal.actions()) {
                TaskNode node = g.byId(action.taskId());
                if (node == null) continue;
                if (drifted) {
                    anyFail = true;
                    metrics.nodeFailed();
                    recordToolOutcome(action.tool(), false);
                    String reason = "drift:偏离子目标 " + node.name();
                    g = g.replace(node.withStatus(TaskNodeStatus.FAILED).withError(reason));
                    FailureDiagnosis dx = recoveryEngine.diagnose(node, action.tool(), reason);
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
                boolean todoDone = todoProjector.isTodoCompleted(sessionId, g, action.taskId());
                String evidence = todoProjector.todoEvidence(sessionId, g, action.taskId());
                if (StringUtils.isBlank(evidence)) evidence = lastAnswer;
                StepEvaluator.EvalResult ev = stepEvaluator.evaluateAfterLoop(
                        node, todoDone, evidence);
                if (ev.ok()) {
                    g = g.replace(node.withStatus(TaskNodeStatus.SUCCESS).withError(""));
                    metrics.nodeSuccess();
                    recordToolOutcome(action.tool(), true);
                    stateStore.appendEvent(sessionId, new DomainEvent(
                            "ev_" + UUID.randomUUID().toString().substring(0, 8),
                            DomainEventType.NODE_SUCCESS, action.actionId(), action.taskId(),
                            Map.of(), null));
                } else {
                    anyFail = true;
                    metrics.nodeFailed();
                    recordToolOutcome(action.tool(), false);
                    g = g.replace(node.withStatus(TaskNodeStatus.FAILED).withError(ev.reason()));
                    stateStore.appendEvent(sessionId, new DomainEvent(
                            "ev_" + UUID.randomUUID().toString().substring(0, 8),
                            DomainEventType.NODE_FAILED, action.actionId(), action.taskId(),
                            Map.of("reason", ev.reason()), null));
                    FailureDiagnosis dx = recoveryEngine.diagnose(node, action.tool(), ev.reason());
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
        }
        if (rounds > properties.getMaxOuterRounds())
            metrics.outerTimeout();

        if (StringUtils.isBlank(lastAnswer))
            lastAnswer = "已按规划图推进任务（version="
                    + stateStore.get(sessionId).map(StateSnapshot::version).orElse(0L)
                    + "，metrics=" + metrics.snapshot() + "）。";
        return lastAnswer;
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
        TaskGraph g = snap.graph();
        for (ActionSpec a : proposal.actions()) {
            TaskNode n = g.byId(a.taskId());
            if (n != null && n.status() == TaskNodeStatus.READY)
                g = g.replace(n.withStatus(TaskNodeStatus.RUNNING).withToolHint(a.tool()));
        }
        try {
            return stateStore.commit(sessionId, snap.version(), snap.withGraph(g));
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

    private void recordToolOutcome(String tool, boolean ok) {
        if (toolSuccessStats != null) toolSuccessStats.record(tool, ok);
    }

    private void trace(String sessionId, AgentStepNode node, String content) {
        if (traceRecorder == null) return;
        traceRecorder.recordNode(sessionId, 0, node.name(), content, RunStatus.SUCCESS.name(), 0);
    }
}
