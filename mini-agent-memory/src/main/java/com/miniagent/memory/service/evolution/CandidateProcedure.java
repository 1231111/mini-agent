package com.miniagent.memory.service.evolution;

import java.util.List;

/** Immutable, validated Procedure content used by the evolution service. */
public record CandidateProcedure(String description, List<String> preconditions,
                                 List<String> steps, List<String> successConditions) {
    public CandidateProcedure {
        description = description == null ? "" : description.trim();
        preconditions = clean(preconditions, "preconditions");
        steps = clean(steps, "steps");
        successConditions = clean(successConditions, "successConditions");
        if (steps.isEmpty()) throw new IllegalArgumentException("candidate steps must not be empty");
    }

    private static List<String> clean(List<String> values, String field) {
        if (values == null) return List.of();
        List<String> result = values.stream().map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty()).toList();
        if (result.size() > 100) throw new IllegalArgumentException(field + " exceeds 100 items");
        if (result.stream().anyMatch(value -> value.length() > 2000))
            throw new IllegalArgumentException(field + " item exceeds 2000 characters");
        return result;
    }
}
