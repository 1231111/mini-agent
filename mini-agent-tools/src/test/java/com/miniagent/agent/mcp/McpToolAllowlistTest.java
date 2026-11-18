package com.miniagent.agent.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpToolAllowlistTest {
    @Test
    void exactRemoteToolAllowlistIsRequired() {
        McpProperties.Server server = server(List.of("read_file", "list_directory"));
        assertDoesNotThrow(() -> McpToolBridge.requireExactToolAllowlist(server, List.of(
                tool("read_file"), tool("list_directory"))));
    }

    @Test
    void emptyAllowlistRejectsRegistration() {
        McpProperties.Server server = server(List.of());
        assertThrows(IllegalStateException.class, () -> McpToolBridge.requireExactToolAllowlist(
                server, List.of(tool("read_file"))));
    }

    @Test
    void unapprovedRemoteToolsAndStaleAllowlistEntriesRejectWholeServer() {
        McpProperties.Server server = server(List.of("read_file", "write_file"));
        assertThrows(IllegalStateException.class, () -> McpToolBridge.requireExactToolAllowlist(
                server, List.of(tool("read_file"), tool("delete_file"))));
    }

    @Test
    void duplicateAndSanitizedCollisionNamesAreRejected() {
        McpProperties.Server duplicateServer = server(List.of("read_file"));
        assertThrows(IllegalStateException.class, () -> McpToolBridge.requireExactToolAllowlist(
                duplicateServer, List.of(tool("read_file"), tool("read_file"))));

        McpProperties.Server collisionServer = server(List.of("a:b", "a_b"));
        assertThrows(IllegalStateException.class, () -> McpToolBridge.requireExactToolAllowlist(
                collisionServer, List.of(tool("a:b"), tool("a_b"))));
    }

    private static McpProperties.Server server(List<String> tools) {
        McpProperties.Server server = new McpProperties.Server();
        server.setId("filesystem");
        server.setAllowedTools(tools);
        return server;
    }

    private static McpStdioClient.McpToolDef tool(String name) {
        return new McpStdioClient.McpToolDef(name, "", java.util.Map.of());
    }
}
