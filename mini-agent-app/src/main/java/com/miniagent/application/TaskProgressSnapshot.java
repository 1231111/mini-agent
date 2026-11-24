package com.miniagent.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.agent.todo.TaskTodoStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable UI projection of actual task nodes, never inferred from tool iterations. */
public record TaskProgressSnapshot(String executionId, long taskId, String status,
                                   String reason, List<Item> items, List<String> outcomeReviewReceipts,
                                   String summary, long version, String currentNodeId,
                                   Map<String, Object> currentProgress) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public record Item(int id, String content, String status, String note, List<Integer> dependsOn) {
        public Item { dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn); }
    }

    public TaskProgressSnapshot(String executionId, long taskId, String status, String reason, List<Item> items) {
        this(executionId, taskId, status, reason, items, List.of(), "", 0L, "", Map.of());
    }

    public TaskProgressSnapshot(String executionId, long taskId, String status, String reason,
                                List<Item> items, List<String> outcomeReviewReceipts) {
        this(executionId, taskId, status, reason, items, outcomeReviewReceipts, "", 0L, "", Map.of());
    }

    public TaskProgressSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
        outcomeReviewReceipts = outcomeReviewReceipts == null ? List.of() : List.copyOf(outcomeReviewReceipts);
        summary = summary == null ? "" : summary;
        currentNodeId = currentNodeId == null ? "" : currentNodeId;
        currentProgress = currentProgress == null ? Map.of() : Map.copyOf(currentProgress);
    }

    public static TaskProgressSnapshot capture(String executionId, long taskId, String status,
                                               String reason, List<TaskTodoStore.TodoItem> todos) {
        return capture(executionId, taskId, status, reason, todos, null);
    }

    public static TaskProgressSnapshot capture(String executionId, long taskId, String status,
                                               String reason, List<TaskTodoStore.TodoItem> todos,
                                               TaskTodoStore.ProgressContext context) {
        boolean contextMatches = context != null && (executionId == null || executionId.isBlank()
                || context.executionId().isBlank() || context.executionId().equals(executionId));
        List<TaskTodoStore.TodoItem> currentTodos = context != null && !contextMatches ? List.of() : todos;
        List<Item> items = currentTodos == null ? List.of() : currentTodos.stream()
                .map(t -> new Item(t.id(), t.content(), t.status().name(), t.note(), t.dependsOn()))
                .toList();
        String activeExecution = contextMatches && !context.executionId().isBlank()
                ? context.executionId() : executionId;
        return new TaskProgressSnapshot(activeExecution, taskId, status, reason, items, List.of(),
                contextMatches ? context.summary() : "", contextMatches ? context.version() : 0L,
                contextMatches ? context.currentNodeId() : "",
                contextMatches ? context.currentProgress() : Map.of());
    }

    public String toJson() {
        try { return JSON.writeValueAsString(this); }
        catch (Exception e) { throw new IllegalStateException("Cannot persist task progress", e); }
    }

    public static TaskProgressSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return JSON.readValue(json, TaskProgressSnapshot.class); }
        catch (Exception e) { return null; } // A damaged legacy card must not hide its answer.
    }

    public static TaskProgressSnapshot fromGraph(StateSnapshot snapshot) {
        Map<String, Integer> ids = new LinkedHashMap<>();
        snapshot.graph().nodes().forEach(n -> ids.put(n.id(), ids.size() + 1));
        List<Item> items = new ArrayList<>();
        snapshot.graph().nodes().forEach(n -> items.add(new Item(ids.get(n.id()), n.name(),
                switch (n.status()) {
                    case SUCCESS -> "completed";
                    case RUNNING, READY -> "in_progress";
                    case AWAITING_CONFIRM -> "awaiting_confirm";
                    case FAILED, RECOVERING, OUTCOME_UNKNOWN -> "blocked";
                    case CANCELLED -> "cancelled";
                    case PENDING -> "pending";
                }, n.lastError(), n.dependsOn().stream().filter(ids::containsKey).map(ids::get).toList())));
        String status = snapshot.graph().nodes().stream().anyMatch(n -> n.status() == TaskNodeStatus.AWAITING_CONFIRM)
                ? "WAITING" : items.stream().anyMatch(n -> "blocked".equals(n.status())) ? "FAILURE"
                : !items.isEmpty() && items.stream().allMatch(n -> "cancelled".equals(n.status())) ? "CANCELED"
                : !items.isEmpty() && items.stream().allMatch(n -> "completed".equals(n.status())
                        || "cancelled".equals(n.status())) ? "SUCCESS" : "INTERRUPTED";
        long taskId = 0;
        if (snapshot.sessionId() != null && snapshot.sessionId().contains("#")) {
            try { taskId = Long.parseLong(snapshot.sessionId().substring(snapshot.sessionId().lastIndexOf('#') + 1)); }
            catch (NumberFormatException ignored) { }
        }
        boolean unknown = snapshot.graph().nodes().stream().anyMatch(n -> n.status() == TaskNodeStatus.OUTCOME_UNKNOWN
                || n.toolStatus() == com.miniagent.agent.tool.ToolStatus.UNKNOWN);
        String summary = snapshot.goal() == null ? "" : snapshot.goal().objective();
        String currentNodeId = "";
        Map<String, Object> currentProgress = Map.of();
        for (TaskNodeStatus preferred : List.of(TaskNodeStatus.RUNNING, TaskNodeStatus.AWAITING_CONFIRM,
                TaskNodeStatus.OUTCOME_UNKNOWN, TaskNodeStatus.FAILED, TaskNodeStatus.RECOVERING,
                TaskNodeStatus.READY, TaskNodeStatus.PENDING)) {
            for (var node : snapshot.graph().nodes()) {
                if (node.status() != preferred || !ids.containsKey(node.id())) continue;
                String id = Integer.toString(ids.get(node.id()));
                currentNodeId = id;
                currentProgress = Map.of("executionId", snapshot.executionId() == null ? "" : snapshot.executionId(),
                        "version", snapshot.version(), "nodeId", id,
                        "message", currentStateMessage(preferred));
                break;
            }
            if (!currentNodeId.isBlank()) break;
        }
        return new TaskProgressSnapshot(snapshot.executionId(), taskId, status,
                unknown ? "OUTCOME_UNKNOWN" : "LEGACY_DAG", items, List.of(), summary,
                snapshot.version(), currentNodeId, currentProgress);
    }

    private static String currentStateMessage(TaskNodeStatus status) {
        return switch (status) {
            case RUNNING -> "执行器已开始此步骤";
            case AWAITING_CONFIRM -> "此步骤正在等待你的操作";
            case OUTCOME_UNKNOWN -> "操作结果待核验";
            case FAILED -> "此步骤受阻";
            case RECOVERING -> "执行器正在恢复此步骤";
            case READY -> "此步骤已就绪，等待执行器调度";
            case PENDING -> "此步骤等待前置步骤完成";
            case SUCCESS -> "执行器已确认此步骤完成";
            case CANCELLED -> "此步骤已取消";
        };
    }
}
