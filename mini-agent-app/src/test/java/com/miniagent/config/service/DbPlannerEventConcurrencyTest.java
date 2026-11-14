package com.miniagent.config.service;

import com.miniagent.agent.planner.*;
import com.miniagent.config.entity.AgentSessionPlanner;
import com.miniagent.config.repository.AgentSessionPlannerRepository;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real H2, Spring Data statements and transaction advice; deterministic replica interleavings. */
class DbPlannerEventConcurrencyTest {
    private static final String SESSION = "event-race";
    private static final Goal GOAL = new Goal("goal", "fixture", "", Map.of(), List.of(), List.of());

    @Test void twoSuccessfulAuditAppendsAtTheSameGraphVersionBothSurvive() throws Exception {
        try (Fixture fixture = new Fixture()) {
            StateSnapshot initial = fixture.store.init(SESSION, "execution", GOAL, new TaskGraph(List.of()));
            CyclicBarrier loadedBoth = new CyclicBarrier(2);
            AtomicInteger reads = new AtomicInteger();
            DelegatingPersistence racing = new DelegatingPersistence(fixture.persistence) {
                @Override public Optional<Bundle> load(String session) {
                    Optional<Bundle> loaded = super.load(session);
                    if (reads.incrementAndGet() <= 2) await(loadedBoth);
                    return loaded;
                }
            };
            PlannerStateStore first = new PlannerStateStore(racing, null), second = new PlannerStateStore(racing, null);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var one = workers.submit(() -> first.appendEvent(SESSION, initial, event("first")));
                var two = workers.submit(() -> second.appendEvent(SESSION, initial, event("second")));
                assertTrue(one.get(10, TimeUnit.SECONDS)); assertTrue(two.get(10, TimeUnit.SECONDS));
            }
            assertTrue(fixture.store.events(SESSION).containsAll(List.of(event("first"), event("second"))),
                    "acknowledging both writes must not silently lose the earlier audit event");
            assertEquals(1, fixture.store.get(SESSION).orElseThrow().version(), "audit append does not advance graph version");
        }
    }

    @Test void graphCommitCannotOverwriteAnAuditAppendCompletedAfterItsSnapshotRead() {
        try (Fixture fixture = new Fixture()) {
            StateSnapshot initial = fixture.store.init(SESSION, "execution", GOAL, new TaskGraph(List.of()));
            DelegatingPersistence racing = new DelegatingPersistence(fixture.persistence) {
                @Override public boolean compareAndSetAppending(String session, long version, String execution,
                                                                StateSnapshot next, List<DomainEvent> events) {
                    assertTrue(fixture.store.appendEvent(SESSION, initial, event("acknowledged-before-graph-write")));
                    return super.compareAndSetAppending(session, version, execution, next, events);
                }
            };
            PlannerStateStore writer = new PlannerStateStore(racing, null);
            writer.commit(SESSION, 1, initial.withExecution(Map.of("progress", "committed")));
            assertTrue(fixture.store.events(SESSION).contains(event("acknowledged-before-graph-write")),
                    "the graph commit must append to current audit history, not replace it from a stale load");
            assertEquals(2, fixture.store.get(SESSION).orElseThrow().version());
        }
    }

    @Test void trimmingBetweenTheGraphReadAndWriteDoesNotResurrectDiscardedEvents() {
        try (Fixture fixture = new Fixture()) {
            StateSnapshot initial = fixture.store.init(SESSION, "execution", GOAL, new TaskGraph(List.of()));
            List<DomainEvent> seed = java.util.stream.IntStream.range(0, 500).mapToObj(i -> event("seed-" + i)).toList();
            fixture.persistence.replace(SESSION, initial, seed);
            DelegatingPersistence racing = new DelegatingPersistence(fixture.persistence) {
                @Override public boolean compareAndSetAppending(String session, long version, String execution,
                                                                StateSnapshot next, List<DomainEvent> events) {
                    assertTrue(fixture.persistence.appendEvents(session, version, execution, List.of(event("triggers-trim"))));
                    return super.compareAndSetAppending(session, version, execution, next, events);
                }
            };
            new PlannerStateStore(racing, null).commit(SESSION, 1, initial);
            List<DomainEvent> retained = fixture.store.events(SESSION);
            assertEquals(401, retained.size());
            assertFalse(retained.contains(event("seed-0")));
            assertFalse(retained.contains(event("seed-100")));
            assertTrue(retained.containsAll(List.of(event("seed-101"), event("seed-499"), event("triggers-trim"))));
        }
    }

    @Test void businessEventsCommitAtomicallyWithTheGraphAndFailedCasAddsNothing() {
        try (Fixture fixture = new Fixture()) {
            StateSnapshot initial = fixture.store.init(SESSION, "execution", GOAL, new TaskGraph(List.of()));
            fixture.store.commit(SESSION, 1, initial.withExecution(Map.of("accepted", true)), List.of(event("accepted")));
            assertTrue(fixture.store.events(SESSION).contains(event("accepted")));
            assertEquals(Boolean.TRUE, fixture.store.get(SESSION).orElseThrow().execution().get("accepted"));
            assertThrows(PlannerStateStore.VersionConflictException.class,
                    () -> fixture.store.commit(SESSION, 1, initial, List.of(event("stale-business-event"))));
            assertFalse(fixture.store.events(SESSION).contains(event("stale-business-event")));
            assertFalse(fixture.persistence.appendEvents(SESSION, 2, "obsolete-execution", List.of(event("obsolete"))));
            assertFalse(fixture.store.events(SESSION).contains(event("obsolete")));
        }
    }

    @Test void retryingAnAlreadyRecordedEventDoesNotDuplicateItOrChangeItsFirstReceipt() {
        try (Fixture fixture = new Fixture()) {
            StateSnapshot initial = fixture.store.init(SESSION, "execution", GOAL, new TaskGraph(List.of()));
            DomainEvent first = event("stable-event-id");
            assertTrue(fixture.store.appendEvent(SESSION, initial, first));
            assertTrue(fixture.store.appendEvent(SESSION, initial,
                    new DomainEvent(first.eventId(), first.type(), null, null, Map.of("changed", true), Instant.EPOCH)));
            List<DomainEvent> events = fixture.store.events(SESSION);
            assertEquals(1, events.stream().filter(event -> first.eventId().equals(event.eventId())).count());
            assertTrue(events.contains(first));
        }
    }

    private static DomainEvent event(String id) {
        return new DomainEvent(id, DomainEventType.STATE_COMMITTED, null, null, Map.of(), Instant.EPOCH);
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(5, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError("deterministic read barrier failed", failure); }
    }

    private static class DelegatingPersistence implements PlannerStatePersistence {
        final PlannerStatePersistence delegate;
        DelegatingPersistence(PlannerStatePersistence delegate) { this.delegate = delegate; }
        @Override public Optional<Bundle> load(String session) { return delegate.load(session); }
        @Override public void replace(String session, StateSnapshot next, List<DomainEvent> events) { delegate.replace(session, next, events); }
        @Override public boolean compareAndSet(String session, long version, StateSnapshot next, List<DomainEvent> events) {
            return delegate.compareAndSet(session, version, next, events);
        }
        @Override public boolean compareAndSet(String session, long version, String execution, StateSnapshot next, List<DomainEvent> events) {
            return delegate.compareAndSet(session, version, execution, next, events);
        }
        @Override public boolean compareAndSetAppending(String session, long version, String execution,
                                                        StateSnapshot next, List<DomainEvent> appended) {
            return delegate.compareAndSetAppending(session, version, execution, next, appended);
        }
        @Override public boolean updateEvents(String session, long version, List<DomainEvent> events) {
            return delegate.updateEvents(session, version, events);
        }
        @Override public boolean updateEvents(String session, long version, String execution, List<DomainEvent> events) {
            return delegate.updateEvents(session, version, execution, events);
        }
        @Override public boolean appendEvents(String session, long version, String execution, List<DomainEvent> appended) {
            return delegate.appendEvents(session, version, execution, appended);
        }
        @Override public void delete(String session) { delegate.delete(session); }
    }

    private static final class Fixture implements AutoCloseable {
        final org.hibernate.SessionFactory sessions;
        final PlannerStatePersistence persistence;
        final PlannerStateStore store;
        Fixture() {
            sessions = new Configuration().addAnnotatedClass(AgentSessionPlanner.class)
                    .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                    .setProperty("hibernate.connection.url", "jdbc:h2:mem:planner-events-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                    .setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.show_sql", "false").buildSessionFactory();
            var entities = SharedEntityManagerCreator.createSharedEntityManager(sessions);
            var repository = new JpaRepositoryFactory(entities).getRepository(AgentSessionPlannerRepository.class);
            DbPlannerStatePersistence target = new DbPlannerStatePersistence();
            ReflectionTestUtils.setField(target, "repo", repository);
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(new JpaTransactionManager(sessions), new AnnotationTransactionAttributeSource()));
            persistence = (PlannerStatePersistence) proxy.getProxy();
            store = new PlannerStateStore(persistence, null);
        }
        @Override public void close() { sessions.close(); }
    }
}
