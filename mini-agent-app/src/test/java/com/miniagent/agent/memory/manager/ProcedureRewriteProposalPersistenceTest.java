package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureApplicationEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.entity.ProcedureRewriteProposalEntity;
import com.miniagent.agent.memory.repository.AgentProcedureApplicationRepository;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.memory.repository.ProcedureRewriteProposalRepository;
import com.miniagent.common.context.OwnerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
        "spring.datasource.url=jdbc:h2:mem:rewrite-proposals-test-2;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ProcedureRewriteProposalService.class, ProcedureRewriteProposalWriteService.class,
        ProcedureRewriteProposalInsertService.class, ProcedureGovernanceProperties.class,
        ProcedureRevisionService.class, ProcedureRewriteProposalPersistenceTest.JsonConfiguration.class})
class ProcedureRewriteProposalPersistenceTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class JsonConfiguration {
        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Autowired private AgentProcedureRepository procedures;
    @Autowired private AgentProcedureApplicationRepository applications;
    @Autowired private ProcedureRewriteProposalRepository proposals;
    @Autowired private ProcedureRewriteProposalService proposalService;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        proposals.deleteAll();
        applications.deleteAll();
        procedures.deleteAll();
    }

    @AfterEach
    void clearOwner() { OwnerContext.clearCurrentTenant(); OwnerContext.clearCurrentUser(); }

    @Test
    void createsReviewableProposalOnlyForSufficientlyMeasuredUniqueFailure() {
        AgentProcedureEntity procedure = persistProcedure();
        persistApplication(procedure, "session-a", 77L, AgentProcedureApplicationEntity.Result.FAILURE);
        assertEquals(1, proposalService.proposeFromFailureEpisode(episode(9L, "session-a", "77", "先校验输入")));
        assertEquals(0, proposalService.proposeFromFailureEpisode(episode(9L, "session-a", "77", "先校验输入")),
                "同源 Episode 重放必须幂等");
        ProcedureRewriteProposalEntity proposal = proposals.findAll().get(0);
        assertEquals(ProcedureRewriteProposalEntity.Status.PENDING, proposal.getStatus());
        assertEquals(1, proposal.getBaseVersion());
        assertTrue(proposal.getBaseContentJson().contains("旧步骤"));
        assertTrue(proposal.getProposedContentJson().contains("先校验输入"));
    }

    @Test
    void ambiguousFailureAttributionAndInsufficientEvidenceDoNotCreateProposal() {
        AgentProcedureEntity procedure = persistProcedure();
        persistApplication(procedure, "session-a", 78L, AgentProcedureApplicationEntity.Result.FAILURE);
        persistApplication(procedure, "session-a", 78L, AgentProcedureApplicationEntity.Result.FAILURE, "node-2");
        assertEquals(0, proposalService.proposeFromFailureEpisode(episode(10L, "session-a", "78", "修复步骤")),
                "同任务多个失败 Procedure 应用不能模糊归因");
        assertEquals(0, proposalService.proposeFromFailureEpisode(episode(11L, "session-a", "79", "")));
        assertTrue(proposals.findAll().isEmpty());
    }

    @Test
    void approvalUpgradesOnlyTheExactBaseVersionAndRejectsStaleProposal() {
        AgentProcedureEntity procedure = persistProcedure();
        persistApplication(procedure, "session-a", 80L, AgentProcedureApplicationEntity.Result.FAILURE);
        assertEquals(1, proposalService.proposeFromFailureEpisode(episode(12L, "session-a", "80", "先校验输入")));
        ProcedureRewriteProposalEntity proposal = proposals.findAll().get(0);

        ProcedureRewriteProposalEntity reviewed;
        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            reviewed = proposalService.review(proposal.getId(), true);
        }
        assertEquals(ProcedureRewriteProposalEntity.Status.APPROVED, reviewed.getStatus());
        AgentProcedureEntity updated = procedures.findById(procedure.getId()).orElseThrow();
        assertEquals(2, updated.getProcedureVersion());
        assertEquals(0L, updated.getAttemptCount());
        assertTrue(updated.getStepsJson().contains("先校验输入"));

        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
                proposalService.review(proposal.getId(), true);
            }
        }, "已审核提案不可重复应用");
    }

    @Test
    void rejectionAndOwnerIsolationDoNotModifyProcedure() {
        AgentProcedureEntity procedure = persistProcedure();
        persistApplication(procedure, "session-a", 81L, AgentProcedureApplicationEntity.Result.FAILURE);
        assertEquals(1, proposalService.proposeFromFailureEpisode(episode(13L, "session-a", "81", "确认前置条件")));
        ProcedureRewriteProposalEntity proposal = proposals.findAll().get(0);

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
            try (var ignored = OwnerContext.bind(new OwnerContext(10L, "tenant-a"))) {
                proposalService.review(proposal.getId(), false);
            }
        });
        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            assertEquals(ProcedureRewriteProposalEntity.Status.REJECTED,
                    proposalService.review(proposal.getId(), false).getStatus());
        }
        assertEquals(1, procedures.findById(procedure.getId()).orElseThrow().getProcedureVersion());
    }

    @Test
    void staleBaseVersionBecomesConflictWithoutOverwritingNewContent() {
        AgentProcedureEntity procedure = persistProcedure();
        persistApplication(procedure, "session-a", 82L, AgentProcedureApplicationEntity.Result.FAILURE);
        assertEquals(1, proposalService.proposeFromFailureEpisode(episode(14L, "session-a", "82", "新增验证步骤")));
        ProcedureRewriteProposalEntity proposal = proposals.findAll().get(0);
        inTransaction(() -> procedures.upgradeContent(procedure.getId(), "tenant-a", 1, 2,
                "deploy", "newer", "[]", "[\"newer step\"]", "[]", 0.5,
                AgentMemoryEntryEntity.Status.ACTIVE));

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            assertEquals(ProcedureRewriteProposalEntity.Status.CONFLICT,
                    proposalService.review(proposal.getId(), true).getStatus());
        }
        AgentProcedureEntity unchanged = procedures.findById(procedure.getId()).orElseThrow();
        assertEquals(2, unchanged.getProcedureVersion());
        assertTrue(unchanged.getStepsJson().contains("newer step"));
        assertFalse(unchanged.getStepsJson().contains("新增验证步骤"));
    }

    @Test
    void proposalUniqueConstraintEnforcesSourceIdempotency() {
        ProcedureRewriteProposalEntity proposal = new ProcedureRewriteProposalEntity();
        proposal.setTenantId("tenant-a"); proposal.setUserId(9L); proposal.setProcedureId(1L);
        proposal.setBaseVersion(1); proposal.setEpisodeId(2L); proposal.setProcedureName("p");
        proposal.setBaseContentJson("{}"); proposal.setProposedContentJson("{}"); proposal.setEvidenceJson("{}");
        inTransaction(() -> proposals.saveAndFlush(proposal));
        ProcedureRewriteProposalEntity duplicate = new ProcedureRewriteProposalEntity();
        duplicate.setTenantId("tenant-a"); duplicate.setUserId(9L); duplicate.setProcedureId(1L);
        duplicate.setBaseVersion(1); duplicate.setEpisodeId(2L); duplicate.setProcedureName("p");
        duplicate.setBaseContentJson("{}"); duplicate.setProposedContentJson("{}"); duplicate.setEvidenceJson("{}");
        assertThrows(DataIntegrityViolationException.class, () -> inTransaction(() -> proposals.saveAndFlush(duplicate)));
    }

    private AgentProcedureEntity persistProcedure() {
        AgentProcedureEntity entity = new AgentProcedureEntity();
        entity.setTenantId("tenant-a"); entity.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
        entity.setScopeId("9"); entity.setName("deploy"); entity.setDescription("description");
        entity.setPreconditionsJson("[]"); entity.setStepsJson("[\"旧步骤\"]");
        entity.setSuccessConditionsJson("[]"); entity.setProcedureVersion(1); entity.setAttemptCount(10L);
        entity.setSuccessCount(1L); entity.setSuccessRate(0.1);
        return inTransaction(() -> procedures.saveAndFlush(entity));
    }

    private void persistApplication(AgentProcedureEntity procedure, String session, long task,
                                    AgentProcedureApplicationEntity.Result result) {
        persistApplication(procedure, session, task, result, "node-1");
    }

    private void persistApplication(AgentProcedureEntity procedure, String session, long task,
                                    AgentProcedureApplicationEntity.Result result, String node) {
        AgentProcedureApplicationEntity application = new AgentProcedureApplicationEntity();
        application.setTenantId("tenant-a"); application.setUserId(9L); application.setSessionId(session);
        application.setTaskId(task); application.setNodeId(node); application.setProcedureId(procedure.getId());
        application.setProcedureVersion(1); application.setActionId("a"); application.setResult(result);
        inTransaction(() -> applications.saveAndFlush(application));
    }

    private AgentEpisodeEntity episode(long id, String session, String task, String resolution) {
        AgentEpisodeEntity episode = new AgentEpisodeEntity();
        episode.setId(id); episode.setTenantId("tenant-a"); episode.setUserId("9");
        episode.setSessionId(session); episode.setTaskId(task); episode.setOutcome(AgentEpisodeEntity.Outcome.FAILURE);
        episode.setResolution(resolution); return episode;
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
