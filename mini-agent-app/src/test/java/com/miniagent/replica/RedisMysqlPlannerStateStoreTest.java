package com.miniagent.replica;

import com.miniagent.agent.planner.DomainEvent;
import com.miniagent.agent.planner.DomainEventType;
import com.miniagent.agent.planner.Goal;
import com.miniagent.agent.planner.PlannerStateJson;
import com.miniagent.agent.planner.PlannerStatePersistence.Bundle;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.config.service.DbPlannerStatePersistence;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contract tests with deterministic database/cache interleavings, without a Redis server or user data. */
class RedisMysqlPlannerStateStoreTest {
    private static final String SESSION = "owned-task";
    private static final String KEY = "planner:session:" + SESSION;

    @Test
    void staleRedisSnapshotAndEventsAreNeverUsedForPlanning() {
        Fixture fixture = new Fixture(bundle(3, "current-execution", "current-events"));
        fixture.seedCache(bundle(2, "older-execution", "older-events"));

        Bundle loaded = fixture.store.load(SESSION).orElseThrow();

        assertEquals(fixture.database.current.get(), loaded);
        assertEquals(3, loaded.snapshot().version());
        verifyNoInteractions(fixture.redis);
    }

    @Test
    void delayedOlderCommitCannotRegressNewerStateOrItsEvents() throws Exception {
        Fixture fixture = new Fixture(bundle(1, "execution", "initial"));
        CountDownLatch olderCommitted = new CountDownLatch(1);
        CountDownLatch releaseOlder = new CountDownLatch(1);
        fixture.database.afterCas = committed -> {
            if (committed.snapshot().version() == 2) {
                olderCommitted.countDown();
                await(releaseOlder);
            }
        };
        try (var threads = Executors.newSingleThreadExecutor()) {
            var older = threads.submit(() -> fixture.store.compareAndSet(
                    SESSION, 1, snapshot(2, "execution"), events("v2")));
            try {
                assertTrue(olderCommitted.await(5, TimeUnit.SECONDS));
                assertTrue(fixture.store.compareAndSet(SESSION, 2, snapshot(3, "execution"), events("v3")));
            } finally {
                releaseOlder.countDown();
            }
            assertTrue(older.get(5, TimeUnit.SECONDS));
        }

        Bundle loaded = fixture.store.load(SESSION).orElseThrow();
        assertEquals(3, loaded.snapshot().version());
        assertEquals(events("v3"), loaded.events());
        verify(fixture.hash, never()).putAll(anyString(), anyMap());
    }

    @Test
    void eventOnlyUpdateNeverPairsLatestSnapshotWithEarlierCallersEvents() {
        Fixture fixture = new Fixture(bundle(2, "execution", "initial"));
        // Another replica commits the next snapshot immediately after the event-only write.
        fixture.database.afterEvents = ignored -> fixture.database.compareAndSet(
                SESSION, 2, snapshot(3, "execution"), events("v3-events"));

        assertTrue(fixture.store.updateEvents(SESSION, 2, events("v2-audit")));
        Bundle loaded = fixture.store.load(SESSION).orElseThrow();

        assertEquals(3, loaded.snapshot().version());
        assertEquals(events("v3-events"), loaded.events());
        verify(fixture.hash, never()).putAll(anyString(), anyMap());
    }

    @Test
    void sameVersionEventsComeFromTheDatabaseBundleEvenWhenRedisHasOlderEvents() {
        Fixture fixture = new Fixture(bundle(2, "execution", "initial"));
        fixture.seedCache(bundle(2, "execution", "initial"));
        fixture.database.updateEvents(SESSION, 2, events("new-audit"));

        Bundle loaded = fixture.store.load(SESSION).orElseThrow();

        assertEquals(2, loaded.snapshot().version());
        assertEquals(events("new-audit"), loaded.events());
    }

    @Test
    void deletedStateCannotBeResurrectedByAnOldRedisEntry() {
        Fixture fixture = new Fixture(null);
        fixture.seedCache(bundle(9, "deleted-execution", "deleted-events"));

        assertTrue(fixture.store.load(SESSION).isEmpty());
        verifyNoInteractions(fixture.redis);
    }

    @Test
    void resetAndDeleteRecreateMayStartAtLowerVersionWithoutUsingPreviousExecution() {
        Fixture fixture = new Fixture(bundle(9, "previous-execution", "old"));
        fixture.seedCache(fixture.database.current.get());
        doThrow(new IllegalStateException("Redis unavailable")).when(fixture.redis).delete(KEY);

        fixture.store.replace(SESSION, snapshot(1, "reset-execution"), events("reset"));
        assertEquals("reset-execution", fixture.store.load(SESSION).orElseThrow().snapshot().executionId());
        fixture.store.delete(SESSION);
        assertTrue(fixture.store.load(SESSION).isEmpty());
        fixture.store.replace(SESSION, snapshot(0, "recreated-execution"), events("created"));

        Bundle loaded = fixture.store.load(SESSION).orElseThrow();
        assertEquals(0, loaded.snapshot().version());
        assertEquals("recreated-execution", loaded.snapshot().executionId());
        assertEquals(events("created"), loaded.events());
    }

