package com.miniagent.config.repository;

import com.miniagent.config.entity.TenantDailyUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface TenantDailyUsageRepository extends JpaRepository<TenantDailyUsage, Long> {
    Optional<TenantDailyUsage> findByTenantIdAndUsageDate(Long tenantId, LocalDate usageDate);

    @Modifying
    @Query(value = "INSERT INTO tenant_daily_usage "
            + "(tenant_id,usage_date,token_count,created_at,updated_at) "
            + "VALUES (:tenantId,:usageDate,:tokens,NOW(6),NOW(6)) "
            + "ON DUPLICATE KEY UPDATE token_count=token_count+:tokens,updated_at=NOW(6)",
            nativeQuery = true)
    void increment(@Param("tenantId") Long tenantId,
                   @Param("usageDate") LocalDate usageDate,
                   @Param("tokens") long tokens);

    @Query("select coalesce(u.tokenCount, 0) from TenantDailyUsage u "
            + "where u.tenantId = :tenantId and u.usageDate = :usageDate")
    Optional<Long> tokenCount(@Param("tenantId") Long tenantId,
                              @Param("usageDate") LocalDate usageDate);
}
