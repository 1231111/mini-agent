package com.miniagent.replica;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis-aside 缓存模式抽象基类。
 * 消除 RedisMysqlMemoryBlobStore、RedisMysqlPlannerStateStore、RedisMysqlSessionTodoStore 中的重复代码。
 *
 * @param <K> key 类型（Long 或 String）
 * @param <V> value 类型
 */
@Slf4j
public abstract class RedisAsideStore<K, V> {

    protected final StringRedisTemplate redis;
    protected final ReplicaProperties properties;

    protected RedisAsideStore(StringRedisTemplate redis, ReplicaProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Redis key 前缀 + key → 完整 Redis key */
    protected abstract String redisKey(K key);

    /** 从 MySQL 加载 */
    protected abstract V loadFromMysql(K key);

    /** 保存到 MySQL */
    protected abstract void saveToMysql(K key, V value);

    /** 将 value 序列化为 Redis hash 字段 */
    protected abstract Map<String, String> serialize(V value);

    /** 从 Redis hash 反序列化；返回 null 表示缓存未命中 */
    protected abstract V deserialize(Map<Object, Object> map);

    /** 缓存 TTL */
    protected abstract Duration ttl();

    /** 存储名称（用于日志） */
    protected abstract String storeName();

    /**
     * 读穿透：先读 Redis，未命中则回源 MySQL 并写入 Redis。
     */
    protected V loadWithFallback(K key) {
        try {
            Map<Object, Object> map = redis.opsForHash().entries(redisKey(key));
            if (map != null && !map.isEmpty()) {
                V cached = deserialize(map);
                if (cached != null) return cached;
            }
        } catch (Exception e) {
            log.warn("Redis {} 读失败，回源 MySQL: {}", storeName(), e.getMessage());
        }
        V fromDb = loadFromMysql(key);
        writeToRedis(key, fromDb);
        return fromDb;
    }

    /**
     * 写穿透：先写 Redis，再写 MySQL；MySQL 失败则删除 Redis 缓存。
     */
    protected void saveWithWriteThrough(K key, V value) {
        writeToRedis(key, value);
        try {
            saveToMysql(key, value);
        } catch (Exception e) {
            deleteFromRedis(key);
            throw e;
        }
    }

    protected void writeToRedis(K key, V value) {
        try {
            Map<String, String> map = serialize(value);
            String rk = redisKey(key);
            redis.opsForHash().putAll(rk, map);
            redis.expire(rk, ttl());
        } catch (Exception e) {
            log.warn("Redis {} 写失败: {}", storeName(), e.getMessage());
        }
    }

    protected void deleteFromRedis(K key) {
        try {
            redis.delete(redisKey(key));
        } catch (Exception ignored) {}
    }

    protected static String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
