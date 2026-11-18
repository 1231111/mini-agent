package com.miniagent.config.repository;

import com.miniagent.config.entity.MembershipUsageOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MembershipUsageOutboxRepository extends JpaRepository<MembershipUsageOutbox, Long> {
    Optional<MembershipUsageOutbox> findByEventId(String eventId);

    List<MembershipUsageOutbox> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            MembershipUsageOutbox.Status status, LocalDateTime now);

    Optional<MembershipUsageOutbox> findFirstByStatusOrderByCreatedAtAsc(MembershipUsageOutbox.Status status);

    @Modifying
    @Query("update MembershipUsageOutbox e set e.status=:processing,e.leaseUntil=:leaseUntil,"
            + "e.claimToken=:claimToken where e.id=:id and e.status=:pending and e.nextAttemptAt<=:now")
    int claim(@Param("id") Long id,
              @Param("pending") MembershipUsageOutbox.Status pending,
              @Param("processing") MembershipUsageOutbox.Status processing,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("claimToken") String claimToken);

    @Modifying
    @Query("update MembershipUsageOutbox e set e.status=:done,e.leaseUntil=null,e.claimToken=null,"
            + "e.lastError=null where e.id=:id and e.status=:processing and e.claimToken=:claimToken")
    int complete(@Param("id") Long id,
                 @Param("processing") MembershipUsageOutbox.Status processing,
                 @Param("done") MembershipUsageOutbox.Status done,
                 @Param("claimToken") String claimToken);

    @Modifying
    @Query("update MembershipUsageOutbox e set e.status=:status,e.attempts=:attempts,"
            + "e.nextAttemptAt=:nextAttemptAt,e.leaseUntil=null,e.claimToken=null,e.lastError=:lastError "
            + "where e.id=:id and e.status=:processing and e.claimToken=:claimToken")
    int failAttempt(@Param("id") Long id,
                    @Param("processing") MembershipUsageOutbox.Status processing,
                    @Param("status") MembershipUsageOutbox.Status status,
                    @Param("attempts") int attempts,
                    @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                    @Param("lastError") String lastError,
                    @Param("claimToken") String claimToken);

    @Modifying
    @Query("update MembershipUsageOutbox e set e.status=:pending,e.leaseUntil=null,e.claimToken=null,"
            + "e.nextAttemptAt=:now where e.status=:processing and e.leaseUntil<:now")
    int recoverExpired(@Param("processing") MembershipUsageOutbox.Status processing,
                       @Param("pending") MembershipUsageOutbox.Status pending,
                       @Param("now") LocalDateTime now);
}
