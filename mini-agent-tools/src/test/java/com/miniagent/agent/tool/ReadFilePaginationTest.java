package com.miniagent.agent.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadFilePaginationTest {
    @TempDir
    Path workspace;

    @AfterEach
    void clearWorkspaceContext() {
        WorkspaceContext.clearAll();
    }

    @Test
    void longUtf8LineCanBePagedByCodePointWithoutLoss() throws Exception {
        String content = ("中😀ab").repeat(30_000);
        Files.writeString(workspace.resolve("large.html"), content);
        ToolRegistry registry = newRegistry();
        StringBuilder reconstructed = new StringBuilder();

        for (int charOffset = 0; charOffset < content.codePointCount(0, content.length()); charOffset += 8000) {
            ToolResult result = registry.executeResult("read_file", "{\"path\":\"large.html\","
                    + "\"offset\":1,\"limit\":1,\"char_offset\":" + charOffset
                    + ",\"char_limit\":8000}");
            assertThat(result.isSuccess()).isTrue();
            String line = body(result.legacyText()).replaceFirst(" \\[read_file 行内截断：.*?\\]$", "");
            assertThat(line).startsWith("1|");
            reconstructed.append(line.substring(2));
        }

        assertThat(reconstructed.toString()).isEqualTo(content);
    }

    @Test
    void highLineOffsetUsesSparseIndexAndPreservesCrlfAndUnicode() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 400; i++) lines.add("行" + i + " 😀");
        Files.writeString(workspace.resolve("many-lines.txt"), String.join("\r\n", lines) + "\r\n");
        ToolRegistry registry = newRegistry();

        ToolResult result = registry.executeResult("read_file",
                "{\"path\":\"many-lines.txt\",\"offset\":250,\"limit\":5}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.legacyText()).contains("line_offset=250", "lines_returned=5", "250|行250 😀",
                "251|行251 😀", "254|行254 😀");
        assertThat(result.legacyText()).doesNotContain("249|", "255|行255");
    }

    @Test
    void responseBudgetBoundsLargeMultiLineOutputAndReturnsNextLineCursor() throws Exception {
        String line = "x".repeat(4000);
        Files.writeString(workspace.resolve("many-long-lines.txt"), (line + "\n").repeat(100));
        ToolRegistry registry = newRegistry();

        ToolResult result = registry.executeResult("read_file", "{\"path\":\"many-long-lines.txt\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.legacyText()).contains("lines_returned=8", "partial=true",
                "reason=response_char_limit next_line_offset=9");
        assertThat(result.legacyText().lines().filter(s -> s.matches("\\d+\\|.*")).count()).isEqualTo(8);
        assertThat(result.legacyText().length()).isLessThan(34_000);
    }

    @Test
    void firstPageOfHugeMinifiedLineStopsEarlyAndProvidesContinuationCursor() throws Exception {
        Files.writeString(workspace.resolve("minified.html"), "a".repeat(200_000));
        ToolRegistry registry = newRegistry();

        ToolResult result = registry.executeResult("read_file", "{\"path\":\"minified.html\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.legacyText()).contains("total_chars=unknown", "next_char_offset=4000");
        assertThat(result.legacyText().length()).isLessThan(5_000);
    }

    @Test
    void invalidPageAndCharacterRangeArgumentsAreRejectedClearly() throws Exception {
        Files.writeString(workspace.resolve("short.txt"), "abc\n");
        ToolRegistry registry = newRegistry();

        assertInvalid(registry, "{\"path\":\"short.txt\",\"offset\":0}", "offset");
        assertInvalid(registry, "{\"path\":\"short.txt\",\"limit\":201}", "limit");
        assertInvalid(registry, "{\"path\":\"short.txt\",\"limit\":0}", "limit");
        assertInvalid(registry, "{\"path\":\"short.txt\",\"offset\":1,\"limit\":1,"
                + "\"char_offset\":-1,\"char_limit\":10}", "char_offset");
        assertInvalid(registry, "{\"path\":\"short.txt\",\"offset\":1,\"limit\":1,"
                + "\"char_offset\":0,\"char_limit\":8001}", "char_limit");
        assertInvalid(registry, "{\"path\":\"short.txt\",\"offset\":1,\"limit\":1,"
                + "\"char_offset\":10,\"char_limit\":8}", "total_chars=3");
        assertInvalid(registry, "{\"path\":\"short.txt\",\"offset\":1,\"limit\":2,"
                + "\"char_offset\":0,\"char_limit\":8}", "仅支持 limit=1");
    }

    private void assertInvalid(ToolRegistry registry, String arguments, String messagePart) {
        ToolResult result = registry.executeResult("read_file", arguments);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
        assertThat(result.message()).contains(messagePart);
    }

    private static String body(String result) {
        int separator = result.indexOf('\n');
        return separator < 0 ? result : result.substring(separator + 1).stripTrailing();
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
