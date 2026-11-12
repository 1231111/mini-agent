package com.miniagent.application;

import com.miniagent.agent.planner.PlannerStateJson;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.agent.scope.TaskScope;
import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.repository.AgentSessionPlannerRepository;
import com.miniagent.config.repository.AgentTraceStepRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Restores legacy cards only when their persisted execution can be identified. */
@Service
public class ConversationTaskProgressService {
    private final AgentSessionPlannerRepository planners;
    private final AgentTraceStepRepository traces;
    private final ChatTaskRepository tasks;
    private record SavedGraph(StateSnapshot snapshot, LocalDateTime updatedAt) { }

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
        if (legacy.isEmpty()) return result;

        Map<String, SavedGraph> graphs = new HashMap<>();
        for (var row : planners.findBySessionIdOrSessionIdStartingWith(sessionId, sessionId + "#")) {
            if (!TaskScope.belongsTo(row.getSessionId(), sessionId)) continue;
            try {
                StateSnapshot snapshot = PlannerStateJson.snapshotFromJson(row.getStateJson());
                if (snapshot.executionId() != null && !snapshot.graph().nodes().isEmpty())
                    graphs.put(snapshot.executionId(), new SavedGraph(snapshot, row.getUpdatedAt()));
            } catch (RuntimeException ignored) { /* Other intact turns can still be restored. */ }
        }
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
}
