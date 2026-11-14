package com.miniagent.agent.core;

import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class ModelCallControlTest {
    private static final String SESSION = "blocked-model";

    @AfterEach void clear() {
        AgentLoop.clearCurrentSession();
        TaskExecutionContext.clear();
    }

    @Test void cancelReturnsWithoutWaitingForAnSdkThatIgnoresInterrupts() throws Exception {
        assertStopBeforeProviderReturns(60_000, false);
    }

    @Test void deadlineReturnsWithoutWaitingForTheProvidersOwnTimeout() throws Exception {
        assertStopBeforeProviderReturns(1_000, true);
    }

    private void assertStopBeforeProviderReturns(long deadline, boolean timeout) throws Exception {
        ExecutionControl control = new ExecutionControl(deadline, 10, 10_000);
        control.start(SESSION);
        AgentLoop.setCurrentSession(SESSION);
        RunScope scope = RunScope.capture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> pending = callers.submit(() -> {
                try (var ignored = scope.bind()) {
                    return new ModelCallControl(control).call(() -> {
                        attempts.incrementAndGet();
                        entered.countDown();
                        awaitIgnoringInterrupts(release);
                        return "late success";
                    });
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                if (!timeout) control.cancel(SESSION);
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> pending.get(2, TimeUnit.SECONDS));
                var stopped = assertInstanceOf(ModelCallControl.StoppedException.class, failure.getCause());
                assertEquals(timeout ? ExecutionControl.StopReason.DEADLINE_EXCEEDED
                        : ExecutionControl.StopReason.CANCELLED, stopped.reason());
                assertEquals(1, attempts.get());
                assertEquals(1, release.getCount(), "caller must stop before the provider returns");
            } finally {
                release.countDown();
            }
        }
    }

    @Test void pauseRetainsTheSameCompletedResponseUntilResume() throws Exception {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.start(SESSION);
        AgentLoop.setCurrentSession(SESSION);
        RunScope scope = RunScope.capture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch providerFinished = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> pending = callers.submit(() -> {
                try (var ignored = scope.bind()) {
                    return new ModelCallControl(control).call(() -> {
                        attempts.incrementAndGet(); entered.countDown();
                        release.await(); providerFinished.countDown();
                        return "retained response";
                    });
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                control.pause(SESSION);
                release.countDown();
                assertTrue(providerFinished.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.get(350, TimeUnit.MILLISECONDS));
                assertTrue(control.isPausedAtCheckpoint(SESSION));
                control.resume(SESSION);
                assertEquals("retained response", pending.get(2, TimeUnit.SECONDS));
                assertEquals(1, attempts.get(), "resume must not issue the model request again");
            } finally {
                control.resume(SESSION); release.countDown();
            }
        }
    }

    @Test void workerPreservesOwnerAndRunIdentity() {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        AgentLoop.setCurrentSession(SESSION);
        try (var owner = OwnerContext.bind(new OwnerContext(27L, "tenant-a"));
             var run = TaskExecutionContext.bindRun("run-a")) {
            control.start(SESSION, null, "run-a");
            assertEquals("27:tenant-a:run-a:" + SESSION, new ModelCallControl(control).call(() ->
                    OwnerContext.currentUserId() + ":" + OwnerContext.currentTenantId() + ":"
                            + TaskExecutionContext.currentRunToken() + ":" + AgentLoop.getCurrentSession()));
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try { latch.await(); }
            catch (InterruptedException ignored) { /* Deliberately broken SDK substitute. */ }
        }
    }
}
