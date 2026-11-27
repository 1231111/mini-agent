package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.FailureLearningOutboxEntity;
import com.miniagent.agent.memory.entity.FailureLearningOutboxEntity.Status;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface FailureLearningOutboxRepository extends JpaRepository<FailureLearningOutboxEntity, Long> {
    boolean existsByEpisodeIdAndSourceHash(Long episodeId, String sourceHash);
    List<FailureLearningOutboxEntity> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(Status status, LocalDateTime now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FailureLearningOutboxEntity o set o.status=:running, o.claimToken=:token, o.leaseUntil=:lease "
            + "where o.id=:id and o.status=:pending and o.nextAttemptAt<=:now")
    int claim(@Param("id") Long id, @Param("pending") Status pending, @Param("running") Status running,
              @Param("token") String token, @Param("lease") LocalDateTime lease, @Param("now") LocalDateTime now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FailureLearningOutboxEntity o set o.status=:next, o.claimToken=null, o.leaseUntil=null, "
            + "o.attempts=:attempts, o.nextAttemptAt=:due, o.lastError=:error "
            + "where o.id=:id and o.status=:running and o.claimToken=:token")
    int finish(@Param("id") Long id, @Param("running") Status running, @Param("token") String token,
               @Param("next") Status next, @Param("attempts") int attempts,
               @Param("due") LocalDateTime due, @Param("error") String error);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FailureLearningOutboxEntity o set o.status=:pending, o.claimToken=null, o.leaseUntil=null, "
            + "o.nextAttemptAt=:now where o.status=:running and o.leaseUntil<:now")
    int recoverExpired(@Param("running") Status running, @Param("pending") Status pending, @Param("now") LocalDateTime now);
}
