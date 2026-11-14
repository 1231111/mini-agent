package com.miniagent.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.replica.ReplicaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Timeout(8)
class SessionEventCenterDeliveryTest {
    @Test void blockedClientCannotHoldStateOrDelayOtherClientsAndHistoryReplay() throws Exception {
        SessionEventCenter center = new SessionEventCenter();
        BlockingEmitter blocked = new BlockingEmitter();
        try {
            String run = center.startRun("s", "work");
            center.attachClient("s", 1L, blocked);
            RecordingEmitter healthy = new RecordingEmitter();
            center.attachClient("s", 1L, healthy);
            center.publish("s", "token", "saved answer", run);
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(300), () -> {
                center.publish("s", "task_outcome", Map.of("status", "SUCCESS"), run);
                center.complete("s", "final answer", run);
            });
            assertFalse(center.hasRunningSession("s"));
            await(() -> healthy.completions.get() == 1);
            assertEquals(List.of("session", "user", "token", "task_outcome", "end"), healthy.names);
            RecordingEmitter replay = new RecordingEmitter();
            assertTimeoutPreemptively(Duration.ofMillis(300), () -> center.attachClient("s", 1L, replay));
            await(() -> replay.completions.get() == 1);
            assertEquals(List.of("session", "user", "token", "task_outcome", "end"), replay.names);
            assertTrue(replay.frames.toString().contains("saved answer"));
            assertTrue(replay.frames.toString().contains("final answer"));
        } finally { blocked.release.countDown(); center.shutdown(); }
    }

    @Test void backlogOverflowDropsOnlyTheSlowConnectionAndKeepsReplayableAnswer() throws Exception {
        SessionEventCenter center = new SessionEventCenter();
        BlockingEmitter blocked = new BlockingEmitter();
        try {
            String run = center.startRun("s", "work");
            center.attachClient("s", 1L, blocked);
            center.publish("s", "token", "first", run);
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
                for (int i = 0; i < 200; i++) center.publish("s", "token", "x", run);
                center.complete("s", "all done", run);
            });
            await(() -> blocked.completions.get() == 1);
            assertEquals(0, center.detachUser(1L), "overflowed connections stay detached and cannot be resurrected");
            RecordingEmitter replay = new RecordingEmitter();
            center.attachClient("s", 1L, replay);
            await(() -> replay.completions.get() == 1);
            assertTrue(replay.frames.toString().contains("first" + "x".repeat(200)));
            assertTrue(replay.frames.toString().contains("all done"));
        } finally { blocked.release.countDown(); center.shutdown(); }
    }

    @Test void stalledSendIsDetachedAtItsOwnDeadlineWhileTheRunRemainsUsable() throws Exception {
        SessionEventCenter center = new SessionEventCenter(100);
        BlockingEmitter blocked = new BlockingEmitter();
        try {
            String run = center.startRun("s", "work");
            center.attachClient("s", 1L, blocked);
            center.publish("s", "token", "retained", run);
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            await(() -> blocked.completions.get() == 1);
            assertTrue(center.hasRunningSession("s"));
            center.publish("s", "token", " later", run);
            center.complete("s", "finished", run);
            RecordingEmitter replay = new RecordingEmitter();
            center.attachClient("s", 1L, replay);
            await(() -> replay.completions.get() == 1);
            assertTrue(replay.frames.toString().contains("retained later"));
        } finally { blocked.release.countDown(); center.shutdown(); }
    }

    @Test void logoutDiscardsQueuedTerminalReplayAndCannotResurrectItsConnection() throws Exception {
        SessionEventCenter center = new SessionEventCenter();
        BlockingEmitter blocked = new BlockingEmitter();
        try {
            String run = center.startRun("s", "work");
            center.publish("s", "token", "saved answer", run);
            center.publish("s", "task_outcome", Map.of("status", "SUCCESS"), run);
            center.complete("s", "final answer", run);
            center.attachClient("s", 1L, blocked);
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(300), () -> assertEquals(1, center.detachUser(1L)));
            await(() -> blocked.completions.get() == 1);
            blocked.release.countDown();
            await(() -> blocked.names.contains("token")); // Already-started I/O is not reversible.
            Thread.sleep(50);
            assertFalse(blocked.names.contains("task_outcome"));
            assertFalse(blocked.names.contains("end"));
            assertEquals(0, center.detachUser(1L));
        } finally { blocked.release.countDown(); center.shutdown(); }
    }

    @Test @SuppressWarnings("unchecked")
    void blockedRedisNotificationDoesNotBlockTerminalStateAndRetainsCrossRunOrder() throws Exception {
        SessionEventCenter center = new SessionEventCenter();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReplicaProperties properties = mock(ReplicaProperties.class);
        when(properties.isRedisMode()).thenReturn(true);
        ReflectionTestUtils.setField(center, "redis", redis);
        ReflectionTestUtils.setField(center, "replicaProperties", properties);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> notifications = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                entered.countDown(); awaitIgnoringInterrupts(release);
            }
            notifications.add(new ObjectMapper().readTree(invocation.getArgument(1, String.class)).path("type").asText());
            return 1L;
        }).when(redis).convertAndSend(anyString(), anyString());
        try {
            String first = center.startRun("s", "first");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(300), () -> {
                center.publish("s", "token", "answer", first);
                center.complete("s", "done", first);
            });
            assertFalse(center.hasRunningSession("s"));
            String second = center.startRun("s", "second");
            center.error("s", "stopped", second);
            release.countDown();
            await(() -> notifications.size() == 5);
            assertEquals(List.of("start", "event", "complete", "start", "error"), notifications);
            // Control-plane close remains synchronous; only convertAndSend notifications are dispatched.
            verify(redis, times(2)).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(), any(Object[].class));
        } finally { release.countDown(); center.shutdown(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILURE", "CANCELED", "WAITING"})
    @SuppressWarnings("unchecked")
    void redisQueueOverflowPreservesFrozenOutcomeBeforeTheTerminalFrame(String status) throws Exception {
        SessionEventCenter center = new SessionEventCenter();
        SessionEventCenter remote = new SessionEventCenter();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReplicaProperties properties = mock(ReplicaProperties.class);
        when(properties.isRedisMode()).thenReturn(true);
        ReflectionTestUtils.setField(center, "redis", redis);
        ReflectionTestUtils.setField(center, "replicaProperties", properties);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<com.fasterxml.jackson.databind.JsonNode> notifications = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                entered.countDown(); awaitIgnoringInterrupts(release);
            }
            String body = invocation.getArgument(1, String.class);
            var message = mock(org.springframework.data.redis.connection.Message.class);
            when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
            ReflectionTestUtils.invokeMethod(remote, "onRedisMessage", message, null);
            notifications.add(new ObjectMapper().readTree(body));
            return 1L;
        }).when(redis).convertAndSend(anyString(), anyString());
        try {
            String run = center.startRun("s", "work");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 127; i++) center.publish("s", "token", "chunk", run);
            Map<String, Object> outcome = new java.util.LinkedHashMap<>(Map.of("status", status));
            center.publish("s", "task_outcome", outcome, run); // Pending queue is now exactly 128 frames.
            center.complete("s", "final result", run); // Compaction must retain task_outcome before end.
            outcome.put("status", "SUCCESS"); // Recovery must use its frozen enqueue-time snapshot.
            release.countDown();
            await(() -> notifications.size() == 4);
            assertEquals(List.of("start", "start", "event", "complete"),
                    notifications.stream().map(frame -> frame.path("type").asText()).toList());
            assertEquals("task_outcome", notifications.get(2).path("name").asText());
            assertEquals(status, notifications.get(2).path("data").path("status").asText());
            RecordingEmitter replay = new RecordingEmitter();
            remote.attachClient("s", 1L, replay);
            await(() -> replay.completions.get() == 1);
            assertEquals(List.of("session", "user", "task_outcome", "end"), replay.names);
            assertTrue(replay.frames.toString().contains(status));
        } finally { release.countDown(); center.shutdown(); remote.shutdown(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"permission_ask", "user_question"})
    @SuppressWarnings("unchecked")
    void overflowingInteractionIsNotDuplicatedByItsRecoverySnapshot(String interaction) throws Exception {
        SessionEventCenter center = new SessionEventCenter();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        ReplicaProperties properties = mock(ReplicaProperties.class);
        when(properties.isRedisMode()).thenReturn(true);
        ReflectionTestUtils.setField(center, "redis", redis);
        ReflectionTestUtils.setField(center, "replicaProperties", properties);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<com.fasterxml.jackson.databind.JsonNode> notifications = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) { entered.countDown(); awaitIgnoringInterrupts(release); }
            notifications.add(new ObjectMapper().readTree(invocation.getArgument(1, String.class)));
            return 1L;
        }).when(redis).convertAndSend(anyString(), anyString());
        try {
            String run = center.startRun("s", "work");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 128; i++) center.publish("s", "token", "chunk", run);
            center.publish("s", interaction, Map.of("prompt", "approve"), run);
            release.countDown();
            await(() -> notifications.size() == 3);
            assertEquals(List.of("start", "start", "event"),
                    notifications.stream().map(frame -> frame.path("type").asText()).toList());
            assertEquals(1, notifications.stream().filter(frame -> interaction.equals(frame.path("name").asText())).count());
        } finally { release.countDown(); center.shutdown(); }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "event delivery did not reach its expected state");
    }

    private static void awaitIgnoringInterrupts(CountDownLatch release) {
        while (release.getCount() > 0) {
            try { release.await(); } catch (InterruptedException ignored) { }
        }
    }

    private static class RecordingEmitter extends SseEmitter {
        final List<String> names = new CopyOnWriteArrayList<>();
        final List<String> frames = new CopyOnWriteArrayList<>();
        final AtomicInteger completions = new AtomicInteger();
        @Override public void send(SseEventBuilder builder) {
            StringBuilder frame = new StringBuilder();
            builder.build().forEach(part -> frame.append(part.getData()));
            var name = Pattern.compile("event:([^\\n]+)").matcher(frame);
            assertTrue(name.find());
            frames.add(frame.toString()); names.add(name.group(1));
        }
        @Override public void complete() { completions.incrementAndGet(); }
    }

    private static final class BlockingEmitter extends RecordingEmitter {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public void send(SseEventBuilder builder) {
            boolean token = builder.build().stream().anyMatch(part -> part.getData().toString().contains("event:token"));
            if (token) { entered.countDown(); awaitIgnoringInterrupts(release); }
            super.send(builder);
        }
    }
}
