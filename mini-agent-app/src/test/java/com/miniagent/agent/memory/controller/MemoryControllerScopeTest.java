package com.miniagent.agent.memory.controller;

import com.miniagent.config.entity.UserRole;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.memory.model.MemoryQuery;
import com.miniagent.memory.model.MemoryScope;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class MemoryControllerScopeTest {

    private final MemoryController controller = new MemoryController();

    @Test
    void rejectsQueryWithAnotherTenantInsteadOfRebindingIt() {
        MemoryQuery query = MemoryQuery.of("secret", MemoryScope.ofUser("tenant-2", "user-7"));
        AuthenticatedUser principal = new AuthenticatedUser(7L, 1L, "alice", UserRole.USER);

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> ReflectionTestUtils.invokeMethod(controller,
                        "bindQueryToPrincipal", query, principal));

        assertEquals("Tenant is not accessible", error.getMessage());
        assertEquals("tenant-2", query.getScope().tenantId());
    }

    @Test
    void rejectsUserScopeOwnedByAnotherUser() {
        MemoryQuery query = MemoryQuery.of("secret", MemoryScope.ofUser("1", "user-8"));
        AuthenticatedUser principal = new AuthenticatedUser(7L, 1L, "alice", UserRole.USER);

        assertThrows(AccessDeniedException.class, () -> ReflectionTestUtils.invokeMethod(
                controller, "bindQueryToPrincipal", query, principal));
    }
}
