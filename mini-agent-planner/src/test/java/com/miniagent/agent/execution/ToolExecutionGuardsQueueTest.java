package com.miniagent.agent.execution;

import com.miniagent.agent.tool.ToolConcurrencyScope;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolExecutionProfile;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolSideEffect;
import com.miniagent.agent.tool.ToolTimeoutRecovery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolExecutionGuardsQueueTest {
    @Test void busyResourceWaitersDoNotConsumeCapacityForUnrelatedTools() throws Exception {
        ToolExecutionGuards guards = guards(2, 5);
        CountDownLatch holderStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> guards.executeGuarded("browser", "{}", "holder", () -> {
                holderStarted.countDown();
                release.await();
                return "holder";
            }));
            try {
                assertTrue(holderStarted.await(3, TimeUnit.SECONDS));
                var queued1 = executor.submit(() -> guards.executeGuarded("browser", "{}", "queued1", () -> "one"));
                var queued2 = executor.submit(() -> guards.executeGuarded("browser", "{}", "queued2", () -> "two"));
                try {
                    await().atMost(Duration.ofSeconds(3)).until(() -> resource(guards, "browser").hasQueuedThreads());
                    assertFalse(guards.hasActiveHandlers("queued1"));
                    var unrelated = executor.submit(() -> guards.executeGuarded("independent", "{}", "free", () -> "free"));
                    assertEquals("free", unrelated.get(1, TimeUnit.SECONDS),
                            "A resource waiter must not occupy the remaining global execution slot");
                } finally {
                    release.countDown();
                }
                assertEquals("holder", holder.get(3, TimeUnit.SECONDS));
                assertEquals("one", queued1.get(3, TimeUnit.SECONDS));
                assertEquals("two", queued2.get(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
        }
        assertEquals(2, global(guards).availablePermits());
        assertEquals(1, resource(guards, "browser").availablePermits());
    }

    @Test void resourceAndGlobalWaitShareOneDeadline() throws Exception {
        ToolExecutionGuards guards = guards(1, 2);
        Semaphore resource = resource(guards, "browser"), global = global(guards);
        resource.acquire();
        AtomicBoolean ran = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            boolean resourceHeld = true, globalHeld = false;
            long started = System.nanoTime();
            var queued = executor.submit(() -> assertThrows(ToolLockTimeoutException.class,
                    () -> guards.executeGuarded("browser", "{}", "queued", () -> ran.getAndSet(true))));
            try {
                await().atMost(Duration.ofSeconds(1)).until(resource::hasQueuedThreads);
                // Block the second gate only after the caller is waiting at the first.
                // With the old acquisition order it has already taken the global permit.
                assertTrue(global.tryAcquire(), "Waiting for a resource must leave global capacity available");
                globalHeld = true;
                TimeUnit.MILLISECONDS.sleep(1100);
                resource.release();
                resourceHeld = false;
                var failure = queued.get(1600, TimeUnit.MILLISECONDS);
                assertTrue(failure.getMessage().contains("全局并发额度"));
                assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(2700),
                        "The second gate must not reset the two-second queue budget");
                assertFalse(ran.get());
                assertFalse(guards.hasActiveHandlers("queued"));
            } finally {
                queued.cancel(true);
                if (globalHeld) global.release();
                if (resourceHeld) resource.release();
            }
        }
        assertEquals(1, global.availablePermits());
        assertEquals(1, resource.availablePermits());
    }

    @Test void interruptionWhileWaitingForResourceDoesNotReleaseUnownedPermits() throws Exception {
        ToolExecutionGuards guards = guards(1, 3);
        Semaphore resource = resource(guards, "browser");
        resource.acquire();
        AtomicBoolean ran = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var queued = executor.submit(() -> assertThrows(InterruptedException.class,
                    () -> guards.executeGuarded("browser", "{}", "queued", () -> ran.getAndSet(true))));
            await().atMost(Duration.ofSeconds(1)).until(resource::hasQueuedThreads);
            queued.cancel(true);
            await().atMost(Duration.ofSeconds(1)).until(() -> !resource.hasQueuedThreads());
        } finally {
            assertEquals(0, resource.availablePermits());
            resource.release();
        }
        assertEquals(1, global(guards).availablePermits());
        assertFalse(ran.get());
        assertFalse(guards.hasActiveHandlers("queued"));
    }

    @Test void interruptionAtGlobalGateReleasesAlreadyAcquiredResource() throws Exception {
        ToolExecutionGuards guards = guards(1, 3);
        Semaphore global = global(guards), resource = resource(guards, "browser");
        global.acquire();
        AtomicBoolean ran = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var queued = executor.submit(() -> assertThrows(InterruptedException.class,
                    () -> guards.executeGuarded("browser", "{}", "queued", () -> ran.getAndSet(true))));
            await().atMost(Duration.ofSeconds(1)).until(global::hasQueuedThreads);
            assertEquals(0, resource.availablePermits());
            queued.cancel(true);
            await().atMost(Duration.ofSeconds(1)).until(() -> resource.availablePermits() == 1);
            assertEquals(0, global.availablePermits());
        } finally {
            global.release();
        }
        assertEquals(1, global.availablePermits());
        assertFalse(ran.get());
    }

    @Test void globalCapAndResourceSerializationStillHold() throws Exception {
        ToolExecutionGuards guards = guards(1, 3);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> guards.executeGuarded("independent", "{}", "one", () -> {
                started.countDown();
                release.await();
                return "one";
            }));
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                var second = executor.submit(() -> guards.executeGuarded("browser", "{}", "two", () -> secondRan.getAndSet(true)));
                await().atMost(Duration.ofSeconds(1)).until(() -> global(guards).hasQueuedThreads());
                assertFalse(secondRan.get());
                release.countDown();
                first.get(3, TimeUnit.SECONDS);
                second.get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertTrue(secondRan.get());
        assertEquals(1, global(guards).availablePermits());
        assertEquals(1, resource(guards, "browser").availablePermits());
    }

    private static ToolExecutionGuards guards(int concurrency, long waitSeconds) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDescriptor(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            ToolExecutionProfile profile = new ToolExecutionProfile(20, 5, waitSeconds,
                    name.equals("browser") ? ToolConcurrencyScope.SHARED_RESOURCE : ToolConcurrencyScope.NONE,
                    "", name.equals("browser") ? "browser" : "", ToolTimeoutRecovery.ABORT, "", 0);
            return Optional.of(new ToolDescriptor(name, "", Map.of(), ToolSideEffect.READ_ONLY, true, false, true, profile));
        });
        return new ToolExecutionGuards(registry, new AgentToolsProperties(concurrency));
    }

    private static Semaphore global(ToolExecutionGuards guards) throws Exception {
        Field field = ToolExecutionGuards.class.getDeclaredField("globalConcurrency");
        field.setAccessible(true);
        return (Semaphore) field.get(guards);
    }

    private static Semaphore resource(ToolExecutionGuards guards, String key) throws Exception {
        Field field = ToolExecutionGuards.class.getDeclaredField("resourceLocks");
        field.setAccessible(true);
        Semaphore[] stripes = (Semaphore[]) field.get(guards);
        return stripes[Math.floorMod(key.hashCode(), stripes.length)];
    }
}
