package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureDefinitionCommand;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import com.miniagent.memory.port.ProcedureSettlementResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteProcedureSettlementPortTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void remoteModeRequiresEndpointAndInternalKey() {
        ProcedureSettlementModeProperties noUrl = new ProcedureSettlementModeProperties(
                "remote", "", "secret", 100, 100);
        assertThrows(IllegalStateException.class, () -> new RemoteProcedureSettlementPort(objectMapper, noUrl));

        ProcedureSettlementModeProperties noKey = new ProcedureSettlementModeProperties(
                "remote", "http://memory", "", 100, 100);
        assertThrows(IllegalStateException.class, () -> new RemoteProcedureSettlementPort(objectMapper, noKey));
    }

    @Test
    void responseStatusUsesTheSharedContractEnum() throws Exception {
        var response = new RemoteProcedureSettlementPort.RemoteSettlementResponse(
                ProcedureSettlementResult.Status.DUPLICATE);
        assertEquals(ProcedureSettlementResult.Status.DUPLICATE, response.toResult().status());

        var json = objectMapper.writeValueAsString(response);
        assertTrue(json.contains("DUPLICATE"));
    }

    @Test
    void commandCarriesIdentityForRemoteCall() {
        var command = new ProcedureSettlementCommand(
                new MemoryPrincipal("tenant", 1L, null, "session"),
                2L, "node", "action", 3L, 4,
                ProcedureSettlementCommand.Outcome.FAILURE);
        assertEquals("tenant", command.principal().tenantId());
        assertEquals(4, command.procedureVersion());
    }

    /**
     * 投影请求必须原样带上归属。只带 userId 的话，项目级 SOP 在服务端会被当成用户级，
     * 按 {@code scope_id = userId} 找不到行，结算全部变成“过期版本”，而任务侧看不出异常。
     */
    @Test
    void projectionRequestCarriesScopeAndVersion() {
        var command = new ProcedureDefinitionCommand(
                new MemoryPrincipal("tenant", 7L, null, null),
                11L, 3, "USER", "7", "Deploy", "desc",
                "[\"pre\"]", "[\"step\"]", "[\"ok\"]", 0.6, "ACTIVE");

        var request = RemoteProcedureSettlementPort.ProcedureDefinitionSyncRequest.from(command);

        assertEquals("tenant", request.tenantId());
        assertEquals(7L, request.userId());
        assertEquals(11L, request.procedureId());
        assertEquals(3, request.procedureVersion());
        assertEquals("USER", request.scopeType());
        assertEquals("7", request.scopeId());
        assertEquals("ACTIVE", request.status());
        assertEquals("[\"step\"]", request.stepsJson());
    }

    @Test
    void projectScopedCommandKeepsItsOwnScope() {
        var command = new ProcedureDefinitionCommand(
                new MemoryPrincipal("tenant", 7L, "proj-a", null),
                12L, 1, "PROJECT", "proj-a", "Team SOP", null,
                null, "[]", null, 0.5, "ACTIVE");

        var request = RemoteProcedureSettlementPort.ProcedureDefinitionSyncRequest.from(command);

        assertEquals("PROJECT", request.scopeType());
        assertEquals("proj-a", request.scopeId());
    }
}
