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
        Outcome outcome
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
