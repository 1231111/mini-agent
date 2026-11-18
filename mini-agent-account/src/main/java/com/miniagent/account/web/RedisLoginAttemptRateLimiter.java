package com.miniagent.account.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Redis-backed, cross-replica login throttle. Both counters are admitted atomically by one Lua script. */
@Component
public final class RedisLoginAttemptRateLimiter implements LoginAttemptRateLimiter {
    static final String SCRIPT_SOURCE = """
            local ipCount = tonumber(redis.call('GET', KEYS[1]) or '0')
            local accountCount = tonumber(redis.call('GET', KEYS[2]) or '0')
            local ipLimit = tonumber(ARGV[1])
            local accountLimit = tonumber(ARGV[2])
            if ipCount >= ipLimit then
              return math.max(1, redis.call('TTL', KEYS[1]))
            end
            if accountCount >= accountLimit then
              return math.max(1, redis.call('TTL', KEYS[2]))
            end
            local ipNext = redis.call('INCR', KEYS[1])
            if ipNext == 1 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            local accountNext = redis.call('INCR', KEYS[2])
            if accountNext == 1 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
            return 0
            """;

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(SCRIPT_SOURCE, Long.class);

    private final StringRedisTemplate redis;
    private final int accountLimit;
    private final int ipLimit;
    private final int windowSeconds;

    public RedisLoginAttemptRateLimiter(StringRedisTemplate redis,
            @Value("${agent.account.login-rate-limit.attempts-per-minute:10}") int accountLimit,
            @Value("${agent.account.login-rate-limit.attempts-per-ip-per-minute:30}") int ipLimit,
            @Value("${agent.account.login-rate-limit.window-seconds:60}") int windowSeconds) {
        this.redis = redis;
        this.accountLimit = Math.max(1, Math.min(100, accountLimit));
        this.ipLimit = Math.max(1, Math.min(1000, ipLimit));
        this.windowSeconds = Math.max(1, Math.min(3600, windowSeconds));
    }

    @Override
    public long acquire(String normalizedAccount, String sourceIp) {
        String accountKey = "mini-agent:login-rate:{auth}:account:" + digest(normalizedAccount);
        String ipKey = "mini-agent:login-rate:{auth}:ip:" + digest(sourceIp);
        Long retryAfter = redis.execute(ACQUIRE, List.of(ipKey, accountKey),
                Integer.toString(ipLimit), Integer.toString(accountLimit), Integer.toString(windowSeconds));
        if (retryAfter == null) throw new IllegalStateException("LOGIN_RATE_LIMIT_BACKEND_UNAVAILABLE");
        return Math.max(0, retryAfter);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("LOGIN_RATE_LIMIT_HASH_UNAVAILABLE", impossible);
        }
    }
}
