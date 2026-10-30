package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureDefinitionCommand;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import com.miniagent.memory.port.ProcedureSettlementResult;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteProcedureSettlementPortTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void remoteModeRequiresEndpointAndInternalKey() {
        ProcedureSettlementModeProperties noUrl = new ProcedureSettlementModeProperties("remote", "", "secret", 100, 100);
        assertThrows(IllegalStateException.class, () -> new RemoteProcedureSettlementPort(objectMapper, noUrl));
        ProcedureSettlementModeProperties noKey = new ProcedureSettlementModeProperties("remote", "http://memory", "", 100, 100);
        assertThrows(IllegalStateException.class, () -> new RemoteProcedureSettlementPort(objectMapper, noKey));
    }

    @Test
    void responseStatusUsesTheSharedContractEnum() throws Exception {
        var response = new RemoteProcedureSettlementPort.RemoteSettlementResponse(ProcedureSettlementResult.Status.DUPLICATE);
        assertEquals(ProcedureSettlementResult.Status.DUPLICATE, response.toResult().status());
        assertTrue(objectMapper.writeValueAsString(response).contains("DUPLICATE"));
    }

    @Test
    void commandCarriesIdentityForRemoteCall() {
        var command = new ProcedureSettlementCommand(new MemoryPrincipal("tenant", 1L, null, "session"),
                2L, "node", "action", 3L, 4, ProcedureSettlementCommand.Outcome.FAILURE);
        assertEquals("tenant", command.principal().tenantId());
        assertEquals(4, command.procedureVersion());
    }

    @Test
    void candidateIdentityIsCarriedInRemoteSettlementDto() throws Exception {
        var command = new ProcedureSettlementCommand(new MemoryPrincipal("tenant", 1L, null, "session"),
                2L, "node", "action", 3L, 4, ProcedureSettlementCommand.Outcome.SUCCESS,
                51L, "c".repeat(64), "CANDIDATE");
        var request = RemoteProcedureSettlementPort.RemoteProcedureSettlementRequest.from(command);
        String json = objectMapper.writeValueAsString(request);
        assertTrue(json.contains("\"evolutionProposalId\":51"));
        assertTrue(json.contains("\"evolutionCandidateHash\":\"" + "c".repeat(64) + "\""));
        assertTrue(json.contains("\"procedureVariant\":\"CANDIDATE\""));
    }

    @Test
    void projectionRequestCarriesScopeAndVersion() {
        var command = new ProcedureDefinitionCommand(new MemoryPrincipal("tenant", 7L, null, null),
                11L, 3, "USER", "7", "Deploy", "desc", "[\"pre\"]", "[\"step\"]", "[\"ok\"]", 0.6, "ACTIVE");
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
    void listsCanaryCandidatesWithOwnerIdentityAndInternalKey() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().toString());
            key.set(exchange.getRequestHeaders().getFirst("X-Memory-Internal-Key"));
            byte[] response = objectMapper.writeValueAsBytes(java.util.List.of(java.util.Map.of(
                    "proposalId", 51L, "status", "CANARY", "candidateHash", "d".repeat(64),
                    "baselineVersion", 3, "tenantId", "tenant", "userId", 7L,
                    "procedureId", 11L, "candidateContentJson", "{\\\"steps\\\":[\\\"candidate\\\"]}",
                    "canaryTargetPercent", 5)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ProcedureSettlementModeProperties("remote", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "internal-secret", 1000, 3000);
            var client = new RemoteProcedureSettlementPort(objectMapper, properties);
            var result = client.listCanaryCandidates("tenant", 7L);
            assertEquals(1, result.size());
            assertEquals(5, result.getFirst().canaryTargetPercent());
            assertEquals("internal-secret", key.get());
            assertTrue(path.get().startsWith("/internal/v1/memory/evolution/proposals?"));
            assertTrue(path.get().contains("tenantId=tenant"));
            assertTrue(path.get().contains("userId=7"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchesCanaryCandidateSnapshotWithOwnerProcedureHashAndInternalKey() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().toString());
            key.set(exchange.getRequestHeaders().getFirst("X-Memory-Internal-Key"));
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "proposalId", 51L, "status", "CANARY", "candidateHash", "d".repeat(64),
                    "tenantId", "tenant", "userId", 7L, "procedureId", 11L,
                    "candidateContentJson", "{\"steps\":[\"candidate\"]}"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ProcedureSettlementModeProperties("remote", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "internal-secret", 1000, 3000);
            var client = new RemoteProcedureSettlementPort(objectMapper, properties);
            var result = client.fetchCanaryCandidate("tenant", 7L, 11L, 51L, "d".repeat(64));
            assertEquals(51L, result.proposalId());
            assertEquals("CANARY", result.status());
            assertEquals(11L, result.procedureId());
            assertTrue(result.candidateContentJson().contains("candidate"));
            assertEquals("internal-secret", key.get());
            assertTrue(path.get().startsWith("/internal/v1/memory/evolution/proposals/51/candidate?"));
            assertTrue(path.get().contains("tenantId=tenant"));
            assertTrue(path.get().contains("userId=7"));
            assertTrue(path.get().contains("procedureId=11"));
            assertTrue(path.get().contains("candidateHash=" + "d".repeat(64)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitsFailureEvidenceToTheProtectedServiceEndpoint() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> payload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("X-Memory-Internal-Key"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"evidenceId\":1,\"status\":\"EVIDENCE_RECORDED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ProcedureSettlementModeProperties("remote", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "internal-secret", 1000, 3000);
            var client = new RemoteProcedureSettlementPort(objectMapper, properties);
            client.submitFailureEvidence(new RemoteProcedureSettlementPort.FailureEvidenceRequest(
                    "tenant", 7L, 11L, 3, "session", 42L, "node-a", "TASK_FAILURE", "summary", "resolution"));
            assertEquals("/internal/v1/memory/evolution/proposals/failure-evidence", path.get());
            assertEquals("internal-secret", key.get());
            assertTrue(payload.get().contains("\"procedureId\":11"));
            assertFalse(payload.get().contains("candidate"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failureEvidenceRequestHasOnlySourceFactsAndNoCandidateContent() throws Exception {
        var request = new RemoteProcedureSettlementPort.FailureEvidenceRequest(
                "tenant", 7L, 11L, 3, "session", 42L, "node-a", "TASK_FAILURE", "summary", "resolution");
        String json = objectMapper.writeValueAsString(request);
        assertTrue(json.contains("\"procedureId\":11"));
        assertTrue(json.contains("\"resolution\":\"resolution\""));
        assertFalse(json.contains("candidate"));
    }

    @Test
    void projectScopedCommandKeepsItsOwnScope() {
        var command = new ProcedureDefinitionCommand(new MemoryPrincipal("tenant", 7L, "proj-a", null),
                12L, 1, "PROJECT", "proj-a", "Team SOP", null, null, "[]", null, 0.5, "ACTIVE");
        var request = RemoteProcedureSettlementPort.ProcedureDefinitionSyncRequest.from(command);
        assertEquals("PROJECT", request.scopeType());
        assertEquals("proj-a", request.scopeId());
    }
}
