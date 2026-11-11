package com.miniagent.replica;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
        private Object[] lastArgs;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(
                RedisScript<T> script, List<String> keys, Object... args) {
            lastArgs = args;
            return (T) Long.valueOf(1);
        }
    }
}
