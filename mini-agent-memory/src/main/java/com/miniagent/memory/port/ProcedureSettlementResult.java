package com.miniagent.memory.port;

/** Procedure 结算结果，供调用方区分新事实、重复事实和无效版本。 */
public record ProcedureSettlementResult(Status status) {
    public enum Status {
        RECORDED,
        DUPLICATE,
        STALE_PROCEDURE,
        SKIPPED
    }

    public static ProcedureSettlementResult recorded() {
        return new ProcedureSettlementResult(Status.RECORDED);
    }

    public static ProcedureSettlementResult duplicate() {
        return new ProcedureSettlementResult(Status.DUPLICATE);
    }

    public static ProcedureSettlementResult staleProcedure() {
        return new ProcedureSettlementResult(Status.STALE_PROCEDURE);
    }

    public static ProcedureSettlementResult skipped() {
        return new ProcedureSettlementResult(Status.SKIPPED);
    }
}
