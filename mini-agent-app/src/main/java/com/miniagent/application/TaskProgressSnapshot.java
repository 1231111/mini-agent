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
                                   String reason, List<Item> items, List<String> outcomeReviewReceipts) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public record Item(int id, String content, String status, String note, List<Integer> dependsOn) {
        public Item { dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn); }
    }

    public TaskProgressSnapshot(String executionId, long taskId, String status, String reason, List<Item> items) {
        this(executionId, taskId, status, reason, items, List.of());
    }

    public TaskProgressSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
        outcomeReviewReceipts = outcomeReviewReceipts == null ? List.of() : List.copyOf(outcomeReviewReceipts);
    }

    public static TaskProgressSnapshot capture(String executionId, long taskId, String status,
                                               String reason, List<TaskTodoStore.TodoItem> todos) {
        List<Item> items = todos == null ? List.of() : todos.stream()
                .map(t -> new Item(t.id(), t.content(), t.status().name(), t.note(), t.dependsOn()))
                .toList();
        return new TaskProgressSnapshot(executionId, taskId, status, reason, items);
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
        return new TaskProgressSnapshot(snapshot.executionId(), taskId, status, unknown ? "OUTCOME_UNKNOWN" : "LEGACY_DAG", items);
    }
}
