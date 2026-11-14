package com.miniagent.replica;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTaskConcurrencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void delayedOldSessionLockReplyCannotOverwriteOrUnlockTheReplacement() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        redis.values = values;
        RedisTaskConcurrency concurrency = new RedisTaskConcurrency();
        ReflectionTestUtils.setField(concurrency, "redis", redis);
        ReflectionTestUtils.setField(concurrency, "properties", new ReplicaProperties());
        when(values.setIfAbsent(anyString(), eq("new-token"), any(Duration.class))).thenAnswer(call -> {
            redis.durableToken.set("new-token");
            return true;
        });
        when(values.setIfAbsent(anyString(), eq("old-token"), any(Duration.class))).thenAnswer(call -> {
            redis.durableToken.set("old-token");
            // The old SET succeeded, its TTL expired, and a newer request completed
            // before the old success response reached this JVM.
            redis.durableToken.set(null);
            assertTrue(concurrency.tryLockSession("s1", "new-token"));
            assertTrue(concurrency.tryOccupyUserQuota(7L, "s1", "new-token", 2));
            return true;
        });

        assertFalse(concurrency.tryLockSession("s1", "old-token"));

        assertEquals("new-token", concurrency.heldTokensView().get("s1"));
        assertEquals("new-token", redis.durableToken.get(), "stale cleanup must compare the durable token");
        assertEquals("old-token", redis.lastUnlockToken);
        assertTrue(concurrency.renewSessionLock("s1", "new-token"));
        assertEquals("new-token", redis.lastArgs[3], "the replacement must keep renewing its own quota");
    }

    @Test
    @SuppressWarnings("unchecked")
    void newerLockReplyCanReplaceAnEarlierReplyWhileBothRequestsWereInFlight() throws Exception {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        redis.values = values;
        RedisTaskConcurrency concurrency = new RedisTaskConcurrency();
        ReflectionTestUtils.setField(concurrency, "redis", redis);
        ReflectionTestUtils.setField(concurrency, "properties", new ReplicaProperties());
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch newEntered = new CountDownLatch(1);
        CountDownLatch oldReply = new CountDownLatch(1);
        CountDownLatch newReply = new CountDownLatch(1);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call -> {
            boolean old = "old-token".equals(call.getArgument(1));
            redis.durableToken.set(call.getArgument(1));
            (old ? oldEntered : newEntered).countDown();
            assertTrue((old ? oldReply : newReply).await(5, TimeUnit.SECONDS));
            return true;
        });

        try (var workers = Executors.newFixedThreadPool(2)) {
            var old = workers.submit(() -> concurrency.tryLockSession("s1", "old-token"));
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS));
            // Redis TTL has elapsed; the new SET succeeds while the old response is still pending.
            var replacement = workers.submit(() -> concurrency.tryLockSession("s1", "new-token"));
            assertTrue(newEntered.await(5, TimeUnit.SECONDS));
            oldReply.countDown();
            assertTrue(old.get(5, TimeUnit.SECONDS));
            newReply.countDown();
            assertTrue(replacement.get(5, TimeUnit.SECONDS), "the newer successful acquisition must remain usable");
            assertEquals("new-token", concurrency.heldTokensView().get("s1"));
            concurrency.unlockSession("s1", "old-token");
            assertEquals("new-token", redis.durableToken.get());
            assertTrue(concurrency.renewSessionLock("s1", "new-token"));
        } finally {
            oldReply.countDown();
            newReply.countDown();
        }
    }

    @Test
    void staleFencingTokenCannotRemoveCurrentLocalOwner() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisTaskConcurrency concurrency = new RedisTaskConcurrency();
        ReflectionTestUtils.setField(concurrency, "redis", redis);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> held =
                (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(
                        concurrency, "heldSessionTokens");
        held.put("s1", "current-token");

        assertFalse(concurrency.renewSessionLock("s1", "stale-token"));
        verifyNoInteractions(redis);
        concurrency.unlockSession("s1", "stale-token");

        assertTrue(concurrency.holdsSessionLocally("s1"));
    }

    @Test
    void renewingQuotaUpdatesTheFencingTokenMember() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisTaskConcurrency concurrency = new RedisTaskConcurrency();
        ReflectionTestUtils.setField(concurrency, "redis", redis);
        ReflectionTestUtils.setField(concurrency, "properties", new ReplicaProperties());
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> held =
                (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(
                        concurrency, "heldSessionTokens");
        held.put("s1", "current-token");

        assertTrue(concurrency.tryOccupyUserQuota(
                7L, "s1", "current-token", 2));
        assertTrue(concurrency.renewSessionLock("s1", "current-token"));

        assertEquals("current-token", redis.lastArgs[3]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void delayedOldQuotaReplyCannotReplaceNewTokensQuotaRenewal() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisTaskConcurrency concurrency = new RedisTaskConcurrency();
        ReflectionTestUtils.setField(concurrency, "redis", redis);
        ReflectionTestUtils.setField(concurrency, "properties", new ReplicaProperties());
        ConcurrentHashMap<String, String> held = (ConcurrentHashMap<String, String>)
                ReflectionTestUtils.getField(concurrency, "heldSessionTokens");
        held.put("s1", "old-token");
        redis.beforeReply = () -> {
            held.put("s1", "new-token");
            assertTrue(concurrency.tryOccupyUserQuota(7L, "s1", "new-token", 2));
        };

        assertFalse(concurrency.tryOccupyUserQuota(7L, "s1", "old-token", 2));

        assertTrue(concurrency.renewSessionLock("s1", "new-token"));
        assertEquals("new-token", redis.lastArgs[3], "renewal must retain the replacement's quota member");
        assertEquals(2, redis.lastKeys.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentTokenIsReadFromRedisWithoutReusingStaleLocalOwnership() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(ReplicaLockKeys.sessionRunKey("s1")))
                .thenReturn("remote-run", "next-run", null, "  ");
        RedisTaskConcurrency concurrency = new RedisTaskConcurrency();
        ReflectionTestUtils.setField(concurrency, "redis", redis);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> held =
                (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(concurrency, "heldSessionTokens");
        held.put("s1", "stale-local-run");

        assertEquals(Optional.of("remote-run"), concurrency.currentSessionToken("s1"));
        assertEquals(Optional.of("next-run"), concurrency.currentSessionToken("s1"));
        assertEquals(Optional.empty(), concurrency.currentSessionToken("s1"));
        assertEquals(Optional.empty(), concurrency.currentSessionToken("s1"));
        assertEquals("stale-local-run", held.get("s1"));
        verify(values, org.mockito.Mockito.times(4)).get(ReplicaLockKeys.sessionRunKey("s1"));
    }

    @Test
    void blankSessionsDoNotReadRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisTaskConcurrency concurrency = new RedisTaskConcurrency();
        ReflectionTestUtils.setField(concurrency, "redis", redis);

        assertEquals(Optional.empty(), concurrency.currentSessionToken(null));
        assertEquals(Optional.empty(), concurrency.currentSessionToken(""));
        assertEquals(Optional.empty(), concurrency.currentSessionToken("  "));

        verifyNoInteractions(redis);
    }

    @Test
    void runLockTtlUsesReplicaConfigurationAndNeverFallsBelowOneSecond() {
        RedisTaskConcurrency concurrency = new RedisTaskConcurrency();
        ReplicaProperties properties = new ReplicaProperties();
        properties.setRunLockTtlSeconds(41L);
        ReflectionTestUtils.setField(concurrency, "properties", properties);

        assertEquals(41_000L, concurrency.runLockTtlMillis());

        properties.setRunLockTtlSeconds(0L);
        assertEquals(1_000L, concurrency.runLockTtlMillis());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unreadableDurableOwnerIsNotConvertedToAMissingRun() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(ReplicaLockKeys.sessionRunKey("s1")))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        RedisTaskConcurrency concurrency = new RedisTaskConcurrency();
        ReflectionTestUtils.setField(concurrency, "redis", redis);

        assertThrows(IllegalStateException.class, () -> concurrency.currentSessionToken("s1"));
    }

    private static final class RecordingRedisTemplate extends StringRedisTemplate {
        private ValueOperations<String, String> values;
        private final AtomicReference<String> durableToken = new AtomicReference<>();
        private String lastUnlockToken;
        private Object[] lastArgs;
        private List<String> lastKeys;
        private Runnable beforeReply;

        @Override public ValueOperations<String, String> opsForValue() { return values; }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(
                RedisScript<T> script, List<String> keys, Object... args) {
            lastArgs = args;
            lastKeys = keys;
            if (script.getScriptAsString().contains("return redis.call('DEL', KEYS[1])")) {
                lastUnlockToken = String.valueOf(args[0]);
                return (T) Long.valueOf(durableToken.compareAndSet(lastUnlockToken, null) ? 1L : 0L);
            }
            Runnable callback = beforeReply;
            beforeReply = null;
            if (callback != null) callback.run();
            return (T) Long.valueOf(1);
        }
    }
}
