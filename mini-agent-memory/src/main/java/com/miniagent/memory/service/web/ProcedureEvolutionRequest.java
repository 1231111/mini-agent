package com.miniagent.memory.service.web;

import com.miniagent.memory.service.evolution.CandidateProcedure;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record ProcedureEvolutionRequest(
        @NotBlank String tenantId,
        @NotNull @Positive Long userId,
        @NotNull @Positive Long procedureId,
        @NotNull @Positive Integer baseVersion,
        @NotBlank String candidateId,
        @NotBlank String generatorVersion,
        @NotNull CandidateProcedure candidate,
        @NotNull List<@NotBlank String> sourceEvidenceIds
) {
    public ProcedureEvolutionRequest {
        sourceEvidenceIds = sourceEvidenceIds == null ? List.of() : List.copyOf(sourceEvidenceIds);
    }
}
