package com.miniagent.memory.service.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record ProjectAuthorizationRequest(
        @NotBlank String tenantId,
        @NotBlank String projectId,
        @NotNull @Positive Long ownerUserId,
        List<@Positive Long> memberUserIds,
        @NotNull @Positive Long version
) {
}
