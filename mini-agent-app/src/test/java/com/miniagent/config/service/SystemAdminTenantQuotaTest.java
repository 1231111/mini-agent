package com.miniagent.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.entity.Tenant;
import com.miniagent.config.repository.AdminAuditLogRepository;
import com.miniagent.config.repository.AuthSessionRepository;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SystemAdminTenantQuotaTest {
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final AdminAuditLogRepository auditLogs = mock(AdminAuditLogRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final SystemAdminService service = new SystemAdminService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "tenants", tenants);
        ReflectionTestUtils.setField(service, "users", users);
        ReflectionTestUtils.setField(service, "sessions", sessions);
        ReflectionTestUtils.setField(service, "auditLogs", auditLogs);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwords);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "selfHostedDefaultDailyUserTokenLimit", 100_000L);
        when(auditLogs.save(any())).thenAnswer(call -> call.getArgument(0));
        when(users.countByTenantId(any())).thenReturn(0L);
    }

    @Test
    void systemGetsConfiguredUserLimitByDefaultButOtherTenantsRemainUnlimitedPerUser() {
        when(tenants.findBySlug(any())).thenReturn(Optional.empty());
        when(tenants.saveAndFlush(any(Tenant.class))).thenAnswer(call -> {
            Tenant saved = call.getArgument(0);
            saved.setId("system".equals(saved.getSlug()) ? 1L : 2L);
            return saved;
        });

        var system = service.createTenant(7L, "system", "System", 1_000_000L, null);
        var paidOrg = service.createTenant(7L, "paid-org", "Paid organization", 500_000L, null);

        assertEquals(100_000L, system.get("dailyUserTokenLimit"));
        assertEquals(0L, paidOrg.get("dailyUserTokenLimit"));
    }

    @Test
    void administratorCanReadAndChangeThePerUserLimit() {
        Tenant tenant = new Tenant();
        tenant.setId(9L);
        tenant.setSlug("paid-org");
        tenant.setName("Paid organization");
        tenant.setEnabled(true);
        tenant.setDailyTokenLimit(500_000L);
        when(users.findByRoleForUpdate(any())).thenReturn(java.util.List.of());
        when(tenants.findByIdForUpdate(9L)).thenReturn(Optional.of(tenant));
        when(tenants.save(tenant)).thenReturn(tenant);

        var view = service.updateTenant(7L, 9L, null, null, 40_000L);

        assertEquals(40_000L, tenant.getDailyUserTokenLimit());
        assertEquals(40_000L, view.get("dailyUserTokenLimit"));
    }
}
