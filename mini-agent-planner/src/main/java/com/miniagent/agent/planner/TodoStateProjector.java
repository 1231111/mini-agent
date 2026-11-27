package com.miniagent.agent.planner;

import com.miniagent.agent.todo.TaskTodoStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图 → Todo UI 单向投影。确认/完成只写 {@link TaskGraph}。
 */
@Component
public class TodoStateProjector {

    private final TaskTodoStore taskTodoStore;

    public TodoStateProjector(TaskTodoStore taskTodoStore) {
        this.taskTodoStore = taskTodoStore;
    }

    /** Read-only routing hint for an explicitly resumable prior task. */
    public String resumableTaskContext(String sessionId) {
        return taskTodoStore == null ? "" : taskTodoStore.renderResumableHint(sessionId);
    }

    /** Compact, user-visible goals for tasks that predate or do not use a Planner graph. */
    public String currentTaskContext(String sessionId) {
        if (taskTodoStore == null || sessionId == null || sessionId.isBlank()) return "";
        List<TaskTodoStore.TodoItem> items = taskTodoStore.get(sessionId);
        if (items.isEmpty()) return "";
        StringBuilder context = new StringBuilder("active checklist:");
        for (TaskTodoStore.TodoItem item : items) {
            if (context.length() >= 3_500) break;
            context.append("\n- [").append(item.status().name()).append("] ")
                    .append(conciseSummary(item.content()));
        }
        return context.toString();
    }

    public void project(String sessionId, TaskGraph graph) {
        project(sessionId, graph, null);
    }

    /** Project graph state and its authoritative identity/version as one UI snapshot. */
    public void project(String sessionId, StateSnapshot snapshot) {
        if (snapshot == null) return;
        TaskTodoStore.ProgressContext context = new TaskTodoStore.ProgressContext(
                snapshot.executionId(), snapshot.version(), conciseSummary(
                        snapshot.goal() == null ? "" : snapshot.goal().objective()),
                currentNodeId(snapshot.graph()), currentProgress(snapshot));
        project(sessionId, snapshot.graph(), context);
    }

