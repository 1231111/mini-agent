package com.miniagent.memory.port;

import java.util.Objects;

/**
 * 一次 Procedure 应用终态结算的跨进程命令。
 *
 * <p>procedureId 与 procedureVersion 是执行时绑定的版本，不允许服务端按当前版本猜测，
 * 否则升版与迟到结算会把结果记到错误版本。</p>
 */
public record ProcedureSettlementCommand(
        MemoryPrincipal principal,
        long taskId,
        String nodeId,
        String actionId,
        long procedureId,
        int procedureVersion,
        Outcome outcome,
        Long evolutionProposalId,
        String evolutionCandidateHash,
        String procedureVariant
) {
    public ProcedureSettlementCommand {
        principal = Objects.requireNonNull(principal, "principal");
        if (!principal.hasSession()) {
            throw new IllegalArgumentException("sessionId is required for procedure settlement");
        }
        if (taskId < 0) {
            throw new IllegalArgumentException("taskId must be non-negative");
        }
        nodeId = requireText(nodeId, "nodeId");
        actionId = normalize(actionId);
        if (actionId == null) {
            actionId = "action";
        }
        if (procedureId <= 0) {
            throw new IllegalArgumentException("procedureId must be positive");
        }
        if (procedureVersion <= 0) {
            throw new IllegalArgumentException("procedureVersion must be positive");
        }
        outcome = Objects.requireNonNull(outcome, "outcome");
        evolutionCandidateHash = normalize(evolutionCandidateHash);
        procedureVariant = normalize(procedureVariant);
        if (procedureVariant == null) procedureVariant = evolutionProposalId == null ? "BASELINE" : "CANDIDATE";
        procedureVariant = procedureVariant.toUpperCase(java.util.Locale.ROOT);
        if (!"BASELINE".equals(procedureVariant) && !"CANDIDATE".equals(procedureVariant)) {
            throw new IllegalArgumentException("procedureVariant must be BASELINE or CANDIDATE");
        }
        if (evolutionProposalId == null && evolutionCandidateHash != null) {
            throw new IllegalArgumentException("candidate hash requires an evolution proposal id");
        }
        if (evolutionCandidateHash != null && !evolutionCandidateHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evolutionCandidateHash must be a lowercase SHA-256 hex digest");
        }
        if (evolutionProposalId != null && (evolutionProposalId <= 0 || evolutionCandidateHash == null
                || !"CANDIDATE".equals(procedureVariant))) {
            throw new IllegalArgumentException("candidate settlement requires proposal id, hash, and CANDIDATE variant");
        }
        if ("CANDIDATE".equals(procedureVariant) && evolutionProposalId == null) {
            throw new IllegalArgumentException("CANDIDATE variant requires evolution proposal identity");
        }
    }

    public ProcedureSettlementCommand(MemoryPrincipal principal, long taskId, String nodeId, String actionId,
                                      long procedureId, int procedureVersion, Outcome outcome) {
        this(principal, taskId, nodeId, actionId, procedureId, procedureVersion, outcome, null, null, "BASELINE");
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public enum Outcome {
        SUCCESS,
        FAILURE
    }
}
