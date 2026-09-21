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

    public void project(String sessionId, TaskGraph graph) {
        if (sessionId == null || graph == null) {
            return;
        }
        Map<String, Integer> idMap = todoIdMap(graph);
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
            m.put("done_when", n.doneWhen().wire());
            m.put("depends_on", deps);
            if (n.lastError() != null && !n.lastError().isBlank()) {
                m.put("note", n.lastError());
            }
            raw.add(m);
        }
        taskTodoStore.overwrite(sessionId, raw);
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

    /** 聊天答复：放行图上第一个 AWAITING_CONFIRM。 */
    public TaskGraph confirmFirst(TaskGraph graph) {
        if (graph == null) {
            return graph;
        }
        for (TaskNode n : graph.nodes()) {
            if (n.status() == TaskNodeStatus.AWAITING_CONFIRM) {
                return graph.replace(n.withStatus(TaskNodeStatus.PENDING).withError(""));
            }
        }
        return graph;
    }

    private static TaskGraph confirmNode(TaskGraph graph, String nodeId) {
        if (graph == null || nodeId == null || nodeId.isBlank()) {
            return graph;
        }
        TaskNode n = graph.byId(nodeId);
        if (n == null || n.status() != TaskNodeStatus.AWAITING_CONFIRM) {
            return graph;
        }
        return graph.replace(n.withStatus(TaskNodeStatus.PENDING).withError(""));
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
