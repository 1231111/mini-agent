package com.miniagent.memory.service.web;

import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;

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
        @NotNull Outcome outcome,
        @Positive Long evolutionProposalId,
        @Pattern(regexp = "[0-9a-f]{64}") String evolutionCandidateHash,
        String procedureVariant
) {
    public ProcedureSettlementRequest(String tenantId, Long userId, String projectId, String sessionId,
                                      long taskId, String nodeId, String actionId, long procedureId,
                                      int procedureVersion, Outcome outcome) {
        this(tenantId, userId, projectId, sessionId, taskId, nodeId, actionId, procedureId,
                procedureVersion, outcome, null, null, "BASELINE");
    }

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
                        : ProcedureSettlementCommand.Outcome.FAILURE,
                evolutionProposalId,
                evolutionCandidateHash,
                procedureVariant);
    }

    public enum Outcome {
        SUCCESS,
        FAILURE
    }
}
