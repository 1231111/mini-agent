package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.ExperienceClusterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperienceClusterRepository extends JpaRepository<ExperienceClusterEntity, Long> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExperienceClusterEntity c set c.version = :next, c.label = :label, c.updatedAt = CURRENT_TIMESTAMP "
            + "where c.id = :id and c.tenantId = :tenant and c.ownerUserId = :owner and c.version = :expected")
    int updateIfVersion(@Param("id") Long id, @Param("tenant") String tenant,
                        @Param("owner") Long owner, @Param("expected") Integer expected,
                        @Param("next") Integer next, @Param("label") String label);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExperienceClusterEntity c set c.version = :next, c.status = :status, c.updatedAt = CURRENT_TIMESTAMP "
            + "where c.id = :id and c.tenantId = :tenant and c.ownerUserId = :owner and c.version = :expected")
    int transitionIfVersion(@Param("id") Long id, @Param("tenant") String tenant,
                            @Param("owner") Long owner, @Param("expected") Integer expected,
                            @Param("next") Integer next, @Param("status") String status);
}
