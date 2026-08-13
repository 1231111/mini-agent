package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.RunStatus;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 只在 Proposal 允许的工具面上执行；hardGate 时锁定 todo 目标 id。
 */
@Component
public class ProposalExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProposalExecutor.class);
    private static final ThreadLocal<Boolean> LAST_DRIFT = new ThreadLocal<>();
    private static final String CONTINUE_HINT =
            "本段轮次用尽但验收未过。接着当前工具上下文继续，不要重复已经完成的打开/导航。"
                    + "若已有文档正文，立刻 write_file，禁止再 evaluate/scroll。";

    private final AgentLoop agentLoop;
    private final PlannerProperties properties;
    private final TodoStateProjector todoProjector;
    private final PlannerMetrics metrics;
    private final ToolRouter toolRouter;
    private final StepEvaluator stepEvaluator;
    private TraceRecorder traceRecorder;

    public ProposalExecutor(AgentLoop agentLoop,
                            PlannerProperties properties,
                            TodoStateProjector todoProjector,
                            PlannerMetrics metrics,
                            ToolRouter toolRouter,
                            StepEvaluator stepEvaluator) {
        this.agentLoop = agentLoop;
        this.properties = properties;
        this.todoProjector = todoProjector;
        this.metrics = metrics;
        this.toolRouter = toolRouter;
        this.stepEvaluator = stepEvaluator;
    }

    public void setTraceRecorder(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    /** PlanningLoop 在 execute 返回后读取本步是否漂移 */
    public static boolean consumeLastDrift() {
        Boolean v = LAST_DRIFT.get();
        LAST_DRIFT.remove();
        return Boolean.TRUE.equals(v);
    }

    public String execute(ChatModel chat,
                          String systemPrompt,
                          String userMessage,
                          UserMessage multimodalUser,
                          List<ChatMessage> history,
                          TaskPlan taskPlan,
                          ActionProposal proposal,
                          TaskGraph graph,
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
        PlanningContext.set(new PlanningContext.Holder(
                sessionId, proposal.basedOnVersion(), proposal.proposalId(),
                allowed, focusTask, focusName, focusTodos, true, hard,
                new java.util.concurrent.atomic.AtomicBoolean(false),
                new java.util.concurrent.atomic.AtomicInteger(0)));
        metrics.proposal();
        LAST_DRIFT.set(false);
        try {
            if (traceRecorder != null)
                traceRecorder.recordNode(sessionId, 0, AgentStepNode.PROPOSAL.name(),
                        "{\"proposalId\":\"" + proposal.proposalId()
                                + "\",\"basedOnVersion\":" + proposal.basedOnVersion()
                                + ",\"hardGate\":" + hard
                                + ",\"focusTodoIds\":" + focusTodos
                                + ",\"tools\":" + toJsonArray(allowed) + "}",
                        RunStatus.SUCCESS.name(), 0);
            String focus = systemPrompt + "\n\n"
                    + focusBlock(proposal, focusTodos, hard, allowed);
            int maxIter = Math.max(2, properties.getProposalMaxIterations());
            for (String t : allowed) {
                if (t.startsWith("browser_")) {
                    maxIter = Math.max(maxIter, 16);
                    break;
                }
            }
            AgentLoop.LoopOutcome out;
            if (multimodalUser != null)
                out = agentLoop.runWithMultimodalOutcome(chat, focus, multimodalUser,
                        history, maxIter, progress, taskPlan, streamSink);
            else
                out = agentLoop.runOutcome(chat, focus, userMessage, history,
                        maxIter, progress, taskPlan, streamSink);
            TaskNode focusNode = graph == null ? null : graph.byId(focusTask);
            int chunks = 1;
            int maxChunks = Math.max(1, properties.getProposalMaxChunks());
            while (shouldResumeChunk(out.endReason(),
                    nodeEvalOk(focusNode, sessionId, graph, out.text()),
                    chunks, maxChunks)) {
                log.info("Proposal 配额续跑 chunk={}/{} task={}", chunks + 1, maxChunks, focusTask);
                java.util.ArrayList<ChatMessage> live = new java.util.ArrayList<>(out.messages());
                live.add(new SystemMessage(CONTINUE_HINT));
                out = agentLoop.continueLoop(chat, live, userMessage,
                        maxIter, progress, taskPlan, streamSink);
                chunks++;
            }
            PlanningContext.Holder h = PlanningContext.get();
            if (h != null && h.consumeDrift())
                LAST_DRIFT.set(true);
            return out.text();
        } finally {
            PlanningContext.clear();
        }
    }

    static boolean shouldResumeChunk(String endReason, boolean evalOk, int chunk, int maxChunks) {
        return !evalOk && chunk < maxChunks
                && AgentLoop.LOOP_MAX_ITERATIONS.equals(endReason);
    }

    private boolean nodeEvalOk(TaskNode node, String sessionId, TaskGraph graph, String evidence) {
        if (node == null) return false;
        boolean todoDone = todoProjector.isTodoCompleted(sessionId, graph, node.id());
        return stepEvaluator.evaluateAfterLoop(node, todoDone, evidence).ok();
    }

    private static String focusBlock(ActionProposal proposal, Set<Integer> focusTodos,
                                     boolean hard, List<String> allowed) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Planner ActionProposal（硬闸门=").append(hard).append("）\n");
        sb.append("- proposalId: ").append(proposal.proposalId()).append('\n');
        sb.append("- basedOnVersion: ").append(proposal.basedOnVersion()).append('\n');
        sb.append("- focusTodoIds: ").append(focusTodos).append('\n');
        sb.append("框架已过滤工具表。禁止 todo.set/clear；只能 update 当前 focusTodoIds。\n");
        boolean hasBrowser = false;
        boolean hasWrite = false;
        if (allowed != null) {
            for (String t : allowed) {
                if (t.startsWith("browser_")) hasBrowser = true;
                if ("write_file".equals(t)) hasWrite = true;
            }
        }
        if (hasBrowser && hasWrite) {
            sb.append("本步必须：打开文档→提取全文→write_file。禁止 delegate_task，禁止空文件交差。\n");
            sb.append("飞书 wiki 是知识空间：点目录章节（如 1. LangChain），不要反复 navigate 同一链接。\n");
            sb.append("拿到 innerText 或 block_map 立刻 write_file，禁止滚动探测。\n");
        }
        else if (hasBrowser)
            sb.append("目录在视口外时用 browser_evaluate 点击，不要反复 click 等到超时。\n");
        sb.append("本步动作：\n");
        for (ActionSpec a : proposal.actions()) {
            sb.append("- taskId=").append(a.taskId())
                    .append(" tool=").append(a.tool())
                    .append(" expected=").append(a.expectedResult()).append('\n');
        }
        sb.append("完成后用 todo update（id∈focusTodoIds）标 completed 并附可校验 evidence。\n");
        return sb.toString();
    }

    private static String toJsonArray(List<String> tools) {
        return tools.stream()
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
