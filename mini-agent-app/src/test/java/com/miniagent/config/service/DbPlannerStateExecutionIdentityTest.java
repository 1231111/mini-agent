package com.miniagent.config.service;

import com.miniagent.agent.planner.*;
import com.miniagent.config.entity.AgentSessionPlanner;
import com.miniagent.config.repository.AgentSessionPlannerRepository;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual Spring Data JPQL against a fresh H2 schema, without application services or user data. */
class DbPlannerStateExecutionIdentityTest {
    @Test void graphAndEventCasBothRejectAReplacedExecutionAtTheSameVersion() {
        try (Fixture fixture = new Fixture()) {
            StateSnapshot old = snapshot("old", 1), replacement = snapshot("replacement", 1);
            fixture.run(() -> fixture.persistence.replace("chat", old, List.of()));
            StateSnapshot previouslyRead = fixture.read();
            fixture.run(() -> fixture.persistence.replace("chat", replacement, List.of(event("new"))));
            fixture.run(() -> {
                assertFalse(fixture.persistence.compareAndSet("chat", 1, previouslyRead.executionId(),
                        snapshot("old", 2), List.of(event("stale-commit"))));
                assertFalse(fixture.persistence.compareAndSet("chat", 1, snapshot("old", 2), List.of()));
                assertFalse(fixture.persistence.updateEvents("chat", 1, "old", List.of(event("stale-audit"))));
            });
            assertEquals("replacement", fixture.read().executionId());
            fixture.run(() -> assertEquals(List.of(event("new")), fixture.persistence.load("chat").orElseThrow().events()));
            fixture.run(() -> assertTrue(fixture.persistence.compareAndSet("chat", 1, "replacement",
                    snapshot("replacement", 2), List.of(event("current")))));
            assertEquals(2, fixture.read().version());
        }
    }

    @Test void deleteAndRecreateAndLegacyNullIdentityHaveTheSameAtomicFence() {
        try (Fixture fixture = new Fixture()) {
            fixture.run(() -> fixture.persistence.replace("chat", snapshot(null, 1), List.of()));
            fixture.run(() -> assertTrue(fixture.persistence.compareAndSet("chat", 1, null, snapshot(null, 2), List.of())));
            fixture.run(() -> fixture.persistence.delete("chat"));
            fixture.run(() -> fixture.persistence.replace("chat", snapshot("new", 2), List.of()));
            fixture.run(() -> {
                assertFalse(fixture.persistence.compareAndSet("chat", 2, null, snapshot(null, 3), List.of()));
                assertFalse(fixture.persistence.updateEvents("chat", 2, null, List.of(event("stale"))));
            });
            assertEquals("new", fixture.read().executionId());
        }
    }

    @Test void casCannotChangeScopeOrExecutionIdentityEvenWithTheCorrectVersion() {
        try (Fixture fixture = new Fixture()) {
            fixture.run(() -> fixture.persistence.replace("chat", snapshot("current", 1), List.of()));
            assertThrows(IllegalArgumentException.class, () -> fixture.run(() -> fixture.persistence.compareAndSet(
                    "other-scope", 1, "current", snapshot("current", 2), List.of())));
            assertThrows(IllegalArgumentException.class, () -> fixture.run(() -> fixture.persistence.compareAndSet(
                    "chat", 1, "current", snapshot("different", 2), List.of())));
            assertEquals(1, fixture.read().version());
        }
    }

    private static StateSnapshot snapshot(String execution, long version) {
        Goal goal = new Goal("goal", "fixture", "", Map.of(), List.of(), List.of());
        return new StateSnapshot(version, "chat", execution, goal, new TaskGraph(List.of()), Map.of(), Map.of(), List.of(), 0);
    }
    private static DomainEvent event(String id) {
        return new DomainEvent(id, DomainEventType.STATE_COMMITTED, null, null, Map.of(), java.time.Instant.EPOCH);
    }

    private static final class Fixture implements AutoCloseable {
        final org.hibernate.SessionFactory sessions;
        final TransactionTemplate transactions;
        final DbPlannerStatePersistence persistence = new DbPlannerStatePersistence();
        Fixture() {
            sessions = new Configuration().addAnnotatedClass(AgentSessionPlanner.class)
                    .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                    .setProperty("hibernate.connection.url", "jdbc:h2:mem:planner-identity-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                    .setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.show_sql", "false").buildSessionFactory();
            var entities = SharedEntityManagerCreator.createSharedEntityManager(sessions);
            var repository = new JpaRepositoryFactory(entities).getRepository(AgentSessionPlannerRepository.class);
            ReflectionTestUtils.setField(persistence, "repo", repository);
            transactions = new TransactionTemplate(new JpaTransactionManager(sessions));
        }
        void run(Runnable action) { transactions.executeWithoutResult(tx -> action.run()); }
        StateSnapshot read() { return transactions.execute(tx -> persistence.load("chat").orElseThrow().snapshot()); }
        @Override public void close() { sessions.close(); }
    }
}