    @Test
    void redisCleanupFailureDoesNotChangeDatabaseResultsOrCasConflicts() {
        Fixture fixture = new Fixture(bundle(1, "execution", "initial"));
        doThrow(new IllegalStateException("Redis unavailable")).when(fixture.redis).delete(KEY);

        assertTrue(fixture.store.compareAndSet(SESSION, 1, snapshot(2, "execution"), events("committed")));
        assertFalse(fixture.store.compareAndSet(SESSION, 1, snapshot(2, "execution"), events("conflict")));
        assertTrue(fixture.store.updateEvents(SESSION, 2, events("audit")));
        assertFalse(fixture.store.updateEvents(SESSION, 1, events("stale-audit")));
        assertEquals(events("audit"), fixture.store.load(SESSION).orElseThrow().events());
        fixture.store.delete(SESSION);
        assertTrue(fixture.store.load(SESSION).isEmpty());
        verify(fixture.redis, times(5)).delete(KEY);
    }

    @Test
    void databaseReadFailureDoesNotFallBackToRedisOrConvertIntoMissingState() {
        Fixture fixture = new Fixture(bundle(3, "execution", "current"));
        fixture.seedCache(bundle(2, "execution", "stale"));
        fixture.database.readFailure = new DataAccessResourceFailureException("database unavailable");

        var failure = assertThrows(DataAccessResourceFailureException.class, () -> fixture.store.load(SESSION));

        assertEquals("database unavailable", failure.getMessage());
        verifyNoInteractions(fixture.redis);
    }

