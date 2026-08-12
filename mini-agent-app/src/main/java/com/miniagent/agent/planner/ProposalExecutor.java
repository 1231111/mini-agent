package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.RunStatus;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
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

    private static final ThreadLocal<Boolean> LAST_DRIFT = new ThreadLocal<>();

    private final AgentLoop agentLoop;
    private final PlannerProperties properties;
    private final TodoStateProjector todoProjector;
    private final PlannerMetrics metrics;
    private TraceRecorder traceRecorder;

    public ProposalExecutor(AgentLoop agentLoop,
                            PlannerProperties properties,
                            TodoStateProjector todoProjector,
                            PlannerMetrics metrics) {
        this.agentLoop = agentLoop;
        this.properties = properties;
        this.todoProjector = todoProjector;
        this.metrics = metrics;
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
        List<String> allowed = ToolRouter.allowedTools(proposal, properties.isHardProposal());
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
            String focus = systemPrompt + "\n\n" + focusBlock(proposal, focusTodos, hard);
            int maxIter = Math.max(2, properties.getProposalMaxIterations());
            String answer;
            if (multimodalUser != null)
                answer = agentLoop.runWithMultimodal(chat, focus, multimodalUser, history,
                        maxIter, progress, taskPlan, streamSink);
            else
                answer = agentLoop.run(chat, focus, userMessage, history,
                        maxIter, progress, taskPlan, streamSink);
            PlanningContext.Holder h = PlanningContext.get();
            if (h != null && h.consumeDrift())
                LAST_DRIFT.set(true);
            return answer;
        } finally {
            PlanningContext.clear();
        }
    }

    private static String focusBlock(ActionProposal proposal, Set<Integer> focusTodos, boolean hard) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Planner ActionProposal（硬闸门=").append(hard).append("）\n");
        sb.append("- proposalId: ").append(proposal.proposalId()).append('\n');
        sb.append("- basedOnVersion: ").append(proposal.basedOnVersion()).append('\n');
        sb.append("- focusTodoIds: ").append(focusTodos).append('\n');
        sb.append("框架已过滤工具表。禁止 todo.set/clear；只能 update 当前 focusTodoIds。\n");
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
