package com.miniagent.replica;

import com.miniagent.agent.todo.SessionTodoPersistence;
import com.miniagent.config.service.DbSessionTodoPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 会话待办：先写 Redis，再写 MySQL；读 Redis 未命中则回源 MySQL。 */
@Component
@Primary
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
public class RedisMysqlSessionTodoStore extends RedisAsideStore<String, SessionTodoPersistence.State>
        implements SessionTodoPersistence {

    private final DbSessionTodoPersistence mysql;

    public RedisMysqlSessionTodoStore(StringRedisTemplate redis,
                                      DbSessionTodoPersistence mysql,
                                      ReplicaProperties properties) {
        super(redis, properties);
        this.mysql = mysql;
    }

    @Override
    protected String redisKey(String sessionId) {
        return "todo:" + sessionId;
    }

    @Override
    protected State loadFromMysql(String sessionId) {
        return mysql.load(sessionId);
    }

    @Override
    protected void saveToMysql(String sessionId, State value) {
        mysql.save(sessionId, value.activeJson(), value.suspendedJson());
    }

    @Override
    protected Map<String, String> serialize(State value) {
        Map<String, String> map = new HashMap<>();
        map.put("active", value.activeJson() == null || value.activeJson().isBlank() ? "[]" : value.activeJson());
        map.put("suspended", value.suspendedJson() == null ? "" : value.suspendedJson());
        return map;
    }

    @Override
    protected State deserialize(Map<Object, Object> map) {
        Object active = map.get("active");
        Object suspended = map.get("suspended");
        return new State(
                active == null ? "[]" : String.valueOf(active),
                suspended == null || String.valueOf(suspended).isBlank() ? null : String.valueOf(suspended));
    }

    @Override
    protected Duration ttl() {
        return Duration.ofSeconds(properties.getTodoTtlSeconds());
    }

    @Override
    protected String storeName() {
        return "todo";
    }

    // === 接口方法委托 ===

    @Override
    public State load(String sessionId) {
        return loadWithFallback(sessionId);
    }

    @Override
    public void save(String sessionId, String activeJson, String suspendedJson) {
        saveWithWriteThrough(sessionId, new State(activeJson, suspendedJson));
    }

    @Override
    public void delete(String sessionId) {
        deleteFromRedis(sessionId);
        mysql.delete(sessionId);
    }
}
