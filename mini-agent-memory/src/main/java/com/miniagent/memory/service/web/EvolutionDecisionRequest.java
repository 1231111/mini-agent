package com.miniagent.memory.service.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record EvolutionDecisionRequest(@NotBlank String tenantId, @NotNull @Positive Long proposalId,
                                       @NotBlank String candidateHash, @NotBlank String routerId,
                                       @NotBlank String routerAttestation) { }
