package com.miniagent.agent.core;

import java.util.List;
import java.util.Set;

/**
 * 本轮循环闸门。规划器注入提案策略；非规划路径用 {@link #NONE}。
 * DIP: AgentLoop / TodoTool 只依赖本端口，不依赖 planner 包。
 */
public interface LoopTurnPolicy {

    LoopTurnPolicy NONE = new Open();

    List<String> allowedTools();

    boolean forceToolsOnly();

    boolean hardGate();

    /** @return 拒绝原因 JSON；null 表示放行 */
    String denyTool(String toolName);

    String denyTodoArgs(String argumentsJson);

    String focusLabel();

    Set<Integer> focusTodoIds();

    void markDrift();

    int driftHits();

    boolean consumeDrift();

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
