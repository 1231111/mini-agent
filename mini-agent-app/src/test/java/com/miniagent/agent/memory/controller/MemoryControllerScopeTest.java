package com.miniagent.agent.memory.controller;

import com.miniagent.config.entity.UserRole;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.SessionAuthorizationService;
import com.miniagent.memory.model.MemoryQuery;
import com.miniagent.memory.model.MemoryScope;
import com.miniagent.memory.MemoryManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    void manualConsolidationPassesAuthenticatedTenantToMemoryManager() {
        MemoryManager manager = mock(MemoryManager.class);
        SessionAuthorizationService authorization = mock(SessionAuthorizationService.class);
        ReflectionTestUtils.setField(controller, "memoryManager", manager);
        ReflectionTestUtils.setField(controller, "sessionAuthorization", authorization);
        AuthenticatedUser principal = new AuthenticatedUser(7L, 1L, "alice", UserRole.USER);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(com.miniagent.config.security.JwtSessionService.ATTR_PRINCIPAL))
                .thenReturn(principal);

        controller.consolidate("session-a", request);

        verify(authorization).requireOwner(7L, "session-a");
        verify(manager).consolidate("1", "session-a");
        verify(manager, never()).consolidate("session-a");
    }

    @Test
    void rejectsUserScopeOwnedByAnotherUser() {
        MemoryQuery query = MemoryQuery.of("secret", MemoryScope.ofUser("1", "user-8"));
        AuthenticatedUser principal = new AuthenticatedUser(7L, 1L, "alice", UserRole.USER);

        assertThrows(AccessDeniedException.class, () -> ReflectionTestUtils.invokeMethod(
                controller, "bindQueryToPrincipal", query, principal));
    }
}
