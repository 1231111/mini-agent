package com.miniagent.application;

import com.miniagent.agent.planner.PlannerStateJson;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.agent.scope.TaskScope;
import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.repository.AgentSessionPlannerRepository;
import com.miniagent.config.repository.AgentTraceStepRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;

/** Restores legacy cards only when their persisted execution can be identified. */
@Service
public class ConversationTaskProgressService {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationTaskProgressService.class);
    private final AgentSessionPlannerRepository planners;
    private final AgentTraceStepRepository traces;
    private final ChatTaskRepository tasks;
    private record SavedGraph(StateSnapshot snapshot, LocalDateTime updatedAt) { }
    public record ReviewedCard(Long messageId, TaskProgressSnapshot progress) { }

    public ConversationTaskProgressService(AgentSessionPlannerRepository planners,
                                           AgentTraceStepRepository traces, ChatTaskRepository tasks) {
        this.planners = planners;
        this.traces = traces;
        this.tasks = tasks;
    }

    public Map<Long, TaskProgressSnapshot> forMessages(Long userId, String sessionId, List<ChatTask> page) {
        Map<Long, TaskProgressSnapshot> result = new HashMap<>();
        List<ChatTask> legacy = new ArrayList<>();
        for (ChatTask task : page) {
            TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromJson(task.getTaskProgressJson());
            if (snapshot != null) result.put(task.getId(), snapshot);
            else legacy.add(task);
        }
        boolean reviewRepair = result.values().stream().anyMatch(card -> "OUTCOME_UNKNOWN".equals(card.reason()));
        if (legacy.isEmpty() && !reviewRepair) return result;

        Map<String, SavedGraph> graphs = new HashMap<>();
        for (var row : planners.findBySessionIdOrSessionIdStartingWith(sessionId, sessionId + "#")) {
            if (!TaskScope.belongsTo(row.getSessionId(), sessionId)) continue;
            try {
                StateSnapshot snapshot = PlannerStateJson.snapshotFromJson(row.getStateJson());
                if (snapshot.executionId() != null && !snapshot.graph().nodes().isEmpty())
                    graphs.put(snapshot.executionId(), new SavedGraph(snapshot, row.getUpdatedAt()));
            } catch (RuntimeException ignored) { /* Other intact turns can still be restored. */ }
        }
        if (reviewRepair) {
            for (SavedGraph graph : graphs.values()) {
                if (!result.values().stream().anyMatch(card -> Objects.equals(card.executionId(), graph.snapshot().executionId())
                        && "OUTCOME_UNKNOWN".equals(card.reason()))) continue;
                try {
                    ReviewedCard updated = persistReviewedOutcome(userId, sessionId, graph.snapshot());
                    if (updated != null && result.containsKey(updated.messageId())) result.put(updated.messageId(), updated.progress());
                } catch (RuntimeException repairFailure) {
                    LOG.warn("Reviewed task-card projection repair deferred session={} execution={}",
                            sessionId, graph.snapshot().executionId(), repairFailure);
                }
            }
        }
        if (legacy.isEmpty()) return result;
        if (graphs.isEmpty()) return result;
        Map<String, List<AgentTraceStep>> executions = new HashMap<>();
        for (AgentTraceStep trace : traces.findBySessionIdOrderByTurnIndexAscIdAsc(sessionId)) {
            if (!graphs.containsKey(trace.getExecutionId()) || trace.getCreatedAt() == null) continue;
            if (trace.getParentExecutionId() != null
                    && !trace.getParentExecutionId().equals(trace.getExecutionId())) continue;
            executions.computeIfAbsent(trace.getExecutionId(), ignored -> new ArrayList<>()).add(trace);
        }

        for (ChatTask task : legacy) {
            if (task.getId() == null || task.getCreatedAt() == null) continue;
            LocalDateTime previous = tasks
                    .findFirstByUserIdAndSessionIdAndDeletedFalseAndIdLessThanOrderByIdDesc(userId, sessionId, task.getId())
                    .map(ChatTask::getCreatedAt).orElse(LocalDateTime.MIN);
            List<String> matches = executions.entrySet().stream().filter(entry -> {
                List<AgentTraceStep> steps = entry.getValue();
                LocalDateTime start = steps.stream().map(AgentTraceStep::getCreatedAt)
                        .min(LocalDateTime::compareTo).orElse(LocalDateTime.MIN);
                LocalDateTime graphUpdated = graphs.get(entry.getKey()).updatedAt();
                return start.isAfter(previous) && !start.isAfter(task.getCreatedAt())
                        // A resumed task may have overwritten this graph after the old
                        // reply. Its newer node statuses are not that reply's snapshot.
                        && (graphUpdated == null || !graphUpdated.isAfter(task.getCreatedAt()))
                        && steps.stream().anyMatch(s -> Objects.equals(task.getQuestion(), s.getUserQuestion())
                            || ("ANSWER".equals(s.getStepType()) && task.getAnswer() != null
                                && !task.getAnswer().isBlank() && task.getAnswer().equals(s.getContent())));
            }).map(Map.Entry::getKey).toList();
            // Repeated questions are disambiguated by the persisted turn interval;
            // ambiguous or absent evidence never becomes a fabricated DAG.
            if (matches.size() == 1)
                result.put(task.getId(), TaskProgressSnapshot.fromGraph(graphs.get(matches.get(0)).snapshot()));
        }
        return result;
    }

    /**
     * Update only the latest saved reply of the exact execution, and only the
     * reviewed node(s). Later graph progress must never rewrite old reply cards.
     * Reconciliation history is already durable in Planner, so this is also a
     * read-repair after a projection write failed or the process stopped.
     */
    public ReviewedCard persistReviewedOutcome(Long userId, String sessionId, StateSnapshot graph) {
        if (userId == null || sessionId == null || graph == null
                || !TaskScope.belongsTo(graph.sessionId(), sessionId)
                || graph.executionId() == null || graph.executionId().isBlank()
                || !(graph.execution().get("outcomeReconciliations") instanceof List<?> history) || history.isEmpty()) return null;
        // Retry a competing projection once; never overwrite a changed reply blindly.
        for (int attempt = 0; attempt < 2; attempt++) {
            ChatTask task = latestExecutionCard(userId, sessionId, graph.executionId());
            if (task == null || task.getCreatedAt() == null) return null;
            String expected = task.getTaskProgressJson();
            TaskProgressSnapshot saved = TaskProgressSnapshot.fromJson(expected);
            if (saved == null || (!"OUTCOME_UNKNOWN".equals(saved.reason()) && saved.outcomeReviewReceipts().isEmpty())) return null;
            TaskProgressSnapshot patched = reviewedProjection(saved, task.getCreatedAt(), userId, graph, history);
            if (patched == null || patched.equals(saved)) return null;
            if (tasks.compareAndSetTaskProgress(task.getId(), userId, sessionId, expected, patched.toJson()) == 1)
                return new ReviewedCard(task.getId(), patched);
        }
        throw new IllegalStateException("Task-card projection changed during outcome review");
    }

    private ChatTask latestExecutionCard(Long userId, String sessionId, String executionId) {
        for (int page = 0; ; page++) {
            var rows = tasks.findByUserIdAndSessionIdAndDeletedFalseOrderByIdDesc(userId, sessionId, PageRequest.of(page, 100));
            if (rows == null || rows.isEmpty()) return null;
            for (ChatTask task : rows) {
                if (!Objects.equals(task.getUserId(), userId) || !Objects.equals(task.getSessionId(), sessionId) || task.isDeleted()) continue;
                TaskProgressSnapshot saved = TaskProgressSnapshot.fromJson(task.getTaskProgressJson());
                if (saved != null && Objects.equals(saved.executionId(), executionId)) return task;
            }
            if (!rows.hasNext()) return null;
        }
    }

    private static TaskProgressSnapshot reviewedProjection(TaskProgressSnapshot saved, LocalDateTime createdAt,
                                                            Long userId, StateSnapshot graph, List<?> history) {
        // TodoStateProjector omits cancelled nodes; older fromGraph cards include them.
        List<TaskProgressSnapshot.Item> complete = TaskProgressSnapshot.fromGraph(graph).items();
        Map<String, Integer> ids = new LinkedHashMap<>();
        graph.graph().nodes().stream().filter(n -> n.status() != TaskNodeStatus.CANCELLED)
                .forEach(n -> ids.put(n.id(), ids.size() + 1));
        List<TaskProgressSnapshot.Item> active = graph.graph().nodes().stream().filter(n -> ids.containsKey(n.id()))
                .map(n -> new TaskProgressSnapshot.Item(ids.get(n.id()), n.name(), "", "",
                        n.dependsOn().stream().filter(ids::containsKey).map(ids::get).toList())).toList();
        boolean allLayout = sameLayout(saved.items(), complete);
        if (!allLayout && !sameLayout(saved.items(), active)) return null;
        if (allLayout) {
            ids.clear(); graph.graph().nodes().forEach(n -> ids.put(n.id(), ids.size() + 1));
        }
        List<TaskProgressSnapshot.Item> items = new ArrayList<>(saved.items());
        List<String> receipts = new ArrayList<>(saved.outcomeReviewReceipts());
        Instant savedAt = createdAt.atZone(ZoneId.systemDefault()).toInstant();
        for (Object entry : history) {
            if (!(entry instanceof Map<?, ?> review) || !(review.get("reviewerId") instanceof Number actor)
                    || actor.longValue() != userId || !(review.get("previousVersion") instanceof Number version)
                    || !(review.get("previousPlanVersion") instanceof Number plan) || !(review.get("nodeId") instanceof String nodeId)
                    || !(review.get("decision") instanceof String decision) || !(review.get("at") instanceof String at)) continue;
            Instant reviewedAt;
            try { reviewedAt = Instant.parse(at); } catch (RuntimeException invalidTime) { continue; }
            // A decision preceding a later reply is not permission to alter that later turn.
            if (reviewedAt.isBefore(savedAt) || !ids.containsKey(nodeId)) continue;
            if (!"CONFIRMED_APPLIED".equals(decision) && !"CONFIRMED_NOT_APPLIED".equals(decision)) continue;
            String receipt = version.longValue() + ":" + plan.longValue() + ":" + nodeId + ":" + decision;
            if (receipts.contains(receipt)) continue;
            int todoId = ids.get(nodeId);
            for (int i = 0; i < items.size(); i++) {
                TaskProgressSnapshot.Item item = items.get(i);
                if (item.id() != todoId || !"blocked".equalsIgnoreCase(item.status())) continue;
                boolean applied = "CONFIRMED_APPLIED".equals(decision);
                items.set(i, new TaskProgressSnapshot.Item(item.id(), item.content(), applied ? "completed" : "pending",
                        applied ? "已核验：操作已生效。" : "已核验：操作未生效，等待继续执行。", item.dependsOn()));
                receipts.add(receipt);
                break;
            }
        }
        if (receipts.equals(saved.outcomeReviewReceipts())) return saved;
        boolean blocked = items.stream().anyMatch(i -> "blocked".equalsIgnoreCase(i.status()));
        // A later graph may already have progressed past a different saved blocked
        // node. Without its own explicit review receipt, preserve the saved warning.
        boolean unresolved = blocked && "OUTCOME_UNKNOWN".equals(saved.reason());
        // Human verification is not whole-goal acceptance. A later explicit turn resumes the DAG.
        return new TaskProgressSnapshot(saved.executionId(), saved.taskId(), blocked ? "FAILURE" : "WAITING",
                unresolved ? "OUTCOME_UNKNOWN" : blocked ? "TASK_INCOMPLETE" : "OUTCOME_REVIEWED", items, receipts);
    }

    private static boolean sameLayout(List<TaskProgressSnapshot.Item> saved, List<TaskProgressSnapshot.Item> graph) {
        if (saved.size() != graph.size()) return false;
        Map<Integer, TaskProgressSnapshot.Item> expected = new HashMap<>();
        for (TaskProgressSnapshot.Item item : graph) {
            if (expected.put(item.id(), item) != null) return false;
        }
        for (TaskProgressSnapshot.Item item : saved) {
            TaskProgressSnapshot.Item match = expected.remove(item.id());
            if (match == null || !Objects.equals(item.content(), match.content())
                    || !Objects.equals(item.dependsOn(), match.dependsOn())) return false;
        }
        return expected.isEmpty();
    }
}
