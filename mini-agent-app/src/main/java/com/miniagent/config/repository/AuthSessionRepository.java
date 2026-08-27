package com.miniagent.config.repository;

import com.miniagent.config.entity.AuthSession;
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE auth_sessions s INNER JOIN users u ON u.id = s.user_id "
            + "SET s.revoked = TRUE WHERE u.tenant_id = :tenantId AND s.revoked = FALSE",
            nativeQuery = true)
    int revokeAllForTenant(@Param("tenantId") Long tenantId);

    @Modifying
    @Query("delete from AuthSession s where s.expiresAt < :cutoff or s.revoked = true")
    int deleteExpiredOrRevoked(@Param("cutoff") LocalDateTime cutoff);
}
