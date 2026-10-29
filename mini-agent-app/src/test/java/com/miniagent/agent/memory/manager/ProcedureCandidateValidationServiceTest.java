package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.ProcedureCandidateValidationEntity;
import com.miniagent.agent.memory.entity.ProcedureRewriteProposalEntity;
import com.miniagent.agent.memory.repository.ProcedureCandidateValidationRepository;
import com.miniagent.agent.memory.repository.ProcedureRewriteProposalRepository;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcedureCandidateValidationServiceTest {
    private final ProcedureCandidateValidationRepository validations = mock(ProcedureCandidateValidationRepository.class);
    private final ProcedureRewriteProposalRepository proposals = mock(ProcedureRewriteProposalRepository.class);
    private final ProcedureGovernanceProperties properties = new ProcedureGovernanceProperties();
    private final ProcedureCandidateValidationService service = new ProcedureCandidateValidationService(
            validations, proposals, properties, new ObjectMapper());

    @AfterEach
    void clearOwner() { OwnerContext.clearCurrentTenant(); OwnerContext.clearCurrentUser(); }

    @Test
    void acceptsOnlyIndependentVerifierAndCountsDistinctPassedHashes() {
        ProcedureRewriteProposalEntity proposal = proposal(42L, 10L, "{\"steps\":[\"safe\"]}");
        when(proposals.findByIdAndTenantId(42L, "tenant-a")).thenReturn(Optional.of(proposal));
        when(validations.saveAndFlush(any(ProcedureCandidateValidationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        try (var ignored = OwnerContext.bind(new OwnerContext(11L, "tenant-a"))) {
            var stored = service.record(42L, 11L, true, "{\"runId\":\"isolated-1\",\"result\":\"passed\"}");
            assertEquals(11L, stored.getVerifierUserId());
            assertTrue(stored.isPassed());
        }
        ProcedureCandidateValidationEntity record = new ProcedureCandidateValidationEntity();
        record.setTenantId("tenant-a"); record.setProposalId(42L);
        record.setCandidateHash(serviceHash(proposal.getProposedContentJson()));
        record.setVerifierUserId(11L); record.setPassed(true);
        when(validations.findByTenantIdAndProposalIdOrderByCreatedAtDesc("tenant-a", 42L))
                .thenReturn(List.of(record));
        try (var ignored = OwnerContext.bind(new OwnerContext(10L, "tenant-a"))) {
            assertFalse(service.independentlyVerified(proposal));
        }
        properties.setMinimumIndependentValidations(1);
        try (var ignored = OwnerContext.bind(new OwnerContext(10L, "tenant-a"))) {
            assertTrue(service.independentlyVerified(proposal));
        }
    }

    @Test
    void proposalAuthorCannotVerifyOwnCandidate() {
        ProcedureRewriteProposalEntity proposal = proposal(42L, 10L, "{}");
        when(proposals.findByIdAndTenantId(42L, "tenant-a")).thenReturn(Optional.of(proposal));
        try (var ignored = OwnerContext.bind(new OwnerContext(10L, "tenant-a"))) {
            assertThrows(AccessDeniedException.class, () -> service.record(42L, 10L, true, "{}"));
        }
        verify(validations, never()).saveAndFlush(any());
    }

    private ProcedureRewriteProposalEntity proposal(Long id, Long author, String candidate) {
        var proposal = new ProcedureRewriteProposalEntity();
        proposal.setId(id); proposal.setTenantId("tenant-a"); proposal.setUserId(author);
        proposal.setProposedContentJson(candidate); proposal.setStatus(ProcedureRewriteProposalEntity.Status.PENDING);
        return proposal;
    }

    private String serviceHash(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
