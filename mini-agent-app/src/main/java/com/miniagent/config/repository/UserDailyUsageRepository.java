package com.miniagent.config.repository;

import com.miniagent.config.entity.UserDailyUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface UserDailyUsageRepository extends JpaRepository<UserDailyUsage, Long> {
    @Modifying
    @Query(value = "INSERT INTO user_daily_usage "
            + "(user_id,usage_date,token_count,created_at,updated_at) "
            + "VALUES (:userId,:usageDate,:tokens,NOW(6),NOW(6)) "
            + "ON DUPLICATE KEY UPDATE token_count=token_count+:tokens,updated_at=NOW(6)",
            nativeQuery = true)
    void increment(@Param("userId") Long userId,
                   @Param("usageDate") LocalDate usageDate,
                   @Param("tokens") long tokens);

    @Query("select coalesce(u.tokenCount, 0) from UserDailyUsage u "
            + "where u.userId = :userId and u.usageDate = :usageDate")
    Optional<Long> tokenCount(@Param("userId") Long userId,
                              @Param("usageDate") LocalDate usageDate);
}
