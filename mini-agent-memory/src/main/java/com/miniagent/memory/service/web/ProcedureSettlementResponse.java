package com.miniagent.memory.service.web;

import com.miniagent.memory.port.ProcedureSettlementResult;

public record ProcedureSettlementResponse(ProcedureSettlementResult.Status status) {
    public static ProcedureSettlementResponse from(ProcedureSettlementResult result) {
        return new ProcedureSettlementResponse(result.status());
    }
}
