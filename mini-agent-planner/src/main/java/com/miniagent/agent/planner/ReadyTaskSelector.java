package com.miniagent.agent.planner;

import java.util.List;

/**
 * 依赖就绪选择：仅 READY；有 RUNNING 时不调度其它节点（独占）。
 */
public class ReadyTaskSelector {

    public List<TaskNode> select(TaskGraph graph) {
        if (graph == null || graph.isEmpty()) return List.of();
        if (graph.hasRunning()) return List.of();
        return graph.readyNodes();
    }
}
