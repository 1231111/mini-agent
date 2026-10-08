package com.miniagent.replica;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
