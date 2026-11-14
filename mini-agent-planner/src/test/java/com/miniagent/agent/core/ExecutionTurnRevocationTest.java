package com.miniagent.agent.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class ExecutionTurnRevocationTest {
    private static final String SESSION = "revoked-plan";

    @AfterEach void clear() {
        AgentLoop.clearCurrentSession();
        ExecutionTurnContext.clear();
        TaskExecutionContext.clear();
    }

    @Test void aRevokedProposalCannotStartAnotherModelRequest() {
        ExecutionControl control = control();
        ExecutionTurnContext.open(SESSION, "execution-a", 1, List.of(), () -> false);
        AtomicInteger calls = new AtomicInteger();
        var stopped = assertThrows(ModelCallControl.StoppedException.class,
                () -> new ModelCallControl(control).call(calls::incrementAndGet));
        assertEquals(ExecutionControl.StopReason.CANCELLED, stopped.reason());
        assertEquals(0, calls.get());
    }

    @Test void lossOfDispatchAuthorityCannotBecomeAModelRetry() {
        ExecutionControl control = control();
        ExecutionTurnContext.open(SESSION, "execution-a", 1, List.of(), () -> {
            throw new IllegalStateException("private authority failure");
        });
        AtomicInteger calls = new AtomicInteger();
        var stopped = assertThrows(ModelCallControl.StoppedException.class,
                () -> new ModelCallControl(control).call(calls::incrementAndGet));
        assertFalse(stopped.getMessage().contains("private"));
        assertEquals(0, calls.get());
        assertTrue(ExecutionTurnContext.current().isRejected());
        assertThrows(ModelCallControl.StoppedException.class,
                () -> new ModelCallControl(control).checkpointForCallback(SESSION));
    }

    @Test void transientAuthorityFailureCannotReviveAParentOrItsChildScope() {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        var parent = ExecutionTurnContext.open(SESSION, "execution-a", 1, List.of(), () -> {
            if (unavailable.get()) throw new IllegalStateException("authority unavailable");
            return true;
        });
        var child = parent.forSubagent("child");
        assertThrows(IllegalStateException.class, parent::isValid);
        unavailable.set(false);
        assertFalse(parent.isValid());
        assertFalse(child.isValid());
    }

    @Test void aRevokedBlockedProviderStopsBeforeItReturns() throws Exception {
        ExecutionControl control = control();
        AtomicBoolean authority = new AtomicBoolean(true);
        ExecutionTurnContext.open(SESSION, "execution-a", 1, List.of(), authority::get);
        RunScope captured = RunScope.capture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> result = callers.submit(() -> {
                try (var ignored = captured.bind()) {
                    return new ModelCallControl(control).call(() -> {
                        entered.countDown();
                        while (release.getCount() != 0) {
                            try { release.await(); }
                            catch (InterruptedException ignoredInterrupt) { /* Simulate an SDK with no abort API. */ }
                        }
                        return "late old-plan answer";
                    });
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                authority.set(false);
                var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
                assertInstanceOf(ModelCallControl.StoppedException.class, failure.getCause());
                assertEquals(1, release.getCount());
            } finally { release.countDown(); }
        }
    }

    @Test void childAndGrandchildKeepParentRevocationWithoutSharingActionAssignments() {
        control();
        AtomicBoolean authority = new AtomicBoolean(true);
        var parent = ExecutionTurnContext.open(SESSION, "execution-a", 3,
                List.of(new ExecutionTurnContext.ActionBinding("parent-action", "node", "write_file", "parent-key")),
                authority::get);
        RunScope child = RunScope.capture().asSubagent("child", "worker", null, null);
        RunScope grandchild;
        try (var ignored = child.bind()) {
            grandchild = RunScope.capture().asSubagent("grandchild", "worker", null, null);
            assertEquals("execution-a", ExecutionTurnContext.current().executionId());
            assertEquals("3", ExecutionTurnContext.current().planVersion());
            assertEquals("child-call", ExecutionTurnContext.current().resolve("child-call", "write_file").actionId());
        }
        assertEquals("parent-action", parent.resolve("parent-call", "write_file").actionId());
        authority.set(false);
        assertFalse(child.execution().isValid());
        assertFalse(grandchild.execution().isValid());
        authority.set(true);
        assertFalse(parent.isValid(), "revoked proposals cannot become live again");
    }

    private static ExecutionControl control() {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        AgentLoop.setCurrentSession(SESSION);
        control.start(SESSION);
        return control;
    }
}
