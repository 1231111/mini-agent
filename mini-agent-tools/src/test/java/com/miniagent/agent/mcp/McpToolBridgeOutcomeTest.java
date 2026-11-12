package com.miniagent.agent.mcp;

import com.miniagent.agent.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpToolBridgeOutcomeTest {
    @Test
    void lostResponseAfterMcpWriteIsUnknownRatherThanAConfirmedFailure() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger commits = new AtomicInteger();
        when(fixture.client.callTool(eq("submit"), anyString())).thenAnswer(invocation -> {
            commits.incrementAndGet();
            throw new IOException("connection reset after commit at C:\\remote\\job\nreply lost");
        });

        ToolResult result = fixture.registry.executeResult("mcp__server__submit", "{}");

        assertEquals(ToolStatus.UNKNOWN, result.status());
        assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(result.legacyText()).status());
        assertFalse(result.retriable());
        assertEquals(1, commits.get());
    }

    @Test
    void missingMcpAcknowledgementRemainsUnresolved() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.client.callTool(eq("submit"), anyString())).thenReturn(null, " ");
        assertEquals(ToolStatus.UNKNOWN, fixture.registry.executeResult("mcp__server__submit", "{}").status());
        assertEquals(ToolStatus.UNKNOWN, fixture.registry.executeResult("mcp__server__submit", "{}").status());
    }

    @Test
    void disconnectedBeforeDispatchIsAConfirmedFailure() throws Exception {
        Fixture fixture = new Fixture();
        fixture.clients.clear();
        ToolResult result = fixture.registry.executeResult("mcp__server__submit", "{}");

        assertEquals(ToolStatus.FAILED, result.status());
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, result.errorCode());
        verify(fixture.client, never()).callTool(anyString(), anyString());
    }

    @Test
    void explicitMcpToolErrorAndSuccessfulResponseRetainTheirStatus() throws Exception {
        Fixture fixture = new Fixture();
        String failure = "{\"error\":\"target rejected request\"}";
        String success = "{\"success\":true,\"error\":\"an ordinary business field\"}";
        when(fixture.client.callTool(eq("submit"), anyString())).thenReturn(failure, success);
        ToolResult rejected = fixture.registry.executeResult("mcp__server__submit", "{}");
        assertEquals(ToolStatus.FAILED, rejected.status());
        assertEquals(failure, rejected.legacyText());
        assertTrue(fixture.registry.executeResult("mcp__server__submit", "{}").isSuccess());
    }

    private static class Fixture {
        final McpStdioClient client = mock(McpStdioClient.class);
        final McpToolBridge bridge = new McpToolBridge();
        final ToolRegistry registry = new ToolRegistry();
        final ConcurrentHashMap<String, McpStdioClient> clients;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            ReflectionTestUtils.setField(bridge, "toolRegistry", registry);
            clients = (ConcurrentHashMap<String, McpStdioClient>) ReflectionTestUtils.getField(bridge, "clients");
            clients.put("server", client);
            when(client.listTools()).thenReturn(List.of(new McpStdioClient.McpToolDef("submit", "submit", Map.of())));
            ReflectionTestUtils.invokeMethod(bridge, "registerToolsFromClient", "server", client);
        }
    }
}
