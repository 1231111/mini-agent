package com.miniagent.config.service;

import com.miniagent.agent.core.TenantTokenQuota;
import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.TenantDailyUsageRepository;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserDailyUsageRepository;
import com.miniagent.config.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

@Service
public class DbTenantTokenQuota implements TenantTokenQuota {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private TenantDailyUsageRepository usage;
    @Autowired
    private UserDailyUsageRepository userUsage;
    @Autowired
    private UserRepository users;
    @Autowired
    private MeterRegistry meters;

    @Value("${agent.quota.zone-id:Asia/Shanghai}")
    private String quotaZoneId;

    private Counter tenantDenied;
    private Counter userDenied;
    private Counter tenantConsumed;
    private Counter userConsumed;
    private ZoneId quotaZone;

    @PostConstruct
    void init() {
        this.tenantDenied = meters.counter("miniagent.quota.denied", "kind", "tenant_daily_tokens");
        this.userDenied = meters.counter("miniagent.quota.denied", "kind", "user_daily_tokens");
        this.tenantConsumed = meters.counter("miniagent.quota.tokens", "kind", "tenant_daily_tokens");
        this.userConsumed = meters.counter("miniagent.quota.tokens", "kind", "user_daily_tokens");
        this.quotaZone = ZoneId.of(quotaZoneId);
    }

    @Override
    @Transactional(readOnly = true)
    public Decision check(Long tenantId, Long userId) {
        Tenant tenant = tenants.findById(tenantId).orElse(null);
        User user = userId == null ? null : users.findById(userId).orElse(null);
        if (!validOwner(tenant, user, tenantId)) {
            return deny(0, 0);
        }
        LocalDate today = today();
        long tenantUsed = usage.tokenCount(tenantId, today).orElse(0L);
        long userUsed = userUsage.tokenCount(userId, today).orElse(0L);
        // The agent does not impose its own token ceiling. Usage is still recorded
        // for observability; the configured model provider/API key owns enforcement.
        return Decision.unlimited(Math.max(tenantUsed, userUsed));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision consume(Long tenantId, Long userId, long tokens) {
        long delta = Math.max(0, tokens);
        // Every replica locks the same tenant row before reading or incrementing either
        // counter. This keeps the tenant-wide and per-user totals atomic as one decision.
        Tenant tenant = tenants.findByIdForUpdate(tenantId).orElse(null);
        User user = userId == null ? null : users.findByIdForUpdate(userId).orElse(null);
        if (!validOwner(tenant, user, tenantId)) {
            return deny(0, 0);
        }
        LocalDate today = today();
        long tenantUsed = usage.tokenCount(tenantId, today).orElse(0L);
        long userUsed = userUsage.tokenCount(userId, today).orElse(0L);
        if (delta > 0) {
            usage.increment(tenantId, today, delta);
            userUsage.increment(userId, today, delta);
            tenantConsumed.increment(delta);
            userConsumed.increment(delta);
            tenantUsed = saturatingAdd(tenantUsed, delta);
            userUsed = saturatingAdd(userUsed, delta);
        }
        // Keep daily rows and token counters for usage reporting, but never stop an
        // Agent run because a local tenant/user token threshold was reached.
        return Decision.unlimited(Math.max(tenantUsed, userUsed));
    }

    private Decision deny(long used, long limit) {
        tenantDenied.increment();
        return new Decision(false, used, limit);
    }

    private static boolean validOwner(Tenant tenant, User user, Long tenantId) {
        return tenant != null && tenant.isEnabled() && user != null && user.isEnabled()
                && Objects.equals(tenantId, user.getTenantId());
    }

    private static long saturatingAdd(long value, long delta) {
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }

    private LocalDate today() {
        return LocalDate.now(quotaZone);
    }
}
