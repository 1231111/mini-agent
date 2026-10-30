package com.miniagent.memory.service.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CanaryObservationRequest(@NotBlank String tenantId, @NotNull @Positive Long proposalId,
                                       @NotBlank String candidateHash, @NotBlank String observationId,
                                       @NotBlank String variant, @NotNull Boolean passed,
                                       @NotBlank String routerAttestation) {
    public CanaryObservationRequest {
        if (!"BASELINE".equals(variant) && !"CANDIDATE".equals(variant)) {
            throw new IllegalArgumentException("variant must be BASELINE or CANDIDATE");
        }
    }
}
