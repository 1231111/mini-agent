package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFileListingContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    @AfterEach
    void clearWorkspaceContext() {
        WorkspaceContext.clearAll();
    }

    @Test
    void listingARegularFileReturnsTypedActionableErrorInsteadOfSuccessfulMalformedJson() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<html>game</html>");
        ToolRegistry registry = newRegistry();

        ToolResult result = registry.executeResult("list_files", "{\"path\":\"workspace/index.html\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
        assertThat(result.message()).contains("不是目录", "read_file");
        assertThat(JSON.readTree(result.legacyText()).path("success").asBoolean()).isFalse();
    }

    @Test
    void metadataListingProvidesFileSizeTypeAndStableTimestampForWorkspaceEvidence() throws Exception {
        Path source = workspace.resolve("server.js");
        Files.writeString(source, "const server = true;\n");
        Instant expectedModified = Instant.parse("2026-11-25T10:15:30Z");
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.from(expectedModified));
        ToolRegistry registry = newRegistry();

        ToolResult result = registry.executeResult("list_files",
                "{\"path\":\"workspace\",\"include_metadata\":true}");

        assertThat(result.isSuccess()).isTrue();
        var data = JSON.readTree(result.legacyText());
        assertThat(data.path("success").asBoolean()).isTrue();
        assertThat(data.path("entries").get(0).path("path").asText()).isEqualTo("server.js");
        assertThat(data.path("entries").get(0).path("type").asText()).isEqualTo("file");
        assertThat(data.path("entries").get(0).path("sizeBytes").asLong()).isEqualTo(21);
        assertThat(data.path("entries").get(0).path("modifiedAt").asText()).isEqualTo(expectedModified.toString());
    }

    @Test
    void missingDirectoryReturnsNotFoundAndMalformedPathsAreNeverReportedAsSuccess() throws Exception {
        ToolRegistry registry = newRegistry();

        ToolResult missing = registry.executeResult("list_files", "{\"path\":\"workspace/shared\"}");

        assertThat(missing.isSuccess()).isFalse();
        assertThat(missing.errorCode()).isEqualTo(ToolErrorCode.NOT_FOUND);
        assertThat(JSON.readTree(missing.legacyText()).path("success").asBoolean()).isFalse();
    }

    @Test
    void listingCapsEntryCountAndExplicitlyMarksPartialResults() throws Exception {
        for (int i = 0; i < 9; i++) Files.writeString(workspace.resolve("file-" + i + ".txt"), "x");
        ToolRegistry registry = newRegistry();

        ToolResult result = registry.executeResult("list_files",
                "{\"path\":\"workspace\",\"limit\":5}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.legacyText()).contains("partial=true", "limit=5");
        assertThat(result.legacyText().lines().filter(line -> line.startsWith("file-")).count()).isEqualTo(5);
        ToolResult invalid = registry.executeResult("list_files",
                "{\"path\":\"workspace\",\"limit\":201}");
        assertThat(invalid.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
    }

    private ToolRegistry newRegistry() {
        WorkspaceContext.setRootOverride(workspace);
        ToolRegistry registry = new ToolRegistry();
        BuiltinTools tools = new BuiltinTools();
        ReflectionTestUtils.setField(tools, "registry", registry);
        ReflectionTestUtils.invokeMethod(tools, "registerFileTools");
        return registry;
    }
}
