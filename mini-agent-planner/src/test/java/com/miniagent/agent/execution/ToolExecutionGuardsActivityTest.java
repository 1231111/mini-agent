package com.miniagent.agent.execution;

import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.WorkspaceContext;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolExecutionGuardsActivityTest {
    @Test void detachedChildHandlerRemainsActiveForItsCapturedRoot() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDescriptor("test_write")).thenReturn(Optional.empty());
        ToolExecutionGuards guards = new ToolExecutionGuards(registry, new AgentToolsProperties());
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            WorkspaceContext.setOperationRootSession("root-session");
            try {
                guards.executeGuarded("test_write", "{}", "child-session", () -> {
                    // The child loop can restore/detach its context before old work exits.
                    WorkspaceContext.setOperationRootSession(null);
                    started.countDown();
                    while (release.getCount() > 0) {
                        try { release.await(); }
                        catch (InterruptedException ignored) { interrupted.countDown(); }
                    }
                    return "done";
                });
            } catch (Exception failure) { throw new AssertionError(failure); }
            finally { WorkspaceContext.clearAll(); }
        });
        try {
            worker.start();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(guards.hasActiveHandlers("root-session"));
            assertTrue(guards.hasActiveHandlers("child-session"));
            assertFalse(guards.hasActiveHandlers("unrelated-session"));
            worker.interrupt();
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertTrue(guards.hasActiveHandlers("root-session"));
        } finally { release.countDown(); worker.join(3000); }
        assertFalse(worker.isAlive());
        assertFalse(guards.hasActiveHandlers("root-session"));
        assertFalse(guards.hasActiveHandlers("child-session"));
    }

    @Test void interruptedCallerDoesNotMakeAnUncooperativeHandlerAppearIdle() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDescriptor("test_write")).thenReturn(Optional.empty());
        ToolExecutionGuards guards = new ToolExecutionGuards(registry, new AgentToolsProperties());
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                guards.executeGuarded("test_write", "{}", "s", () -> {
                    started.countDown();
                    while (release.getCount() > 0) {
                        try { release.await(); }
                        catch (InterruptedException ignored) { interrupted.countDown(); }
                    }
                    return "done";
                });
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        try {
            assertFalse(guards.hasActiveHandlers("s")); worker.start();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(guards.hasActiveHandlers("s"));
            assertFalse(guards.hasActiveHandlers("another-session"));
            worker.interrupt(); assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertTrue(guards.hasActiveHandlers("s"));
        } finally { release.countDown(); worker.join(3000); }
        assertFalse(worker.isAlive()); assertFalse(guards.hasActiveHandlers("s"));
    }

    @Test void failedHandlerReleasesActivityCount() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDescriptor("test_write")).thenReturn(Optional.empty());
        ToolExecutionGuards guards = new ToolExecutionGuards(registry, new AgentToolsProperties());
        assertThrows(IllegalStateException.class, () -> guards.executeGuarded("test_write", "{}", "s", () -> {
            assertTrue(guards.hasActiveHandlers("s")); throw new IllegalStateException("failed");
        }));
        assertFalse(guards.hasActiveHandlers("s"));
    }
}
