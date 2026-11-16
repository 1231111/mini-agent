package com.miniagent.config.service;

import com.miniagent.agent.planner.*;
import com.miniagent.config.entity.AgentSessionPlanner;
import com.miniagent.config.repository.AgentSessionPlannerRepository;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real H2/Spring Data/transaction advice, no user database or model calls. */
class DbPlannerDispatchFenceTest {
    @Test void authoritativeProjectionExcludesEventsAndEveryCommittedTransitionRevokesOldDispatch() {
        try (Fixture fixture = new Fixture()) {
            StateSnapshot state = state("execution", TaskNodeStatus.RUNNING);
            ActionProposal proposal = proposal(state);
            fixture.run(() -> fixture.persistence.replace("chat", state, List.of()));
            fixture.statements.clear();
            assertTrue(fixture.persistence.dispatchFenceValid("chat", proposal));
            assertEquals(1, fixture.statements.size());
            String select = fixture.statements.getFirst().toLowerCase(java.util.Locale.ROOT);
            assertTrue(select.contains("state_json"), select);
            assertTrue(select.contains("execution_id"), select);
            assertFalse(select.contains("events_json"), select);
            fixture.run(() -> assertTrue(fixture.persistence.compareAndSetAppending("chat", 1, "execution",
                    state.withGraph(state.graph().replace(state.graph().nodes().getFirst().withStatus(TaskNodeStatus.SUCCESS)))
                            .bumpVersion(), List.of())));
            assertFalse(fixture.persistence.dispatchFenceValid("chat", proposal));
            fixture.run(() -> fixture.persistence.replace("chat", state("replacement", TaskNodeStatus.RUNNING), List.of()));
            assertFalse(fixture.persistence.dispatchFenceValid("chat", proposal));
            fixture.run(() -> fixture.persistence.delete("chat"));
            assertFalse(fixture.persistence.dispatchFenceValid("chat", proposal));
        }
    }

    @Test void aCachedManagedEntityAndOuterTransactionCannotResurrectAReplacedExecution() {
        try (Fixture fixture = new Fixture()) {
            StateSnapshot state = state("old", TaskNodeStatus.RUNNING);
            fixture.run(() -> fixture.persistence.replace("chat", state, List.of()));
            fixture.run(() -> {
                AgentSessionPlanner cached = fixture.repository.findById("chat").orElseThrow();
                fixture.independent.executeWithoutResult(tx -> fixture.persistence.replace("chat",
                        state("replacement", TaskNodeStatus.RUNNING), List.of()));
                assertEquals("old", cached.getExecutionId(), "the outer persistence context really retains its old entity");
                assertFalse(fixture.persistence.dispatchFenceValid("chat", proposal(state)));
                assertTrue(fixture.persistence.dispatchFenceValid("chat", proposal(state("replacement", TaskNodeStatus.RUNNING))));
            });
        }
    }

    @Test void projectionStillRejectsCorruptedStateMetadataAndPlanRevision() {
        try (Fixture fixture = new Fixture()) {
            StateSnapshot state = state("execution", TaskNodeStatus.RUNNING);
            fixture.run(() -> fixture.persistence.replace("chat", state, List.of()));
            ActionProposal proposal = proposal(state);
            fixture.run(() -> {
                AgentSessionPlanner row = fixture.repository.findById("chat").orElseThrow();
                row.setStateJson(PlannerStateJson.snapshotToJson(state).replace("\"executionId\":\"execution\"",
                        "\"executionId\":\"other\""));
                fixture.repository.saveAndFlush(row);
            });
            assertFalse(fixture.persistence.dispatchFenceValid("chat", proposal));
            fixture.run(() -> fixture.persistence.replace("chat", state.revisePlan(state.graph(), state.goal(), "new plan"), List.of()));
            assertFalse(fixture.persistence.dispatchFenceValid("chat", proposal));
            fixture.run(() -> {
                AgentSessionPlanner row = fixture.repository.findById("chat").orElseThrow();
                row.setStateJson("{invalid state");
                fixture.repository.saveAndFlush(row);
            });
            assertFalse(fixture.persistence.dispatchFenceValid("chat", proposal));
        }
    }

    private static StateSnapshot state(String execution, TaskNodeStatus status) {
        TaskNode node = new TaskNode("node", "Check evidence", "web", List.of(), List.of(), List.of(), status,
                0, DoneWhen.note(), "", "", 0, "");
        return new StateSnapshot(1, "chat", execution, null, new TaskGraph(List.of(node)), Map.of(), Map.of(), List.of(), 0);
    }
    private static ActionProposal proposal(StateSnapshot state) {
        return new GraphScheduler().propose(state, state.graph().nodes(), 1);
    }

    private static final class Fixture implements AutoCloseable {
        final List<String> statements = new ArrayList<>();
        final org.hibernate.SessionFactory sessions;
        final TransactionTemplate transactions;
        final TransactionTemplate independent;
        final AgentSessionPlannerRepository repository;
        final DbPlannerStatePersistence persistence;

        Fixture() {
            sessions = new Configuration().addAnnotatedClass(AgentSessionPlanner.class)
                    .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                    .setProperty("hibernate.connection.url", "jdbc:h2:mem:dispatch-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                    .setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setStatementInspector((StatementInspector) sql -> { statements.add(sql); return sql; })
                    .buildSessionFactory();
            var entities = SharedEntityManagerCreator.createSharedEntityManager(sessions);
            repository = new JpaRepositoryFactory(entities).getRepository(AgentSessionPlannerRepository.class);
            JpaTransactionManager manager = new JpaTransactionManager(sessions);
            transactions = new TransactionTemplate(manager);
            independent = new TransactionTemplate(manager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            DbPlannerStatePersistence target = new DbPlannerStatePersistence();
            ReflectionTestUtils.setField(target, "repo", repository);
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            persistence = (DbPlannerStatePersistence) proxy.getProxy();
        }
        void run(Runnable operation) { transactions.executeWithoutResult(tx -> operation.run()); }
        @Override public void close() { sessions.close(); }
    }
}
