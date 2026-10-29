package com.miniagent.memory.port;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPortContractTest {
    @Test
    void principalNormalizesOptionalScopeAndRequiresTenantAndUser() {
        MemoryPrincipal principal = new MemoryPrincipal(" tenant-a ", 7L, " project-a ", " session-a ");

        assertEquals("tenant-a", principal.tenantId());
        assertEquals(7L, principal.userId());
        assertEquals("project-a", principal.projectId());
        assertEquals("session-a", principal.sessionId());
        assertTrue(principal.hasProject());
        assertTrue(principal.hasSession());
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryPrincipal(" ", 7L, null, null));
        assertThrows(NullPointerException.class,
                () -> new MemoryPrincipal("tenant-a", null, null, null));
    }

    @Test
    void settlementCommandKeepsBoundProcedureVersionAndNormalizesAction() {
        MemoryPrincipal principal = new MemoryPrincipal("tenant-a", 7L, null, "session-a");
        ProcedureSettlementCommand command = new ProcedureSettlementCommand(
                principal, 3L, " node-1 ", " ", 22L, 4,
                ProcedureSettlementCommand.Outcome.FAILURE);

        assertEquals("node-1", command.nodeId());
        assertEquals("action", command.actionId());
        assertEquals(22L, command.procedureId());
        assertEquals(4, command.procedureVersion());
        assertEquals(ProcedureSettlementCommand.Outcome.FAILURE, command.outcome());
    }

    @Test
    void settlementCommandRejectsInvalidIdentity() {
        MemoryPrincipal principal = new MemoryPrincipal("tenant-a", 7L, null, "session-a");

        assertThrows(IllegalArgumentException.class,
                () -> new ProcedureSettlementCommand(principal, 1L, "node", "action", 0L, 1,
                        ProcedureSettlementCommand.Outcome.SUCCESS));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcedureSettlementCommand(principal, 1L, "node", "action", 1L, 0,
                        ProcedureSettlementCommand.Outcome.SUCCESS));
        assertThrows(NullPointerException.class,
                () -> new ProcedureSettlementCommand(principal, 1L, "node", "action", 1L, 1, null));

        MemoryPrincipal withoutSession = new MemoryPrincipal("tenant-a", 7L, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> new ProcedureSettlementCommand(withoutSession, 1L, "node", "action", 1L, 1,
                        ProcedureSettlementCommand.Outcome.SUCCESS));
    }
}
