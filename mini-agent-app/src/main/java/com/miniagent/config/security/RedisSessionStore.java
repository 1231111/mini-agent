package com.miniagent.config.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 后端会话存储：键 {@code session:jwt:{sha256(token)}}，值是 userId。
 *
 * <p>滑动超时不在这里手写，直接靠 Redis 的键 TTL —— 每次校验成功调一次
 * {@code EXPIRE} 把 TTL 推回满额。键被删（登出）或 TTL 到期（空闲超时），
 * 下一次校验就会读不到值而返回 null。
 *
 * <p>只在 {@code agent.replica.mode=redis} 时装配 —— 与 {@code RedisReplicaConfig}
 * 等同口径，见 {@link SessionStore} 关于「不要用 bean 是否为 null 判断」的说明。
 *
 * <p>与本次改造前的差异：键的第二段从 {@code jti} 换成了 token 摘要。语义等价
 * （两者都是「这次签发」的唯一标识别），换的理由是两个后端共用一条键派生规则。
 * 影响面：升级前已经发出去的会话在 Redis 里找不到对应键，用户需要重新登录一次。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "redis")
public class RedisSessionStore implements SessionStore {

    private static final String KEY_PREFIX = "session:jwt:";

    private final StringRedisTemplate redis;

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
        log.info("会话存储后端: Redis（键 {}<token sha256>）", KEY_PREFIX);
    }

    @Override
    public void create(String token, Long userId, Duration ttl) {
        redis.opsForValue().set(key(token), String.valueOf(userId), ttl);
    }

    @Override
    public Long validateAndTouch(String token, Duration ttl) {
        String k = key(token);
        String value = redis.opsForValue().get(k);
        if (value == null) {
            // 两种可能：登出时键被删，或空闲超时后 TTL 到期。对调用方是同一件事。
            return null;
        }
        redis.expire(k, ttl);
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            // 键在但值不是 userId：异常数据。删掉，免得每个请求都走到这个分支。
            log.warn("会话键值非法，已删除: key={}, value={}", k, value);
            redis.delete(k);
            return null;
        }
    }

    @Override
    public void revoke(String token) {
        redis.delete(key(token));
    }

    @Override
    public String backendName() {
        return "redis";
    }

    private static String key(String token) {
        return KEY_PREFIX + SessionKeys.digest(token);
    }
}
