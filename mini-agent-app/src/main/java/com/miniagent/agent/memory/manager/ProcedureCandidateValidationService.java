package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.ProcedureCandidateValidationEntity;
import com.miniagent.agent.memory.entity.ProcedureRewriteProposalEntity;
import com.miniagent.agent.memory.repository.ProcedureCandidateValidationRepository;
import com.miniagent.agent.memory.repository.ProcedureRewriteProposalRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.SecurityScanner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Service
public class ProcedureCandidateValidationService {
    private final ProcedureCandidateValidationRepository validations;
    private final ProcedureRewriteProposalRepository proposals;
    private final ProcedureGovernanceProperties properties;
    private final ObjectMapper mapper;

    public ProcedureCandidateValidationService(ProcedureCandidateValidationRepository validations,
                                               ProcedureRewriteProposalRepository proposals,
                                               ProcedureGovernanceProperties properties,
                                               ObjectMapper mapper) {
        this.validations = validations;
        this.proposals = proposals;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Transactional
    public ProcedureCandidateValidationEntity record(Long proposalId, Long verifierUserId,
                                                       boolean passed, String evidenceJson) {
        OwnerContext owner = OwnerContext.capture();
        requireOwner(owner);
        if (verifierUserId == null || !verifierUserId.equals(owner.userId()))
            throw new AccessDeniedException("Verifier identity must be authenticated user");
        ProcedureRewriteProposalEntity proposal = proposals.findByIdAndTenantId(
                proposalId, owner.tenantId()).orElseThrow(
                () -> new AccessDeniedException("Proposal is not accessible"));
        if (verifierUserId.equals(proposal.getUserId()))
            throw new AccessDeniedException("Proposal author cannot be independent verifier");
        if (evidenceJson == null || evidenceJson.isBlank() || SecurityScanner.scan(evidenceJson) != null)
            throw new IllegalArgumentException("safe validation evidence required");
        ProcedureCandidateValidationEntity validation = new ProcedureCandidateValidationEntity();
        validation.setTenantId(owner.tenantId());
        validation.setProposalId(proposalId);
        validation.setCandidateHash(sha256(proposal.getProposedContentJson()));
        validation.setVerifierUserId(verifierUserId);
        validation.setPassed(passed);
        validation.setEvidenceJson(evidenceJson);
        try {
            return validations.saveAndFlush(validation);
        } catch (DataIntegrityViolationException duplicate) {
            return validations.findByTenantIdAndProposalIdOrderByCreatedAtDesc(
                    owner.tenantId(), proposalId).stream()
                    .filter(v -> verifierUserId.equals(v.getVerifierUserId())).findFirst().orElseThrow();
        }
    }

    @Transactional(readOnly = true)
    public boolean independentlyVerified(ProcedureRewriteProposalEntity proposal) {
        OwnerContext owner = OwnerContext.capture();
        requireOwner(owner);
        String hash = sha256(proposal.getProposedContentJson());
        List<ProcedureCandidateValidationEntity> records = validations
                .findByTenantIdAndProposalIdOrderByCreatedAtDesc(owner.tenantId(), proposal.getId());
        return records.stream().filter(ProcedureCandidateValidationEntity::isPassed)
                .filter(v -> hash.equals(v.getCandidateHash()))
                .map(ProcedureCandidateValidationEntity::getVerifierUserId).distinct().count()
                >= properties.getMinimumIndependentValidations();
    }

    public boolean automaticAcceptanceEnabled() { return properties.isAutomaticAcceptanceEnabled(); }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void requireOwner(OwnerContext owner) {
        if (owner.userId() == null || owner.tenantId() == null || owner.tenantId().isBlank())
            throw new AccessDeniedException("Authenticated owner required");
    }
}
