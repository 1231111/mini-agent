package com.miniagent.account.repository;

import com.miniagent.account.entity.MembershipUsageEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MembershipUsageEventRepository
        extends JpaRepository<MembershipUsageEvent, Long> {

    List<MembershipUsageEvent> findByUserIdAndReportedAtGreaterThanEqualOrderByIdDesc(
            Long userId, LocalDateTime from, Pageable pageable);

    @Query("select new com.miniagent.account.repository.UsageSum("
            + "coalesce(sum(e.inputTokens), 0), "
            + "coalesce(sum(e.outputTokens), 0), "
            + "count(e)) "
            + "from MembershipUsageEvent e "
            + "where e.userId = :userId and e.reportedAt >= :from and e.reportedAt < :to")
    UsageSum sumBetween(@Param("userId") Long userId,
                        @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to);
}
