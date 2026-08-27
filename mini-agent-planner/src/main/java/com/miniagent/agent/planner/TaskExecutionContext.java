package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.intent.TaskPlan;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 统一的任务执行上下文，封装 PlanningLoop.run() 所需的全部参数。
 *
 * <p>设计目标：
 * <ul>
 *   <li>消除 PlanningLoop.run() 的 12+ 参数列表</li>
 *   <li>提供分层上下文访问（全局/节点/工具）</li>
 *   <li>支持上下文快照和恢复（用于检查点/回滚）</li>
 *   <li>不可变，线程安全</li>
 * </ul>
 *
 * <p>上下文分层：
 * <ul>
 *   <li><b>全局层</b>：session、execution、用户消息、系统提示、历史消息</li>
 *   <li><b>规划层</b>：TaskPlan、Goal、TaskGraph</li>
 *   <li><b>节点层</b>：当前执行节点、前置节点输出</li>
 *   <li><b>工具层</b>：允许的工具列表、工具路由规则</li>
 * </ul>
 */
public record TaskExecutionContext(
        // ===== 全局层 =====
        /** 会话ID，用于隔离不同用户的任务 */
        String sessionId,
        /** 执行ID，用于追踪单次任务执行 */
        String executionId,
        /** 用户原始消息 */
        String userMessage,
        /** 多模态用户消息（图片等） */
        UserMessage multimodalUser,
        /** 系统提示词 */
        String systemPrompt,
        /** 对话历史 */
        List<ChatMessage> history,
        /** LLM模型实例 */
        ChatModel chatModel,
        /** 流式输出接收器 */
        AgentStreamSink streamSink,
        /** 进度回调 */
        Consumer<String> progress,

        // ===== 规划层 =====
        /** 任务规划结果 */
        TaskPlan taskPlan,
        /** 编译后的目标 */
        Goal goal,
        /** 任务执行图 */
        TaskGraph graph,
        /** 状态快照（含版本号，用于CAS） */
        StateSnapshot stateSnapshot,

        // ===== 节点层 =====
        /** 当前提案（正在执行的节点集合） */
        ActionProposal currentProposal,
        /** 前置节点产出（key: artifact名, value: 产出内容） */
        Map<String, String> predecessorOutputs,

        // ===== 工具层 =====
        /** 当前允许使用的工具列表 */
        List<String> allowedTools,
        /** 是否启用硬闸门（严格模式） */
        boolean hardGate,

        // ===== 控制层 =====
        /** 单轮最大迭代次数 */
        int maxIterations,
        /** 最大续跑轮次 */
        int maxChunks,
        /** 外层最大轮次 */
        int maxOuterRounds
) {

    public TaskExecutionContext {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(userMessage, "userMessage");
    }

    // ===== 工厂方法 =====

    /**
     * 从 PlanningLoop.run() 的参数创建上下文。
     * 这是主要的入口点，保持向后兼容。
     */
    public static TaskExecutionContext of(
            ChatModel chatModel,
            String systemPrompt,
            String userMessage,
            UserMessage multimodalUser,
            List<ChatMessage> history,
            TaskPlan taskPlan,
            String sessionId,
            String executionId,
            Consumer<String> progress,
            AgentStreamSink streamSink,
            PlannerProperties properties) {
        return new TaskExecutionContext(
                sessionId,
                executionId != null ? executionId : "exec_" + UUID.randomUUID().toString().substring(0, 8),
                userMessage,
                multimodalUser,
                systemPrompt,
                history,
                chatModel,
                streamSink,
                progress,
                taskPlan,
                null, // goal - 编译后设置
                null, // graph - 编译后设置
                null, // stateSnapshot - 初始化后设置
                null, // currentProposal - 执行时设置
                Map.of(), // predecessorOutputs - 执行时设置
                List.of(), // allowedTools - 提案时设置
                properties.isHardProposal(),
                properties.getProposalMaxIterations(),
                properties.getProposalMaxChunks(),
                properties.getMaxOuterRounds()
        );
    }

    // ===== 不可变更新方法 =====

    /** 设置编译结果（Goal + TaskGraph） */
    public TaskExecutionContext withCompiled(Goal goal, TaskGraph graph) {
        return new TaskExecutionContext(
                sessionId, executionId, userMessage, multimodalUser, systemPrompt,
                history, chatModel, streamSink, progress, taskPlan, goal, graph,
                stateSnapshot, currentProposal, predecessorOutputs, allowedTools,
                hardGate, maxIterations, maxChunks, maxOuterRounds
        );
    }

    /** 设置状态快照 */
    public TaskExecutionContext withSnapshot(StateSnapshot snapshot) {
        return new TaskExecutionContext(
                sessionId, executionId, userMessage, multimodalUser, systemPrompt,
                history, chatModel, streamSink, progress, taskPlan, goal, graph,
                snapshot, currentProposal, predecessorOutputs, allowedTools,
                hardGate, maxIterations, maxChunks, maxOuterRounds
        );
    }

    /** 设置当前提案和前置输出 */
    public TaskExecutionContext withProposal(ActionProposal proposal, Map<String, String> predecessorOutputs) {
        return new TaskExecutionContext(
                sessionId, executionId, userMessage, multimodalUser, systemPrompt,
                history, chatModel, streamSink, progress, taskPlan, goal, graph,
                stateSnapshot, proposal, predecessorOutputs, allowedTools,
                hardGate, maxIterations, maxChunks, maxOuterRounds
        );
    }

    /** 设置允许的工具列表 */
    public TaskExecutionContext withAllowedTools(List<String> tools) {
        return new TaskExecutionContext(
                sessionId, executionId, userMessage, multimodalUser, systemPrompt,
                history, chatModel, streamSink, progress, taskPlan, goal, graph,
                stateSnapshot, currentProposal, predecessorOutputs, tools,
                hardGate, maxIterations, maxChunks, maxOuterRounds
        );
    }

    /** 更新用户消息（用于续跑） */
    public TaskExecutionContext withUserMessage(String newMessage) {
        return new TaskExecutionContext(
                sessionId, executionId, newMessage, multimodalUser, systemPrompt,
                history, chatModel, streamSink, progress, taskPlan, goal, graph,
                stateSnapshot, currentProposal, predecessorOutputs, allowedTools,
                hardGate, maxIterations, maxChunks, maxOuterRounds
        );
    }

    // ===== 便捷访问方法 =====

    /** 获取当前执行节点 */
    public TaskNode currentTaskNode() {
        if (currentProposal == null || currentProposal.actions().isEmpty() || graph == null) {
            return null;
        }
        return graph.byId(currentProposal.actions().get(0).taskId());
    }

    /** 获取当前任务ID */
    public String currentTaskId() {
        TaskNode node = currentTaskNode();
        return node != null ? node.id() : null;
    }

    /** 判断是否需要续跑 */
    public boolean shouldResumeChunk(String endReason, int chunk) {
        return chunk < maxChunks
                && (isLoopLimit(endReason)
                || com.miniagent.agent.core.AgentLoop.RESOURCE_QUOTA_EXCEEDED.equals(endReason)
                || "TENANT_QUOTA_EXCEEDED".equals(endReason)
                || "TOKEN_BUDGET_EXCEEDED".equals(endReason)
                || "TOOL_BUDGET_EXCEEDED".equals(endReason));
    }

    private static boolean isLoopLimit(String endReason) {
        return endReason != null
                && (com.miniagent.agent.core.AgentLoop.LOOP_MAX_ITERATIONS.equalsIgnoreCase(endReason)
                || "loop_max_iterations".equalsIgnoreCase(endReason));
    }

    /** 判断是否达到外层轮次上限 */
    public boolean isOuterRoundLimit(int rounds) {
        return rounds >= maxOuterRounds;
    }

    /** 获取状态版本号 */
    public long version() {
        return stateSnapshot != null ? stateSnapshot.version() : 0;
    }

    /** 获取计划版本号 */
    public long planVersion() {
        return stateSnapshot != null ? stateSnapshot.planVersion() : 0;
    }

    /** 是否有图且未全部成功 */
    public boolean hasIncompleteGraph() {
        return graph != null && !graph.isEmpty() && !graph.allTerminalSuccess();
    }

    /** 是否有待确认节点 */
    public boolean hasAwaitingConfirm() {
        return graph != null && graph.hasAwaitingConfirm();
    }
}
