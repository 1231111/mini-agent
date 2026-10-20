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
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
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
     * 本轮是否进入规划器。
     *
     * <p>默认 Agent 路径对齐 WorkBuddy：不先用词表判断「简单任务/复杂任务」，
     * 每条消息直接交给 AgentLoop，由 LLM 自己决定回答、调用工具、反问或委派。
     * 只有用户显式把会话切到 {@link PermissionMode#PLAN}，才进入
     * GoalCompiler → PlanValidator → GraphScheduler 这条计划执行链。</p>
     *
     * <p>默认 Agent 模式不因为历史上有未完成图、用户说「继续」或消息命中某个
     * 任务信号而重新进入规划器；这些消息仍交给 AgentLoop，让 LLM 结合会话历史自行处理。
     * 规划器只由显式 {@link PermissionMode#PLAN} 模式打开。</p>
     */
    public boolean shouldHandle(TaskPlan plan, String sessionId, String requestText) {
        return properties.isEnabled()
                && plan != null
                && PermissionContext.mode() == PermissionMode.PLAN;
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
    public record RunOutcome(String answer, Outcome outcome, String reason, boolean degraded) {
        public RunOutcome {
            answer = answer == null ? "" : answer;
            reason = reason == null ? "" : reason;
        }

        public RunOutcome(String answer, Outcome outcome, String reason) {
            this(answer, outcome, reason, false);
        }

        public boolean goalSatisfied() {
            return outcome == Outcome.COMPLETED;
        }

        static RunOutcome satisfied(String answer) {
            return satisfied(answer, false);
        }

        static RunOutcome satisfied(String answer, boolean degraded) {
            return new RunOutcome(answer, Outcome.COMPLETED,
                    degraded ? "GRAPH_COMPLETED_DEGRADED" : "GRAPH_COMPLETED", degraded);
        }

        static RunOutcome direct(String answer) {
            return new RunOutcome(answer, Outcome.COMPLETED, "DIRECT_LOOP");
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
                      Consumer<String> progress,
                      AgentStreamSink streamSink) {
        return runWithOutcome(chat, systemPrompt, userMessage, multimodalUser, history, taskPlan,
                sessionId, fencingToken, executionId, progress, streamSink).answer();
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
                      Consumer<String> progress,
                      AgentStreamSink streamSink) {
        if (!shouldHandle(taskPlan, sessionId, userMessage)) {
            // 直跑路径：结局由 AgentLoop 自己判定，规划器不替它宣称完成
            if (multimodalUser != null) {
                return RunOutcome.direct(nodeExecutor.runDirectMultimodal(chat, systemPrompt,
                        multimodalUser, history, 90, progress, taskPlan, streamSink));
            }
            return RunOutcome.direct(nodeExecutor.runDirect(chat, systemPrompt, userMessage, history,
                    90, progress, taskPlan, streamSink));
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
                taskPlan.signals(), userMessage,
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
        if (clarifyRecompile) {
            String merged = mergeClarifyObjective(
                    existing.get().goal().objective(), userMessage);
            TaskPlan nextPlan = taskPlan.withTaskGoal(merged);
            compiled = compileAndValidate(chat, merged, nextPlan);
            if (!planValidator.accept(compiled.graph(), nextPlan, compiled.goal())) {
                // 同上：澄清后图仍编不出来，降级直跑（合并后的目标进直跑消息，历史仍在），
                // 不报「任务图未通过验收」。
                log.warn("PlanningLoop 澄清后任务图仍无法验收，降级直跑 session={} code={}",
                        sessionId, ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getCode());
                return directFallback(chat, systemPrompt, merged, multimodalUser,
                        history, nextPlan, progress, streamSink);
            }
            String execId = StringUtils.isNotBlank(executionId)
                    ? executionId : "exec_" + UUID.randomUUID().toString().substring(0, 8);
            snap = stateStore.init(sessionId, execId, compiled.goal(), compiled.graph(),
                    compiled.intent(), compiled.source(), compiled.degraded());
            log.info("PlanningLoop 澄清后重编译 session={} nodes={} clarify={}",
                    sessionId, compiled.graph().nodes().size(),
                    compiled.goal().isClarify());
        } else if (planRevision) {
            String merged = mergeClarifyObjective(
                    existing.get().goal().objective(), userMessage);
            TaskPlan nextPlan = taskPlan.withTaskGoal(merged);
            compiled = compileAndValidate(chat, merged, nextPlan);
            String execId = StringUtils.isNotBlank(executionId)
                    ? executionId : "exec_" + UUID.randomUUID().toString().substring(0, 8);
            // 重编译后是全新快照，审批状态自动回到未批准，会再次停下等确认。
            snap = stateStore.init(sessionId, execId, compiled.goal(), compiled.graph(),
                    compiled.intent(), compiled.source(), compiled.degraded());
            log.info("PlanningLoop 按审批意见重编译 session={} nodes={}",
                    sessionId, compiled.graph().nodes().size());
        } else if (resume) {
            snap = existing.get();
            compiled = new GoalCompiler.CompileResult(snap.goal(), snap.graph(), snap.degraded(),
                    snap.compilationSource(), "", snap.intent());
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

        } else {
            compiled = compileAndValidate(chat, userMessage, taskPlan);
            if (!planValidator.accept(compiled.graph(), taskPlan, compiled.goal())) {
                // 规划模型没产出可用图 ≠ 任务失败。对齐 WorkBuddy：没有「图没编出来就拒活」的硬门，
                // 编译/验收失败一律降级直跑 —— 交给 AgentLoop 让模型自主选工具完成请求，
                // 而不是回一句「任务图未通过验收，无法调度」。这是切换到 AgentLoop 的直跑路径，
                // 直跑是另一条正确路径：模型看得到历史与全部工具面，自己决定怎么干。
                log.warn("PlanningLoop 任务图验收失败，降级直跑 session={} code={}",
                        sessionId, ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getCode());
                return directFallback(chat, systemPrompt, userMessage, multimodalUser,
                        history, taskPlan, progress, streamSink);
            }
            String execId = StringUtils.isNotBlank(executionId)
                    ? executionId : "exec_" + UUID.randomUUID().toString().substring(0, 8);
            snap = stateStore.init(sessionId, execId, compiled.goal(), compiled.graph(),
                    compiled.intent(), compiled.source(), compiled.degraded());
        }
        todoProjector.project(sessionId, snap.graph());
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
            if (progress != null) {
                progress.accept("规划模型未返回有效任务图，本轮使用降级编译结果；"
                        + "完成情况可能弱于模型规划，可在 trace 的 GOAL_COMPILED 查看 source。");
            }
        }

        String lastAnswer = "";
        int rounds = 0;
        // 只有"图全部通过终验"这一条路会把 completed 置真；其余出口都是"没做完"。
        String unfinishedReason = "NO_READY_NODES";
        while (rounds++ < properties.getMaxOuterRounds()) {
            if (!sessionLock.renewSessionLock(sessionId, fencingToken)) {
                log.warn("PlanningLoop 会话锁丢失，中止 session={} code={}",
                        sessionId, ErrorCode.AGENT_PLANNER_LOCK_LOST.getCode());
                metrics.outerTimeout();
                String msg = ErrorCode.AGENT_PLANNER_LOCK_LOST.getMessage();
                String answer = StringUtils.isBlank(lastAnswer) ? msg : lastAnswer + "\n（" + msg + "）";
                return RunOutcome.failed(answer, "SESSION_LOCK_LOST");
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
                        unfinishedReason = "ACCEPTANCE_NODE_MISSING";
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
                // A node-level AgentLoop deliberately returns STEP_SEGMENT_DONE when the
                // proposal's focus todo is complete.  That marker is useful for the
                // scheduler, but it is not a user-facing answer.  If every node ended
                // that way (bound actions are another common case), lastAnswer is empty
                // after keepBetterAnswer filters the marker.  Never let a successful
                // graph leak an empty SSE `end` event; derive a concrete summary from
                // the persisted node outputs instead.
                return RunOutcome.satisfied(buildCompletedAnswer(lastAnswer, snap.graph()),
                        compiled.degraded());
            }
            List<TaskNode> ready = graphScheduler.select(snap.graph());
            if (ready.isEmpty()) {
                if (snap.graph().hasAwaitingConfirm()) {
                    log.info("PlanningLoop 等待人工 confirm session={}", sessionId);
                    String hint = firstAwaitingHint(snap.graph());
                    if (StringUtils.isNotBlank(lastAnswer)) {
                        return RunOutcome.blockedOnHuman(lastAnswer, "AWAITING_CONFIRM");
                    }
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
                snap = applyRecoveryOrCancel(sessionId, snap, stuck, dx, stuck.id(),
                        chat, userMessage, taskPlan);
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

            // 只提案 READY 节点
            ActionProposal proposal = graphScheduler.propose(snap, ready,
                    properties.getProposalBatchSize());
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
                    return RunOutcome.blockedOnHuman(lastAnswer, "HUMAN_WAIT");
                }
                return RunOutcome.blockedOnHuman("需要你确认后才能继续。", "HUMAN_WAIT");
            }
            TaskGraph latest = stateStore.get(sessionId).map(StateSnapshot::graph).orElse(g);
            if (latest.hasAwaitingConfirm() || g.hasAwaitingConfirm()) {
                log.info("PlanningLoop 等待人工输入 session={}", sessionId);
                if (StringUtils.isNotBlank(lastAnswer)) {
                    return RunOutcome.blockedOnHuman(lastAnswer, "AWAITING_CONFIRM");
                }
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

        if (StringUtils.isBlank(lastAnswer))
            lastAnswer = "已按规划图推进任务（version="
                    + stateStore.get(sessionId).map(StateSnapshot::version).orElse(0L)
                    + "，metrics=" + metrics.snapshot() + "）。";
        // 走到这里说明是"没做完"退出（死锁/恢复耗尽/轮次用尽/配额中止）。
        // 绝不能把这种结果报成完成：上层会据此决定任务状态与"完成率"口径，
        // 而用户看到的这句"已按规划图推进"听起来像成功。附带未完成节点让用户知道差在哪。
        String pending = pendingSummary(sessionId);
        if (StringUtils.isNotBlank(pending)) {
            lastAnswer = lastAnswer + "\n\n⚠️ 任务未完成（" + unfinishedReason + "）。仍待处理：\n" + pending;
        }
        return RunOutcome.unfinished(lastAnswer, unfinishedReason);
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
                sb.append("- [").append(n.status()).append("] ")
                        .append(StringUtils.isBlank(n.name()) ? n.id() : n.name());
                if (StringUtils.isNotBlank(n.lastError())) {
                    String err = n.lastError();
                    sb.append("（").append(err.length() <= 120 ? err : err.substring(0, 120) + "…")
                            .append("）");
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
            if (n != null && StringUtils.isNotBlank(n.lastError())) {
                sb.append("上一轮失败上下文（必须先处理，不要盲重试）：")
                        .append(n.lastError()).append('\n');
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
        sb.append("完成后用 todo update（id∈focusTodoIds）标 completed 并附可校验 evidence。\n");
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
            return stateStore.get(sessionId).orElse(snap);
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
                // 把诊断出的处置建议交给模型。此前只用于选分支（规则恢复 vs 重规划），
                // 模型这边只看到「失败原因」，得自己重推一遍该修参数、换工具还是改目标 ——
                // 而失败分类正是这里做的判断（RecoveryEngine.classify），模型重推纯属重复劳动且会跑偏。
                + "\n建议处置: " + dx.suggestedFix()
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
        if (compiled == null || compiled.degradedCompilation()
                || compiled.graph() == null || compiled.graph().isEmpty()) {
            return Optional.empty();
        }
        TaskGraph merged = mergeKeepSuccess(
                snap.graph(), compiled.graph(), snap.recoveryCount() + 1);
        if (merged == null || merged.isEmpty()) {
            return Optional.empty();
        }
        if (!mergeValidationReports(
                planValidator.validate(merged, taskPlan, compiled.goal()),
                intentGraphValidator.validate(compiled.intent(), merged)).valid()) {
            log.warn("运行时 LLM replan 图验收失败（结构或意图）session={}", sessionId);
            return Optional.empty();
        }
        Goal nextGoal = compiled.goal() != null ? compiled.goal() : snap.goal();
        Map<String, Object> exec = new HashMap<>(snap.execution());
        exec.put("recovery." + dx.failureClass().name(), used + 1);
        exec.put("lastFailureKind", dx.kind().name());
        StateSnapshot patched = snap.revisePlan(merged, nextGoal, compiled.intent(),
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

    /** 编译/验收失败后的直跑降级：多模态走多模态直跑，否则走文本直跑。结局由 AgentLoop 自己判定。 */
    private RunOutcome directFallback(ChatModel chat, String systemPrompt, String userMessage,
                                      UserMessage multimodalUser, List<ChatMessage> history,
                                      TaskPlan taskPlan, Consumer<String> progress,
                                      AgentStreamSink streamSink) {
        if (multimodalUser != null) {
            return RunOutcome.direct(nodeExecutor.runDirectMultimodal(chat, systemPrompt,
                    multimodalUser, history, 90, progress, taskPlan, streamSink));
        }
        return RunOutcome.direct(nodeExecutor.runDirect(chat, systemPrompt, userMessage, history,
                90, progress, taskPlan, streamSink));
    }

    private GoalCompiler.CompileResult compileAndValidate(ChatModel chat,
                                                         String userMessage,
                                                         TaskPlan taskPlan) {
        // 第一次编译
        GoalCompiler.CompileResult compiled = goalCompiler.compile(chat, userMessage, taskPlan);

        // 结构化验证
        PlanValidationReport report = mergeValidationReports(
                planValidator.validate(compiled.graph(), taskPlan, compiled.goal()),
                intentGraphValidator.validate(compiled.intent(), compiled.graph()));
        if (report.valid()) {
            return compiled;
        }

        // 保留第一次拒绝原因。后面即使 replan 失败，也必须能从
        // GOAL_COMPILED 看出「模型产图但未通过验收」，不能退化成无原因 TEMPLATE。
        String initialValidation = report.summary();
        log.info("PlanValidator 拒绝，进入 replan 闭环，错误数={}", report.errors().size());
        log.debug("验证报告: {}", initialValidation);

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
            report = mergeValidationReports(
                    planValidator.validate(replanned.graph(), taskPlan, replanned.goal()),
                    intentGraphValidator.validate(replanned.intent(), replanned.graph()));
            if (report.valid()) {
                log.info("Replan 成功，尝试次数={}", attempt);
                return replanned;
            }

            log.warn("Replan 尝试 {} 仍然失败，错误数={}", attempt, report.errors().size());
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
