package com.miniagent.web.dto.resp;

import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.config.entity.AgentTraceStep;

import java.util.List;

/** One-request snapshot used by the chat inspector to avoid multiplying API traffic per trace event. */
public record InspectorSnapshotDTO(
        List<TaskTodoStore.TodoItem> todos,
        boolean running,
        TraceExecutionDTO execution,
        List<AgentTraceStep> steps) {

    public InspectorSnapshotDTO {
        todos = todos == null ? List.of() : List.copyOf(todos);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
