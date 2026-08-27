package com.miniagent.config.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** Redis-backed atomic fixed-window limiter shared by all application replicas. */
@Component
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
public class RedisRateLimiter {

    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return count", Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long increment(String key, long ttlSeconds) {
        Long count = redis.execute(INCREMENT, List.of(key), String.valueOf(Math.max(1, ttlSeconds)));
        return count == null ? Long.MAX_VALUE : count;
    }
}
