package com.miniagent.agent.planner;

import java.util.List;

/** Structured, owner-authorized procedure input exposed to the planner. */
public record ProcedurePromptInput(Long procedureId, Integer version, String name,
                                   String description, List<String> preconditions,
                                   List<String> steps, List<String> successConditions) {
    public ProcedurePromptInput {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        steps = steps == null ? List.of() : List.copyOf(steps);
        successConditions = successConditions == null ? List.of() : List.copyOf(successConditions);
    }
}
