package com.miniagent.config.repository;

import com.miniagent.config.entity.TenantDailyUsage;
import com.miniagent.config.entity.Tenant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

public interface TenantDailyUsageRepository extends JpaRepository<TenantDailyUsage, Long> {
    Optional<TenantDailyUsage> findByTenantIdAndUsageDate(Long tenantId, LocalDate usageDate);
    long countByTenantIdAndUsageDate(Long tenantId, LocalDate usageDate);

    /**
     * Lock the owning row before checking for the daily row. The parent exists
     * even on the first usage of a day, so this serializes concurrent first
     * inserts across replicas without relying on vendor-specific upsert SQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :tenantId")
    Optional<Tenant> lockTenantForUsage(@Param("tenantId") Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from TenantDailyUsage u "
            + "where u.tenantId = :tenantId and u.usageDate = :usageDate")
    Optional<TenantDailyUsage> findForUpdate(@Param("tenantId") Long tenantId,
                                            @Param("usageDate") LocalDate usageDate);

    @Transactional
    default void increment(Long tenantId, LocalDate usageDate, long tokens) {
        if (tenantId == null || usageDate == null) {
            throw new IllegalArgumentException("tenantId and usageDate are required");
        }
        if (tokens <= 0) return;

        lockTenantForUsage(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("tenant not found: " + tenantId));
        TenantDailyUsage row = findForUpdate(tenantId, usageDate).orElse(null);
        if (row == null) {
            row = new TenantDailyUsage();
            row.setTenantId(tenantId);
            row.setUsageDate(usageDate);
            row.setTokenCount(tokens);
            saveAndFlush(row);
            return;
        }
        row.setTokenCount(saturatingAdd(row.getTokenCount(), tokens));
    }

    @Query("select coalesce(u.tokenCount, 0) from TenantDailyUsage u "
            + "where u.tenantId = :tenantId and u.usageDate = :usageDate")
    Optional<Long> tokenCount(@Param("tenantId") Long tenantId,
                              @Param("usageDate") LocalDate usageDate);

    private static long saturatingAdd(long current, long delta) {
        return current > Long.MAX_VALUE - delta ? Long.MAX_VALUE : current + delta;
    }
}
