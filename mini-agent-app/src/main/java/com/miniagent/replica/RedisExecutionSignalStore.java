package com.miniagent.replica;

import com.miniagent.agent.core.ExecutionSignalStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.List;

@Component
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
public class RedisExecutionSignalStore implements ExecutionSignalStore {

    @Autowired
    private StringRedisTemplate redis;

    private static final DefaultRedisScript<Long> INITIALIZE_RUN = new DefaultRedisScript<>("""
            redis.call('HSETNX', KEYS[1], 'cancelled', '0')
            redis.call('HSETNX', KEYS[1], 'pauseRequested', '0')
            redis.call('HSETNX', KEYS[1], 'pausedAtCheckpoint', '0')
            redis.call('HSETNX', KEYS[1], 'humanWaitpoint', '0')
            redis.call('HSET', KEYS[1], 'deadline', ARGV[1], 'heartbeat', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    @Override
    public void startPreservingControls(String signalId, long deadlineEpochMillis, long ttlMillis) {
        Long initialized = redis.execute(INITIALIZE_RUN, List.of(key(signalId)),
                String.valueOf(deadlineEpochMillis), String.valueOf(System.currentTimeMillis()),
                String.valueOf(ttlMillis));
        if (!Long.valueOf(1).equals(initialized)) throw new IllegalStateException("Execution signal initialization failed");
    }

    @Override
    public void start(String sessionId, long deadlineEpochMillis, long ttlMillis) {
        String key = key(sessionId);
        redis.delete(key);
        redis.opsForHash().putAll(key, Map.of(
                "cancelled", "0",
                "pauseRequested", "0",
                "pausedAtCheckpoint", "0",
                "humanWaitpoint", "0",
                "deadline", String.valueOf(deadlineEpochMillis),
                "heartbeat", String.valueOf(System.currentTimeMillis())));
        redis.expire(key, Duration.ofMillis(ttlMillis));
    }

    @Override
    public void cancel(String sessionId, long ttlMillis) {
        String key = key(sessionId);
        redis.opsForHash().put(key, "cancelled", "1");
        redis.expire(key, Duration.ofMillis(ttlMillis));
    }

    @Override
    public boolean isCancelled(String sessionId) {
        Object value = redis.opsForHash().get(key(sessionId), "cancelled");
        return "1".equals(String.valueOf(value));
    }

    @Override
    public void pause(String sessionId, long ttlMillis) {
        updateFlag(sessionId, "pauseRequested", true, ttlMillis);
    }

    @Override
    public void resume(String sessionId, long ttlMillis) {
        String key = key(sessionId);
        redis.opsForHash().put(key, "pauseRequested", "0");
        redis.opsForHash().put(key, "pausedAtCheckpoint", "0");
        redis.expire(key, Duration.ofMillis(ttlMillis));
    }

    @Override
    public boolean isPaused(String sessionId) {
        Object value = redis.opsForHash().get(key(sessionId), "pauseRequested");
        return "1".equals(String.valueOf(value));
    }

    @Override
    public void markPausedAtCheckpoint(String sessionId, boolean paused, long ttlMillis) {
        updateFlag(sessionId, "pausedAtCheckpoint", paused, ttlMillis);
    }

    @Override
    public boolean isPausedAtCheckpoint(String sessionId) {
        Object value = redis.opsForHash().get(key(sessionId), "pausedAtCheckpoint");
        return "1".equals(String.valueOf(value));
    }

    @Override
    public void markHumanWaitpoint(String sessionId, boolean waiting, long ttlMillis) {
        updateFlag(sessionId, "humanWaitpoint", waiting, ttlMillis);
    }

    @Override
    public boolean isAtHumanWaitpoint(String sessionId) {
        Object value = redis.opsForHash().get(key(sessionId), "humanWaitpoint");
        return "1".equals(String.valueOf(value));
    }

    @Override
    public void heartbeat(String sessionId, long epochMillis, long ttlMillis) {
        String key = key(sessionId);
        redis.opsForHash().put(key, "heartbeat", String.valueOf(epochMillis));
        redis.expire(key, Duration.ofMillis(ttlMillis));
    }

    @Override
    public void finish(String sessionId) {
        redis.delete(key(sessionId));
    }

    private void updateFlag(String sessionId, String field, boolean value, long ttlMillis) {
        String key = key(sessionId);
        redis.opsForHash().put(key, field, value ? "1" : "0");
        redis.expire(key, Duration.ofMillis(ttlMillis));
    }

    private static String key(String sessionId) {
        return "execution:signal:" + sessionId;
    }
}