    @Test
    void uncertainDatabaseMutationFailureIsPreservedWhileOldCacheCleanupIsAttempted() {
        DbPlannerStatePersistence database = mock(DbPlannerStatePersistence.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisMysqlPlannerStateStore store = store(database, redis);
        var original = new DataAccessResourceFailureException("commit outcome unavailable");
        when(database.compareAndSet(eq(SESSION), eq(1L), any(), anyList())).thenThrow(original);
        when(redis.delete(KEY)).thenThrow(new IllegalStateException("Redis unavailable"));

        assertSame(original, assertThrows(DataAccessResourceFailureException.class,
                () -> store.compareAndSet(SESSION, 1, snapshot(2, "execution"), events("attempt"))));
        verify(redis).delete(KEY);
    }

    @Test void executionIdentityIsPreservedThroughRedisWrapperWhenVersionsRestart() {
        Fixture fixture = new Fixture(bundle(1, "replacement", "new-execution"));
        fixture.seedCache(bundle(1, "old-execution", "stale-cache"));
        assertFalse(fixture.store.compareAndSet(SESSION, 1, "old-execution",
                snapshot(2, "old-execution"), events("stale-commit")));
        assertFalse(fixture.store.updateEvents(SESSION, 1, "old-execution", events("stale-event")));
        assertEquals("replacement", fixture.store.load(SESSION).orElseThrow().snapshot().executionId());
        assertEquals(events("new-execution"), fixture.store.load(SESSION).orElseThrow().events());
        assertTrue(fixture.store.compareAndSet(SESSION, 1, "replacement",
                snapshot(2, "replacement"), events("current-commit")));
        verify(fixture.redis, times(3)).delete(KEY);
    }

    @Test void incrementalAuditAndGraphEventsAreForwardedWithoutReplacingCommittedHistory() {
        Fixture fixture = new Fixture(bundle(1, "execution", "initial"));
        assertTrue(fixture.store.appendEvents(SESSION, 1, "execution", events("audit")));
        assertTrue(fixture.store.compareAndSetAppending(SESSION, 1, "execution",
                snapshot(2, "execution"), events("graph")));
        assertEquals(List.of(events("initial").getFirst(), events("audit").getFirst(), events("graph").getFirst()),
                fixture.store.load(SESSION).orElseThrow().events());
        assertFalse(fixture.store.appendEvents(SESSION, 2, "old-execution", events("stale")));
        verify(fixture.redis, times(3)).delete(KEY);
    }

    private static RedisMysqlPlannerStateStore store(DbPlannerStatePersistence database, StringRedisTemplate redis) {
        RedisMysqlPlannerStateStore store = new RedisMysqlPlannerStateStore();
        ReflectionTestUtils.setField(store, "mysql", database);
        ReflectionTestUtils.setField(store, "redis", redis);
        return store;
    }

    private static StateSnapshot snapshot(long version, String executionId) {
        Goal goal = new Goal("goal", "Test independent planner reads", "", Map.of(), List.of(), List.of());
        return new StateSnapshot(version, SESSION, executionId, goal, null, Map.of(), Map.of(), List.of(), 0);
    }

    private static List<DomainEvent> events(String label) {
        return List.of(new DomainEvent(label, DomainEventType.STATE_COMMITTED, "action", "node",
                Map.of("label", label), Instant.parse("2026-11-13T00:00:00Z")));
    }

    private static Bundle bundle(long version, String execution, String event) {
        return new Bundle(snapshot(version, execution), events(event));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for controlled interleaving");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class Fixture {
        final MemoryDatabase database;
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        final HashOperations<String, Object, Object> hash;
        final Map<String, Map<Object, Object>> cache = new ConcurrentHashMap<>();
        final RedisMysqlPlannerStateStore store;

        @SuppressWarnings("unchecked")
        Fixture(Bundle initial) {
            database = new MemoryDatabase(initial);
            hash = mock(HashOperations.class);
            when(redis.<Object, Object>opsForHash()).thenReturn(hash);
            when(hash.entries(anyString())).thenAnswer(call -> Map.copyOf(cache.getOrDefault(call.getArgument(0), Map.of())));
            doAnswer(call -> {
                cache.computeIfAbsent(call.getArgument(0), ignored -> new ConcurrentHashMap<>())
                        .putAll(call.getArgument(1));
                return null;
            }).when(hash).putAll(anyString(), anyMap());
            when(redis.delete(anyString())).thenAnswer(call -> cache.remove(call.getArgument(0)) != null);
            store = store(database, redis);
            // Stubbing is not an interaction; the assertion below ensures the real implementation does not read Redis.
        }

        void seedCache(Bundle bundle) {
            cache.put(KEY, new ConcurrentHashMap<>(Map.of(
                    "state", PlannerStateJson.snapshotToJson(bundle.snapshot()),
                    "events", PlannerStateJson.eventsToJson(bundle.events()),
                    "version", String.valueOf(bundle.snapshot().version()))));
        }
    }

    /** Models atomic database snapshots and the CAS contract; not a replacement for database integration tests. */
    private static final class MemoryDatabase extends DbPlannerStatePersistence {
        final AtomicReference<Bundle> current;
        volatile Consumer<Bundle> afterCas = ignored -> {};
        volatile Consumer<Bundle> afterEvents = ignored -> {};
        volatile RuntimeException readFailure;

        MemoryDatabase(Bundle initial) { current = new AtomicReference<>(initial); }

        @Override public Optional<Bundle> load(String session) {
            if (readFailure != null) throw readFailure;
            return Optional.ofNullable(current.get());
        }

        @Override public void replace(String session, StateSnapshot next, List<DomainEvent> events) {
            current.set(new Bundle(next, events));
        }

        @Override public boolean compareAndSet(String session, long expected, StateSnapshot next, List<DomainEvent> events) {
            return compareAndSet(session, expected, next.executionId(), next, events);
        }

        @Override public boolean compareAndSet(String session, long expected, String execution,
                                                StateSnapshot next, List<DomainEvent> events) {
            if (next.version() != expected + 1) throw new IllegalArgumentException("invalid next version");
            Bundle previous = current.get();
            Bundle committed = new Bundle(next, events);
            if (previous == null || previous.snapshot().version() != expected
                    || !java.util.Objects.equals(execution, previous.snapshot().executionId())
                    || !current.compareAndSet(previous, committed))
                return false;
            afterCas.accept(committed);
            return true;
        }

        @Override public boolean compareAndSetAppending(String session, long expected, String execution,
                                                        StateSnapshot next, List<DomainEvent> appended) {
            Bundle previous = current.get();
            return compareAndSet(session, expected, execution, next,
                    com.miniagent.agent.planner.PlannerEventHistory.append(previous == null ? List.of() : previous.events(), appended));
        }

        @Override public boolean updateEvents(String session, long expected, List<DomainEvent> events) {
            Bundle previous = current.get();
            return previous != null && updateEvents(session, expected, previous.snapshot().executionId(), events);
        }

        @Override public boolean updateEvents(String session, long expected, String execution, List<DomainEvent> events) {
            Bundle previous = current.get();
            if (previous == null || previous.snapshot().version() != expected
                    || !java.util.Objects.equals(execution, previous.snapshot().executionId())) return false;
            Bundle committed = new Bundle(previous.snapshot(), events);
            if (!current.compareAndSet(previous, committed)) return false;
            afterEvents.accept(committed);
            return true;
        }

        @Override public boolean appendEvents(String session, long expected, String execution, List<DomainEvent> appended) {
            Bundle previous = current.get();
            return updateEvents(session, expected, execution,
                    com.miniagent.agent.planner.PlannerEventHistory.append(previous == null ? List.of() : previous.events(), appended));
        }

        @Override public void delete(String session) { current.set(null); }
    }
}
