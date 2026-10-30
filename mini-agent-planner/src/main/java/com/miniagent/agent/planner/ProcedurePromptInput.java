package com.miniagent.agent.planner;

import java.util.List;

/** Structured, owner-authorized procedure input exposed to the planner. */
public record ProcedurePromptInput(Long procedureId, Integer version, String name,
                                   String description, List<String> preconditions,
                                   List<String> steps, List<String> successConditions,
                                   Long evolutionProposalId, String evolutionCandidateHash,
                                   String procedureVariant, int canaryTargetPercent) {
    public ProcedurePromptInput {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        steps = steps == null ? List.of() : List.copyOf(steps);
        successConditions = successConditions == null ? List.of() : List.copyOf(successConditions);
        evolutionCandidateHash = evolutionCandidateHash == null ? "" : evolutionCandidateHash.trim();
        procedureVariant = procedureVariant == null || procedureVariant.isBlank()
                ? (evolutionProposalId == null ? "BASELINE" : "CANDIDATE")
                : procedureVariant.trim().toUpperCase(java.util.Locale.ROOT);
        if (canaryTargetPercent < 0 || canaryTargetPercent > 100)
            throw new IllegalArgumentException("canaryTargetPercent must be between 0 and 100");
        if (!"BASELINE".equals(procedureVariant) && !"CANDIDATE".equals(procedureVariant))
            throw new IllegalArgumentException("procedureVariant must be BASELINE or CANDIDATE");
        if (evolutionProposalId == null && !evolutionCandidateHash.isEmpty())
            throw new IllegalArgumentException("candidate hash requires an evolution proposal id");
        if (evolutionProposalId != null && (evolutionProposalId <= 0
                || !evolutionCandidateHash.matches("[0-9a-f]{64}") || !"CANDIDATE".equals(procedureVariant)))
            throw new IllegalArgumentException("candidate input requires proposal id, hash, and CANDIDATE variant");
    }

    public ProcedurePromptInput(Long procedureId, Integer version, String name, String description,
                                List<String> preconditions, List<String> steps, List<String> successConditions) {
        this(procedureId, version, name, description, preconditions, steps, successConditions,
                null, "", "BASELINE", 0);
    }
}
