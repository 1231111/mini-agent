package com.miniagent.memory.service.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CandidateValidationRequest(
        @NotBlank String tenantId,
        @NotNull @Positive Long userId,
        @NotNull @Positive Long proposalId,
        @NotBlank String candidateHash,
        @NotBlank String suiteId,
        @NotBlank String suiteVersion,
        @NotBlank String runnerAttestation,
        @NotNull List<@NotNull Trial> trials
) {
    public CandidateValidationRequest {
        trials = trials == null ? List.of() : List.copyOf(trials);
    }

    public record Trial(@NotBlank String sampleId, @NotBlank String variant,
                        @NotNull Boolean passed, @NotNull Long durationMillis) { }
}
