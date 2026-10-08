package com.miniagent.config.security;

import com.miniagent.config.entity.AuthSession;
import com.miniagent.config.repository.AuthSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 数据库后端会话存储，落在 {@code auth_sessions} 表。
 *
 * <p>用这张表不是新设计 —— 它本来就在 schema 里，字段是照着「服务端会话记录」造的：
 * {@code token_hash}（主键，只存 token 的 SHA-256）、{@code user_id}、
 * {@code expires_at}、{@code revoked}、{@code last_seen_at}。在本次改造之前，
 * 除了租户级吊销会读它，没有任何代码往里写过 —— 也就是说它一直是一张空表。
 * 现在把它变成 {@code agent.replica.mode=local} 下的会话真相源。
 *
 * <p>滑动续期的写放大控制：{@link #validateAndTouch} 在每个带鉴权的请求上都会跑，
 * 如果每次都 UPDATE，读路径就变成了读+写。这里只在「距上次活动已经走过半个 TTL」
 * 时才落库 —— 最坏情况下空闲判定会晚半个 TTL 生效，换来的是绝大多数请求只读不写。
 * 半个 TTL（默认 15 分钟）的松弛对「30 分钟不活动就登出」这个语义没有实际影响。
 *
 * <p>默认装配（{@code matchIfMissing = true}）：没有显式配置 {@code agent.replica.mode}
 * 时也走数据库，这样单机跑起来不需要 Redis。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.replica.mode", havingValue = "local", matchIfMissing = true)
public class DbSessionStore implements SessionStore {

    private final AuthSessionRepository sessions;

    public DbSessionStore(AuthSessionRepository sessions) {
        this.sessions = sessions;
        log.info("会话存储后端: 数据库（表 auth_sessions）");
    }

    @Override
    @Transactional
    public void create(String token, Long userId, Duration ttl) {
        LocalDateTime now = LocalDateTime.now();

        // 顺手清掉死行。auth_sessions 一行一个会话（主键是 token 摘要），
        // 登录次数多了会无限增长，而这张表在单机形态下没有别的清理入口。
        // deleteExpiredOrRevoked 原本就存在但没有任何调用方，这里让它真正生效。
        // 它删的是「已过期」或「已吊销」两种行 —— 后者删掉不影响登出效果：
        // 行没了，validateAndTouch 同样查不到，一样是失效。
        int purged = sessions.deleteExpiredOrRevoked(now);

        AuthSession s = new AuthSession();
        s.setTokenHash(SessionKeys.digest(token));
        s.setUserId(userId);
        s.setRevoked(false);
        s.setLastSeenAt(now);
        s.setExpiresAt(now.plus(ttl));
        sessions.save(s);

        if (purged > 0) {
            log.debug("清理过期/已吊销会话 {} 条", purged);
        }
    }

    @Override
    @Transactional
    public Long validateAndTouch(String token, Duration ttl) {
        LocalDateTime now = LocalDateTime.now();
        AuthSession s = sessions.findById(SessionKeys.digest(token)).orElse(null);
        if (s == null) {
            return null;
        }
        if (s.isRevoked()) {
            return null;
        }
        if (!s.getExpiresAt().isAfter(now)) {
            return null;
        }
        if (s.getLastSeenAt() == null
                || Duration.between(s.getLastSeenAt(), now).compareTo(ttl.dividedBy(2)) >= 0) {
            s.setExpiresAt(now.plus(ttl));
            s.setLastSeenAt(now);
            sessions.save(s);
        }
        return s.getUserId();
    }

    @Override
    @Transactional
    public void revoke(String token) {
        sessions.findById(SessionKeys.digest(token)).ifPresent(s -> {
            s.setRevoked(true);
            sessions.save(s);
        });
    }

    @Override
    public String backendName() {
        return "db(auth_sessions)";
    }
}
