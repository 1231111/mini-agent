package com.miniagent.replica;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨实例：用户并发任务名额 + 会话运行锁（持有者 token，可续期）。
 */
@Component
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
public class RedisTaskConcurrency {

    private final StringRedisTemplate redis;
    private final ReplicaProperties properties;
    /** 本 JVM 持有的 session → lockToken */
    private final ConcurrentHashMap<String, String> heldSessionTokens = new ConcurrentHashMap<>();

    private static final DefaultRedisScript<Long> OCCUPY_USER_QUOTA = new DefaultRedisScript<>(
            """
            local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
            local max = tonumber(ARGV[1])
            if cur >= max then return 0 end
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> UNLOCK_IF_OWNER = new DefaultRedisScript<>(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_IF_OWNER = new DefaultRedisScript<>(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    public RedisTaskConcurrency(StringRedisTemplate redis, ReplicaProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public boolean tryOccupyUserQuota(long userId, int maxPerUser) {
        Long ok = redis.execute(OCCUPY_USER_QUOTA,
                List.of(ReplicaLockKeys.userRunningKey(userId)),
                String.valueOf(maxPerUser),
                String.valueOf(properties.getRunLockTtlSeconds()));
        return ok != null && ok == 1L;
    }

    public void releaseUserQuota(long userId) {
        String key = ReplicaLockKeys.userRunningKey(userId);
        Long value = redis.opsForValue().decrement(key);
        if (value != null && value <= 0)
            redis.delete(key);
    }

    public boolean tryLockSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        String token = UUID.randomUUID().toString().replace("-", "");
        Boolean ok = redis.opsForValue().setIfAbsent(
                ReplicaLockKeys.sessionRunKey(sessionId),
                token,
                Duration.ofSeconds(properties.getRunLockTtlSeconds()));
        if (Boolean.TRUE.equals(ok)) {
            heldSessionTokens.put(sessionId, token);
            return true;
        }
        return false;
    }

    /** 仅当本 JVM 仍持有该锁时续期；丢失则 false（应中止 Planner）。 */
    public boolean renewSessionLock(String sessionId) {
        if (sessionId == null) return false;
        String token = heldSessionTokens.get(sessionId);
        if (token == null) return false;
        Long ok = redis.execute(RENEW_IF_OWNER,
                List.of(ReplicaLockKeys.sessionRunKey(sessionId)),
                token,
                String.valueOf(properties.getRunLockTtlSeconds()));
        return ok != null && ok == 1L;
    }

    public void unlockSession(String sessionId) {
        if (sessionId == null) return;
        String token = heldSessionTokens.remove(sessionId);
        if (token == null) {
            redis.delete(ReplicaLockKeys.sessionRunKey(sessionId));
            return;
        }
        redis.execute(UNLOCK_IF_OWNER,
                List.of(ReplicaLockKeys.sessionRunKey(sessionId)), token);
    }

    /** 测试可见：本机是否登记了该 session 锁 */
    public boolean holdsSessionLocally(String sessionId) {
        return heldSessionTokens.containsKey(sessionId);
    }

    Map<String, String> heldTokensView() {
        return Map.copyOf(heldSessionTokens);
    }
}
