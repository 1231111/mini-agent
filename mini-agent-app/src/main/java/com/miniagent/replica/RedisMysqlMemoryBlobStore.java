package com.miniagent.replica;

import com.miniagent.config.service.DbMemoryBlobStore;
import com.miniagent.memory.MemoryBlobStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 用户记忆文本：先写 Redis，再写 MySQL；读 Redis 未命中则回源 MySQL。 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
public class RedisMysqlMemoryBlobStore implements MemoryBlobStore {

    private final StringRedisTemplate redis;
    private final DbMemoryBlobStore mysql;
    private final ReplicaProperties properties;

    public RedisMysqlMemoryBlobStore(StringRedisTemplate redis, DbMemoryBlobStore mysql, ReplicaProperties properties) {
        this.redis = redis;
        this.mysql = mysql;
        this.properties = properties;
    }

    private static String redisKey(long userId) {
        return "mem:" + userId;
    }

    @Override
    public Blob load(long userId) {
        try {
            Map<Object, Object> map = redis.opsForHash().entries(redisKey(userId));
            if (map != null && !map.isEmpty()) {
                return new Blob(asText(map.get("memory")), asText(map.get("user")), asText(map.get("midterm")));
            }
        } catch (Exception e) {
            log.warn("Redis memory 读失败，回源 MySQL: {}", e.getMessage());
        }
        Blob blob = mysql.load(userId);
        writeToRedis(userId, blob);
        return blob;
    }

    @Override
    public void saveMemory(long userId, String content) {
        Blob current = load(userId);
        Blob next = new Blob(content == null ? "" : content, current.userRaw(), current.midtermRaw());
        writeToRedis(userId, next);
        try {
            mysql.saveMemory(userId, content);
        } catch (Exception e) {
            deleteFromRedis(userId);
            throw e;
        }
    }

    @Override
    public void saveUser(long userId, String content) {
        Blob current = load(userId);
        Blob next = new Blob(current.memoryRaw(), content == null ? "" : content, current.midtermRaw());
        writeToRedis(userId, next);
        try {
            mysql.saveUser(userId, content);
        } catch (Exception e) {
            deleteFromRedis(userId);
            throw e;
        }
    }

    @Override
    public void saveMidterm(long userId, String content) {
        Blob current = load(userId);
        Blob next = new Blob(current.memoryRaw(), current.userRaw(), content == null ? "" : content);
        writeToRedis(userId, next);
        try {
            mysql.saveMidterm(userId, content);
        } catch (Exception e) {
            deleteFromRedis(userId);
            throw e;
        }
    }

    private void writeToRedis(long userId, Blob blob) {
        try {
            Map<String, String> map = new HashMap<>();
            map.put("memory", blob.memoryRaw());
            map.put("user", blob.userRaw());
            map.put("midterm", blob.midtermRaw());
            String key = redisKey(userId);
            redis.opsForHash().putAll(key, map);
            redis.expire(key, Duration.ofSeconds(properties.getMemoryTtlSeconds()));
        } catch (Exception e) {
            log.warn("Redis memory 写失败: {}", e.getMessage());
        }
    }

    private void deleteFromRedis(long userId) {
        try { redis.delete(redisKey(userId)); } catch (Exception ignored) {}
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
