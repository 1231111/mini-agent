package com.miniagent.agent.planner;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 任务 DAG。
 */
public record TaskGraph(List<TaskNode> nodes) {
    public TaskGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public TaskNode byId(String id) {
        if (id == null) {
            return null;
        }
        for (TaskNode n : nodes)
            if (id.equals(n.id())) {
                return n;
            }
        return null;
    }

    public TaskGraph replace(TaskNode updated) {
        List<TaskNode> next = new ArrayList<>(nodes.size());
        for (TaskNode n : nodes)
            next.add(Objects.equals(n.id(), updated.id()) ? updated : n);
        return new TaskGraph(next);
    }

    public TaskGraph replaceAll(List<TaskNode> all) {
        return new TaskGraph(all);
    }

    /** 依赖均已 SUCCESS 且自身为 READY 的节点（调用前请先 {@link #normalizeForScheduling()}） */
    @JsonIgnore
    public List<TaskNode> readyNodes() {
        List<TaskNode> ready = new ArrayList<>();
        for (TaskNode n : nodes) {
            if (n.status() == TaskNodeStatus.READY)
                ready.add(n);
        }
        ready.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        return ready;
    }

    @JsonIgnore
    public boolean hasRunning() {
        for (TaskNode n : nodes)
            if (n.status() == TaskNodeStatus.RUNNING) {
                return true;
            }
        return false;
    }

    @JsonIgnore
    public boolean hasAwaitingConfirm() {
        for (TaskNode n : nodes)
            if (n.status() == TaskNodeStatus.AWAITING_CONFIRM) {
                return true;
            }
        return false;
    }

    /**
     * 调度前规范化：RUNNING 表示工具可能已派发，进程中断后无法证明副作用未发生，
     * 因而必须隔离为结果未知；再将依赖满足的 PENDING/FAILED/RECOVERING→READY。
     */
    @JsonIgnore
    public TaskGraph normalizeForScheduling() {
        List<TaskNode> phase1 = new ArrayList<>(nodes.size());
        for (TaskNode n : nodes) {
            if (n.toolStatus() == com.miniagent.agent.tool.ToolStatus.UNKNOWN
                    || n.status() == TaskNodeStatus.RUNNING)
                phase1.add(n.withStatus(TaskNodeStatus.OUTCOME_UNKNOWN)
                        .withError(n.lastError().isBlank()
                                ? "OUTCOME_UNKNOWN: 执行中断，实际副作用尚未核验" : n.lastError()));
            else
                phase1.add(n);
        }
        Map<String, TaskNodeStatus> st = new HashMap<>();
        for (TaskNode n : phase1) {
            st.put(n.id(), n.status());
        }
        List<TaskNode> phase2 = new ArrayList<>(phase1.size());
        for (TaskNode n : phase1) {
            TaskNodeStatus s = n.status();
            if (s == TaskNodeStatus.PENDING || s == TaskNodeStatus.READY || s == TaskNodeStatus.FAILED
                    || s == TaskNodeStatus.RECOVERING) {
                boolean depsOk = true;
                for (String d : n.dependsOn()) {
                    if (st.get(d) != TaskNodeStatus.SUCCESS) {
                        depsOk = false;
                        break;
                    }
                }
                if (depsOk)
                    phase2.add(n.withStatus(TaskNodeStatus.READY));
                else if (s != TaskNodeStatus.PENDING)
                    phase2.add(n.withStatus(TaskNodeStatus.PENDING));
                else
                    phase2.add(n);
            } else {
                phase2.add(n);
            }
        }
        return new TaskGraph(phase2);
    }

    @JsonIgnore
    public boolean allTerminalSuccess() {
        if (nodes.isEmpty()) {
            return false;
        }
        for (TaskNode n : nodes)
            if (n.status() != TaskNodeStatus.SUCCESS)
                return false;
        return true;
    }

    @JsonIgnore
    public boolean hasCycle() {
        Map<String, List<String>> adj = new HashMap<>();
        for (TaskNode n : nodes) {
            adj.put(n.id(), n.dependsOn());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (TaskNode n : nodes)
            if (cycleDfs(n.id(), adj, visiting, visited)) {
                return true;
            }
        return false;
    }

    private static boolean cycleDfs(String id, Map<String, List<String>> adj,
                                   Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) {
            return false;
        }
        if (visiting.contains(id)) {
            return true;
        }
        visiting.add(id);
        for (String d : adj.getOrDefault(id, List.of()))
            if (cycleDfs(d, adj, visiting, visited)) {
                return true;
            }
        visiting.remove(id);
        visited.add(id);
        return false;
    }
}
