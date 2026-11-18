package com.miniagent.agent.memory;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 锁定 {@link MemoryTaskLock} 的互斥语义。
 *
 * <p>背景：巩固 Worker 与索引 Outbox 都是 {@code @Scheduled}，多实例部署时每个实例都会跑，
 * 会把同一批待处理数据投两遍。锁是这两条链路的唯一并发闸门。
 *
 * <p>注意：本用例不注入 Redis，走的是进程内降级分支。
 * 降级分支的状态是<b>按实例</b>存的（{@code heldTokens} 是实例字段），
 * 单机部署下 Spring 只会有一个该类型的 Bean，所以「同一实例重入」就是真实的互斥场景；
 * 跨实例互斥由 Redis 分支负责，此处无法覆盖。
 */
class MemoryTaskLockTest {

    private static final Duration TTL = Duration.ofMinutes(1);

    @Test
    void 同一把锁不能被重复获取() {
        MemoryTaskLock lock = new MemoryTaskLock();

        assertTrue(lock.tryLock("consolidation-worker", TTL), "首次获取应当成功");
        assertFalse(lock.tryLock("consolidation-worker", TTL),
                "未释放前再次获取必须失败，否则两个实例会同时跑同一批数据");
    }

    @Test
    void 释放后可以重新获取() {
        MemoryTaskLock lock = new MemoryTaskLock();

        assertTrue(lock.tryLock("memory-index-outbox", TTL));
        lock.unlock("memory-index-outbox");
        assertTrue(lock.tryLock("memory-index-outbox", TTL),
                "释放后必须能重新获取，否则任务只会跑一轮");
    }

    @Test
    void 不同任务的锁互不阻塞() {
        MemoryTaskLock lock = new MemoryTaskLock();

        assertTrue(lock.tryLock("consolidation-worker", TTL));
        assertTrue(lock.tryLock("memory-index-outbox", TTL),
                "两条后台链路的锁必须彼此独立");
    }

    @Test
    void 释放未持有的锁是安全的() {
        MemoryTaskLock lock = new MemoryTaskLock();

        // 不允许抛异常：finally 块在异常路径上也会无条件调用 unlock
        lock.unlock("never-acquired");
        assertTrue(lock.tryLock("never-acquired", TTL));
    }

    @Test
    void configuredRedisFailureDoesNotFallBackToPerProcessLock() {
        MemoryTaskLock lock = new MemoryTaskLock();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        ReflectionTestUtils.setField(lock, "redis", redis);

        assertThrows(IllegalStateException.class, () -> lock.tryLock("worker", TTL));
        assertThrows(IllegalStateException.class, () -> lock.tryLock("worker", TTL));
    }

    @Test
    void redisReplicaModeWithoutRedisBeanFailsClosed() {
        MemoryTaskLock lock = new MemoryTaskLock();
        ReflectionTestUtils.setField(lock, "replicaMode", "redis");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> lock.tryLock("worker", TTL));

        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("requires Redis"));
    }
}
