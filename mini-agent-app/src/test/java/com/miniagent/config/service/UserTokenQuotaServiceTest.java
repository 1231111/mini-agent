package com.miniagent.config.service;

import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.TenantDailyUsageRepository;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserDailyUsageRepository;
import com.miniagent.config.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserTokenQuotaServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final UserDailyUsageRepository userUsage = mock(UserDailyUsageRepository.class);
    private final TenantDailyUsageRepository tenantUsage = mock(TenantDailyUsageRepository.class);
    private final UserTokenQuotaService service = new UserTokenQuotaService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "users", users);
        ReflectionTestUtils.setField(service, "tenants", tenants);
        ReflectionTestUtils.setField(service, "userUsage", userUsage);
        ReflectionTestUtils.setField(service, "tenantUsage", tenantUsage);
        ReflectionTestUtils.setField(service, "quotaZoneId", "UTC");
    }

    @Test
    void currentSummaryUsesActualDailyCountersAndEffectiveMembershipLimit() {
        User user = new User();
        user.setId(7L);
        user.setTenantId(9L);
        user.setMembershipDailyTokenLimit(1_000L);
        user.setMembershipMaxConcurrentTasks(5);
        user.setMembershipExpiresAt(LocalDateTime.now(ZoneId.of("UTC")).plusDays(1));
        Tenant tenant = new Tenant();
        tenant.setId(9L);
        tenant.setDailyTokenLimit(10_000L);
        tenant.setDailyUserTokenLimit(200L);
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(tenants.findById(9L)).thenReturn(Optional.of(tenant));
        when(userUsage.tokenCount(7L, today)).thenReturn(Optional.of(650L));
        when(tenantUsage.tokenCount(9L, today)).thenReturn(Optional.of(800L));

        var view = service.current(7L, 9L);

        assertEquals(650L, view.get("userUsedTokens"));
        assertEquals(1_000L, view.get("userDailyLimit"));
        assertEquals(350L, view.get("userRemainingTokens"));
        assertEquals(350L, view.get("availableTokens"));
        assertEquals("MEMBERSHIP", view.get("quotaSource"));
        assertEquals(800L, view.get("tenantUsedTokens"));
        assertEquals(9_200L, view.get("tenantRemainingTokens"));
    }

    @Test
    void userCannotReadAnotherTenantsQuota() {
        User user = new User();
        user.setTenantId(10L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(tenants.findById(9L)).thenReturn(Optional.of(new Tenant()));

        assertThrows(IllegalArgumentException.class, () -> service.current(7L, 9L));
    }

    @Test
    void membershipSyncPersistsPlanAllowanceAndExpiryOnLocalShadowUser() {
        User user = new User();
        user.setId(7L);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        LocalDateTime expiry = LocalDateTime.of(2026, 12, 1, 0, 0);

        service.synchronizeMembership(7L, 5_000L, 7, expiry);

        assertEquals(5_000L, user.getMembershipDailyTokenLimit());
        assertEquals(7, user.getMembershipMaxConcurrentTasks());
        assertEquals(expiry, user.getMembershipExpiresAt());
        verify(users).save(user);
    }

    @Test
    void concurrencyLimitUsesActiveMembershipAndFallsBackWhenExpired() {
        User user = new User();
        user.setMembershipMaxConcurrentTasks(5);
        user.setMembershipExpiresAt(LocalDateTime.now(ZoneId.of("UTC")).plusDays(1));
        when(users.findById(7L)).thenReturn(Optional.of(user));

        assertEquals(5, service.effectiveConcurrentLimit(7L, 2));

        user.setMembershipExpiresAt(LocalDateTime.now(ZoneId.of("UTC")).minusSeconds(1));
        assertEquals(2, service.effectiveConcurrentLimit(7L, 2));
    }

    @Test
    void nonPositiveMembershipConcurrencyMeansUnlimited() {
        User user = new User();
        user.setMembershipMaxConcurrentTasks(0);
        when(users.findById(7L)).thenReturn(Optional.of(user));

        assertEquals(Integer.MAX_VALUE, service.effectiveConcurrentLimit(7L, 2));
    }
}
