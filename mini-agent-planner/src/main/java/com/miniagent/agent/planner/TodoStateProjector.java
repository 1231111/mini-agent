package com.miniagent.agent.planner;

import com.miniagent.agent.todo.TaskTodoStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * StateSnapshot.graph → Todo UI 投影（Todo 不再当主状态）。
 */
@Component
public class TodoStateProjector {

    private final TaskTodoStore taskTodoStore;

    public TodoStateProjector(TaskTodoStore taskTodoStore) {
        this.taskTodoStore = taskTodoStore;
    }

    public void project(String sessionId, TaskGraph graph) {
        if (sessionId == null || graph == null) return;
        Map<String, Integer> idMap = todoIdMap(graph);
        List<Map<String, Object>> raw = new ArrayList<>();
        for (TaskNode n : graph.nodes()) {
            if (n.status() == TaskNodeStatus.CANCELLED) continue;
            Integer id = idMap.get(n.id());
            if (id == null) continue;
            List<Integer> deps = new ArrayList<>();
            for (String d : n.dependsOn()) {
                Integer depId = idMap.get(d);
                if (depId != null) deps.add(depId);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("content", n.name());
            m.put("status", toTodoStatus(n.status()));
            m.put("done_when", n.doneWhen().wire());
            m.put("depends_on", deps);
            if (n.lastError() != null && !n.lastError().isBlank())
                m.put("note", n.lastError());
            raw.add(m);
        }
        taskTodoStore.set(sessionId, raw);
    }

    /** taskId(图节点) → todo 数字 id */
    public int todoIdFor(TaskGraph graph, String taskId) {
        if (graph == null || taskId == null) return 0;
        Integer id = todoIdMap(graph).get(taskId);
        return id == null ? 0 : id;
    }

    public Map<String, Integer> todoIdMap(TaskGraph graph) {
        Map<String, Integer> idMap = new LinkedHashMap<>();
        if (graph == null) return idMap;
        int i = 1;
        for (TaskNode n : graph.nodes()) {
            if (n.status() == TaskNodeStatus.CANCELLED) continue;
            idMap.put(n.id(), i++);
        }
        return idMap;
    }

    /** 从 Todo 回读节点是否已 completed（执行后同步）。 */
    public boolean isTodoCompleted(String sessionId, TaskGraph graph, String taskId) {
        int todoId = todoIdFor(graph, taskId);
        if (todoId <= 0 || sessionId == null) return false;
        for (TaskTodoStore.TodoItem it : taskTodoStore.get(sessionId))
            if (it.id() == todoId)
                return it.status() == TaskTodoStore.Status.completed;
        return false;
    }

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

    public void yieldNodeToHuman(String sessionId, TaskGraph graph, String taskId,
                                 String note) {
        int todoId = todoIdFor(graph, taskId);
        Set<Integer> ids = todoId > 0 ? Set.of(todoId) : Set.of();
        taskTodoStore.yieldToHuman(sessionId, ids, note);
    }

    public boolean confirmAwaiting(String sessionId, String note) {
        return taskTodoStore.confirmAwaiting(sessionId, note);
    }

    public String todoEvidence(String sessionId, TaskGraph graph, String taskId) {
        int todoId = todoIdFor(graph, taskId);
        if (todoId <= 0 || sessionId == null) return "";
        for (TaskTodoStore.TodoItem it : taskTodoStore.get(sessionId))
            if (it.id() == todoId)
                return it.evidence() == null ? "" : it.evidence();
        return "";
    }

    /**
     * 与 Todo confirm 门禁对齐：awaiting_confirm ↔ AWAITING_CONFIRM；
     * 已确认则从 AWAITING_CONFIRM 回到 PENDING 以便 normalize→READY。
     */
    public TaskGraph syncConfirmFromTodo(String sessionId, TaskGraph graph) {
        if (sessionId == null || graph == null) return graph;
        Map<String, Integer> idMap = todoIdMap(graph);
        Map<Integer, TaskTodoStore.Status> todoStatus = new LinkedHashMap<>();
        for (TaskTodoStore.TodoItem it : taskTodoStore.get(sessionId))
            todoStatus.put(it.id(), it.status());
        List<TaskNode> next = new ArrayList<>();
        for (TaskNode n : graph.nodes()) {
            Integer tid = idMap.get(n.id());
            if (tid == null) {
                next.add(n);
                continue;
            }
            TaskTodoStore.Status ts = todoStatus.get(tid);
            if (ts == TaskTodoStore.Status.awaiting_confirm)
                next.add(n.withStatus(TaskNodeStatus.AWAITING_CONFIRM));
            else if (n.status() == TaskNodeStatus.AWAITING_CONFIRM
                    && (ts == TaskTodoStore.Status.in_progress || ts == TaskTodoStore.Status.pending
                    || ts == TaskTodoStore.Status.completed))
                next.add(n.withStatus(TaskNodeStatus.PENDING));
            else
                next.add(n);
        }
        return new TaskGraph(next);
    }

    private static String toTodoStatus(TaskNodeStatus s) {
        return switch (s) {
            case SUCCESS -> "completed";
            case RUNNING, READY -> "in_progress";
            case AWAITING_CONFIRM -> "awaiting_confirm";
            case FAILED, RECOVERING -> "blocked";
            case CANCELLED -> "cancelled";
            case PENDING -> "pending";
        };
    }
}
