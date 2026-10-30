package com.miniagent.memory.service.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record FailureEvidenceRequest(
        @NotBlank String tenantId,
        @NotNull @Positive Long userId,
        @NotNull @Positive Long procedureId,
        @NotNull @Positive Integer procedureVersion,
        @NotBlank @Size(max = 100) String sessionId,
        @NotNull Long taskId,
        @NotBlank @Size(max = 128) String nodeId,
        @NotBlank @Size(max = 128) String failureCode,
        @NotBlank @Size(max = 500) String taskSummary,
        @NotBlank @Size(max = 4000) String resolution
) { }
