package com.miniagent.agent.planner;

import com.miniagent.agent.scope.TaskBoundary;
import com.miniagent.agent.scope.TaskScopeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlannerStatePersistenceIdentityTest {
    @TempDir Path directory;
    private static final Goal GOAL = new Goal("goal", "fixture", "", Map.of(), List.of(), List.of());

    @Test void persistentCommitCannotOverwriteAReplacementAtTheSameVersion() {
        InterleavedPersistence persistence = new InterleavedPersistence();
        PlannerStateStore store = new PlannerStateStore(persistence, null);
        StateSnapshot old = store.init("chat", "old-execution", GOAL, new TaskGraph(List.of()));
        persistence.beforeCas = () -> store.init("chat", "replacement", GOAL, new TaskGraph(List.of()));
        assertThrows(PlannerStateStore.VersionConflictException.class,
                () -> store.commit("chat", 1, old.withExecution(Map.of("approved", true))));
        assertEquals("replacement", store.get("chat").orElseThrow().executionId());
        assertFalse(store.get("chat").orElseThrow().execution().containsKey("approved"));
    }

    @Test void auditRetryDoesNotAttachTheOldExecutionsEventsToAReplacement() {
        InterleavedPersistence persistence = new InterleavedPersistence();
        PlannerStateStore store = new PlannerStateStore(persistence, null);
        StateSnapshot old = store.init("chat", "old-execution", GOAL, new TaskGraph(List.of()));
        DomainEvent event = event("old-audit");
        persistence.beforeEvent = () -> store.init("chat", "replacement", GOAL, new TaskGraph(List.of()));
        assertFalse(store.appendEvent("chat", old, event));
        assertFalse(store.events("chat").contains(event));
        assertFalse(store.appendEvent("chat", old, event));
    }

    @Test void callerBoundAuditAcceptsOnlyTheSameExecutionAndCurrentScope() {
        TaskScopeRegistry scopes = new TaskScopeRegistry();
        PlannerStateStore store = new PlannerStateStore(scopes);
        StateSnapshot old = store.init("chat", "task", GOAL, new TaskGraph(List.of()));
        store.commit("chat", 1, old.withExecution(Map.of("progress", true)));
        assertTrue(store.appendEvent("chat", old, event("same-execution-later-version")));
        scopes.resolve("chat", TaskBoundary.NEW, "fixture-switch");
        store.init("chat", "task", GOAL, new TaskGraph(List.of()));
        assertFalse(store.appendEvent("chat", old, event("old-scope")));
        assertThrows(IllegalArgumentException.class, () -> store.commit("chat", 1, old));
    }

    @Test void filePersistenceUsesExecutionIdentityForBothGraphAndEventCas() {
        FilePlannerStatePersistence persistence = new FilePlannerStatePersistence(directory.toString());
        StateSnapshot old = snapshot("old", 1), replacement = snapshot("replacement", 1);
        persistence.replace("chat", old, List.of());
        persistence.replace("chat", replacement, List.of(event("new")));
        assertFalse(persistence.compareAndSet("chat", 1, "old", snapshot("old", 2), List.of()));
        assertFalse(persistence.compareAndSet("chat", 1, snapshot("old", 2), List.of()));
        assertFalse(persistence.updateEvents("chat", 1, "old", List.of(event("old"))));
        assertTrue(persistence.compareAndSet("chat", 1, "replacement", snapshot("replacement", 2), List.of()));
        assertEquals("replacement", persistence.load("chat").orElseThrow().snapshot().executionId());
    }

    private static StateSnapshot snapshot(String execution, long version) {
        return new StateSnapshot(version, "chat", execution, GOAL, new TaskGraph(List.of()), Map.of(), Map.of(), List.of(), 0);
    }
    private static DomainEvent event(String id) {
        return new DomainEvent(id, DomainEventType.STATE_COMMITTED, null, null, Map.of(), null);
    }

    private static final class InterleavedPersistence implements PlannerStatePersistence {
        final AtomicReference<Bundle> current = new AtomicReference<>();
        Runnable beforeCas = () -> { }, beforeEvent = () -> { };
        @Override public Optional<Bundle> load(String key) { return Optional.ofNullable(current.get()); }
        @Override public void replace(String key, StateSnapshot snapshot, List<DomainEvent> events) {
            current.set(new Bundle(snapshot, events));
        }
        @Override public boolean compareAndSet(String key, long version, StateSnapshot next, List<DomainEvent> events) {
            fail("Production store must pass execution identity"); return false;
        }
        @Override public boolean compareAndSet(String key, long version, String execution, StateSnapshot next, List<DomainEvent> events) {
            Runnable hook = beforeCas; beforeCas = () -> { }; hook.run();
            Bundle old = current.get();
            return old != null && old.snapshot().version() == version
                    && java.util.Objects.equals(execution, old.snapshot().executionId())
                    && current.compareAndSet(old, new Bundle(next, events));
        }
        @Override public boolean compareAndSetAppending(String key, long version, String execution,
                                                        StateSnapshot next, List<DomainEvent> appended) {
            Bundle currentBundle = current.get();
            return compareAndSet(key, version, execution, next,
                    PlannerEventHistory.append(currentBundle == null ? List.of() : currentBundle.events(), appended));
        }
        @Override public boolean updateEvents(String key, long version, List<DomainEvent> events) {
            fail("Production store must pass execution identity"); return false;
        }
        @Override public boolean updateEvents(String key, long version, String execution, List<DomainEvent> events) {
            Runnable hook = beforeEvent; beforeEvent = () -> { }; hook.run();
            Bundle old = current.get();
            return old != null && old.snapshot().version() == version
                    && java.util.Objects.equals(execution, old.snapshot().executionId())
                    && current.compareAndSet(old, new Bundle(old.snapshot(), events));
        }
        @Override public boolean appendEvents(String key, long version, String execution, List<DomainEvent> appended) {
            Bundle currentBundle = current.get();
            return updateEvents(key, version, execution,
                    PlannerEventHistory.append(currentBundle == null ? List.of() : currentBundle.events(), appended));
        }
        @Override public void delete(String key) { current.set(null); }
    }
}
