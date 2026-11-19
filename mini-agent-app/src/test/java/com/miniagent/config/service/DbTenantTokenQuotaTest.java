package com.miniagent.config.service;

import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.TenantDailyUsageRepository;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserDailyUsageRepository;
import com.miniagent.config.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DbTenantTokenQuotaTest {
    private static final Long TENANT_ID = 11L;
    private static final Long USER_ID = 42L;
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("UTC"));

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantDailyUsageRepository tenantUsage = mock(TenantDailyUsageRepository.class);
    private final UserDailyUsageRepository userUsage = mock(UserDailyUsageRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DbTenantTokenQuota quota = new DbTenantTokenQuota();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(quota, "tenants", tenants);
        ReflectionTestUtils.setField(quota, "usage", tenantUsage);
        ReflectionTestUtils.setField(quota, "userUsage", userUsage);
        ReflectionTestUtils.setField(quota, "users", users);
        ReflectionTestUtils.setField(quota, "meters", new SimpleMeterRegistry());
        ReflectionTestUtils.setField(quota, "quotaZoneId", "UTC");
        quota.init();

        Tenant tenant = new Tenant();
        tenant.setId(TENANT_ID);
        tenant.setEnabled(true);
        tenant.setDailyTokenLimit(1_000L);
        tenant.setDailyUserTokenLimit(100L);
        User user = new User();
        user.setId(USER_ID);
        user.setTenantId(TENANT_ID);
        user.setEnabled(true);
        when(tenants.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(tenants.findByIdForUpdate(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(tenantUsage.tokenCount(TENANT_ID, TODAY)).thenReturn(Optional.of(50L));
        when(userUsage.tokenCount(USER_ID, TODAY)).thenReturn(Optional.of(90L));
    }

    @Test
    void preRunCheckDoesNotApplyTenantOrUserTokenCeilings() {
        when(tenantUsage.tokenCount(TENANT_ID, TODAY)).thenReturn(Optional.of(1_000L));
        when(userUsage.tokenCount(USER_ID, TODAY)).thenReturn(Optional.of(100L));

        var decision = quota.check(TENANT_ID, USER_ID);

        assertTrue(decision.allowed());
        assertEquals(1_000L, decision.usedTokens());
        assertEquals(0L, decision.limitTokens());
        verify(tenantUsage, never()).increment(any(), any(), anyLong());
    }

    @Test
    void runtimeConsumptionAtomicallyCountsUsageWithoutApplyingEitherTokenCeiling() {
        Tenant tenant = tenants.findById(TENANT_ID).orElseThrow();
        tenant.setDailyTokenLimit(60L);
        var decision = quota.consume(TENANT_ID, USER_ID, 20L);

        assertTrue(decision.allowed(), "local token ceilings are not enforced");
        assertEquals(110L, decision.usedTokens());
        assertEquals(0L, decision.limitTokens());
        verify(tenants).findByIdForUpdate(TENANT_ID);
        verify(users).findByIdForUpdate(USER_ID);
        verify(tenantUsage).increment(TENANT_ID, TODAY, 20L);
        verify(userUsage).increment(USER_ID, TODAY, 20L);
    }

    @Test
    void disabledUserIsStillRejectedEvenThoughTokenCeilingsAreIgnored() {
        User user = users.findById(USER_ID).orElseThrow();
        user.setEnabled(false);

        assertFalse(quota.check(TENANT_ID, USER_ID).allowed());
    }
}
