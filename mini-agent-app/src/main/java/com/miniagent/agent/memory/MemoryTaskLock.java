package com.miniagent.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆后台任务的跨实例互斥锁。
 *
 * <p><b>为什么需要它</b>：巩固 Worker（{@code EventDrivenConsolidationWorker}）与索引
 * Outbox（{@code MemoryIndexOutboxService}）都基于 {@code @Scheduled}，
 * 多实例部署时每个实例都会跑，导致：
 * <ul>
 *   <li>同一批 {@code processed=false} 事件被两个实例重复巩固 → 重复 Episode</li>
 *   <li>同一批 PENDING outbox 记录被两个实例同时取走 → 重复写向量库</li>
 * </ul>
 *
 * <p><b>锁策略</b>：{@code agent.replica.mode=redis} 时必须使用 Redis {@code SET NX EX} + Lua 释放
 * （只有持有者能释放）；Redis 缺失或故障时拒绝运行后台任务，绝不改用进程内锁。
 * 只有显式单机模式（{@code agent.replica.mode=local}，桌面/开发）才使用进程内锁。
 * 这样不必依赖 {@code RedisTaskConcurrency}（它是 {@code @ConditionalOnProperty}
 * 条件启用的，单机模式下根本不存在）。
 */
@Component
public class MemoryTaskLock {

    private static final Logger log = LoggerFactory.getLogger(MemoryTaskLock.class);

    /** 只有持有者 token 匹配才删除，避免锁过期后误释放他人的锁。 */
    private static final DefaultRedisScript<Long> UNLOCK_IF_OWNER = new DefaultRedisScript<>(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final String KEY_PREFIX = "miniagent:memory:lock:";

    @Autowired(required = false)
    private StringRedisTemplate redis;

    @Value("${agent.replica.mode:local}")
    private String replicaMode = "local";

    /** name → 本 JVM 持有的 token。仅用于显式单机模式或记录当前 Redis 锁 token。 */
    private final ConcurrentHashMap<String, String> heldTokens = new ConcurrentHashMap<>();

    public boolean tryLock(String name, Duration ttl) {
        if (isRedisMode() && redis == null) {
            throw new IllegalStateException(
                    "MEMORY_DISTRIBUTED_LOCK_UNAVAILABLE: replica mode requires Redis lock storage");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        if (redis != null) {
            try {
                Boolean ok = redis.opsForValue().setIfAbsent(KEY_PREFIX + name, token, ttl);
                if (Boolean.TRUE.equals(ok)) {
                    heldTokens.put(name, token);
                    return true;
                }
                return false;
            } catch (Exception e) {
                // A configured Redis instance is the cross-replica lock authority.
                // Falling back to JVM-local locking here would let every replica
                // process the same outbox batch concurrently.
                log.warn("Redis 加锁失败，本次后台任务拒绝运行: name={}", name, e);
                throw new IllegalStateException("MEMORY_DISTRIBUTED_LOCK_UNAVAILABLE", e);
            }
        }
        return heldTokens.putIfAbsent(name, token) == null;
    }

    private boolean isRedisMode() {
        return "redis".equalsIgnoreCase(replicaMode);
    }

    public void unlock(String name) {
        String token = heldTokens.remove(name);
        if (token == null) {
            return;
        }
        if (redis != null) {
            try {
                redis.execute(UNLOCK_IF_OWNER, List.of(KEY_PREFIX + name), token);
                return;
            } catch (Exception e) {
                log.warn("Redis 解锁失败；等待锁 TTL 回收: name={}", name, e);
                throw new IllegalStateException("MEMORY_DISTRIBUTED_UNLOCK_UNAVAILABLE", e);
            }
        }
    }
}
