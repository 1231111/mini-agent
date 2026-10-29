package com.miniagent.memory.service.web;

import com.miniagent.memory.port.ProcedureSettlementPort;
import com.miniagent.memory.service.security.MemoryServiceSecurityProperties;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@RestController
@Profile("memory-service")
@RequestMapping("/internal/v1/memory/procedure-settlements")
public class ProcedureSettlementController {
    static final String INTERNAL_KEY_HEADER = "X-Memory-Internal-Key";

    private final ProcedureSettlementPort settlementPort;
    private final MemoryServiceSecurityProperties security;

    public ProcedureSettlementController(ProcedureSettlementPort settlementPort,
                                         MemoryServiceSecurityProperties security) {
        this.settlementPort = settlementPort;
        this.security = security;
    }

    @PostMapping
    public ProcedureSettlementResponse settle(
            @RequestHeader(value = INTERNAL_KEY_HEADER, required = false) String internalKey,
            @Valid @RequestBody ProcedureSettlementRequest request) {
        requireInternalKey(internalKey);
        return ProcedureSettlementResponse.from(settlementPort.settle(request.toCommand()));
    }

    private void requireInternalKey(String supplied) {
        if (!security.configured() || supplied == null
                || !MessageDigest.isEqual(security.internalKey().getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "memory service authentication required");
        }
    }
}
