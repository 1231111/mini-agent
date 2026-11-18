package com.miniagent.config.service;

import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.TenantDailyUsageRepository;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserDailyUsageRepository;
import com.miniagent.config.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Authoritative per-user quota view and synchronized account-plan entitlement. */
@Service
public class UserTokenQuotaService {
    @Autowired private UserRepository users;
    @Autowired private TenantRepository tenants;
    @Autowired private UserDailyUsageRepository userUsage;
    @Autowired private TenantDailyUsageRepository tenantUsage;

    @Value("${agent.quota.zone-id:Asia/Shanghai}")
    private String quotaZoneId;

    @Transactional(readOnly = true)
    public Map<String, Object> current(Long userId, Long tenantId) {
        User user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new IllegalArgumentException("tenant not found"));
        if (!Objects.equals(user.getTenantId(), tenantId)) {
            throw new IllegalArgumentException("user does not belong to the current tenant");
        }

        ZoneId zone = ZoneId.of(quotaZoneId);
        LocalDate today = LocalDate.now(zone);
        long userUsed = userUsage.tokenCount(userId, today).orElse(0L);
        long tenantUsed = tenantUsage.tokenCount(tenantId, today).orElse(0L);
        EffectiveLimit effective = effectiveLimit(user, tenant, LocalDateTime.now(zone));
        Long userRemaining = remaining(effective.limit(), userUsed);
        Long tenantRemaining = remaining(tenant.getDailyTokenLimit(), tenantUsed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("usageDate", today);
        out.put("timeZone", zone.getId());
        out.put("userUsedTokens", userUsed);
        out.put("userDailyLimit", effective.limit());
        out.put("userRemainingTokens", userRemaining);
        out.put("availableTokens", minimumRemaining(userRemaining, tenantRemaining));
        out.put("userUnlimited", effective.limit() <= 0);
        out.put("quotaSource", effective.source());
        out.put("adminDailyTokenLimitOverride", user.getDailyTokenLimitOverride());
        out.put("membershipDailyTokenLimit", activeMembershipLimit(user, LocalDateTime.now(zone)));
        out.put("tenantDefaultDailyUserTokenLimit", tenant.getDailyUserTokenLimit());
        out.put("tenantUsedTokens", tenantUsed);
        out.put("tenantDailyLimit", tenant.getDailyTokenLimit());
        out.put("tenantRemainingTokens", tenantRemaining);
        out.put("tenantUnlimited", tenant.getDailyTokenLimit() <= 0);
        return out;
    }

    @Transactional
    public void synchronizeMembership(Long userId, long dailyLimit, int maxConcurrentTasks,
                                      LocalDateTime expiresAt) {
        if (dailyLimit < 0) {
            throw new IllegalArgumentException("membership daily token limit must be >= 0");
        }
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        user.setMembershipDailyTokenLimit(dailyLimit);
        user.setMembershipMaxConcurrentTasks(maxConcurrentTasks);
        user.setMembershipExpiresAt(expiresAt);
        users.save(user);
    }

    /** Resolve the active membership cap, falling back to the service configuration. */
    @Transactional(readOnly = true)
    public int effectiveConcurrentLimit(Long userId, int fallback) {
        User user = users.findById(userId).orElse(null);
        if (user == null || user.getMembershipMaxConcurrentTasks() == null) return fallback;
        LocalDateTime now = LocalDateTime.now(ZoneId.of(quotaZoneId));
        if (user.getMembershipExpiresAt() != null && !now.isBefore(user.getMembershipExpiresAt())) {
            return fallback;
        }
        int cap = user.getMembershipMaxConcurrentTasks();
        return cap <= 0 ? Integer.MAX_VALUE : cap;
    }

    private EffectiveLimit effectiveLimit(User user, Tenant tenant, LocalDateTime now) {
        if (user.getDailyTokenLimitOverride() != null) {
            return new EffectiveLimit(user.getDailyTokenLimitOverride(), "ADMIN_OVERRIDE");
        }
        Long membershipLimit = activeMembershipLimit(user, now);
        if (membershipLimit != null) {
            return new EffectiveLimit(membershipLimit, "MEMBERSHIP");
        }
        return new EffectiveLimit(tenant.getDailyUserTokenLimit(), "TENANT_DEFAULT");
    }

    private static Long activeMembershipLimit(User user, LocalDateTime now) {
        if (user.getMembershipDailyTokenLimit() == null) {
            return null;
        }
        return user.getMembershipExpiresAt() == null || now.isBefore(user.getMembershipExpiresAt())
                ? user.getMembershipDailyTokenLimit() : null;
    }

    private static Long remaining(long limit, long used) {
        return limit <= 0 ? null : Math.max(0L, limit - used);
    }

    private static Long minimumRemaining(Long first, Long second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.min(first, second);
    }

    private record EffectiveLimit(long limit, String source) {}
}
