package com.miniagent.agent.core;

import com.miniagent.agent.tool.ToolResult;

import java.util.List;
import java.util.Set;

/**
 * 本轮循环闸门。规划器注入提案策略；非规划路径用 {@link #NONE}。
 * DIP: AgentLoop / TodoTool 只依赖本端口，不依赖 planner 包。
 */
public interface LoopTurnPolicy {

    enum StopKind { NONE, NO_PROGRESS, TOOL_BUDGET_EXHAUSTED }

    LoopTurnPolicy NONE = new Open();

    List<String> allowedTools();

    boolean forceToolsOnly();

    boolean hardGate();

    /** @return 拒绝原因 JSON；null 表示放行 */
    String denyTool(String toolName);

    String denyTodoArgs(String argumentsJson);

    /** Per-invocation authorization, after the model has supplied tool arguments. */
    default String denyToolInvocation(String toolName, String argumentsJson) {
        return denyTool(toolName);
    }

    /** Observe one terminal tool result. Returns a stop reason when the node is stagnating. */
    default String observeToolOutcome(String toolName, String argumentsJson, ToolResult result) {
        return "";
    }

    /** Observe the exact result chunk made visible to the model after context shaping. */
    default String observeToolOutcome(String toolName, String argumentsJson, ToolResult result,
                                      String visibleResult) {
        return observeToolOutcome(toolName, argumentsJson, result);
    }

    /** A policy may terminate the current loop after a hard per-node circuit breaker trips. */
    default String stopReason() { return ""; }

    /** Typed terminal cause for the policy stop; callers must not infer it from display text. */
    default StopKind stopKind() { return StopKind.NONE; }

    String focusLabel();

    Set<Integer> focusTodoIds();

    void markDrift();

    int driftHits();

    boolean consumeDrift();

    /**
     * 本步节点超时上限（秒）。0=不额外封顶，沿用工具自身 timeout。
     */
    default int actionTimeoutSeconds() {
        return 0;
    }

    /** 非规划：不过滤、不闸门。 */
    final class Open implements LoopTurnPolicy {
        @Override
        public List<String> allowedTools() { return List.of(); }

        @Override
        public boolean forceToolsOnly() { return false; }

        @Override
        public boolean hardGate() { return false; }

        @Override
        public String denyTool(String toolName) { return null; }

        @Override
        public String denyTodoArgs(String argumentsJson) { return null; }

        @Override
        public String focusLabel() { return ""; }

        @Override
        public Set<Integer> focusTodoIds() { return Set.of(); }

        @Override
        public void markDrift() {}

        @Override
        public int driftHits() { return 0; }

        @Override
        public boolean consumeDrift() { return false; }
    }
}
