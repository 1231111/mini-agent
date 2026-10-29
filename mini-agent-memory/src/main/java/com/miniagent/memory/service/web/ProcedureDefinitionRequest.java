package com.miniagent.memory.service.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 执行侧 Procedure 投影请求。
 *
 * <p>这里刻意没有结算计数字段：计数只能由 {@code ProcedureSettlementController}
 * 根据真实节点终态累加。允许投影覆盖计数，等于给了一条绕过事实的通道——
 * 任何一次重投影都能把成功率改写成任意值。</p>
 */
public record ProcedureDefinitionRequest(
        @NotBlank String tenantId,
        @NotNull @Positive Long userId,
        @NotNull @Positive Long procedureId,
        @NotNull @Positive Integer procedureVersion,
        @NotBlank @Pattern(regexp = "USER|PROJECT", message = "scopeType must be USER or PROJECT")
        String scopeType,
        @NotBlank String scopeId,
        @NotBlank String name,
        String description,
        String preconditionsJson,
        @NotBlank String stepsJson,
        String successConditionsJson,
        Double importance,
        @NotBlank @Pattern(regexp = "ACTIVE|ARCHIVED|DELETED", message = "status must be ACTIVE, ARCHIVED or DELETED")
        String status
) {
}
