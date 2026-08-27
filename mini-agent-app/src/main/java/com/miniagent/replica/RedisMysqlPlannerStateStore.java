package com.miniagent.replica;

import com.miniagent.agent.planner.DomainEvent;
import com.miniagent.agent.planner.PlannerStateJson;
import com.miniagent.agent.planner.PlannerStatePersistence;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.config.service.DbPlannerStatePersistence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Planner 跨副本：MySQL 负责 CAS 真相源；Redis 热缓存读路径。
 * CAS 绝不只写 Redis，避免多实例脑裂。
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
@ConditionalOnBean(DbPlannerStatePersistence.class)
public class RedisMysqlPlannerStateStore implements PlannerStatePersistence {

    private static final String KEY_PREFIX = "planner:session:";

    private final StringRedisTemplate redis;
    private final DbPlannerStatePersistence mysql;
    private final ReplicaProperties properties;

    public RedisMysqlPlannerStateStore(StringRedisTemplate redis,
                                       DbPlannerStatePersistence mysql,
                                       ReplicaProperties properties) {
        this.redis = redis;
        this.mysql = mysql;
        this.properties = properties;
    }

    private static String redisKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    @Override
    public Optional<Bundle> load(String sessionId) {
        try {
            Map<Object, Object> map = redis.opsForHash().entries(redisKey(sessionId));
            if (map != null && !map.isEmpty()) {
                Object state = map.get("state");
                Object events = map.get("events");
                if (state != null && !String.valueOf(state).isBlank()) {
                    StateSnapshot snap = PlannerStateJson.snapshotFromJson(String.valueOf(state));
                    List<DomainEvent> ev = PlannerStateJson.eventsFromJson(
                            events == null ? "[]" : String.valueOf(events));
                    return Optional.of(new Bundle(snap, ev));
                }
            }
        } catch (Exception e) {
            log.warn("Redis planner 读失败，回源 MySQL: {}", e.getMessage());
        }
        Optional<Bundle> fromDb = mysql.load(sessionId);
        fromDb.ifPresent(b -> writeToRedis(sessionId, b.snapshot(), b.events()));
        return fromDb;
    }

    @Override
    public void replace(String sessionId, StateSnapshot snapshot, List<DomainEvent> events) {
        mysql.replace(sessionId, snapshot, events);
        writeToRedis(sessionId, snapshot, events);
    }

    @Override
    public boolean compareAndSet(String sessionId, long expectedVersion,
                                 StateSnapshot next, List<DomainEvent> events) {
        boolean ok = mysql.compareAndSet(sessionId, expectedVersion, next, events);
        if (ok) {
            writeToRedis(sessionId, next, events);
        } else {
            // 冲突：丢掉可能过期的热缓存，迫使下次回源
            evict(sessionId);
        }
        return ok;
    }

    @Override
    public boolean updateEvents(String sessionId, long expectedVersion, List<DomainEvent> events) {
        boolean ok = mysql.updateEvents(sessionId, expectedVersion, events);
        if (ok) {
            Optional<Bundle> loaded = mysql.load(sessionId);
            loaded.ifPresent(b -> writeToRedis(sessionId, b.snapshot(), events));
        } else {
            evict(sessionId);
        }
        return ok;
    }

    @Override
    public void delete(String sessionId) {
        evict(sessionId);
        mysql.delete(sessionId);
    }

    private void evict(String sessionId) {
        try {
            redis.delete(redisKey(sessionId));
        } catch (Exception e) {
            log.warn("Redis planner 删失败: {}", e.getMessage());
        }
    }

    private void writeToRedis(String sessionId, StateSnapshot snapshot, List<DomainEvent> events) {
        try {
            Map<String, String> map = new HashMap<>();
            map.put("state", PlannerStateJson.snapshotToJson(snapshot));
            map.put("events", PlannerStateJson.eventsToJson(events));
            map.put("version", String.valueOf(snapshot.version()));
            String key = redisKey(sessionId);
            redis.opsForHash().putAll(key, map);
            redis.expire(key, Duration.ofSeconds(properties.getPlannerTtlSeconds()));
        } catch (Exception e) {
            log.warn("Redis planner 写失败（MySQL 已落盘）: {}", e.getMessage());
        }
    }
}
