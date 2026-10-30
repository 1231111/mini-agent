package com.miniagent.memory.service.web;

public record FailureEvidenceResponse(long evidenceId, Long proposalId, String status, String candidateHash) { }
