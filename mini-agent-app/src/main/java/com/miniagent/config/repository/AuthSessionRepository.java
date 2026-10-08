package com.miniagent.config.repository;

import com.miniagent.config.entity.AuthSession;
import com.miniagent.config.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {
    Optional<AuthSession> findByTokenHashAndRevokedFalseAndExpiresAtAfter(
            String tokenHash, LocalDateTime now);

    List<AuthSession> findByUserIdAndRevokedFalseOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("update AuthSession s set s.revoked = true where s.userId = :userId and s.revoked = false")
    int revokeAllForUser(@Param("userId") Long userId);

    /**
     * 吊销某租户下所有未吊销会话。
     *
     * 原实现是 nativeQuery 的 MySQL 多表更新：
     *   UPDATE auth_sessions s INNER JOIN users u ON u.id = s.user_id SET s.revoked = TRUE ...
     * H2（含 MODE=MySQL）不支持 UPDATE ... INNER JOIN 语法，实测报
     *   Syntax error ... expected "SET"
     * 改成 JPQL 子查询后两边方言通用，语义不变：只有 user_id 能在 users 里
     * 命中的会话才会被吊销（与原 INNER JOIN 的过滤效果一致）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuthSession s set s.revoked = true "
            + "where s.revoked = false and s.userId in "
            + "(select u.id from User u where u.tenantId = :tenantId)")
    int revokeAllForTenant(@Param("tenantId") Long tenantId);

    /**
     * 清理死会话行：已过期 或 已吊销。
     *
     * clearAutomatically / flushAutomatically 是批量删除必须的：JPQL 的 delete 绕过
     * 持久化上下文，上下文里如果还留着这些实体的旧状态，后续 save 会把它们又写回去。
     * 调用方见 DbSessionStore.create()。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AuthSession s where s.expiresAt < :cutoff or s.revoked = true")
    int deleteExpiredOrRevoked(@Param("cutoff") LocalDateTime cutoff);
}
