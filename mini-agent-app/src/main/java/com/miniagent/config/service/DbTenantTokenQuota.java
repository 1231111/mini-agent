package com.miniagent.config.service;

import com.miniagent.agent.core.TenantTokenQuota;
import com.miniagent.config.entity.Tenant;
import com.miniagent.config.repository.TenantDailyUsageRepository;
import com.miniagent.config.repository.TenantRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class DbTenantTokenQuota implements TenantTokenQuota {
    private final TenantRepository tenants;
    private final TenantDailyUsageRepository usage;
    private final Counter denied;
    private final Counter consumed;
    private final ZoneId quotaZone;

    public DbTenantTokenQuota(TenantRepository tenants, TenantDailyUsageRepository usage,
                              MeterRegistry meters,
                              @Value("${agent.quota.zone-id:Asia/Shanghai}") String quotaZone) {
        this.tenants = tenants;
        this.usage = usage;
        this.denied = meters.counter("miniagent.quota.denied", "kind", "tenant_daily_tokens");
        this.consumed = meters.counter("miniagent.quota.tokens", "kind", "tenant_daily_tokens");
        this.quotaZone = ZoneId.of(quotaZone);
    }

    @Override
    @Transactional(readOnly = true)
    public Decision check(Long tenantId) {
        Tenant tenant = tenants.findById(tenantId).orElse(null);
        if (tenant == null || !tenant.isEnabled()) {
            return deny(0, 0);
        }
        long used = usage.tokenCount(tenantId, today()).orElse(0L);
        long limit = tenant.getDailyTokenLimit();
        return limit <= 0 ? Decision.unlimited(used) : decision(used < limit, used, limit);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision consume(Long tenantId, long tokens) {
        long delta = Math.max(0, tokens);
        // Serialize accounting for a tenant across replicas. Actual model usage is always
        // recorded; a response may cross the boundary once, then all later calls are stopped.
        Tenant tenant = tenants.findByIdForUpdate(tenantId).orElse(null);
        if (tenant == null || !tenant.isEnabled()) {
            return deny(0, 0);
        }
        LocalDate today = today();
        long used = usage.tokenCount(tenantId, today).orElse(0L);
        long limit = tenant.getDailyTokenLimit();
        if (limit > 0 && used >= limit) {
            return deny(used, limit);
        }
        if (delta > 0) {
            usage.increment(tenantId, today, delta);
            consumed.increment(delta);
            used += delta;
        }
        return limit <= 0 ? Decision.unlimited(used) : decision(used <= limit, used, limit);
    }

    private Decision decision(boolean allowed, long used, long limit) {
        if (!allowed) {
            denied.increment();
        }
        return new Decision(allowed, used, limit);
    }

    private Decision deny(long used, long limit) {
        return decision(false, used, limit);
    }

    private LocalDate today() {
        return LocalDate.now(quotaZone);
    }
}
