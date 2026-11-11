package com.miniagent.config.service;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionSignalStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

class TaskRunLeaseRenewerTest {

    @Test
    void startValidatesLeaseOwnershipBeforeSchedulingAnyRenewal() {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionControl executionControl = mock(ExecutionControl.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(runService.renewSessionLock("s1", "token-1")).thenReturn(false);
        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, scheduler);

        TaskRunLeaseRenewer.Guard guard = renewer.start(run("s1", "token-1"));

        verify(executionControl).cancel("s1", "token-1");
        verify(scheduler, never()).scheduleWithFixedDelay(
                any(), anyLong(), anyLong(), any());
        guard.close();
        renewer.shutdown();
    }

    @Test
    void initialRenewalExceptionCancelsBeforeScheduling() {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionControl executionControl = mock(ExecutionControl.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(runService.renewSessionLock("s1", "token-1"))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, scheduler);

        assertDoesNotThrow(() -> renewer.start(run("s1", "token-1")));

        verify(executionControl).cancel("s1", "token-1");
        verify(scheduler, never()).scheduleWithFixedDelay(
                any(), anyLong(), anyLong(), any());
        renewer.shutdown();
    }

    @Test
    void renewsAtThirdOfTtlAndCapsIntervalAtThirtySeconds() {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionControl executionControl = mock(ExecutionControl.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(runService.runLockTtlMillis()).thenReturn(60_000L);
        when(runService.renewSessionLock("s1", "token-1")).thenReturn(true);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));

        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, scheduler);
        AutoCloseable guard = renewer.start(run("s1", "token-1"));

        verify(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), eq(20_000L), eq(20_000L), eq(TimeUnit.MILLISECONDS));
        assertEquals(30_000L, TaskRunLeaseRenewer.renewalIntervalMillis(7_200_000L));
        renewer.shutdown();
        assertDoesNotThrow(guard::close);
        verify(future).cancel(false);
        verify(scheduler).shutdownNow();
    }

    @Test
    void lostLeaseCancelsOnlyItsRunAndStopsItsOwnFuture() {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionControl executionControl = mock(ExecutionControl.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(runService.runLockTtlMillis()).thenReturn(9_000L);
        when(runService.renewSessionLock("s1", "token-1")).thenReturn(true, false);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        org.mockito.ArgumentCaptor<Runnable> tick =
                org.mockito.ArgumentCaptor.forClass(Runnable.class);

        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, scheduler);
        renewer.start(run("s1", "token-1"));
        verify(scheduler).scheduleWithFixedDelay(tick.capture(),
                eq(3_000L), eq(3_000L), eq(TimeUnit.MILLISECONDS));

        tick.getValue().run();
        tick.getValue().run();

        verify(runService, times(2)).renewSessionLock("s1", "token-1");
        verify(executionControl).cancel("s1", "token-1");
        verify(future).cancel(false);
        renewer.shutdown();
    }

    @Test
    void renewalAndRedisCancellationErrorsDoNotEscapeTick() {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionControl executionControl = mock(ExecutionControl.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(runService.runLockTtlMillis()).thenReturn(3_000L);
        when(runService.renewSessionLock("s1", "token-1"))
                .thenReturn(true)
                .thenThrow(new IllegalStateException("redis renewal failed"));
        doReturn(future).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        doThrow(new IllegalStateException("redis cancellation failed"))
                .when(executionControl).cancel("s1", "token-1");
        org.mockito.ArgumentCaptor<Runnable> tick =
                org.mockito.ArgumentCaptor.forClass(Runnable.class);

        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, scheduler);
        renewer.start(run("s1", "token-1"));
        verify(scheduler).scheduleWithFixedDelay(tick.capture(),
                eq(1_000L), eq(1_000L), eq(TimeUnit.MILLISECONDS));

        assertDoesNotThrow(() -> tick.getValue().run());
        verify(executionControl).cancel("s1", "token-1");
        verify(future).cancel(false);
        renewer.shutdown();
    }

    @Test
    void closingOldGuardDoesNotStopRenewalForNewRunOnSameSession() throws Exception {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionControl executionControl = mock(ExecutionControl.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> oldFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> newFuture = mock(ScheduledFuture.class);
        when(runService.runLockTtlMillis()).thenReturn(3_000L);
        when(runService.renewSessionLock("s1", "old-token")).thenReturn(true);
        when(runService.renewSessionLock("s1", "new-token")).thenReturn(true);
        doReturn(oldFuture, newFuture).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));

        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, scheduler);
        AutoCloseable oldGuard = renewer.start(run("s1", "old-token"));
        renewer.start(run("s1", "new-token"));

        oldGuard.close();

        verify(oldFuture).cancel(false);
        verify(newFuture, never()).cancel(false);
        verify(executionControl, never()).cancel(any(), any());
        renewer.shutdown();
        verify(newFuture).cancel(false);
    }

    @Test
    void tickThatRunsAfterCloseCannotRenewOrCancelTheClosedRun() throws Exception {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionControl executionControl = mock(ExecutionControl.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(runService.runLockTtlMillis()).thenReturn(3_000L);
        when(runService.renewSessionLock("s1", "token-1")).thenReturn(true);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        org.mockito.ArgumentCaptor<Runnable> tick =
                org.mockito.ArgumentCaptor.forClass(Runnable.class);

        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, scheduler);
        AutoCloseable guard = renewer.start(run("s1", "token-1"));
        verify(scheduler).scheduleWithFixedDelay(tick.capture(),
                eq(1_000L), eq(1_000L), eq(TimeUnit.MILLISECONDS));

        guard.close();
        tick.getValue().run();

        verify(runService, times(1)).renewSessionLock("s1", "token-1");
        verify(executionControl, never()).cancel(any(), any());
        renewer.shutdown();
    }

    @Test
    void schedulerRejectionImmediatelyCancelsRun() {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionControl executionControl = mock(ExecutionControl.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(runService.runLockTtlMillis()).thenReturn(3_000L);
        when(runService.renewSessionLock("s1", "token-1")).thenReturn(true);
        doThrow(new java.util.concurrent.RejectedExecutionException("shutdown"))
                .when(scheduler).scheduleWithFixedDelay(
                        any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));

        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, scheduler);

        assertDoesNotThrow(() -> renewer.start(run("s1", "token-1")));
        verify(executionControl).cancel("s1", "token-1");
        renewer.shutdown();
    }

    @Test
    void closeWaitsForInFlightTickSoCancellationCannotArriveAfterClose() throws Exception {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionControl executionControl = mock(ExecutionControl.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        CountDownLatch renewalEntered = new CountDownLatch(1);
        CountDownLatch releaseRenewal = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        when(runService.runLockTtlMillis()).thenReturn(3_000L);
        when(runService.renewSessionLock("s1", "token-1"))
                .thenReturn(true).thenAnswer(call -> {
                    renewalEntered.countDown();
                    assertTrue(releaseRenewal.await(2, TimeUnit.SECONDS));
                    return false;
                });
        doReturn(future).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        org.mockito.ArgumentCaptor<Runnable> tick =
                org.mockito.ArgumentCaptor.forClass(Runnable.class);
        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, scheduler);
        TaskRunLeaseRenewer.Guard guard = renewer.start(run("s1", "token-1"));
        verify(scheduler).scheduleWithFixedDelay(tick.capture(),
                anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var renewal = workers.submit(tick.getValue());
            assertTrue(renewalEntered.await(2, TimeUnit.SECONDS));
            var closing = workers.submit(() -> {
                closeStarted.countDown();
                guard.close();
                closeFinished.countDown();
            });
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS));

            releaseRenewal.countDown();
            renewal.get(2, TimeUnit.SECONDS);
            closing.get(2, TimeUnit.SECONDS);
            verify(executionControl).cancel("s1", "token-1");
            tick.getValue().run();
            verify(executionControl, times(1)).cancel("s1", "token-1");
        } finally {
            releaseRenewal.countDown();
            workers.shutdownNow();
            renewer.shutdown();
        }
    }

    @Test
    void redisCancellationFailureStillSetsLocalRunCancellation() {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionSignalStore signals = mock(ExecutionSignalStore.class);
        ExecutionControl executionControl = new ExecutionControl(
                60_000L, 100, 1_000L, Optional.of(signals), Optional.empty());
        executionControl.start("s1", null, "token-1");
        doThrow(new IllegalStateException("redis cancellation failed"))
                .when(signals).cancel(any(), anyLong());
        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, mock(ScheduledExecutorService.class));

        assertDoesNotThrow(() -> renewer.start(run("s1", "token-1")));

        assertTrue(executionControl.isCancelled("s1", "token-1"));
        renewer.shutdown();
    }

    @Test
    void failedInitialRedisRenewalRetainsCancellationBeforeWorkerStarts() {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionSignalStore signals = mock(ExecutionSignalStore.class);
        ExecutionControl executionControl = new ExecutionControl(
                60_000L, 100, 1_000L, Optional.of(signals), Optional.empty());
        when(runService.renewSessionLock("s1", "token-1"))
                .thenThrow(new IllegalStateException("redis renewal failed"));
        doThrow(new IllegalStateException("redis cancellation failed"))
                .when(signals).cancel(any(), anyLong());
        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, mock(ScheduledExecutorService.class));

        assertDoesNotThrow(() -> renewer.start(run("s1", "token-1")));

        assertTrue(executionControl.isCancelled("s1", "token-1"));
        executionControl.start("s1", null, "token-1");
        assertTrue(executionControl.isCancelled("s1", "token-1"));
        renewer.shutdown();
    }

    @Test
    void shutdownCancelsActiveRunAndRejectsNewRenewals() {
        TaskRunService runService = mock(TaskRunService.class);
        ExecutionControl executionControl = mock(ExecutionControl.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(runService.runLockTtlMillis()).thenReturn(3_000L);
        when(runService.renewSessionLock("s1", "token-1")).thenReturn(true);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        TaskRunLeaseRenewer renewer = new TaskRunLeaseRenewer(
                runService, executionControl, scheduler);
        TaskRunLeaseRenewer.Guard guard = renewer.start(run("s1", "token-1"));

        renewer.shutdown();
        renewer.shutdown();
        guard.close();
        renewer.start(run("s1", "token-2"));

        verify(future, times(1)).cancel(false);
        verify(executionControl).cancel("s1", "token-1");
        verify(executionControl).cancel("s1", "token-2");
        verify(scheduler, times(1)).shutdownNow();
        verify(runService, never()).renewSessionLock("s1", "token-2");
    }

    private static TaskRunService.RunHandle run(String sessionId, String token) {
        return new TaskRunService.RunHandle(11L, 7L, sessionId, token);
    }
}
