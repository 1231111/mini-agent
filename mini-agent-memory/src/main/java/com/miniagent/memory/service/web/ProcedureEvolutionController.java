package com.miniagent.memory.service.web;

import com.miniagent.memory.service.persistence.ProcedureEvolutionService;
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
@RequestMapping("/internal/v1/memory/evolution/proposals")
public class ProcedureEvolutionController {
    private final ProcedureEvolutionService evolution;
    private final MemoryServiceSecurityProperties security;

    public ProcedureEvolutionController(ProcedureEvolutionService evolution, MemoryServiceSecurityProperties security) {
        this.evolution = evolution;
        this.security = security;
    }

    @PostMapping("/failure-evidence")
    public FailureEvidenceResponse acceptFailureEvidence(
            @RequestHeader(value = ProcedureSettlementController.INTERNAL_KEY_HEADER, required = false) String key,
            @Valid @RequestBody FailureEvidenceRequest request) {
        requireKey(key);
        return evolution.acceptFailureEvidence(request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.GONE)
    public EvolutionResponse create(@RequestHeader(value = ProcedureSettlementController.INTERNAL_KEY_HEADER, required = false) String key,
                                   @Valid @RequestBody ProcedureEvolutionRequest request) {
        requireKey(key);
        return evolution.createCandidate(request);
    }

    @PostMapping("/{proposalId}/validation-runs")
    public EvolutionResponse validate(@RequestHeader(value = ProcedureSettlementController.INTERNAL_KEY_HEADER, required = false) String key,
                                      @PathVariable long proposalId,
                                      @Valid @RequestBody CandidateValidationRequest request) {
        requireKey(key);
        return evolution.validate(proposalId, request);
    }

    @PostMapping("/{proposalId}/decision")
    public EvolutionResponse decide(@RequestHeader(value = ProcedureSettlementController.INTERNAL_KEY_HEADER, required = false) String key,
                                    @PathVariable long proposalId,
                                    @Valid @RequestBody EvolutionDecisionRequest request) {
        requireKey(key);
        return evolution.decide(proposalId, request);
    }

    @PostMapping("/{proposalId}/canary-observations")
    public EvolutionResponse observe(@RequestHeader(value = ProcedureSettlementController.INTERNAL_KEY_HEADER, required = false) String key,
                                     @PathVariable long proposalId,
                                     @Valid @RequestBody CanaryObservationRequest request) {
        requireKey(key);
        return evolution.observeCanary(proposalId, request);
    }

    @GetMapping("/{proposalId}")
    public EvolutionResponse get(@RequestHeader(value = ProcedureSettlementController.INTERNAL_KEY_HEADER, required = false) String key,
                                 @RequestParam String tenantId, @PathVariable long proposalId) {
        requireKey(key);
        return evolution.get(tenantId, proposalId);
    }

    @GetMapping
    public java.util.List<EvolutionResponse> listCanaryCandidates(
            @RequestHeader(value = ProcedureSettlementController.INTERNAL_KEY_HEADER, required = false) String key,
            @RequestParam String tenantId, @RequestParam long userId) {
        requireKey(key);
        return evolution.listCanaryCandidates(tenantId, userId);
    }

    @GetMapping("/{proposalId}/candidate")
    public EvolutionResponse getCanaryCandidate(
            @RequestHeader(value = ProcedureSettlementController.INTERNAL_KEY_HEADER, required = false) String key,
            @RequestParam String tenantId, @RequestParam long userId, @RequestParam long procedureId,
            @RequestParam String candidateHash, @PathVariable long proposalId) {
        requireKey(key);
        return evolution.getCanaryCandidate(tenantId, userId, procedureId, proposalId, candidateHash);
    }

    private void requireKey(String supplied) {
        if (!security.configured() || supplied == null
                || !MessageDigest.isEqual(security.internalKey().getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "memory service authentication required");
    }
}
