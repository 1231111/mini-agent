package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FileToolProductionContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @BeforeEach void setup() { WorkspaceContext.setRootOverride(workspace); }

    @AfterEach void clear() {
        WorkspaceContext.clearAll();
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test void sharedSingletonDoesNotLeakSameNamedFilesAcrossWorkspaces() throws Exception {
        Path a = Files.createDirectory(workspace.resolve("a"));
        Path b = Files.createDirectory(workspace.resolve("b"));
        Files.writeString(a.resolve("page.txt"), "workspace A only");
        Files.writeString(b.resolve("page.txt"), "workspace B only");
        ToolRegistry registry = registry(new BuiltinTools());
        WorkspaceContext.setRootOverride(a);
        assertThat(registry.executeResult("read_file", "{\"path\":\"workspace/page.txt\"}").legacyText()).contains("workspace A only");
        WorkspaceContext.setRootOverride(b);
        assertThat(registry.executeResult("read_file", "{\"path\":\"workspace/page.txt\"}").legacyText())
                .contains("workspace B only").doesNotContain("workspace A only");
    }

    @Test void externalEditsInvalidateCachedReadOfExactlyTheSameArguments() throws Exception {
        Path target = workspace.resolve("page.txt");
        Files.writeString(target, "before");
        ToolRegistry registry = registry(new BuiltinTools());
        read(registry, "page.txt");
        Files.writeString(target, "after-external-edit");
        Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        assertThat(read(registry, "page.txt").legacyText()).contains("after-external-edit").doesNotContain("before");
    }

    @Test void readInOneSessionDoesNotAuthorizeOverwriteInAnotherSession() throws Exception {
        Files.writeString(workspace.resolve("page.txt"), "original content");
        BuiltinTools tools = new BuiltinTools();
        ToolRegistry registry = registry(tools);
        WorkspaceContext.setSessionOverride("session-a");
        read(registry, "page.txt");
        WorkspaceContext.setSessionOverride("session-b");
        ToolResult denied = registry.executeResult("write_file", "{\"path\":\"page.txt\",\"content\":\"unauthorized overwrite\"}");
        assertThat(denied.errorCode()).isEqualTo(ToolErrorCode.CONFLICT);
        tools.clearToolCache("session-b");
        WorkspaceContext.setSessionOverride("session-a");
        ToolResult allowed = registry.executeResult("write_file", "{\"path\":\"page.txt\",\"content\":\"updated content\"}");
        assertThat(allowed.isSuccess()).isTrue();
        assertThat(Files.readString(workspace.resolve("page.txt"))).isEqualTo("updated content");
    }

    @Test void editRejectsOversizeAndInvalidUtf8WithoutChangingTheSource() throws Exception {
        Path large = workspace.resolve("large.txt");
        try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) { file.setLength(8L * 1024 * 1024 + 1); }
        byte[] invalid = {(byte) 0xc3, 0x28};
        Files.write(workspace.resolve("invalid.txt"), invalid);
        ToolRegistry registry = registry(new BuiltinTools());
        ToolResult oversize = edit(registry, "large.txt", "x", "y");
        ToolResult malformed = edit(registry, "invalid.txt", "x", "y");
        assertThat(oversize.errorCode()).isEqualTo(ToolErrorCode.CONFLICT);
        assertThat(malformed.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
        assertThat(Files.size(large)).isEqualTo(8L * 1024 * 1024 + 1);
        assertThat(Files.readAllBytes(workspace.resolve("invalid.txt"))).isEqualTo(invalid);
    }

    @Test void preciseEditAndAppendPreserveExistingUnicodeAndReturnValidReceipts() throws Exception {
        Files.writeString(workspace.resolve("page.txt"), "hello 世界😀\n");
        ToolRegistry registry = registry(new BuiltinTools());
        assertThat(edit(registry, "page.txt", "hello", "你好").isSuccess()).isTrue();
        ToolResult appended = registry.executeResult("write_file",
                "{\"path\":\"page.txt\",\"content\":\"tail\\n\",\"mode\":\"append\"}");
        assertThat(appended.isSuccess()).isTrue();
        assertThat(Files.readString(workspace.resolve("page.txt"))).isEqualTo("你好 世界😀\ntail\n");
        try (var children = Files.list(workspace)) {
            assertThat(children.map(p -> p.getFileName().toString()).toList()).containsExactly("page.txt");
        }
    }

    @Test void outputOverflowTriggersImmediateTerminationHookOnlyOnce() {
        AtomicInteger stopCalls = new AtomicInteger();
        BoundedOutputCapture capture = new BoundedOutputCapture(16, 32, stopCalls::incrementAndGet);
        capture.write(new byte[33], 33);
        capture.write(new byte[32], 32);
        assertThat(stopCalls).hasValue(1);
        assertThat(capture.overflowed()).isTrue();
        assertThat(capture.asString(java.nio.charset.StandardCharsets.UTF_8).length()).isEqualTo(16);
    }

    @Test void searchFindsMatchAtEndOfMinifiedLineAndReturnsContinuationOffset() throws Exception {
        Files.writeString(workspace.resolve("game.html"), "a".repeat(238_600) + "function updatePlayerState() {}\n");
        String result = BoundedCodeSearch.search("updatePlayerState", workspace, "*.html", 10, Duration.ofSeconds(3));
        assertThat(result).contains("game.html:1:", "updatePlayerState", "char_offset=", "partial=false");
        assertThat(result.length()).isLessThan(1_000);
    }

    @Test void searchUsesLinearRegexAndSkipsSecretsOversizeFilesAndIgnoredTrees() throws Exception {
        Path dependency = Files.createDirectories(workspace.resolve("node_modules/module"));
        Files.writeString(dependency.resolve("vendor.js"), "credential-placeholder");
        Files.writeString(workspace.resolve(".env"), "credential-placeholder");
        Files.writeString(workspace.resolve("safe.js"), "a".repeat(100_000) + "!");
        try (var huge = new java.io.RandomAccessFile(workspace.resolve("huge.js").toFile(), "rw")) {
            huge.setLength(9L * 1024 * 1024);
        }
        long start = System.nanoTime();
        String result = BoundedCodeSearch.search("(a+)+$|credential-placeholder", workspace, null, 10, Duration.ofSeconds(3));
        assertThat(result).contains("partial=true", "matches=0").doesNotContain("vendor.js:", ".env:");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(ToolResult.fromLegacy(BoundedCodeSearch.search("(?<=x)y", workspace, null, 10, Duration.ofSeconds(3)))
                .errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
    }

    @Test void searchHasOutputBudgetAndCooperativeCancellation() throws Exception {
        Files.writeString(workspace.resolve("many.js"), "match ".repeat(30).concat("\n").repeat(100));
        String result = BoundedCodeSearch.search("match", workspace, "*.js", 100, Duration.ofSeconds(3));
        assertThat(result).contains("partial=true");
        assertThat(result.length()).isLessThan(4_000);
        try {
            Thread.currentThread().interrupt();
            assertThat(ToolResult.fromLegacy(BoundedCodeSearch.search("match", workspace, null, 10, Duration.ofSeconds(3)))
                    .errorCode()).isEqualTo(ToolErrorCode.CANCELLED);
        } finally { Thread.interrupted(); }
    }

    private ToolRegistry registry(BuiltinTools tools) {
        WorkspaceContext.setRootOverride(workspace);
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.setField(tools, "registry", registry);
        ReflectionTestUtils.invokeMethod(tools, "registerFileTools");
        ReflectionTestUtils.invokeMethod(tools, "registerSearchAndEditTools");
        return registry;
    }
    private ToolResult read(ToolRegistry registry, String path) throws Exception {
        return registry.executeResult("read_file", JSON.writeValueAsString(Map.of("path", "workspace/" + path)));
    }
    private ToolResult edit(ToolRegistry registry, String path, String before, String after) throws Exception {
        return registry.executeResult("edit_file", JSON.writeValueAsString(
                Map.of("path", "workspace/" + path, "oldString", before, "newString", after)));
    }
}
