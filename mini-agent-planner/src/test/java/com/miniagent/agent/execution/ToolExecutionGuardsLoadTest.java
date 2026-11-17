package com.miniagent.agent.execution;

import com.miniagent.agent.tool.ToolConcurrencyScope;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolExecutionProfile;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolSideEffect;
import com.miniagent.agent.tool.ToolTimeoutRecovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Component load checks against the guard used by the AgentLoop tool pipeline. */
@Timeout(30)
class ToolExecutionGuardsLoadTest {
    @Test
    void sixtyFourCallersRespectTheGlobalCapAndReturnEveryPermit() throws Exception {
        int concurrency = 4, callers = 64;
        ToolExecutionGuards guards = guards(concurrency, 15);
        Semaphore global = global(guards);
        CountDownLatch full = new CountDownLatch(concurrency), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), completed = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, callers).mapToObj(index -> executor.submit(() ->
                    guards.executeGuarded("independent", "{}", "load-" + index, () -> {
                        int running = active.incrementAndGet();
                        peak.accumulateAndGet(running, Math::max);
                        full.countDown();
                        try {
                            assertTrue(release.await(12, TimeUnit.SECONDS));
                            completed.incrementAndGet();
                            return index;
                        } finally {
                            active.decrementAndGet();
                        }
                    }))).toList();
            try {
                assertTrue(full.await(3, TimeUnit.SECONDS));
                await().atMost(Duration.ofSeconds(3))
                        .until(() -> global.getQueueLength() == callers - concurrency);
                assertEquals(concurrency, active.get());
                assertEquals(concurrency, peak.get());
            } finally {
                release.countDown();
            }
            for (var future : futures) future.get(3, TimeUnit.SECONDS);
        }
        assertEquals(callers, completed.get());
        assertEquals(concurrency, peak.get());
        assertEquals(0, active.get());
        assertEquals(concurrency, global.availablePermits());
        assertFalse(global.hasQueuedThreads());
        IntStream.range(0, callers).forEach(index -> assertFalse(guards.hasActiveHandlers("load-" + index)));
    }

    @Test
    void cancellingFortyEightResourceWaitersNeverStartsTheirHandlers() throws Exception {
        int waiting = 48;
        ToolExecutionGuards guards = guards(4, 15);
        Semaphore resource = resource(guards), global = global(guards);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(waiting);
        AtomicInteger cancelledHandlers = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> guards.executeGuarded("browser", "{}", "holder", () -> {
                entered.countDown();
                assertTrue(release.await(12, TimeUnit.SECONDS));
                return "holder";
            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var futures = IntStream.range(0, waiting).mapToObj(index -> executor.submit(() -> {
                    try {
                        return guards.executeGuarded("browser", "{}", "cancelled-" + index,
                                cancelledHandlers::incrementAndGet);
                    } finally {
                        finished.countDown();
                    }
                })).toList();
                await().atMost(Duration.ofSeconds(3)).until(() -> resource.getQueueLength() == waiting);
                futures.forEach(future -> future.cancel(true));
                assertTrue(finished.await(3, TimeUnit.SECONDS));
                assertEquals(0, cancelledHandlers.get());
                assertFalse(resource.hasQueuedThreads());
                assertEquals(0, resource.availablePermits(), "The holder still owns the resource");
                assertEquals(3, global.availablePermits(), "Resource waiters must not occupy global slots");
                assertEquals("free", guards.executeGuarded("independent", "{}", "unrelated", () -> "free"));
                IntStream.range(0, waiting)
                        .forEach(index -> assertFalse(guards.hasActiveHandlers("cancelled-" + index)));
            } finally {
                release.countDown();
            }
            assertEquals("holder", holder.get(3, TimeUnit.SECONDS));
        }
        assertEquals(4, global.availablePermits());
        assertEquals(1, resource.availablePermits());
        assertFalse(guards.hasActiveHandlers("holder"));
        assertFalse(guards.hasActiveHandlers("unrelated"));
    }

    @Test
    void twentyFourOverloadedCallersTimeOutWithoutRunningOrLeakingCapacity() throws Exception {
        int concurrency = 3, waiting = 24;
        ToolExecutionGuards guards = guards(concurrency, 1);
        Semaphore global = global(guards);
        CountDownLatch full = new CountDownLatch(concurrency), release = new CountDownLatch(1);
        AtomicInteger overloadedHandlers = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holders = IntStream.range(0, concurrency).mapToObj(index -> executor.submit(() ->
                    guards.executeGuarded("independent", "{}", "holder-" + index, () -> {
                        full.countDown();
                        assertTrue(release.await(12, TimeUnit.SECONDS));
                        return "holder";
                    }))).toList();
            try {
                assertTrue(full.await(3, TimeUnit.SECONDS));
                var overloaded = IntStream.range(0, waiting).mapToObj(index -> executor.submit(() ->
                        assertThrows(ToolLockTimeoutException.class,
                                () -> guards.executeGuarded("independent", "{}", "overloaded-" + index,
                                        overloadedHandlers::incrementAndGet)))).toList();
                for (var future : overloaded) {
                    assertTrue(future.get(5, TimeUnit.SECONDS).getMessage().contains("全局并发额度"));
                }
                assertEquals(0, overloadedHandlers.get());
                assertEquals(0, global.availablePermits(), "Timed-out callers cannot release holder permits");
                assertFalse(global.hasQueuedThreads());
                IntStream.range(0, waiting)
                        .forEach(index -> assertFalse(guards.hasActiveHandlers("overloaded-" + index)));
            } finally {
                release.countDown();
            }
            for (var holder : holders) assertEquals("holder", holder.get(3, TimeUnit.SECONDS));
        }
        assertEquals(concurrency, global.availablePermits());
        assertEquals("recovered", guards.executeGuarded("independent", "{}", "retry", () -> "recovered"));
        assertEquals(concurrency, global.availablePermits());
        assertFalse(guards.hasActiveHandlers("retry"));
        IntStream.range(0, concurrency)
                .forEach(index -> assertFalse(guards.hasActiveHandlers("holder-" + index)));
    }

    private static ToolExecutionGuards guards(int concurrency, long waitSeconds) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDescriptor(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            boolean browser = name.equals("browser");
            ToolExecutionProfile profile = new ToolExecutionProfile(20, 5, waitSeconds,
                    browser ? ToolConcurrencyScope.SHARED_RESOURCE : ToolConcurrencyScope.NONE,
                    "", browser ? "browser" : "", ToolTimeoutRecovery.ABORT, "", 0);
            return Optional.of(new ToolDescriptor(name, "", Map.of(), ToolSideEffect.READ_ONLY,
                    true, false, true, profile));
        });
        return new ToolExecutionGuards(registry, new AgentToolsProperties(concurrency));
    }

    private static Semaphore global(ToolExecutionGuards guards) throws Exception {
        Field field = ToolExecutionGuards.class.getDeclaredField("globalConcurrency");
        field.setAccessible(true);
        return (Semaphore) field.get(guards);
    }

    private static Semaphore resource(ToolExecutionGuards guards) throws Exception {
        Field field = ToolExecutionGuards.class.getDeclaredField("resourceLocks");
        field.setAccessible(true);
        Semaphore[] stripes = (Semaphore[]) field.get(guards);
        return stripes[Math.floorMod("browser".hashCode(), stripes.length)];
    }
}
