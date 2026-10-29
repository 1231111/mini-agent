package com.miniagent.memory.service.web;

import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** HTTP 传输对象；转换后仍由领域契约做第二次不变量校验。 */
public record ProcedureSettlementRequest(
        @NotBlank String tenantId,
        @NotNull @Positive Long userId,
        String projectId,
        @NotBlank String sessionId,
        @PositiveOrZero long taskId,
        @NotBlank String nodeId,
        String actionId,
        @Positive long procedureId,
        @Positive int procedureVersion,
        @NotNull Outcome outcome
) {
    public ProcedureSettlementCommand toCommand() {
        return new ProcedureSettlementCommand(
                new MemoryPrincipal(tenantId, userId, projectId, sessionId),
                taskId,
                nodeId,
                actionId,
                procedureId,
                procedureVersion,
                outcome == Outcome.SUCCESS
                        ? ProcedureSettlementCommand.Outcome.SUCCESS
                        : ProcedureSettlementCommand.Outcome.FAILURE);
    }

    public enum Outcome {
        SUCCESS,
        FAILURE
    }
}
