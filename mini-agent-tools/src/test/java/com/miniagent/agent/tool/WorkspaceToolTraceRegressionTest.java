package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression harness for the large-HTML research path observed in session s_e4802c49c8c641628bf3fea87f6a6fce. */
class WorkspaceToolTraceRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TRACE_LINE_CODE_POINTS = 238_667;
    private static final String MARKER = "function updatePlayerState() { return 'trace-marker'; }";

    @TempDir
    Path workspace;

    @AfterEach
    void clearWorkspaceContext() {
        WorkspaceContext.clearAll();
    }

    @Test
    void largeHtmlResearchSearchesFirstThenReadsOnlyTheHighOffsetAndListsMetadataSafely() throws Exception {
        String longLine = "x".repeat(TRACE_LINE_CODE_POINTS - MARKER.length()) + MARKER;
        Files.writeString(workspace.resolve("index.html"), longLine);
        Files.writeString(workspace.resolve("README.md"), "LAN game test instructions\n");
        WorkspaceContext.setRootOverride(workspace);
        ToolRegistry registry = newRegistry();

        ToolResult search = registry.executeResult("search_code",
                "{\"pattern\":\"updatePlayerState\",\"path\":\"workspace\",\"glob\":\"*.html\",\"maxResults\":5}");
        assertThat(search.isSuccess()).isTrue();
        assertThat(search.legacyText()).contains("index.html:1:");

        int markerOffset = TRACE_LINE_CODE_POINTS - MARKER.codePointCount(0, MARKER.length());
        ToolResult targetedRead = registry.executeResult("read_file",
                "{\"path\":\"workspace/index.html\",\"offset\":1,\"limit\":1,"
                        + "\"char_offset\":" + markerOffset + ",\"char_limit\":100}");
        assertThat(targetedRead.isSuccess()).isTrue();
        assertThat(targetedRead.legacyText()).contains(MARKER, "char_offset=" + markerOffset);
        assertThat(targetedRead.legacyText().length()).isLessThan(1_000);

        ToolResult inventory = registry.executeResult("list_files",
                "{\"path\":\"workspace\",\"include_metadata\":true,\"limit\":10}");
        assertThat(inventory.isSuccess()).isTrue();
        var entries = JSON.readTree(inventory.legacyText()).path("entries");
        var htmlEntry = java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                .filter(entry -> "index.html".equals(entry.path("path").asText()))
                .findFirst().orElseThrow();
        assertThat(htmlEntry.path("sizeBytes").asLong()).isEqualTo(Files.size(workspace.resolve("index.html")));
        assertThat(htmlEntry.path("modifiedAt").asText()).isNotBlank();

        ToolResult wrongKind = registry.executeResult("list_files", "{\"path\":\"workspace/index.html\"}");
        assertThat(wrongKind.isSuccess()).isFalse();
        assertThat(wrongKind.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
        assertThat(wrongKind.message()).contains("不是目录", "read_file");
    }

    private ToolRegistry newRegistry() {
        ToolRegistry registry = new ToolRegistry();
        BuiltinTools tools = new BuiltinTools();
        ReflectionTestUtils.setField(tools, "registry", registry);
        ReflectionTestUtils.invokeMethod(tools, "registerFileTools");
        ReflectionTestUtils.invokeMethod(tools, "registerSearchAndEditTools");
        return registry;
    }
}
