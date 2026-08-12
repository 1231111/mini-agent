package com.miniagent.replica;

import com.miniagent.agent.todo.SessionTodoPersistence;
import com.miniagent.config.service.DbSessionTodoPersistence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 会话待办：先写 Redis，再写 MySQL；读 Redis 未命中则回源 MySQL。 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
public class RedisMysqlSessionTodoStore implements SessionTodoPersistence {

    private final StringRedisTemplate redis;
    private final DbSessionTodoPersistence mysql;
    private final ReplicaProperties properties;

    public RedisMysqlSessionTodoStore(StringRedisTemplate redis,
                                      DbSessionTodoPersistence mysql,
                                      ReplicaProperties properties) {
        this.redis = redis;
        this.mysql = mysql;
        this.properties = properties;
    }

    private static String redisKey(String sessionId) {
        return "todo:" + sessionId;
    }

    @Override
    public State load(String sessionId) {
        try {
            Map<Object, Object> map = redis.opsForHash().entries(redisKey(sessionId));
            if (map != null && !map.isEmpty()) {
                Object active = map.get("active");
                Object suspended = map.get("suspended");
                return new State(active == null ? "[]" : String.valueOf(active),
                        suspended == null || String.valueOf(suspended).isBlank() ? null : String.valueOf(suspended));
            }
        } catch (Exception e) {
            log.warn("Redis todo 读失败，回源 MySQL: {}", e.getMessage());
        }
        State state = mysql.load(sessionId);
        writeToRedis(sessionId, state.activeJson(), state.suspendedJson());
        return state;
    }

    @Override
    public void save(String sessionId, String activeJson, String suspendedJson) {
        writeToRedis(sessionId, activeJson, suspendedJson);
        try {
            mysql.save(sessionId, activeJson, suspendedJson);
        } catch (Exception e) {
            try { redis.delete(redisKey(sessionId)); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public void delete(String sessionId) {
        try { redis.delete(redisKey(sessionId)); } catch (Exception e) {
            log.warn("Redis todo 删失败: {}", e.getMessage());
        }
        mysql.delete(sessionId);
    }

    private void writeToRedis(String sessionId, String activeJson, String suspendedJson) {
        try {
            Map<String, String> map = new HashMap<>();
            map.put("active", activeJson == null || activeJson.isBlank() ? "[]" : activeJson);
            map.put("suspended", suspendedJson == null ? "" : suspendedJson);
            String key = redisKey(sessionId);
            redis.opsForHash().putAll(key, map);
            redis.expire(key, Duration.ofSeconds(properties.getTodoTtlSeconds()));
        } catch (Exception e) {
            log.warn("Redis todo 写失败（MySQL 仍写）: {}", e.getMessage());
        }
    }
}
