package com.miniagent.replica;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨实例：用户并发任务名额 + 会话运行锁（持有者 token，可续期）。
 */
@Component
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
public class RedisTaskConcurrency {

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ReplicaProperties properties;
    /** 本 JVM 持有的 session → lockToken */
    private final ConcurrentHashMap<String, String> heldSessionTokens = new ConcurrentHashMap<>();
    /** 本 JVM 持有的 session → 用户配额租约。 */
    private final ConcurrentHashMap<String, HeldLease> heldSessionLeases =
            new ConcurrentHashMap<>();

    private record HeldLease(String fencingToken, long userId) {
    }

    private static final DefaultRedisScript<Long> OCCUPY_USER_QUOTA = new DefaultRedisScript<>(
            """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_USER_QUOTA = new DefaultRedisScript<>(
            """
            redis.call('ZREM', KEYS[1], ARGV[1])
            if redis.call('ZCARD', KEYS[1]) == 0 then
              redis.call('DEL', KEYS[1])
            end
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

    private static final DefaultRedisScript<Long> RENEW_LEASES_IF_OWNER =
            new DefaultRedisScript<>(
                    """
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                      redis.call('EXPIRE', KEYS[1], ARGV[2])
                      redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
                      redis.call('EXPIRE', KEYS[2], ARGV[2])
                      return 1
                    end
                    return 0
                    """, Long.class);

    public boolean tryOccupyUserQuota(
            long userId, String sessionId, String fencingToken, int maxPerUser) {
        if (sessionId == null || sessionId.isBlank()
                || fencingToken == null || fencingToken.isBlank()
                || !fencingToken.equals(heldSessionTokens.get(sessionId))) {
            return false;
        }
        long ttlSeconds = properties.getRunLockTtlSeconds();
        long now = System.currentTimeMillis();
        Long ok = redis.execute(OCCUPY_USER_QUOTA,
                List.of(ReplicaLockKeys.userRunningKey(userId)),
                String.valueOf(now),
                String.valueOf(now + Duration.ofSeconds(ttlSeconds).toMillis()),
                String.valueOf(maxPerUser),
                fencingToken,
                String.valueOf(ttlSeconds));
        if (ok != null && ok == 1L) {
            heldSessionLeases.put(sessionId, new HeldLease(fencingToken, userId));
            return true;
        }
        return false;
    }

    public void releaseUserQuota(
            long userId, String sessionId, String fencingToken) {
        if (sessionId == null || sessionId.isBlank()
                || fencingToken == null || fencingToken.isBlank()) {
            return;
        }
        heldSessionLeases.remove(sessionId, new HeldLease(fencingToken, userId));
        redis.execute(RELEASE_USER_QUOTA,
                List.of(ReplicaLockKeys.userRunningKey(userId)),
                fencingToken);
    }

    public boolean tryLockSession(String sessionId, String fencingToken) {
        if (sessionId == null || sessionId.isBlank()
                || fencingToken == null || fencingToken.isBlank()) {
            return false;
        }
        Boolean ok = redis.opsForValue().setIfAbsent(
                ReplicaLockKeys.sessionRunKey(sessionId),
                fencingToken,
                Duration.ofSeconds(properties.getRunLockTtlSeconds()));
        if (Boolean.TRUE.equals(ok)) {
            heldSessionTokens.put(sessionId, fencingToken);
            return true;
        }
        return false;
    }

    /** 仅当本 JVM 仍持有该锁时续期；丢失则 false（应中止 Planner）。 */
    public boolean renewSessionLock(String sessionId, String fencingToken) {
        if (sessionId == null || fencingToken == null || fencingToken.isBlank()) {
            return false;
        }
        String token = heldSessionTokens.get(sessionId);
        if (!fencingToken.equals(token)) {
            return false;
        }
        long ttlSeconds = properties.getRunLockTtlSeconds();
        HeldLease lease = heldSessionLeases.get(sessionId);
        if (lease != null && token.equals(lease.fencingToken())) {
            long expiresAt = System.currentTimeMillis()
                    + Duration.ofSeconds(ttlSeconds).toMillis();
            Long ok = redis.execute(RENEW_LEASES_IF_OWNER,
                    List.of(
                            ReplicaLockKeys.sessionRunKey(sessionId),
                            ReplicaLockKeys.userRunningKey(lease.userId())),
                    token,
                    String.valueOf(ttlSeconds),
                    String.valueOf(expiresAt),
                    fencingToken);
            return ok != null && ok == 1L;
        }
        Long ok = redis.execute(RENEW_IF_OWNER,
                List.of(ReplicaLockKeys.sessionRunKey(sessionId)),
                token,
                String.valueOf(ttlSeconds));
        return ok != null && ok == 1L;
    }

    public void unlockSession(String sessionId, String fencingToken) {
        if (sessionId == null || fencingToken == null || fencingToken.isBlank()) {
            return;
        }
        heldSessionTokens.remove(sessionId, fencingToken);
        redis.execute(UNLOCK_IF_OWNER,
                List.of(ReplicaLockKeys.sessionRunKey(sessionId)), fencingToken);
    }

    public boolean isSessionLocked(String sessionId) {
        return sessionId != null
                && !sessionId.isBlank()
                && Boolean.TRUE.equals(redis.hasKey(
                        ReplicaLockKeys.sessionRunKey(sessionId)));
    }

    /** 测试可见：本机是否登记了该 session 锁 */
    public boolean holdsSessionLocally(String sessionId) {
        return heldSessionTokens.containsKey(sessionId);
    }

    Map<String, String> heldTokensView() {
        return Map.copyOf(heldSessionTokens);
    }
}
