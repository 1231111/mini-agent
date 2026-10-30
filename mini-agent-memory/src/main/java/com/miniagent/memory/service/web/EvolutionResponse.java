package com.miniagent.memory.service.web;

public record EvolutionResponse(long proposalId, String status, String candidateHash,
                                int baselineVersion, Integer candidateVersion,
                                int baselineSamples, int candidateSamples,
                                String decisionReason, String tenantId, Long userId,
                                Long procedureId, String candidateContentJson, int canaryTargetPercent) {
    public EvolutionResponse(long proposalId, String status, String candidateHash,
                             int baselineVersion, Integer candidateVersion,
                             int baselineSamples, int candidateSamples, String decisionReason) {
        this(proposalId, status, candidateHash, baselineVersion, candidateVersion,
                baselineSamples, candidateSamples, decisionReason, null, null, null, null, 0);
    }
}
