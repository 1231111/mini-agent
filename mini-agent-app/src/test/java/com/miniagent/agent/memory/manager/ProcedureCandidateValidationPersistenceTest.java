package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.ProcedureCandidateValidationEntity;
import com.miniagent.agent.memory.entity.ProcedureRewriteProposalEntity;
import com.miniagent.agent.memory.repository.ProcedureCandidateValidationRepository;
import com.miniagent.agent.memory.repository.ProcedureRewriteProposalRepository;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:candidate-validations;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ProcedureCandidateValidationService.class, ProcedureGovernanceProperties.class})
class ProcedureCandidateValidationPersistenceTest {
    @Autowired private ProcedureCandidateValidationService service;
    @Autowired private ProcedureRewriteProposalRepository proposals;
    @Autowired private ProcedureCandidateValidationRepository validations;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private ObjectMapper mapper;

    @BeforeEach
    void clearRows() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            validations.deleteAll();
            proposals.deleteAll();
        });
    }

    @AfterEach
    void clearOwner() {
        OwnerContext.clearCurrentTenant();
        OwnerContext.clearCurrentUser();
    }

    @Test
    void twoIndependentPassedRecordsReachThresholdAndPersistCandidateHash() {
        ProcedureRewriteProposalEntity proposal = saveProposal("tenant-a", 10L, "{\"steps\":[\"safe\"]}");

        try (var ignored = OwnerContext.bind(new OwnerContext(11L, "tenant-a"))) {
            service.record(proposal.getId(), 11L, true, "{\"runId\":\"v1\",\"result\":\"passed\"}");
        }
        try (var ignored = OwnerContext.bind(new OwnerContext(12L, "tenant-a"))) {
            service.record(proposal.getId(), 12L, true, "{\"runId\":\"v2\",\"result\":\"passed\"}");
        }

        List<ProcedureCandidateValidationEntity> records = validations
                .findByTenantIdAndProposalIdOrderByCreatedAtDesc("tenant-a", proposal.getId());
        assertEquals(2, records.size());
        assertEquals(2, records.stream().map(ProcedureCandidateValidationEntity::getVerifierUserId).distinct().count());
        assertTrue(records.stream().allMatch(record -> record.getCandidateHash() != null
                && record.getCandidateHash().length() == 64));

        try (var ignored = OwnerContext.bind(new OwnerContext(10L, "tenant-a"))) {
            assertTrue(service.independentlyVerified(proposal));
        }
    }

    @Test
    void changingCandidateContentMakesOldValidationRecordsIneligible() {
        ProcedureRewriteProposalEntity proposal = saveProposal("tenant-a", 10L, "{\"steps\":[\"safe\"]}");
        try (var ignored = OwnerContext.bind(new OwnerContext(11L, "tenant-a"))) {
            service.record(proposal.getId(), 11L, true, "{\"runId\":\"v1\"}");
        }
        ProcedureRewriteProposalEntity changed = proposals.findById(proposal.getId()).orElseThrow();
        changed.setProposedContentJson("{\"steps\":[\"changed\"]}");
        saveProposalEntity(changed);

        try (var ignored = OwnerContext.bind(new OwnerContext(10L, "tenant-a"))) {
            assertFalse(service.independentlyVerified(changed));
        }
    }

    @Test
    void validationLookupIsTenantScopedAndAuthorCannotSubmit() {
        ProcedureRewriteProposalEntity proposal = saveProposal("tenant-a", 10L, "{}");
        try (var ignored = OwnerContext.bind(new OwnerContext(11L, "tenant-a"))) {
            service.record(proposal.getId(), 11L, true, "{\"runId\":\"tenant-a\"}");
        }
        try (var ignored = OwnerContext.bind(new OwnerContext(12L, "tenant-b"))) {
            assertThrows(AccessDeniedException.class,
                    () -> service.record(proposal.getId(), 12L, true, "{\"runId\":\"tenant-b\"}"));
            assertFalse(service.independentlyVerified(proposal));
        }
        try (var ignored = OwnerContext.bind(new OwnerContext(10L, "tenant-a"))) {
            assertThrows(AccessDeniedException.class,
                    () -> service.record(proposal.getId(), 10L, true, "{\"runId\":\"author\"}"));
        }
        assertEquals(1, validations.findByTenantIdAndProposalIdOrderByCreatedAtDesc("tenant-a", proposal.getId()).size());
    }

    @Test
    void databaseUniqueConstraintRejectsSameVerifierForSameProposal() {
        ProcedureRewriteProposalEntity proposal = saveProposal("tenant-a", 10L, "{}");
        ProcedureCandidateValidationEntity first = validation("tenant-a", proposal.getId(), 11L);
        saveValidation(first);
        ProcedureCandidateValidationEntity duplicate = validation("tenant-a", proposal.getId(), 11L);
        assertThrows(DataIntegrityViolationException.class, () -> saveValidation(duplicate));
    }

    private ProcedureRewriteProposalEntity saveProposal(String tenant, Long author, String content) {
        ProcedureRewriteProposalEntity proposal = new ProcedureRewriteProposalEntity();
        proposal.setTenantId(tenant);
        proposal.setUserId(author);
        proposal.setProcedureId(100L);
        proposal.setBaseVersion(1);
        proposal.setEpisodeId(200L + author);
        proposal.setProcedureName("procedure-a");
        proposal.setBaseContentJson("{\"steps\":[]}");
        proposal.setProposedContentJson(content);
        proposal.setEvidenceJson("{\"source\":\"episode\"}");
        proposal.setStatus(ProcedureRewriteProposalEntity.Status.PENDING);
        return saveProposalEntity(proposal);
    }

    private ProcedureRewriteProposalEntity saveProposalEntity(ProcedureRewriteProposalEntity proposal) {
        return new TransactionTemplate(transactionManager).execute(status -> proposals.saveAndFlush(proposal));
    }

    private ProcedureCandidateValidationEntity validation(String tenant, Long proposalId, Long verifier) {
        ProcedureCandidateValidationEntity validation = new ProcedureCandidateValidationEntity();
        validation.setTenantId(tenant);
        validation.setProposalId(proposalId);
        validation.setCandidateHash("0".repeat(64));
        validation.setVerifierUserId(verifier);
        validation.setPassed(true);
        validation.setEvidenceJson("{\"runId\":\"manual\"}");
        return validation;
    }

    private void saveValidation(ProcedureCandidateValidationEntity validation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> validations.saveAndFlush(validation));
    }
}
