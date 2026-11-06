package com.miniagent.agent.core;

import com.miniagent.agent.execution.ToolPipeline;
import com.miniagent.agent.execution.ToolRequest;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/** Planner 的执行边界，拒绝未持有 dispatch fence 的节点执行。 */
@Component
public class NodeExecutor {
    private final AgentLoop agentLoop;
    private final ToolPipeline toolPipeline;

    public NodeExecutor(AgentLoop agentLoop, ToolPipeline toolPipeline) {
        this.agentLoop = agentLoop;
        this.toolPipeline = toolPipeline;
    }

    public AgentLoop.LoopOutcome execute(
            ChatModel chat, String system, String user, UserMessage multimodal,
            List<ChatMessage> history, int maxIterations,
            TaskPlan plan, AgentStreamSink sink) {
        requireFence();
        if (multimodal == null) {
            return agentLoop.runOutcome(
                    chat, system, user, history, maxIterations, plan, sink);
        }
        return agentLoop.runWithMultimodalOutcome(
                chat, system, multimodal, history, maxIterations, plan, sink);
    }

    public AgentLoop.LoopOutcome continueNode(
            ChatModel chat, List<ChatMessage> messages, String user,
            int maxIterations, TaskPlan plan,
            AgentStreamSink sink) {
        requireFence();
        return agentLoop.continueLoop(
                chat, messages, user, maxIterations, plan, sink);
    }

    /**
     * 参数已绑死时不再问模型选参，但仍走同一条 {@link ToolPipeline}。
     *
     * <p>同样要 {@code withApprovalWait()}：绑定动作和 ReAct 调用在授权上是同一件事。
     * 少了它，这条路径只会返回「等批准」并把控制权交回去，而用户点批准时
     * 已经没有等待中的调用点可以唤醒 —— 于是模型下一轮再提一次同样的调用、再弹一次窗。</p>
     */
    public ToolResult executeBound(String toolName, String argumentsJson) {
        requireFence();
        RunScope scope = RunScope.capture();
        return toolPipeline.invoke(
                        ToolRequest.bound(scope, toolName, argumentsJson).withApprovalWait())
                .result();
    }

    public String runDirect(ChatModel chat, String system, String user,
                            List<ChatMessage> history, int maxIterations,
                            TaskPlan plan, AgentStreamSink sink) {
        return agentLoop.run(
                chat, system, user, history, maxIterations, plan, sink);
    }

    public String runDirectMultimodal(
            ChatModel chat, String system, UserMessage user,
            List<ChatMessage> history, int maxIterations,
            TaskPlan plan, AgentStreamSink sink) {
        return agentLoop.runWithMultimodal(
                chat, system, user, history, maxIterations, plan, sink);
    }

    private static void requireFence() {
        ExecutionTurnContext.Scope scope = ExecutionTurnContext.current();
        if (scope == null || !scope.isValid()) {
            throw new IllegalStateException("NodeExecutor 缺少有效 dispatch fence");
        }
    }
}
