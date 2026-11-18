package com.miniagent.agent.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpExecutableAllowlistTest {
    @TempDir Path directory;

    @Test
    void acceptsOnlyTheSameExistingCanonicalAbsoluteExecutable() throws Exception {
        Path executable = Files.createFile(directory.resolve("mcp-server"));
        assertEquals(executable.toRealPath(), McpStdioClient.resolveAllowedExecutable(
                executable.toString(), executable.toString()));
    }

    @Test
    void refusesMissingRelativeOrMismatchedAllowlistEntries() throws Exception {
        Path executable = Files.createFile(directory.resolve("mcp-server"));
        Path other = Files.createFile(directory.resolve("other-server"));

        assertThrows(IllegalArgumentException.class,
                () -> McpStdioClient.resolveAllowedExecutable(executable.toString(), ""));
        assertThrows(IllegalArgumentException.class,
                () -> McpStdioClient.resolveAllowedExecutable("mcp-server", executable.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> McpStdioClient.resolveAllowedExecutable(executable.toString(), other.toString()));
        assertThrows(java.nio.file.NoSuchFileException.class,
                () -> McpStdioClient.resolveAllowedExecutable(directory.resolve("missing").toString(), executable.toString()));
    }
}
