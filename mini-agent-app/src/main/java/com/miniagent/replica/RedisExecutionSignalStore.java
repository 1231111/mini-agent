package com.miniagent.replica;

import com.miniagent.agent.core.ExecutionSignalStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
public class RedisExecutionSignalStore implements ExecutionSignalStore {

    @Autowired
    private StringRedisTemplate redis;

    @Override
    public void start(String sessionId, long deadlineEpochMillis, long ttlMillis) {
        String key = key(sessionId);
        redis.delete(key);
        redis.opsForHash().putAll(key, Map.of(
                "cancelled", "0",
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
    public void heartbeat(String sessionId, long epochMillis, long ttlMillis) {
        String key = key(sessionId);
        redis.opsForHash().put(key, "heartbeat", String.valueOf(epochMillis));
        redis.expire(key, Duration.ofMillis(ttlMillis));
    }

    @Override
    public void finish(String sessionId) {
        redis.delete(key(sessionId));
    }

    private static String key(String sessionId) {
        return "execution:signal:" + sessionId;
    }
}
