package com.miniagent.config.cloud;

import com.miniagent.common.ErrorCode;
import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Cloud authentication must never select a different local identity by a colliding numeric id. */
class ShadowUserServiceTest {
    private ShadowUserService service;
    private UserRepository users;
    private TenantRepository tenants;
    private PasswordEncoder passwords;

    @BeforeEach void setup() {
        service = new ShadowUserService();
        users = mock(UserRepository.class);
        tenants = mock(TenantRepository.class);
        passwords = mock(PasswordEncoder.class);
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "tenants", tenants);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwords);
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test void sharedModeReusesTheAuthenticatedRowWithoutCreatingOrElevatingIt() {
        shared(true);
        ReflectionTestUtils.setField(service, "adoptRole", true);
        User existing = local(2L, "alice", 10L);
        existing.setRole(UserRole.USER);
        when(users.findById(2L)).thenReturn(Optional.of(existing));

        User result = service.materialize(cloud("2", "alice", "Alice updated", "SYSTEM_ADMIN", "10"));

        assertSame(existing, result);
        assertEquals(2L, result.getId());
        assertEquals(10L, result.getTenantId());
        assertEquals("Alice updated", result.getDisplayName());
        assertEquals(UserRole.USER, result.getRole());
        assertNull(result.getExternalId(), "Shared rows are not converted into shadow records");
        verify(users).save(existing);
        verify(users, never()).findByExternalIdForUpdate(anyString());
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(tenants, passwords);
    }

    @Test void missingSharedRowIsRejectedWithoutFallingBackToAShadowUser() {
        shared(true);
        when(users.findById(2L)).thenReturn(Optional.empty());

        CloudAccountException failure = assertThrows(CloudAccountException.class,
                () -> service.materialize(cloud("2", "alice", "Alice", "USER", "10")));

        assertEquals(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE, failure.errorCode());
        verify(users).findById(2L);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(tenants, passwords);
    }

    @Test void collidingSharedIdWithDifferentUsernameCannotMutateOrAuthenticateTheLocalUser() {
        shared(true);
        User unrelated = local(2L, "local-owner", 10L);
        unrelated.setRole(UserRole.SYSTEM_ADMIN);
        when(users.findById(2L)).thenReturn(Optional.of(unrelated));

        CloudAccountException failure = assertThrows(CloudAccountException.class,
                () -> service.materialize(cloud("2", "alice", "New name", "USER", "10")));

        assertEquals(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE, failure.errorCode());
        assertUnchanged(unrelated, "local-owner", 10L, UserRole.SYSTEM_ADMIN);
        verify(users).findById(2L);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(tenants, passwords);
    }

    @Test void collidingSharedIdAndUsernameWithDifferentTenantIsRejectedBeforeAnyMutation() {
        shared(true);
        User unrelated = local(2L, "alice", 20L);
        when(users.findById(2L)).thenReturn(Optional.of(unrelated));

        CloudAccountException failure = assertThrows(CloudAccountException.class,
                () -> service.materialize(cloud("2", "alice", "New name", "USER", "10")));

        assertEquals(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE, failure.errorCode());
        assertUnchanged(unrelated, "alice", 20L, UserRole.USER);
        verify(users).findById(2L);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(tenants, passwords);
    }

    @Test void missingSharedTenantCannotBeAcceptedAsAnIdentityMatch() {
        shared(true);
        User existing = local(2L, "alice", null);
        when(users.findById(2L)).thenReturn(Optional.of(existing));

        CloudAccountException failure = assertThrows(CloudAccountException.class,
                () -> service.materialize(cloud("2", "alice", "New name", "USER", null)));

        assertEquals(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE, failure.errorCode());
        assertUnchanged(existing, "alice", null, UserRole.USER);
        verify(users, never()).save(any());
        verify(users, never()).saveAndFlush(any());
    }

    @Test void disabledSharedUserRemainsDisabledAndCannotBeRecreated() {
        shared(true);
        User existing = local(2L, "alice", 10L);
        existing.setEnabled(false);
        when(users.findById(2L)).thenReturn(Optional.of(existing));

        CloudAccountException failure = assertThrows(CloudAccountException.class,
                () -> service.materialize(cloud("2", "alice", "New name", "USER", "10")));

        assertEquals(ErrorCode.AUTH_FORBIDDEN, failure.errorCode());
        assertFalse(existing.isEnabled());
        assertUnchanged(existing, "alice", 10L, UserRole.USER);
        verify(users).findById(2L);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(tenants, passwords);
    }

    @Test void separateModeRefreshesOnlyTheExternalIdentityEvenWhenNumericIdsDiffer() {
        shared(false);
        User shadow = local(91L, "alice#2", 1L);
        shadow.setExternalId("2");
        when(users.findByExternalIdForUpdate("2")).thenReturn(Optional.of(shadow));

        User result = service.materialize(cloud("2", "alice", "Alice updated", "SYSTEM_ADMIN", "10"));

        assertSame(shadow, result);
        assertEquals(91L, result.getId());
        assertEquals("alice#2", result.getUsername());
        assertEquals("2", result.getExternalId());
        assertEquals(1L, result.getTenantId());
        assertEquals("10", result.getCloudTenantId());
        assertEquals("Alice updated", result.getDisplayName());
        assertEquals(UserRole.USER, result.getRole());
        verify(users, never()).findById(anyLong());
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(tenants, passwords);
    }

    @Test void separateModeCreatesAnIndependentShadowAndDoesNotTakeOverTheSameLocalUsername() {
        shared(false);
        when(users.findByExternalIdForUpdate("2")).thenReturn(Optional.empty());
        when(users.existsByUsername("alice")).thenReturn(true);
        when(users.existsByUsername("alice#2")).thenReturn(false);
        Tenant system = new Tenant();
        system.setId(1L);
        system.setSlug("system");
        when(tenants.findBySlug("system")).thenReturn(Optional.of(system));
        when(passwords.encode(anyString())).thenReturn("test-placeholder-hash");

        User created = service.materialize(cloud("2", "alice", "Alice", "SYSTEM_ADMIN", "10"));

        assertNull(created.getId(), "The local database assigns a fresh id, never the cloud id");
        assertEquals("alice#2", created.getUsername());
        assertEquals("2", created.getExternalId());
        assertEquals(1L, created.getTenantId());
        assertEquals("10", created.getCloudTenantId());
        assertEquals(UserRole.USER, created.getRole());
        assertTrue(created.isEnabled());
        verify(users).saveAndFlush(created);
        verify(users, never()).findById(anyLong());
        verify(users, never()).findByUsername(anyString());
    }

    @Test void disabledSeparateShadowIsNotRefreshedOrRecreated() {
        shared(false);
        User shadow = local(91L, "alice#2", 1L);
        shadow.setExternalId("2");
        shadow.setEnabled(false);
        when(users.findByExternalIdForUpdate("2")).thenReturn(Optional.of(shadow));

        CloudAccountException failure = assertThrows(CloudAccountException.class,
                () -> service.materialize(cloud("2", "alice", "New name", "USER", "10")));

        assertEquals(ErrorCode.AUTH_FORBIDDEN, failure.errorCode());
        assertFalse(shadow.isEnabled());
        assertUnchanged(shadow, "alice#2", 1L, UserRole.USER);
        verify(users, never()).save(any());
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(tenants, passwords);
    }

    private void shared(boolean enabled) {
        ReflectionTestUtils.setField(service, "sharedDatabase", enabled);
    }

    private static CloudUser cloud(String id, String name, String display, String role, String tenant) {
        // These identity tests do not require credentials; no access or refresh secrets are used.
        return new CloudUser(id, name, display, role, tenant, null, null);
    }

    private static User local(Long id, String username, Long tenantId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setTenantId(tenantId);
        user.setDisplayName("Original name");
        return user;
    }

    private static void assertUnchanged(User user, String username, Long tenant, UserRole role) {
        assertEquals(username, user.getUsername());
        assertEquals(tenant, user.getTenantId());
        assertEquals("Original name", user.getDisplayName());
        assertEquals(role, user.getRole());
        assertNull(user.getCloudTenantId());
        assertNull(user.getMemoryAccessToken());
        assertNull(user.getMemoryRefreshCredential());
    }
}
