package com.miniagent.config.service;

import com.miniagent.config.entity.AgentTaskRun;
import com.miniagent.config.repository.AgentTaskRunRepository;
import com.miniagent.replica.RedisTaskConcurrency;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    void terminalTransactionBeginFailureStillReleasesItsTokenWithoutWritingTheDatabase() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        org.springframework.transaction.PlatformTransactionManager manager =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        ReflectionTestUtils.setField(service, "transactionManager", manager);
        when(manager.getTransaction(any())).thenThrow(
                new org.springframework.transaction.CannotCreateTransactionException("injected begin failure"));
        TaskRunService.RunHandle run = new TaskRunService.RunHandle(11L, 7L, "s1", "token-11");

        assertThrows(org.springframework.transaction.CannotCreateTransactionException.class,
                () -> service.markCompleted(run));

        verifyNoInteractions(repository);
        verify(concurrency).unlockSession("s1", "token-11");
        verify(concurrency).releaseUserQuota(7L, "s1", "token-11");
    }

    @Test
    void terminalTransactionRollbackFailureStillReleasesItsOwnResources() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        org.springframework.transaction.PlatformTransactionManager manager =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.springframework.transaction.TransactionStatus transaction =
                mock(org.springframework.transaction.TransactionStatus.class);
        ReflectionTestUtils.setField(service, "transactionManager", manager);
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(repository.finishIfRunning(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("injected database failure"));
        doThrow(new org.springframework.transaction.TransactionSystemException("injected rollback failure"))
                .when(manager).rollback(transaction);
        TaskRunService.RunHandle run = new TaskRunService.RunHandle(11L, 7L, "s1", "token-11");

        org.springframework.transaction.TransactionSystemException failure = assertThrows(
                org.springframework.transaction.TransactionSystemException.class, () -> service.markFailed(run, "failed"));

        assertEquals("injected database failure", failure.getApplicationException().getMessage());
        verify(concurrency).unlockSession("s1", "token-11");
        verify(concurrency).releaseUserQuota(7L, "s1", "token-11");
    }

    @Test
    void missingTerminalTransactionManagerFailsClosedInsteadOfWritingWithoutItsOwnTransaction() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        ReflectionTestUtils.setField(service, "transactionManager", null);
        TaskRunService.RunHandle run = new TaskRunService.RunHandle(11L, 7L, "s1", "token-11");

        assertThrows(NullPointerException.class, () -> service.markCompleted(run));

        verifyNoInteractions(repository);
        verify(concurrency).unlockSession("s1", "token-11");
        verify(concurrency).releaseUserQuota(7L, "s1", "token-11");
    }

    @Test
    void busyOldHandlerReleasesOnlyItsLocalAdmissionAndAllowsRetryAfterItExits() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        TaskRunService service = service(repository, null);
        ReflectionTestUtils.setField(service, "maxPerUser", 1);
        TaskAdmissionGuard guard = mock(TaskAdmissionGuard.class);
        ReflectionTestUtils.setField(service, "admissionGuard", guard);
        when(guard.check(7L, "s1")).thenReturn(TaskAdmissionGuard.Decision.BUSY, TaskAdmissionGuard.Decision.ALLOW);
        when(repository.save(any(AgentTaskRun.class))).thenAnswer(call -> {
            AgentTaskRun run = call.getArgument(0); run.setId(21L); return run;
        });

        TaskRunService.StartResult blocked = service.tryStart(7L, "s1");

        assertFalse(blocked.accepted());
        assertTrue(blocked.error().contains("尚未结束"));
        assertFalse(service.isRunning("s1"));
        verifyNoInteractions(repository);
        TaskRunService.StartResult retried = service.tryStart(7L, "s1");
        assertTrue(retried.accepted(), "rejected admission must release its user quota too");
        service.markCompleted(retried.handle());
    }

    @Test
    void unavailableActivityReadRejectsRedisAdmissionBeforeAnyDatabaseMutation() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        TaskAdmissionGuard guard = mock(TaskAdmissionGuard.class);
        ReflectionTestUtils.setField(service, "admissionGuard", guard);
        when(concurrency.tryLockSession(eq("s1"), any())).thenReturn(true);
        when(concurrency.tryOccupyUserQuota(eq(7L), eq("s1"), any(), eq(2))).thenReturn(true);
        when(guard.check(7L, "s1")).thenThrow(new IllegalStateException("status unavailable"));

        TaskRunService.StartResult blocked = service.tryStart(7L, "s1");

        assertFalse(blocked.accepted());
        assertEquals("暂时无法确认上一轮执行状态，请稍后重试", blocked.error());
        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(concurrency).tryLockSession(eq("s1"), token.capture());
        verify(concurrency).unlockSession("s1", token.getValue());
        verify(concurrency).releaseUserQuota(7L, "s1", token.getValue());
        verifyNoInteractions(repository);
    }

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
        live.setId(11L);
        orphan.setId(12L);
        when(repository.findByStatus(AgentTaskRun.Status.RUNNING))
                .thenReturn(List.of(live, orphan));
        when(concurrency.isSessionLocked("live")).thenReturn(true);
        when(concurrency.isSessionLocked("orphan")).thenReturn(false);
        when(repository.finishIfRunning(eq(12L), eq(AgentTaskRun.Status.RUNNING),
                eq(AgentTaskRun.Status.INTERRUPTED), any(), any())).thenReturn(1);

        service.recoverOrphanedRuns();

        assertEquals(AgentTaskRun.Status.RUNNING, live.getStatus());
        verify(repository, never()).finishIfRunning(eq(11L), any(), any(), any(), any());
        verify(repository).finishIfRunning(eq(12L), eq(AgentTaskRun.Status.RUNNING),
                eq(AgentTaskRun.Status.INTERRUPTED), any(), eq("Process restarted; previous run interrupted"));
        verify(repository, never()).save(live);
        verify(repository, never()).save(orphan);
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
    void localOrphanRecoveryCannotOverwriteAConcurrentlyCompletedRun() {
        assertOrphanRecoveryPreservesConcurrentCompletion(false);
    }

    @Test
    void redisOrphanRecoveryCannotOverwriteAConcurrentlyCompletedRun() {
        assertOrphanRecoveryPreservesConcurrentCompletion(true);
    }

    private void assertOrphanRecoveryPreservesConcurrentCompletion(boolean redisMode) {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = redisMode ? mock(RedisTaskConcurrency.class) : null;
        TaskRunService service = service(repository, concurrency);
        if (concurrency != null) {
            when(concurrency.tryLockSession(eq("s1"), any())).thenReturn(true);
            when(concurrency.tryOccupyUserQuota(eq(7L), eq("s1"), any(), eq(2))).thenReturn(true);
            when(concurrency.renewSessionLock(eq("s1"), any())).thenReturn(true);
        }
        AgentTaskRun snapshot = run("s1");
        snapshot.setId(11L);
        AtomicReference<AgentTaskRun.Status> durableStatus = new AtomicReference<>(AgentTaskRun.Status.RUNNING);
        when(repository.findFirstBySessionIdAndStatusOrderByStartedAtDesc("s1", AgentTaskRun.Status.RUNNING))
                .thenAnswer(ignored -> {
                    // The old owner's terminal transaction commits after the
                    // new admission queried its previous RUNNING snapshot.
                    durableStatus.set(AgentTaskRun.Status.COMPLETED);
                    return Optional.of(snapshot);
                });
        when(repository.save(any(AgentTaskRun.class))).thenAnswer(invocation -> {
            AgentTaskRun entity = invocation.getArgument(0);
            if (Long.valueOf(11L).equals(entity.getId())) durableStatus.set(entity.getStatus());
            else entity.setId(12L);
            return entity;
        });
        when(repository.finishIfRunning(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            if (!Long.valueOf(11L).equals(invocation.getArgument(0, Long.class))) return 1;
            return durableStatus.compareAndSet(invocation.getArgument(1), invocation.getArgument(2)) ? 1 : 0;
        });

        TaskRunService.StartResult started = service.tryStart(7L, "s1");

        assertTrue(started.accepted());
        assertEquals(AgentTaskRun.Status.COMPLETED, durableStatus.get(),
                "orphan recovery must not overwrite the previous owner's committed completion");
        verify(repository, never()).save(snapshot);
        service.markCancelled(started.handle(), "test cleanup");
    }

    @Test
    void startupOrphanRecoveryCannotOverwriteAConcurrentlyCompletedRun() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        AgentTaskRun snapshot = run("s1");
        snapshot.setId(11L);
        AtomicReference<AgentTaskRun.Status> durableStatus = new AtomicReference<>(AgentTaskRun.Status.COMPLETED);
        when(repository.findByStatus(AgentTaskRun.Status.RUNNING)).thenReturn(List.of(snapshot));
        when(repository.save(snapshot)).thenAnswer(invocation -> {
            durableStatus.set(snapshot.getStatus());
            return snapshot;
        });
        when(repository.finishIfRunning(any(), any(), any(), any(), any())).thenAnswer(invocation ->
                durableStatus.compareAndSet(invocation.getArgument(1), invocation.getArgument(2)) ? 1 : 0);

        service.recoverOrphanedRuns();

        assertEquals(AgentTaskRun.Status.COMPLETED, durableStatus.get());
        verify(repository, never()).save(snapshot);
    }

    @Test
    void localRunCanRecoverAfterATerminalDatabaseFailureWithoutReleasingItsReplacement() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        Map<Long, AgentTaskRun> persisted = new LinkedHashMap<>();
        AtomicLong ids = new AtomicLong();
        when(repository.save(any(AgentTaskRun.class))).thenAnswer(invocation -> {
            AgentTaskRun entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId(ids.incrementAndGet());
            persisted.put(entity.getId(), entity);
            return entity;
        });
        when(repository.existsBySessionIdAndStatus(eq("s1"), eq(AgentTaskRun.Status.RUNNING)))
                .thenAnswer(ignored -> persisted.values().stream().anyMatch(entity ->
                        "s1".equals(entity.getSessionId()) && entity.getStatus() == AgentTaskRun.Status.RUNNING));
        when(repository.findFirstBySessionIdAndStatusOrderByStartedAtDesc(
                eq("s1"), eq(AgentTaskRun.Status.RUNNING))).thenAnswer(ignored ->
                persisted.values().stream().filter(entity -> entity.getStatus() == AgentTaskRun.Status.RUNNING)
                        .reduce((earlier, later) -> later));
        AtomicBoolean firstTerminalWrite = new AtomicBoolean(true);
        when(repository.finishIfRunning(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            if (firstTerminalWrite.getAndSet(false)) throw new IllegalStateException("temporary database outage");
            AgentTaskRun entity = persisted.get(invocation.getArgument(0, Long.class));
            if (entity.getStatus() != AgentTaskRun.Status.RUNNING) return 0;
            entity.setStatus(invocation.getArgument(2));
            entity.setFinishedAt(invocation.getArgument(3));
            entity.setErrorMessage(invocation.getArgument(4));
            return 1;
        });
        TaskRunService service = service(repository, null);
        ReflectionTestUtils.setField(service, "maxPerUser", 1);
        TaskRunService.StartResult first = service.tryStart(7L, "s1");
        assertTrue(first.accepted());

        assertThrows(IllegalStateException.class, () -> service.markCompleted(first.handle()));

        assertEquals(Optional.empty(), service.activeRunToken("s1"));
        assertFalse(service.isRunning("s1"), "a stale database row has no live local worker");
        TaskRunService.StartResult next = service.tryStart(7L, "s1");
        assertTrue(next.accepted(), "a recovered database must allow the session to start again");
        assertEquals(AgentTaskRun.Status.INTERRUPTED, persisted.get(first.handle().runId()).getStatus());
        assertTrue(persisted.get(first.handle().runId()).getErrorMessage().contains("Local run ownership lost"));
        service.markCompleted(first.handle());
        assertEquals(AgentTaskRun.Status.RUNNING, persisted.get(next.handle().runId()).getStatus());
        assertEquals(Optional.of(next.handle().fencingToken()), service.activeRunToken("s1"));
        assertFalse(service.tryStart(7L, "s2").accepted(), "an old finish cannot release the replacement's quota");
        service.markCompleted(next.handle());
    }

    @Test
    void localOwnerBlocksAdmissionEvenWhenTheDatabaseHasNoRunningRow() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        TaskRunService service = service(repository, null);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> locks =
                (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(service, "localSessionLocks");
        locks.put("s1", "live-owner");

        assertTrue(service.isRunning("s1"));
        assertFalse(service.tryStart(7L, "s1").accepted());

        assertEquals(Optional.of("live-owner"), service.activeRunToken("s1"));
        verifyNoInteractions(repository);
    }

    @Test
    void failedLocalOrphanRecoveryRollsBackAdmissionBeforeAnyNewRunIsCreated() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        TaskRunService service = service(repository, null);
        ReflectionTestUtils.setField(service, "maxPerUser", 1);
        AgentTaskRun orphan = run("s1");
        orphan.setId(11L);
        when(repository.existsBySessionIdAndStatus("s1", AgentTaskRun.Status.RUNNING)).thenReturn(true);
        when(repository.findFirstBySessionIdAndStatusOrderByStartedAtDesc("s1", AgentTaskRun.Status.RUNNING))
                .thenReturn(Optional.of(orphan));
        when(repository.finishIfRunning(eq(11L), eq(AgentTaskRun.Status.RUNNING),
                eq(AgentTaskRun.Status.INTERRUPTED), any(), any()))
                .thenThrow(new IllegalStateException("database still unavailable")).thenReturn(1);

        TaskRunService.StartResult rejected = service.tryStart(7L, "s1");

        assertFalse(rejected.accepted());
        assertTrue(rejected.error().contains("database still unavailable"));
        assertEquals(Optional.empty(), service.activeRunToken("s1"));
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Long, AtomicInteger> counts =
                (ConcurrentHashMap<Long, AtomicInteger>) ReflectionTestUtils.getField(service, "localRunningCount");
        assertEquals(0, counts.get(7L).get());
        verify(repository, never()).save(any(AgentTaskRun.class));
        when(repository.save(any(AgentTaskRun.class))).thenAnswer(invocation -> {
            AgentTaskRun entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId(12L);
            return entity;
        });
        TaskRunService.StartResult recovered = service.tryStart(7L, "s1");
        assertTrue(recovered.accepted());
        service.markCancelled(recovered.handle(), "test cleanup");
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
        ReflectionTestUtils.setField(service, "transactionManager",
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "taskConcurrency", concurrency);
        return service;
    }
}
