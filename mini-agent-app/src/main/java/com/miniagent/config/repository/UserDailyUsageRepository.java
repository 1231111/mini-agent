package com.miniagent.config.repository;

import com.miniagent.config.entity.UserDailyUsage;
import com.miniagent.config.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

public interface UserDailyUsageRepository extends JpaRepository<UserDailyUsage, Long> {
    long countByUserIdAndUsageDate(Long userId, LocalDate usageDate);
    /** Lock the owning user before first-row detection to serialize daily inserts. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> lockUserForUsage(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserDailyUsage u "
            + "where u.userId = :userId and u.usageDate = :usageDate")
    Optional<UserDailyUsage> findForUpdate(@Param("userId") Long userId,
                                          @Param("usageDate") LocalDate usageDate);

    @Transactional
    default void increment(Long userId, LocalDate usageDate, long tokens) {
        if (userId == null || usageDate == null) {
            throw new IllegalArgumentException("userId and usageDate are required");
        }
        if (tokens <= 0) return;

        lockUserForUsage(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
        UserDailyUsage row = findForUpdate(userId, usageDate).orElse(null);
        if (row == null) {
            row = new UserDailyUsage();
            row.setUserId(userId);
            row.setUsageDate(usageDate);
            row.setTokenCount(tokens);
            saveAndFlush(row);
            return;
        }
        row.setTokenCount(saturatingAdd(row.getTokenCount(), tokens));
    }

    @Query("select coalesce(u.tokenCount, 0) from UserDailyUsage u "
            + "where u.userId = :userId and u.usageDate = :usageDate")
    Optional<Long> tokenCount(@Param("userId") Long userId,
                              @Param("usageDate") LocalDate usageDate);

    private static long saturatingAdd(long current, long delta) {
        return current > Long.MAX_VALUE - delta ? Long.MAX_VALUE : current + delta;
    }
}
