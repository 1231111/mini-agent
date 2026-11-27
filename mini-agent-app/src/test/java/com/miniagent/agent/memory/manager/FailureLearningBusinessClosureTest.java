package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.*;
import com.miniagent.agent.memory.repository.*;
import com.miniagent.agent.planner.ProcedurePromptInput;
import com.miniagent.application.SubjectErasureService;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2/JPA transactions, real proposal/review/version/snapshot services. No model or client UI. */
class FailureLearningBusinessClosureTest {
    private AnnotationConfigApplicationContext context;
    private AgentEpisodeRepository episodes;
    private AgentProcedureRepository procedures;
    private AgentProcedureApplicationRepository applications;
    private ProcedureRewriteProposalRepository proposals;
    private FailureLearningOutboxRepository outbox;
    private FailureLearningOutboxService delivery;
    private ProcedureRewriteProposalService rewrite;
    private TransactionTemplate transaction;
    private MutableClock clock;

    @BeforeEach void start() {
        context = new AnnotationConfigApplicationContext(Configuration.class);
        episodes = context.getBean(AgentEpisodeRepository.class);
        procedures = context.getBean(AgentProcedureRepository.class);
        applications = context.getBean(AgentProcedureApplicationRepository.class);
        proposals = context.getBean(ProcedureRewriteProposalRepository.class);
        outbox = context.getBean(FailureLearningOutboxRepository.class);
        delivery = context.getBean(FailureLearningOutboxService.class);
        rewrite = context.getBean(ProcedureRewriteProposalService.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        clock = context.getBean(MutableClock.class);
    }

    @AfterEach void stop() { OwnerContext.clearCurrentUser(); OwnerContext.clearCurrentTenant(); context.close(); }

    @Test void failureCommitGeneratesOnlyPendingProposalThenApprovalChangesNextPromptSnapshot() {
        AgentProcedureEntity original = seedProcedure("UI verification", "Read evidence and verify UI", 0, 12);
        original.setStatus(AgentMemoryEntryEntity.Status.ARCHIVED);
        procedures.saveAndFlush(original);
        seedApplication(original, "t1", 7L, "session-one", 901L, "verify-ui");
        AgentEpisodeEntity episode = failure("t1", "7", "session-one", "901", "Use actual test receipts before accepting UI.");
        transaction.executeWithoutResult(ignored -> { episodes.saveAndFlush(episode); delivery.enqueue(episode); });

        // Simulate an application restart: a new worker sees the persisted pending row.
        newWorker(rewrite).drain();
        newWorker(rewrite).drain();
        assertThat(proposals.findAll()).hasSize(1);
        var pending = proposals.findAll().get(0);
        assertThat(pending.getStatus()).isEqualTo(ProcedureRewriteProposalEntity.Status.PENDING);
        assertThat(procedures.findById(original.getId()).orElseThrow().getProcedureVersion()).isEqualTo(1);
        assertThat(outbox.findAll().get(0).getStatus()).isEqualTo(FailureLearningOutboxEntity.Status.DONE);

        try (var owner = OwnerContext.bind(new OwnerContext(7L, "t1"))) {
            DefaultMemoryManager manager = manager();
            assertThat(manager.findForCurrentOwner("UI verification")).isEmpty(); // archived candidate is never silently adopted
            rewrite.review(pending.getId(), true);
            List<ProcedurePromptInput> next = manager.findForCurrentOwner("UI verification");
            assertThat(next).hasSize(1);
            assertThat(next.get(0).version()).isEqualTo(2);
            assertThat(next.get(0).steps()).contains("Use actual test receipts before accepting UI.");
            assertThat(context.getBean(AgentProcedureRevisionRepository.class).findAll()).hasSize(1);
        }
        assertThat(procedures.findById(original.getId()).orElseThrow().getAttemptCount()).isZero();
    }

    @Test void episodeAndTriggerRollbackTogetherAndDuplicateEnqueueIsIdempotent() {
        AgentEpisodeEntity rolledBack = failure("t1", "7", "rollback", "902", "Check receipts.");
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            episodes.saveAndFlush(rolledBack); delivery.enqueue(rolledBack); throw new IllegalStateException("rollback fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(episodes.count()).isZero(); assertThat(outbox.count()).isZero();
        AgentEpisodeEntity committed = failure("t1", "7", "committed", "903", "Check receipts.");
        transaction.executeWithoutResult(ignored -> {
            episodes.saveAndFlush(committed); delivery.enqueue(committed); delivery.enqueue(committed);
        });
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test void transientDeliveryFailureSurvivesRestartBackoffAndExpiredLeaseRecovery() {
        AgentEpisodeEntity episode = failure("t1", "7", "retry", "904", "Check receipts.");
        transaction.executeWithoutResult(ignored -> { episodes.saveAndFlush(episode); delivery.enqueue(episode); });
        ProcedureRewriteProposalService failing = mock(ProcedureRewriteProposalService.class);
        when(failing.proposeFromFailureEpisode(any())).thenThrow(new IllegalStateException("transient db fixture"));
        newWorker(failing).drain();
        var row = outbox.findAll().get(0);
        assertThat(row.getStatus()).isEqualTo(FailureLearningOutboxEntity.Status.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("IllegalStateException");
        newWorker(failing).drain();
        verify(failing, times(1)).proposeFromFailureEpisode(any()); // due time, no busy retry
        clock.advance(Duration.ofSeconds(3));
        transaction.executeWithoutResult(ignored -> outbox.claim(row.getId(), FailureLearningOutboxEntity.Status.PENDING,
                FailureLearningOutboxEntity.Status.PROCESSING, "crashed-worker", LocalDateTime.now(clock).minusSeconds(1), LocalDateTime.now(clock)));
        ProcedureRewriteProposalService recovered = mock(ProcedureRewriteProposalService.class);
        when(recovered.proposeFromFailureEpisode(any())).thenReturn(0);
        newWorker(recovered).drain();
        assertThat(outbox.findById(row.getId()).orElseThrow().getStatus()).isEqualTo(FailureLearningOutboxEntity.Status.DONE);
        verify(recovered).proposeFromFailureEpisode(any());
    }

    @Test void ambiguousFailureAttributionNeverCreatesOrAppliesProcedureChange() {
        AgentProcedureEntity one = seedProcedure("UI one", "UI tests", 0, 12);
        AgentProcedureEntity two = seedProcedure("UI two", "UI tests", 0, 12);
        seedApplication(one, "t1", 7L, "ambiguous", 905L, "one");
        seedApplication(two, "t1", 7L, "ambiguous", 905L, "two");
        AgentEpisodeEntity episode = failure("t1", "7", "ambiguous", "905", "Check receipts.");
        transaction.executeWithoutResult(ignored -> { episodes.saveAndFlush(episode); delivery.enqueue(episode); });
        delivery.drain();
        assertThat(proposals.count()).isZero();
        assertThat(procedures.findAll()).allSatisfy(p -> assertThat(p.getProcedureVersion()).isEqualTo(1));
    }

    @Test void retrievalRanksRelevantCapabilitiesAndReliabilityStablyWithoutOwnerLeakage() {
        AgentProcedureEntity weak = seedProcedure("UI verification B", "UI page verification", 0, 12);
        AgentProcedureEntity good = seedProcedure("UI verification A", "UI page verification", 10, 12);
        AgentProcedureEntity byCapability = seedProcedure("Browser receipts", "Evidence collection", 12, 12);
        byCapability.setStepsJson("[\"UI page verification\"]"); procedures.saveAndFlush(byCapability);
        seedProcedure("unrelated deployment", "Docker release", 12, 12);
        AgentProcedureEntity foreign = seedProcedure("UI verification foreign", "UI page verification", 12, 12);
        foreign.setTenantId("t2"); procedures.saveAndFlush(foreign);
        try (var owner = OwnerContext.bind(new OwnerContext(7L, "t1"))) {
            List<Long> expected = List.of(good.getId(), weak.getId(), byCapability.getId());
            assertThat(manager().findForCurrentOwner("UI page verification").stream().map(ProcedurePromptInput::procedureId)).containsExactlyElementsOf(expected);
            assertThat(manager().findForCurrentOwner("UI page verification").stream().map(ProcedurePromptInput::procedureId)).containsExactlyElementsOf(expected);
            assertThat(manager().findForCurrentOwner("没有关系的中文目标")).isEmpty();
        }
    }

    private FailureLearningOutboxService newWorker(ProcedureRewriteProposalService target) {
        return new FailureLearningOutboxService(outbox, episodes, target,
                context.getBean(PlatformTransactionManager.class), context.getBean(SubjectErasureService.class), clock);
    }
    private DefaultMemoryManager manager() {
        DefaultMemoryManager manager = new DefaultMemoryManager();
        ReflectionTestUtils.setField(manager, "procedureRepository", procedures);
        ReflectionTestUtils.setField(manager, "objectMapper", context.getBean(ObjectMapper.class));
        return manager;
    }
    private AgentProcedureEntity seedProcedure(String name, String description, long success, long attempts) {
        AgentProcedureEntity p = new AgentProcedureEntity(); p.setTenantId("t1");
        p.setScopeType(AgentMemoryEntryEntity.ScopeType.USER); p.setScopeId("7"); p.setName(name);
        p.setDescription(description); p.setPreconditionsJson("[]"); p.setStepsJson("[\"Read receipts\"]");
        p.setSuccessConditionsJson("[\"Evidence accepted\"]"); p.setSuccessCount(success); p.setAttemptCount(attempts);
        p.setSuccessRate(attempts == 0 ? 0.0 : success / (double) attempts); return procedures.saveAndFlush(p);
    }
    private void seedApplication(AgentProcedureEntity p, String tenant, Long user, String session, long task, String node) {
        AgentProcedureApplicationEntity a = new AgentProcedureApplicationEntity(); a.setTenantId(tenant); a.setUserId(user);
        a.setSessionId(session); a.setTaskId(task); a.setNodeId(node); a.setActionId("action-" + node);
        a.setProcedureId(p.getId()); a.setProcedureVersion(p.getProcedureVersion());
        a.setResult(AgentProcedureApplicationEntity.Result.FAILURE); applications.saveAndFlush(a);
    }
    private static AgentEpisodeEntity failure(String tenant, String user, String session, String task, String resolution) {
        AgentEpisodeEntity e = new AgentEpisodeEntity(); e.setTenantId(tenant); e.setUserId(user);
        e.setSessionId(session); e.setTaskId(task); e.setTaskSummary("UI verification");
        e.setOutcome(AgentEpisodeEntity.Outcome.FAILURE); e.setActionsJson("[]"); e.setResolution(resolution); return e;
    }

    static class MutableClock extends Clock {
        Instant instant = Instant.parse("2026-11-26T12:00:00Z");
        void advance(Duration amount) { instant = instant.plus(amount); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = AgentEpisodeRepository.class)
    static class Configuration {
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:failure-learning-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.miniagent.agent.memory.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory entityManagerFactory) { return new JpaTransactionManager(entityManagerFactory); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean ProcedureGovernanceProperties governance() { return new ProcedureGovernanceProperties(); }
        @Bean SubjectErasureService erasure() { return mock(SubjectErasureService.class); }
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean ProcedureRewriteProposalInsertService insertion(ProcedureRewriteProposalRepository proposals) { return new ProcedureRewriteProposalInsertService(proposals); }
        @Bean ProcedureRewriteProposalWriteService writing(ProcedureRewriteProposalInsertService insertion) { return new ProcedureRewriteProposalWriteService(insertion); }
        @Bean ProcedureRevisionService revisions(AgentProcedureRepository procedures, AgentProcedureRevisionRepository revisions, ObjectMapper mapper) { return new ProcedureRevisionService(procedures, revisions, mapper); }
        @Bean ProcedureRewriteProposalService proposals(AgentProcedureApplicationRepository apps, AgentProcedureRepository procedures,
                ProcedureRewriteProposalRepository proposals, ProcedureGovernanceProperties governance,
                ProcedureRewriteProposalWriteService writing, ProcedureRevisionService revisions, ObjectMapper mapper) {
            return new ProcedureRewriteProposalService(apps, procedures, proposals, governance, writing, revisions, mapper);
        }
        @Bean FailureLearningOutboxService delivery(FailureLearningOutboxRepository outbox, AgentEpisodeRepository episodes,
                ProcedureRewriteProposalService proposals, PlatformTransactionManager transactions, SubjectErasureService erasure, MutableClock clock) {
            return new FailureLearningOutboxService(outbox, episodes, proposals, transactions, erasure, clock);
        }
    }
}
