package com.miniagent.memory.service.web;

import com.miniagent.memory.service.persistence.ProcedureDefinitionSynchronizer;
import com.miniagent.memory.service.security.MemoryServiceSecurityProperties;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@Profile("memory-service")
@RequestMapping("/internal/v1/memory/procedures")
public class ProcedureDefinitionController {
    private final ProcedureDefinitionSynchronizer synchronizer;
    private final MemoryServiceSecurityProperties security;

    public ProcedureDefinitionController(ProcedureDefinitionSynchronizer synchronizer,
                                         MemoryServiceSecurityProperties security) {
        this.synchronizer = synchronizer;
        this.security = security;
    }

    @PutMapping("/{procedureId}")
    public ProcedureDefinitionResponse synchronize(
            @RequestHeader(value = ProcedureSettlementController.INTERNAL_KEY_HEADER, required = false) String key,
            @PathVariable long procedureId,
            @Valid @RequestBody ProcedureDefinitionRequest request) {
        requireKey(key);
        if (request.procedureId() != procedureId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "procedure id mismatch");
        }
        return synchronizer.synchronize(request);
    }

    private void requireKey(String supplied) {
        if (!security.configured() || supplied == null
                || !MessageDigest.isEqual(security.internalKey().getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "memory service authentication required");
        }
    }
}
