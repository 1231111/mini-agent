package com.miniagent.replica;

import com.miniagent.agent.planner.DomainEvent;
import com.miniagent.agent.planner.PlannerStatePersistence;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.config.service.DbPlannerStatePersistence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Redis 副本模式下，Planner 的状态和事件仍只读写 MySQL 真相源。
 *
 * <p>旧热缓存没有 execution generation / event revision。只比较 planner version
 * 不能正确处理同版本事件更新、强制重置及删除后重建，晚到的缓存回填也会令派发闸门读到旧图。
 * 因此在具备持久化缓存修订号之前，宁可增加数据库读取，也不让 Redis 参与执行决策。
 * 写入后尽力清理旧版本遗留的缓存键；清理失败不改变数据库结果。
 * 该策略不替代任务租约、工具进程隔离或业务侧幂等。</p>
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
@ConditionalOnBean(DbPlannerStatePersistence.class)
public class RedisMysqlPlannerStateStore implements PlannerStatePersistence {

    private static final String KEY_PREFIX = "planner:session:";

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DbPlannerStatePersistence mysql;
    private static String redisKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    @Override
    public Optional<Bundle> load(String sessionId) {
        // Keep snapshot and events from the same database read. A Redis hit is
        // never a fallback, including while MySQL is unavailable.
        return mysql.load(sessionId);
    }

    @Override
    public void replace(String sessionId, StateSnapshot snapshot, List<DomainEvent> events) {
        try {
            mysql.replace(sessionId, snapshot, events);
        } finally {
            evict(sessionId);
        }
    }

    @Override
    public boolean compareAndSet(String sessionId, long expectedVersion,
                                 StateSnapshot next, List<DomainEvent> events) {
        try {
            return mysql.compareAndSet(sessionId, expectedVersion, next, events);
        } finally {
            // Also invalidate after conflicts or uncertain database failures.
            evict(sessionId);
        }
    }

    @Override
    public boolean compareAndSet(String sessionId, long expectedVersion, String expectedExecutionId,
                                 StateSnapshot next, List<DomainEvent> events) {
        try { return mysql.compareAndSet(sessionId, expectedVersion, expectedExecutionId, next, events); }
        finally { evict(sessionId); }
    }

    @Override
    public boolean compareAndSetAppending(String sessionId, long expectedVersion, String expectedExecutionId,
                                          StateSnapshot next, List<DomainEvent> appended) {
        try { return mysql.compareAndSetAppending(sessionId, expectedVersion, expectedExecutionId, next, appended); }
        finally { evict(sessionId); }
    }

    @Override
    public boolean updateEvents(String sessionId, long expectedVersion, List<DomainEvent> events) {
        try {
            return mysql.updateEvents(sessionId, expectedVersion, events);
        } finally {
            evict(sessionId);
        }
    }

    @Override
    public boolean updateEvents(String sessionId, long expectedVersion, String expectedExecutionId,
                                List<DomainEvent> events) {
        try { return mysql.updateEvents(sessionId, expectedVersion, expectedExecutionId, events); }
        finally { evict(sessionId); }
    }

    @Override
    public boolean appendEvents(String sessionId, long expectedVersion, String expectedExecutionId,
                                List<DomainEvent> appended) {
        try { return mysql.appendEvents(sessionId, expectedVersion, expectedExecutionId, appended); }
        finally { evict(sessionId); }
    }

    @Override
    public void delete(String sessionId) {
        try {
            mysql.delete(sessionId);
        } finally {
            evict(sessionId);
        }
    }

    private void evict(String sessionId) {
        if (sessionId == null) return;
        try {
            redis.delete(redisKey(sessionId));
        } catch (Exception e) {
            log.warn("Redis planner 旧缓存清理失败（不参与状态读取）: {}", e.getClass().getSimpleName());
        }
    }
}
