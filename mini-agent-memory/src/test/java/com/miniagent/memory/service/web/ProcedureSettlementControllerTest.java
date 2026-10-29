package com.miniagent.memory.service.web;

import com.miniagent.memory.port.ProcedureSettlementPort;
import com.miniagent.memory.port.ProcedureSettlementResult;
import com.miniagent.memory.service.security.MemoryServiceSecurityProperties;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("memory-service")
class ProcedureSettlementControllerTest {
    private final ProcedureSettlementPort port = mock(ProcedureSettlementPort.class);
    private final ProcedureSettlementController controller = new ProcedureSettlementController(
            port, new MemoryServiceSecurityProperties("secret"));

    @Test
    void validInternalRequestReachesPortWithTrustedFieldsAndBoundVersion() {
        when(port.settle(any())).thenReturn(ProcedureSettlementResult.recorded());
        ProcedureSettlementResponse response = controller.settle("secret", request());

        assertEquals(ProcedureSettlementResult.Status.RECORDED, response.status());
        var command = org.mockito.ArgumentCaptor
                .forClass(com.miniagent.memory.port.ProcedureSettlementCommand.class);
        verify(port).settle(command.capture());
        assertEquals("tenant-a", command.getValue().principal().tenantId());
        assertEquals(7L, command.getValue().principal().userId());
        assertEquals("project-a", command.getValue().principal().projectId());
        assertEquals(22L, command.getValue().procedureId());
        assertEquals(4, command.getValue().procedureVersion());
    }

    @Test
    void missingOrWrongInternalKeyIsRejectedBeforePortCall() {
        assertThrows(ResponseStatusException.class, () -> controller.settle(null, request()));
        assertThrows(ResponseStatusException.class, () -> controller.settle("wrong", request()));
        verifyNoInteractions(port);
    }

    @Test
    void emptyConfiguredKeyDoesNotDisableAuthentication() {
        ProcedureSettlementController secured = new ProcedureSettlementController(
                port, new MemoryServiceSecurityProperties(""));
        assertThrows(ResponseStatusException.class, () -> secured.settle("", request()));
        verifyNoInteractions(port);
    }

    private static ProcedureSettlementRequest request() {
        return new ProcedureSettlementRequest("tenant-a", 7L, "project-a", "session-a",
                3L, "node-a", "action-a", 22L, 4,
                ProcedureSettlementRequest.Outcome.SUCCESS);
    }
}
