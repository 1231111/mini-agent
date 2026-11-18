package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;
import com.miniagent.replica.ReplicaProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class SessionEventCenterDisconnectTest {
    @Test void lastClientDisconnectCancelsAfterGraceButReconnectResetsGrace() {
        SessionEventCenter center = new SessionEventCenter(1_000, 80, 20_000);
        AtomicInteger cancellations = new AtomicInteger();
        center.setOrphanedRunHandler((session, token) -> {
            assertEquals("session", session);
            assertNotNull(token);
            cancellations.incrementAndGet();
        });
        try {
            String run = center.startRun("session", "work");
            CountingEmitter first = new CountingEmitter();
            assertTrue(center.attachClient("session", 1L, first));
            detach(center, "session", first);

            CountingEmitter reconnected = new CountingEmitter();
            assertTrue(center.attachClient("session", 1L, reconnected));
            sleep(120);
            assertEquals(0, cancellations.get(), "a reconnect inside the grace window keeps the run alive");

            detach(center, "session", reconnected);
            await(() -> cancellations.get() == 1);
            assertTrue(center.hasRunningSession("session"));

            // A delayed callback must be tied to this exact run token.
            assertNotEquals(run, center.startRun("session", "next work"));
        } finally {
            center.shutdown();
        }
    }

    @Test void terminalCompletionDoesNotTriggerDisconnectCancellation() {
        SessionEventCenter center = new SessionEventCenter(1_000, 40, 20_000);
        AtomicInteger cancellations = new AtomicInteger();
        center.setOrphanedRunHandler((session, token) -> cancellations.incrementAndGet());
        try {
            String run = center.startRun("session", "work");
            CountingEmitter emitter = new CountingEmitter();
            center.attachClient("session", 1L, emitter);
            center.complete("session", "done", run);
            detach(center, "session", emitter);
            sleep(100);
            assertEquals(0, cancellations.get());
        } finally {
            center.shutdown();
        }
    }

    @Test void activeChatStreamsReceivePeriodicHeartbeatComments() {
        SessionEventCenter center = new SessionEventCenter(1_000, 5_000, 20);
        try {
            center.startRun("session", "work");
            CountingEmitter emitter = new CountingEmitter();
            center.attachClient("session", 1L, emitter);
            await(() -> emitter.sends.get() > 2);
            assertTrue(emitter.sends.get() > 2, "session/user replay plus a heartbeat should be sent");
        } finally {
            center.shutdown();
        }
    }

    @Test @SuppressWarnings("unchecked")
    void redisOwnerSweepObservesRemoteOnlyClientsAndDoesNotCancelBeforeFirstAttach() {
        SessionEventCenter center = new SessionEventCenter(1_000, 50, 5_000);
        AtomicBoolean remoteClientPresent = new AtomicBoolean();
        AtomicInteger presenceChecks = new AtomicInteger();
        AtomicInteger observedRemotePresence = new AtomicInteger();
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class, call -> {
            if ("execute".equals(call.getMethod().getName())) {
                if (call.getArguments().length > 2) return 1L; // Presence update.
                presenceChecks.incrementAndGet();
                if (remoteClientPresent.get()) observedRemotePresence.incrementAndGet();
                return remoteClientPresent.get() ? 1L : 0L;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);
        ReplicaProperties properties = org.mockito.Mockito.mock(ReplicaProperties.class);
        org.mockito.Mockito.when(properties.isRedisMode()).thenReturn(true);
        ReflectionTestUtils.setField(center, "redis", redis);
        ReflectionTestUtils.setField(center, "replicaProperties", properties);
        AtomicInteger cancellations = new AtomicInteger();
        center.setOrphanedRunHandler((session, token) -> cancellations.incrementAndGet());
        try {
            center.startRun("remote-session", "work");
            sleep(100);
            assertEquals(0, cancellations.get(), "a run with no observed client must not be canceled");

            remoteClientPresent.set(true);
            await(() -> observedRemotePresence.get() >= 1);
            remoteClientPresent.set(false); // The remote replica's final client disconnected.
            await(() -> cancellations.get() == 1);
            assertEquals(1, cancellations.get());
        } finally {
            center.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static void detach(SessionEventCenter center, String sessionId, SseEmitter emitter) {
        Map<String, SessionEventCenter.SessionChannel> channels =
                (Map<String, SessionEventCenter.SessionChannel>) ReflectionTestUtils.getField(center, "channels");
        ReflectionTestUtils.invokeMethod(center, "detachClient", channels.get(sessionId), emitter);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) sleep(5);
        assertTrue(condition.getAsBoolean(), "timed out waiting for SSE lifecycle callback");
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static final class CountingEmitter extends SseEmitter {
        final AtomicInteger sends = new AtomicInteger();
        @Override public void send(SseEventBuilder event) { sends.incrementAndGet(); }
    }
}
