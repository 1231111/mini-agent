package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FilePlannerEventConcurrencyTest {
    @TempDir Path directory;
    private static final Goal GOAL = new Goal("goal", "fixture", "", Map.of(), List.of(), List.of());

    @Test void concurrentStoreAppendsDoNotLoseAcknowledgedFileEvents() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        FilePlannerStatePersistence persistence = new FilePlannerStatePersistence(directory.toString()) {
            @Override public Optional<Bundle> load(String session) {
                Optional<Bundle> loaded = super.load(session);
                if (Thread.currentThread().getName().startsWith("event-writer") && reads.incrementAndGet() <= 2) {
                    try { barrier.await(5, TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
                return loaded;
            }
        };
        PlannerStateStore store = new PlannerStateStore(persistence, null);
        StateSnapshot initial = store.init("chat", "execution", GOAL, new TaskGraph(List.of()));
        try (var workers = Executors.newFixedThreadPool(2, runnable -> new Thread(runnable, "event-writer"))) {
            var first = workers.submit(() -> store.appendEvent("chat", initial, event("first")));
            var second = workers.submit(() -> store.appendEvent("chat", initial, event("second")));
            assertTrue(first.get(10, TimeUnit.SECONDS)); assertTrue(second.get(10, TimeUnit.SECONDS));
        }
        assertTrue(store.events("chat").containsAll(List.of(event("first"), event("second"))));
    }

    @Test void staleGraphEventSnapshotCannotRestoreTrimmedFileHistory() {
        FilePlannerStatePersistence persistence = new FilePlannerStatePersistence(directory.toString()) {
            boolean injected;
            @Override public synchronized boolean compareAndSetAppending(String session, long version, String execution,
                                                                          StateSnapshot next, List<DomainEvent> events) {
                if (!injected) {
                    injected = true;
                    assertTrue(appendEvents(session, version, execution, List.of(event("trim"))));
                }
                return super.compareAndSetAppending(session, version, execution, next, events);
            }
        };
        PlannerStateStore store = new PlannerStateStore(persistence, null);
        StateSnapshot initial = store.init("chat", "execution", GOAL, new TaskGraph(List.of()));
        persistence.replace("chat", initial,
                java.util.stream.IntStream.range(0, 500).mapToObj(i -> event("seed-" + i)).toList());
        store.commit("chat", 1, initial, List.of(event("business")));
        List<DomainEvent> retained = store.events("chat");
        assertEquals(402, retained.size());
        assertFalse(retained.contains(event("seed-0")));
        assertTrue(retained.containsAll(List.of(event("seed-499"), event("trim"), event("business"))));
    }

    @Test void memoryAndFileBothCommitGraphAndAuditUnderOneStreamBoundary() {
        for (PlannerStateStore store : List.of(new PlannerStateStore(),
                new PlannerStateStore(new FilePlannerStatePersistence(directory.toString()), null))) {
            StateSnapshot initial = store.init("chat", "execution", GOAL, new TaskGraph(List.of()));
            store.commit("chat", 1, initial.withExecution(Map.of("done", true)), List.of(event("accepted")));
            assertTrue(store.events("chat").contains(event("accepted")));
            assertThrows(PlannerStateStore.VersionConflictException.class,
                    () -> store.commit("chat", 1, initial, List.of(event("failed-commit"))));
            assertFalse(store.events("chat").contains(event("failed-commit")));
        }
    }

    private static DomainEvent event(String id) {
        return new DomainEvent(id, DomainEventType.STATE_COMMITTED, null, null, Map.of(), Instant.EPOCH);
    }
}
