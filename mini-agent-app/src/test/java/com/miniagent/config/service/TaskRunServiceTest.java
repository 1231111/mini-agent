package com.miniagent.config.service;

import com.miniagent.config.entity.AgentTaskRun;
import com.miniagent.config.repository.AgentTaskRunRepository;
import com.miniagent.replica.RedisTaskConcurrency;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class TaskRunServiceTest {

    @Test
    void terminalTransitionUsesRunIdAndFencingToken() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        TaskRunService.RunHandle run =
                new TaskRunService.RunHandle(11L, 7L, "s1", "token-11");

        service.markCompleted(run);

        verify(repository).finishIfRunning(
                eq(11L),
                eq(AgentTaskRun.Status.RUNNING),
                eq(AgentTaskRun.Status.COMPLETED),
                any(),
                isNull());
        verify(concurrency).unlockSession("s1", "token-11");
        verify(concurrency).releaseUserQuota(7L, "s1", "token-11");
    }

    @Test
    void redisReleaseFailuresDoNotSuppressTheOtherReleaseOrDatabaseTerminalUpdate() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        TaskRunService.RunHandle run =
                new TaskRunService.RunHandle(11L, 7L, "s1", "token-11");
        doThrow(new IllegalStateException("session redis unavailable"))
                .when(concurrency).unlockSession("s1", "token-11");
        doThrow(new IllegalStateException("quota redis unavailable"))
                .when(concurrency).releaseUserQuota(7L, "s1", "token-11");

        service.markCompleted(run);

        verify(repository).finishIfRunning(eq(11L), eq(AgentTaskRun.Status.RUNNING),
                eq(AgentTaskRun.Status.COMPLETED), any(), isNull());
        verify(concurrency).unlockSession("s1", "token-11");
        verify(concurrency).releaseUserQuota(7L, "s1", "token-11");
    }

    @Test
    void databaseTerminalUpdateExceptionIsNotSwallowedWhenRedisCleanupAlsoFails() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        TaskRunService.RunHandle run =
                new TaskRunService.RunHandle(11L, 7L, "s1", "token-11");
        when(repository.finishIfRunning(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(concurrency).unlockSession("s1", "token-11");
        doThrow(new IllegalStateException("redis unavailable"))
                .when(concurrency).releaseUserQuota(7L, "s1", "token-11");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.markCompleted(run));

        assertEquals("database unavailable", failure.getMessage());
        verify(concurrency).unlockSession("s1", "token-11");
        verify(concurrency).releaseUserQuota(7L, "s1", "token-11");
    }

    @Test
    void redisQuotaAcquisitionExceptionBestEffortReleasesTokenOwnedResources() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        when(concurrency.tryLockSession(eq("s1"), any())).thenReturn(true);
        when(concurrency.tryOccupyUserQuota(eq(7L), eq("s1"), any(), eq(2)))
                .thenThrow(new IllegalStateException("quota command timed out after write"));

        TaskRunService.StartResult result = service.tryStart(7L, "s1");

        assertFalse(result.accepted());
        assertTrue(result.error().contains("quota command timed out after write"));
        org.mockito.ArgumentCaptor<String> token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(concurrency).tryLockSession(eq("s1"), token.capture());
        verify(concurrency).tryOccupyUserQuota(7L, "s1", token.getValue(), 2);
        verify(concurrency).unlockSession("s1", token.getValue());
        verify(concurrency).releaseUserQuota(7L, "s1", token.getValue());
        verifyNoInteractions(repository);
    }

    @Test
    void admissionCleanupFailuresPreserveTheOriginalFailureAndAttemptBothReleases() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        when(concurrency.tryLockSession(eq("s1"), any())).thenReturn(true);
        when(concurrency.tryOccupyUserQuota(eq(7L), eq("s1"), any(), eq(2)))
                .thenThrow(new IllegalStateException("original quota failure"));
        doThrow(new IllegalStateException("unlock cleanup failure"))
                .when(concurrency).unlockSession(eq("s1"), any());
        doThrow(new IllegalStateException("quota cleanup failure"))
                .when(concurrency).releaseUserQuota(eq(7L), eq("s1"), any());

        TaskRunService.StartResult result = service.tryStart(7L, "s1");

        assertFalse(result.accepted());
        assertEquals("无法启动任务: original quota failure", result.error());
        org.mockito.ArgumentCaptor<String> token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(concurrency).tryLockSession(eq("s1"), token.capture());
        verify(concurrency).unlockSession("s1", token.getValue());
        verify(concurrency).releaseUserQuota(7L, "s1", token.getValue());
        verifyNoInteractions(repository);
    }

    @Test
    void unconfirmedSessionLockAcquisitionAlsoCleansItsUniqueToken() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        when(concurrency.tryLockSession(eq("s1"), any()))
                .thenThrow(new IllegalStateException("session lock command timed out after write"));

        TaskRunService.StartResult result = service.tryStart(7L, "s1");

        assertFalse(result.accepted());
        org.mockito.ArgumentCaptor<String> token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(concurrency).tryLockSession(eq("s1"), token.capture());
        verify(concurrency).unlockSession("s1", token.getValue());
        verify(concurrency).releaseUserQuota(7L, "s1", token.getValue());
        verify(concurrency, never()).tryOccupyUserQuota(anyLong(), any(), any(), anyInt());
        verifyNoInteractions(repository);
    }

    @Test
    void staleRunCannotRenewOrReleaseCurrentLocalLease() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        TaskRunService service = service(repository, null);
        TaskRunService.RunHandle stale =
                new TaskRunService.RunHandle(11L, 7L, "s1", "stale-token");
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> locks =
                (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(
                        service, "localSessionLocks");
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Long, AtomicInteger> counts =
                (ConcurrentHashMap<Long, AtomicInteger>) ReflectionTestUtils.getField(
                        service, "localRunningCount");
        locks.put("s1", "current-token");
        counts.put(7L, new AtomicInteger(1));

        service.markCompleted(stale);

        assertFalse(service.renewSessionLock("s1", "stale-token"));
        assertTrue(service.renewSessionLock("s1", "current-token"));
        assertEquals("current-token", locks.get("s1"));
        assertEquals(1, counts.get(7L).get());
    }

    @Test
    void startupKeepsLiveReplicaRunAndRecoversOnlyExpiredLease() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        AgentTaskRun live = run("live");
        AgentTaskRun orphan = run("orphan");
        when(repository.findByStatus(AgentTaskRun.Status.RUNNING))
                .thenReturn(List.of(live, orphan));
        when(concurrency.isSessionLocked("live")).thenReturn(true);
        when(concurrency.isSessionLocked("orphan")).thenReturn(false);

        service.recoverOrphanedRuns();

        assertEquals(AgentTaskRun.Status.RUNNING, live.getStatus());
        assertEquals(AgentTaskRun.Status.INTERRUPTED, orphan.getStatus());
        verify(repository, never()).save(live);
        verify(repository).save(orphan);
    }

    @Test
    void redisLeaseIsTheRunningSourceOfTruth() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        when(concurrency.isSessionLocked("stale")).thenReturn(false);

        assertFalse(service.isRunning("stale"));
        verify(repository, never())
                .existsBySessionIdAndStatus("stale", AgentTaskRun.Status.RUNNING);
    }

    @Test
    void humanWaitReleasesTheRunWithoutRecordingGoalCompletion() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        TaskRunService.RunHandle run = new TaskRunService.RunHandle(11L, 7L, "s1", "token-11");

        service.markWaiting(run, "USER_QUESTION");

        verify(repository).finishIfRunning(eq(11L), eq(AgentTaskRun.Status.RUNNING),
                eq(AgentTaskRun.Status.WAITING), any(), eq("USER_QUESTION"));
        verify(concurrency).unlockSession("s1", "token-11");
        verify(concurrency).releaseUserQuota(7L, "s1", "token-11");
    }

    @Test
    void activeLocalRunTokenTracksTheAcceptedRunAndItsRelease() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        TaskRunService service = service(repository, null);
        AgentTaskRun persisted = run("s1");
        persisted.setId(11L);
        when(repository.save(any(AgentTaskRun.class))).thenReturn(persisted);

        assertEquals(Optional.empty(), service.activeRunToken("s1"));
        TaskRunService.StartResult started = service.tryStart(7L, "s1");
        assertTrue(started.accepted());
        assertEquals(Optional.of(started.handle().fencingToken()), service.activeRunToken("s1"));

        service.markCancelled(started.handle(), "cancelled");
        assertEquals(Optional.empty(), service.activeRunToken("s1"));
    }

    @Test
    void oldLocalFinishCannotHideTheCurrentToken() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        TaskRunService service = service(repository, null);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> locks =
                (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(service, "localSessionLocks");
        locks.put("s1", "new-run");

        service.markCompleted(new TaskRunService.RunHandle(11L, 7L, "s1", "old-run"));

        assertEquals(Optional.of("new-run"), service.activeRunToken("s1"));
    }

    @Test
    void activeRunTokenUsesDurableRedisOwnerIncludingItsAbsence() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> local =
                (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(service, "localSessionLocks");
        local.put("s1", "stale-local");
        when(concurrency.currentSessionToken("s1"))
                .thenReturn(Optional.of("other-replica-run"), Optional.empty());

        assertEquals(Optional.of("other-replica-run"), service.activeRunToken("s1"));
        assertEquals(Optional.empty(), service.activeRunToken("s1"));
        verifyNoInteractions(repository);
    }

    @Test
    void activeRunTokenRejectsBlankSessionsWithoutLookingUpAnyOwner() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);

        assertEquals(Optional.empty(), service.activeRunToken(null));
        assertEquals(Optional.empty(), service.activeRunToken(""));
        assertEquals(Optional.empty(), service.activeRunToken("  "));

        verifyNoInteractions(repository, concurrency);
    }

    @Test
    void runLockTtlUsesConfiguredValueInLocalMode() {
        TaskRunService service = service(mock(AgentTaskRunRepository.class), null);
        ReflectionTestUtils.setField(service, "runLockTtlSeconds", 17L);

        assertEquals(Duration.ofSeconds(17).toMillis(), service.runLockTtlMillis());
    }

    @Test
    void runLockTtlDelegatesToRedisConfigurationWhenEnabled() {
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(mock(AgentTaskRunRepository.class), concurrency);
        when(concurrency.runLockTtlMillis()).thenReturn(45_000L);

        assertEquals(45_000L, service.runLockTtlMillis());
        verify(concurrency).runLockTtlMillis();
    }

    private static AgentTaskRun run(String sessionId) {
        AgentTaskRun run = new AgentTaskRun();
        run.setSessionId(sessionId);
        run.setStatus(AgentTaskRun.Status.RUNNING);
        return run;
    }

    private static TaskRunService service(
            AgentTaskRunRepository repository,
            RedisTaskConcurrency concurrency) {
        TaskRunService service = new TaskRunService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "taskConcurrency", concurrency);
        return service;
    }
}
