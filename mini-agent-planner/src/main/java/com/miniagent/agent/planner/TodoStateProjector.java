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
            m.put("done_when", n.doneWhen());
            m.put("evidence", n.output());
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
        taskTodoStore.overwrite(sessionId, raw);
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
        return graph.replace(n.withStatus(TaskNodeStatus.PENDING).withError(""));
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