    private void project(String sessionId, TaskGraph graph, TaskTodoStore.ProgressContext context) {
        if (sessionId == null || graph == null) {
            return;
        }
        Map<String, Integer> idMap = todoIdMap(graph);
        Map<Integer, String> acceptedEvidence = acceptedEvidence(sessionId);
        List<Map<String, Object>> raw = new ArrayList<>();
        for (TaskNode n : graph.nodes()) {
            if (n.status() == TaskNodeStatus.CANCELLED) {
                continue;
            }
            Integer id = idMap.get(n.id());
            if (id == null) {
                continue;
            }
            List<Integer> deps = new ArrayList<>();
            for (String d : n.dependsOn()) {
                Integer depId = idMap.get(d);
                if (depId != null) {
                    deps.add(depId);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("content", n.name());
            m.put("status", toTodoStatus(n.status()));
            m.put("done_when", n.doneWhen());
            // The graph only carries committed output. While a node is still
            // executing, its output is empty — projecting that empty value would
            // erase the evidence the executor just submitted, and the scheduler
            // would then grade the node on an unrelated last tool receipt.
            String nodeOutput = n.output();
            if ((nodeOutput == null || nodeOutput.isBlank())
                    && (n.status() == TaskNodeStatus.RUNNING
                        || n.status() == TaskNodeStatus.AWAITING_CONFIRM)) {
                nodeOutput = acceptedEvidence.getOrDefault(id, "");
            }
            m.put("evidence", nodeOutput);
            m.put("depends_on", deps);
            // 重跑 / 重规划标记：页面要区分"这个节点被恢复机制重跑过"和"这是重规划新生成的节点"。
            // retryCount 来自图节点；replanGeneration 只能从图 id 前缀 rpN_ 反推
            // （mergeKeepSuccess 生成的新节点一律带该前缀）。
            m.put("retry_count", n.retryCount());
            m.put("replan_generation", replanGeneration(n.id()));
            if (n.lastError() != null && !n.lastError().isBlank()) {
                m.put("note", n.lastError());
            }
            raw.add(m);
        }
        if (context == null) taskTodoStore.overwrite(sessionId, raw);
        else taskTodoStore.overwrite(sessionId, raw, context);
    }

    /** Evidence already accepted for this session's todos, keyed by todo id. */
    private Map<Integer, String> acceptedEvidence(String sessionId) {
        if (taskTodoStore == null) {
            return Map.of();
        }
        Map<Integer, String> evidence = new LinkedHashMap<>();
        for (TaskTodoStore.TodoItem item : taskTodoStore.get(sessionId)) {
            if (item.evidence() != null && !item.evidence().isBlank()) {
                evidence.put(item.id(), item.evidence());
            }
        }
        return evidence;
    }

    /**
     * Authoritative evidence submitted for one node. This is the persisted todo
     * record, not the conversation copy that the context budget clamps.
     */
    public String submittedEvidence(String sessionId, int todoId) {
        if (taskTodoStore == null || sessionId == null || todoId <= 0) {
            return "";
        }
        for (TaskTodoStore.TodoItem item : taskTodoStore.get(sessionId)) {
            if (item.id() == todoId) {
                return item.evidence() == null ? "" : item.evidence();
            }
        }
        return "";
    }

    private String currentNodeId(TaskGraph graph) {        if (graph == null) return "";
        TaskNode current = preferredCurrentNode(graph);
        if (current == null) return "";
        int id = todoIdFor(graph, current.id());
        return id <= 0 ? "" : Integer.toString(id);
    }

    private Map<String, Object> currentProgress(StateSnapshot snapshot) {
        TaskNode current = preferredCurrentNode(snapshot.graph());
        if (current == null) return Map.of();
        String message = switch (current.status()) {
            case RUNNING -> "执行器已开始此步骤";
            case AWAITING_CONFIRM -> "此步骤正在等待你的操作";
            case FAILED, RECOVERING, OUTCOME_UNKNOWN -> "此步骤受阻，任务状态已保留";
            case READY, PENDING -> "此步骤尚未开始执行";
            case SUCCESS -> "执行器已确认此步骤完成";
            case CANCELLED -> "此步骤已取消";
        };
        int id = todoIdFor(snapshot.graph(), current.id());
        if (id <= 0) return Map.of();
        return Map.of("executionId", snapshot.executionId() == null ? "" : snapshot.executionId(),
                "version", snapshot.version(), "nodeId", Integer.toString(id),
                "message", message);
    }

    private static TaskNode preferredCurrentNode(TaskGraph graph) {
        if (graph == null) return null;
        for (TaskNodeStatus preferred : List.of(TaskNodeStatus.RUNNING, TaskNodeStatus.AWAITING_CONFIRM,
                TaskNodeStatus.OUTCOME_UNKNOWN, TaskNodeStatus.FAILED, TaskNodeStatus.RECOVERING,
                TaskNodeStatus.READY, TaskNodeStatus.PENDING)) {
            for (TaskNode node : graph.nodes()) if (node.status() == preferred) return node;
        }
        return null;
    }

    private static String conciseSummary(String raw) {
        if (raw == null) return "";
        String text = raw.replaceAll("\\s+", " ").trim();
        if (text.length() <= 180) return text;
        int cut = text.lastIndexOf(' ', 177);
        if (cut < 80) cut = 177;
        return text.substring(0, cut).stripTrailing() + "…";
    }

    /** 图节点 id 形如 {@code rp2_node1}：2 表示第 2 次重规划生成；原始节点返回 0。 */
    static int replanGeneration(String taskId) {
        if (taskId == null || taskId.length() < 5 || !taskId.startsWith("rp")) {
            return 0;
        }
        int sep = taskId.indexOf('_');
        if (sep <= 2) {
            return 0;
        }
        try {
            int gen = Integer.parseInt(taskId.substring(2, sep));
            return Math.max(0, gen);
        }
        catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** taskId(图节点) → todo 数字 id */
    public int todoIdFor(TaskGraph graph, String taskId) {
        if (graph == null || taskId == null) {
            return 0;
        }
        Integer id = todoIdMap(graph).get(taskId);
        return id == null ? 0 : id;
    }

    public String nodeIdFor(TaskGraph graph, int todoId) {
        if (graph == null || todoId <= 0) {
            return "";
        }
        for (Map.Entry<String, Integer> e : todoIdMap(graph).entrySet()) {
            if (e.getValue() == todoId) {
                return e.getKey();
            }
        }
        return "";
    }

    public Map<String, Integer> todoIdMap(TaskGraph graph) {
        Map<String, Integer> idMap = new LinkedHashMap<>();
        if (graph == null) {
            return idMap;
        }
        int i = 1;
        for (TaskNode n : graph.nodes()) {
            if (n.status() == TaskNodeStatus.CANCELLED) {
                continue;
            }
            idMap.put(n.id(), i++);
        }
        return idMap;
    }

    /**
     * 模型在本步把对应 todo 标成 awaiting 时，把该信号抄到图上。
     * 确认之后不再从 Todo 回写图。
     */
    public boolean isTodoAwaiting(String sessionId, TaskGraph graph, String taskId) {
        int todoId = todoIdFor(graph, taskId);
        if (todoId <= 0 || sessionId == null) {
            return false;
        }
        for (TaskTodoStore.TodoItem it : taskTodoStore.get(sessionId)) {
            if (it.id() == todoId) {
                return it.status() == TaskTodoStore.Status.awaiting_confirm;
            }
        }
        return false;
    }

    /** 按页面 todo id 放行对应节点；无变化时返回原图。 */
    public TaskGraph confirmByTodoId(TaskGraph graph, int todoId) {
        String nodeId = nodeIdFor(graph, todoId);
        return confirmNode(graph, nodeId);
    }

    private static TaskGraph confirmNode(TaskGraph graph, String nodeId) {
        if (graph == null || nodeId == null || nodeId.isBlank()) {
            return graph;
        }
        TaskNode n = graph.byId(nodeId);
        if (n == null || n.status() != TaskNodeStatus.AWAITING_CONFIRM) {
            return graph;
        }
        // Confirmation releases the execution gate; it does not resolve the
        // failure. Keep the diagnostic, accepted output and retry history so
        // the resumed executor can change its next action instead of repeating
        // the strategy that caused this wait. Successful acceptance clears it.
        return graph.replace(n.withStatus(TaskNodeStatus.PENDING).withToolStatus(null));
    }

    private static String toTodoStatus(TaskNodeStatus s) {
        return switch (s) {
            case SUCCESS -> "completed";
            case RUNNING, READY -> "in_progress";
            case AWAITING_CONFIRM -> "awaiting_confirm";
            case FAILED, RECOVERING, OUTCOME_UNKNOWN -> "blocked";
            case CANCELLED -> "cancelled";
            case PENDING -> "pending";
        };
    }
}
