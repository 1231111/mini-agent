package com.miniagent.agent.memory.controller;

import com.miniagent.agent.memory.manager.ProcedureRevisionService;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.service.SystemAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcedureRevisionControllerTest {
    private ProcedureRevisionService revisions;
    private SystemAdminService audit;
    private ProcedureRevisionController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        revisions = mock(ProcedureRevisionService.class);
        audit = mock(SystemAdminService.class);
        controller = new ProcedureRevisionController(revisions, audit);
        request = new MockHttpServletRequest();
    }

    @Test
    void rollbackBindsAuthenticatedOwnerAndWritesVersionedAuditReceipt() {
        request.setAttribute(JwtSessionService.ATTR_PRINCIPAL,
                new AuthenticatedUser(7L, 11L, "owner", UserRole.USER));
        when(revisions.rollbackWithReceipt(42L, 2)).thenAnswer(call -> {
            assertEquals(new OwnerContext(7L, "11"), OwnerContext.capture());
            return new ProcedureRevisionService.RollbackResult(true, 4, 5, 2, "a".repeat(64));
        });

        var response = controller.rollback(42L, 2, request);

        assertTrue(response.isSuccess());
        assertEquals(new ProcedureRevisionController.RollbackReceipt(true, 4, 5, 2), response.getData());
        verify(audit).recordAudit(7L, "PROCEDURE_REVISION_ROLLBACK", "AGENT_PROCEDURE", 42L, 11L,
                Map.of("fromVersion", 4, "toVersion", 5, "restoredRevision", 2,
                        "contentHash", "a".repeat(64)));
        assertNull(OwnerContext.currentUserId(), "owner scope must be cleared after the request");
    }

    @Test
    void rejectsMissingAuthenticationBeforeCallingRevisionService() {
        assertThrows(AccessDeniedException.class, () -> controller.rollback(42L, 2, request));
        verifyNoInteractions(revisions, audit);
    }

    @Test
    void rejectsInvalidPathValuesBeforeCallingRevisionService() {
        request.setAttribute(JwtSessionService.ATTR_PRINCIPAL,
                new AuthenticatedUser(7L, 11L, "owner", UserRole.USER));
        assertThrows(com.miniagent.common.exception.BusinessException.class,
                () -> controller.rollback(0L, 2, request));
        assertThrows(com.miniagent.common.exception.BusinessException.class,
                () -> controller.rollback(42L, 0, request));
        verifyNoInteractions(revisions, audit);
    }

    @Test
    void rejectsCrossOwnerProcedureAndDoesNotWriteAudit() {
        request.setAttribute(JwtSessionService.ATTR_PRINCIPAL,
                new AuthenticatedUser(7L, 11L, "owner", UserRole.USER));
        when(revisions.rollbackWithReceipt(42L, 2)).thenThrow(new SecurityException("procedure inaccessible"));

        assertThrows(AccessDeniedException.class, () -> controller.rollback(42L, 2, request));
        verifyNoInteractions(audit);
        assertNull(OwnerContext.currentUserId(), "owner scope must be cleared after denial");
    }

    @Test
    void conflictAndMissingRevisionDoNotWriteAudit() {
        request.setAttribute(JwtSessionService.ATTR_PRINCIPAL,
                new AuthenticatedUser(7L, 11L, "owner", UserRole.USER));
        when(revisions.rollbackWithReceipt(42L, 2))
                .thenReturn(new ProcedureRevisionService.RollbackResult(false, 4, 4, 2, "b".repeat(64)))
                .thenThrow(new java.util.NoSuchElementException());

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> controller.rollback(42L, 2, request));
        assertEquals(409, conflict.getStatusCode().value());
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> controller.rollback(42L, 2, request));
        assertEquals(404, missing.getStatusCode().value());
        verifyNoInteractions(audit);
    }
}
