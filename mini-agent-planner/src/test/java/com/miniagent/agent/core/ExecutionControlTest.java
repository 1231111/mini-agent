package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionControlTest {

    @Test
    void tenantQuotaStopsTheLeaseAfterUsageCrossesTheLimit() {
        AtomicLong used = new AtomicLong();
        TenantTokenQuota quota = new TenantTokenQuota() {
            @Override
            public Decision check(Long tenantId) {
                long value = used.get();
                return new Decision(value < 5, value, 5);
            }

            @Override
            public Decision consume(Long tenantId, long tokens) {
                long value = used.addAndGet(tokens);
                return new Decision(value <= 5, value, 5);
            }
        };
        ExecutionControl control = new ExecutionControl(
                60_000, 10, 10_000, Optional.empty(), Optional.of(quota));
        control.start("quota-session", 42L);

        assertEquals(ExecutionControl.StopReason.NONE,
                control.afterModelTokens("quota-session", 3));
        assertEquals(ExecutionControl.StopReason.TENANT_QUOTA_EXCEEDED,
                control.afterModelTokens("quota-session", 3));
        assertEquals(ExecutionControl.StopReason.TENANT_QUOTA_EXCEEDED,
                control.afterModelTokens("quota-session", 0));
    }

    @Test
    void childUsesParentLeaseForCancelAndToolBudget() {
        ExecutionControl control = new ExecutionControl(60_000, 1, 10_000);
        control.start("parent");
        control.attach("parent:sub:1", "parent");

        assertEquals(ExecutionControl.StopReason.NONE, control.beforeTool("parent"));
        assertEquals(ExecutionControl.StopReason.TOOL_BUDGET_EXCEEDED,
                control.beforeTool("parent:sub:1"));

        ExecutionControl cancelled = new ExecutionControl(60_000, 10, 10_000);
        cancelled.start("parent");
        cancelled.attach("parent:sub:1", "parent");
        cancelled.cancel("parent");
        assertEquals(ExecutionControl.StopReason.CANCELLED,
                cancelled.beforeTool("parent:sub:1"));
    }

    @Test
    void pauseFromAnotherReplicaBlocksAtCheckpointUntilResumed() throws Exception {
        SharedSignals signals = new SharedSignals();
        ExecutionControl worker = new ExecutionControl(60_000, 10, 10_000,
                Optional.of(signals), Optional.empty());
        ExecutionControl apiReplica = new ExecutionControl(60_000, 10, 10_000,
                Optional.of(signals), Optional.empty());
        worker.start("pause-session");
        apiReplica.pause("pause-session");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> worker.beforeTool("pause-session"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!apiReplica.isPausedAtCheckpoint("pause-session")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(apiReplica.isPausedAtCheckpoint("pause-session"));
            apiReplica.resume("pause-session");
            assertEquals(ExecutionControl.StopReason.NONE,
                    result.get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelBeforeStartSurvivesAndNextRunStartsClean() {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.cancel("s", "A");
        control.start("s", null, "A");
        assertEquals(ExecutionControl.StopReason.CANCELLED, control.beforeTool("s"));
        control.finish("s", "A");
        control.start("s", null, "B");
        assertEquals(ExecutionControl.StopReason.NONE, control.beforeTool("s"));
    }

    @Test
    void failedRemotePrestartCancelStillStopsTheLocalRunAndReportsFailure() {
        ExecutionSignalStore failingSignals = new SharedSignals() {
            @Override public void cancel(String id, long ttl) {
                throw new IllegalStateException("redis unavailable");
            }
        };
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000,
                Optional.of(failingSignals), Optional.empty());

        assertThrows(IllegalStateException.class, () -> control.cancel("s", "A"));
        assertTrue(control.isCancelled("s", "A"));
        control.start("s", null, "A");
        assertEquals(ExecutionControl.StopReason.CANCELLED, control.stopReason("s"));

        control.finish("s", "A");
        control.start("s", null, "B");
        assertEquals(ExecutionControl.StopReason.NONE, control.stopReason("s"));
    }

    @Test
    void stopReasonObservesDeadlineWithoutEnteringPauseWait() throws Exception {
        ExecutionControl control = new ExecutionControl(1_000, 10, 10_000);
        control.start("s");
        control.pause("s");
        long started = System.nanoTime();
        assertEquals(ExecutionControl.StopReason.NONE, control.stopReason("s"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 250,
                "checking an executing handler must not wait for the pause to resume");
        Thread.sleep(1_050);
        assertEquals(ExecutionControl.StopReason.DEADLINE_EXCEEDED, control.stopReason("s"));
    }

    @Test
    void concurrentCancelAndStartNeverLoseCancellation() throws Exception {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                String token = "run-" + i;
                var gate = new java.util.concurrent.CyclicBarrier(2);
                var start = executor.submit(() -> { gate.await(); control.start("s", null, token); return null; });
                var cancel = executor.submit(() -> { gate.await(); control.cancel("s", token); return null; });
                start.get(2, TimeUnit.SECONDS);
                cancel.get(2, TimeUnit.SECONDS);
                assertEquals(ExecutionControl.StopReason.CANCELLED, control.beforeTool("s"));
                control.finish("s", token);
            }
        } finally { executor.shutdownNow(); }
    }

    @Test
    void delayedOldControlsAndFinishCannotChangeNewRun() {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.start("s", null, "A");
        control.start("s", null, "B");
        control.pause("s", "A");
        control.cancel("s", "A");
        control.finish("s", "A");
        assertFalse(control.isPauseRequested("s", "B"));
        assertEquals(ExecutionControl.StopReason.NONE, control.beforeTool("s"));
    }

    @Test
    void oldWorkerCannotAdoptNewLeaseAndChildRetainsRunIdentity() {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.start("s", null, "A");
        control.attach("child", "s");
        RunScope captured;
        try (var ignored = TaskExecutionContext.bindRun("A")) {
            captured = RunScope.capture().withSession("s").asSubagent(
                    "child", "worker", null, null);
        }
        control.start("s", null, "B");
        try (var ignored = captured.bind()) {
            assertEquals("A", TaskExecutionContext.currentRunToken());
            assertEquals(ExecutionControl.StopReason.CANCELLED, control.beforeTool("child"));
        }
        assertEquals(ExecutionControl.StopReason.NONE, control.beforeTool("s"));
    }

    @Test
    void remotePrestartCancelIsRetainedAndOldFinishIsIsolated() {
        SharedSignals signals = new SharedSignals();
        ExecutionControl worker = new ExecutionControl(60_000, 10, 10_000,
                Optional.of(signals), Optional.empty());
        ExecutionControl api = new ExecutionControl(60_000, 10, 10_000,
                Optional.of(signals), Optional.empty());
        api.cancel("s", "A");
        worker.start("s", null, "A");
        assertEquals(ExecutionControl.StopReason.CANCELLED, worker.beforeTool("s"));
        worker.start("s", null, "B");
        api.cancel("s", "A");
        worker.finish("s", "A");
        assertEquals(ExecutionControl.StopReason.NONE, worker.beforeTool("s"));
    }

    @Test
    void redisCleanupFailureDoesNotRetainLocalRunStateOrEscapeFinish() {
        ExecutionSignalStore failingSignals = new SharedSignals() {
            @Override public void finish(String id) { throw new IllegalStateException("redis unavailable"); }
        };
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000,
                Optional.of(failingSignals), Optional.empty());
        control.start("s", null, "A");

        assertDoesNotThrow(() -> control.finish("s", "A"));
        try (var ignored = TaskExecutionContext.bindRun("A")) {
            assertEquals(ExecutionControl.StopReason.CANCELLED, control.beforeTool("s"),
                    "a completed run must lose its local lease even when Redis cleanup fails");
        }
        control.start("s", null, "B");
        assertEquals(ExecutionControl.StopReason.NONE, control.beforeTool("s"));
    }

    @Test
    void runScopedPauseAcrossReplicasCanBeResumed() throws Exception {
        SharedSignals signals = new SharedSignals();
        ExecutionControl worker = new ExecutionControl(60_000, 10, 10_000,
                Optional.of(signals), Optional.empty());
        ExecutionControl api = new ExecutionControl(60_000, 10, 10_000,
                Optional.of(signals), Optional.empty());
        api.pause("s", "A");
        worker.start("s", null, "A");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> worker.beforeTool("s"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!api.isPausedAtCheckpoint("s", "A") && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(api.isPausedAtCheckpoint("s", "A"));
            api.resume("s", "A");
            assertEquals(ExecutionControl.StopReason.NONE, result.get(2, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    private static class SharedSignals implements ExecutionSignalStore {
        private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> state =
                new ConcurrentHashMap<>();

        @Override public void start(String id, long deadline, long ttl) {
            var values = new ConcurrentHashMap<String, String>();
            values.put("cancelled", "0");
            values.put("pauseRequested", "0");
            values.put("pausedAtCheckpoint", "0");
            state.put(id, values);
        }
        @Override public void startPreservingControls(String id, long deadline, long ttl) {
            state.compute(id, (ignored, previous) -> {
                var values = previous == null ? new ConcurrentHashMap<String, String>() : previous;
                values.putIfAbsent("cancelled", "0");
                values.putIfAbsent("pauseRequested", "0");
                values.putIfAbsent("pausedAtCheckpoint", "0");
                return values;
            });
        }
        @Override public void cancel(String id, long ttl) { put(id, "cancelled", "1"); }
        @Override public boolean isCancelled(String id) { return "1".equals(get(id, "cancelled")); }
        @Override public void pause(String id, long ttl) { put(id, "pauseRequested", "1"); }
        @Override public void resume(String id, long ttl) {
            put(id, "pauseRequested", "0");
            put(id, "pausedAtCheckpoint", "0");
        }
        @Override public boolean isPaused(String id) { return "1".equals(get(id, "pauseRequested")); }
        @Override public void markPausedAtCheckpoint(String id, boolean paused, long ttl) {
            put(id, "pausedAtCheckpoint", paused ? "1" : "0");
        }
        @Override public boolean isPausedAtCheckpoint(String id) {
            return "1".equals(get(id, "pausedAtCheckpoint"));
        }
        @Override public void heartbeat(String id, long time, long ttl) { put(id, "heartbeat", String.valueOf(time)); }
        @Override public void finish(String id) { state.remove(id); }

        private void put(String id, String key, String value) {
            state.computeIfAbsent(id, ignored -> new ConcurrentHashMap<>()).put(key, value);
        }
        private String get(String id, String key) {
            var values = state.get(id);
            return values == null ? null : values.get(key);
        }
    }
}
