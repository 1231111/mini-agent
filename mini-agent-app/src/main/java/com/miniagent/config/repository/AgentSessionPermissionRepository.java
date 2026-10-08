package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentSessionPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface AgentSessionPermissionRepository
        extends JpaRepository<AgentSessionPermission, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update AgentSessionPermission state
               set state.mode = :mode,
                   state.planApproved = :planApproved,
                   state.askGrantsJson = :askGrantsJson,
                   state.confirmPolicy = :confirmPolicy,
                   state.execPolicyOverride = :execPolicyOverride,
                   state.version = state.version + 1,
                   state.updatedAt = :updatedAt
             where state.sessionId = :sessionId
               and state.version = :expectedVersion
            """)
    int compareAndSet(
            @Param("sessionId") String sessionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("mode") String mode,
            @Param("planApproved") boolean planApproved,
            @Param("askGrantsJson") String askGrantsJson,
            @Param("confirmPolicy") String confirmPolicy,
            @Param("execPolicyOverride") String execPolicyOverride,
            @Param("updatedAt") LocalDateTime updatedAt);
}
