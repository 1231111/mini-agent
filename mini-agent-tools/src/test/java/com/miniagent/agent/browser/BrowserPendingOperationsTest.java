package com.miniagent.agent.browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.context.OwnerContext;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BrowserPendingOperationsTest {
    private final BrowserService service = new BrowserService();
    private boolean closed;

    @AfterEach
    void close() {
        if (!closed) service.shutdown();
        WorkspaceContext.clearAll();
    }

    @Test
    void detachedChildBrowserActorKeepsItsTrustedRootGroupPending() throws Exception {
        String rootKey;
        String otherTenantRootKey;
        try (var ignored = OwnerContext.bind(new OwnerContext(7L, "tenant-1"))) {
            WorkspaceContext.setSessionOverride("root-session");
            rootKey = BrowserSessionIdentity.resolve(null);
        }
        try (var ignored = OwnerContext.bind(new OwnerContext(7L, "tenant-2"))) {
            otherTenantRootKey = BrowserSessionIdentity.resolve(null);
        }
        WorkspaceContext.clearAll();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<String> childKey = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try (var ignored = OwnerContext.bind(new OwnerContext(7L, "tenant-1"))) {
                WorkspaceContext.setSessionOverride("child-session");
                WorkspaceContext.setOperationRootSession("root-session");
                childKey.set(BrowserSessionIdentity.resolve(null));
                result.set(dispatch(childKey.get(), () -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return "finished";
                }));
            } finally {
                // Simulates SubagentScope closing while its browser actor is still running.
                WorkspaceContext.clearAll();
            }
        });
        try {
            caller.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertNotEquals(rootKey, childKey.get(), "browser resources remain isolated");
            caller.interrupt();
            caller.join(5_000);
            assertFalse(caller.isAlive());
            assertTrue(result.get().contains("OUTCOME_UNKNOWN"), result.get());
            assertTrue(service.hasPendingOperations(rootKey));
            assertTrue(service.hasPendingOperations(childKey.get()));
            assertFalse(service.hasPendingOperations(otherTenantRootKey));
        } finally {
            release.countDown();
            caller.join(5_000);
        }
        drain();
        assertFalse(service.hasPendingOperations(rootKey));
        assertFalse(service.hasPendingOperations(childKey.get()));
    }

    @Test
    void interruptedCallerDoesNotHideTheStillRunningActorOperation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(dispatch("trusted-chat", () -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "finished";
        })));
        try {
            assertFalse(service.hasPendingOperations("trusted-chat"));
            caller.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(service.hasPendingOperations("trusted-chat"));
            assertFalse(service.hasPendingOperations("another-chat"));
            caller.interrupt();
            caller.join(5_000);
            assertFalse(caller.isAlive());
            assertTrue(result.get().contains("OUTCOME_UNKNOWN"), result.get());
            assertTrue(service.hasPendingOperations("trusted-chat"),
                    "ending the tool caller must not allow review while its actor still runs");
        } finally {
            release.countDown();
            caller.join(5_000);
        }
        drain();
        assertFalse(service.hasPendingOperations("trusted-chat"));
    }

    @Test
    void cancelledQueuedWorkRemainsPendingUntilTheActorAcknowledgesIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread busy = new Thread(() -> dispatch("busy-chat", () -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "finished";
        }));
        AtomicReference<String> result = new AtomicReference<>();
        Thread queued = new Thread(() -> result.set(dispatch("queued-chat", () -> {
            fail("cancelled queued work must never execute");
            return "unreachable";
        })));
        try {
            busy.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queued.start();
            ThreadPoolExecutor actor = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "actor");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (actor.getQueue().isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
            assertFalse(actor.getQueue().isEmpty());
            queued.interrupt();
            queued.join(5_000);
            assertTrue(result.get().contains("CANCELLED"), result.get());
            assertTrue(service.hasPendingOperations("queued-chat"));
        } finally {
            release.countDown();
            busy.join(5_000);
            queued.join(5_000);
        }
        drain();
        assertFalse(service.hasPendingOperations("busy-chat"));
        assertFalse(service.hasPendingOperations("queued-chat"));
    }

    @Test
    void workRejectedBeforeEnqueueDoesNotLeaveAPendingSession() throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            result.set(dispatch("cancelled-chat", () -> {
                fail("already interrupted work must not execute");
                return "unreachable";
            }));
        });
        caller.start();
        caller.join(5_000);
        assertTrue(result.get().contains("CANCELLED"), result.get());
        assertFalse(service.hasPendingOperations("cancelled-chat"));

        service.shutdown();
        closed = true;
        assertThrows(RejectedExecutionException.class, () -> service.close("closed-chat"));
        assertFalse(service.hasPendingOperations("closed-chat"));
        assertFalse(service.hasPendingOperations(null));
    }

    @Test
    void failureAndNestedSameSessionWorkReleaseTheirOwnCounts() {
        assertThrows(IllegalStateException.class, () -> dispatch("trusted-chat", () -> {
            assertTrue(service.hasPendingOperations("trusted-chat"));
            assertEquals("nested", dispatch("trusted-chat", () -> "nested"));
            assertTrue(service.hasPendingOperations("trusted-chat"));
            throw new IllegalStateException("test failure");
        }));
        assertFalse(service.hasPendingOperations("trusted-chat"));
    }

    private String dispatch(String session, Callable<String> operation) {
        return ReflectionTestUtils.invokeMethod(service, "onSessionActor", session, operation);
    }

    private void drain() {
        assertEquals("drained", ReflectionTestUtils.invokeMethod(service, "onActor", (Callable<String>) () -> "drained"));
    }
}
