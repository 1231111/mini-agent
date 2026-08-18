package com.miniagent.agent.core;

import com.miniagent.agent.intent.TaskPlan;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/** Planner 的执行边界，拒绝未持有 dispatch fence 的节点执行。 */
@Component
public class NodeExecutor {
    private final AgentLoop agentLoop;
    public NodeExecutor(AgentLoop agentLoop) { this.agentLoop = agentLoop; }

    public AgentLoop.LoopOutcome execute(ChatModel chat, String system, String user, UserMessage multimodal,
                                         List<ChatMessage> history, int maxIterations, Consumer<String> progress,
                                         TaskPlan plan, AgentStreamSink sink) {
        requireFence();
        return multimodal == null ? agentLoop.runOutcome(chat, system, user, history, maxIterations, progress, plan, sink)
                : agentLoop.runWithMultimodalOutcome(chat, system, multimodal, history, maxIterations, progress, plan, sink);
    }

    public AgentLoop.LoopOutcome continueNode(ChatModel chat, List<ChatMessage> messages, String user,
                                              int maxIterations, Consumer<String> progress, TaskPlan plan,
                                              AgentStreamSink sink) {
        requireFence();
        return agentLoop.continueLoop(chat, messages, user, maxIterations, progress, plan, sink);
    }

    public String runDirect(ChatModel chat, String system, String user, List<ChatMessage> history,
                            int maxIterations, Consumer<String> progress, TaskPlan plan,
                            AgentStreamSink sink) {
        return agentLoop.run(chat, system, user, history, maxIterations, progress, plan, sink);
    }

    public String runDirectMultimodal(ChatModel chat, String system, UserMessage user,
                                      List<ChatMessage> history, int maxIterations,
                                      Consumer<String> progress, TaskPlan plan, AgentStreamSink sink) {
        return agentLoop.runWithMultimodal(chat, system, user, history, maxIterations, progress, plan, sink);
    }

    private static void requireFence() {
        ExecutionTurnContext.Scope scope = ExecutionTurnContext.current();
        if (scope == null || !scope.isValid()) throw new IllegalStateException("NodeExecutor 缺少有效 dispatch fence");
    }
}
