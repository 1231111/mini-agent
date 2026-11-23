package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceImageInspectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void textReaderIdentifiesPngAndImageInspectorReturnsValidatedVisualEvidence() throws Exception {
        BufferedImage bitmap = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        Path screenshot = workspace.resolve("page.png");
        assertThat(ImageIO.write(bitmap, "png", screenshot.toFile())).isTrue();
        WorkspaceContext.setRootOverride(workspace);
        ToolRegistry registry = newFileRegistry();
        try {
            ToolResult textRead = registry.executeResult("read_file", "{\"path\":\"page.png\"}");
            assertThat(textRead.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
            assertThat(textRead.message()).contains("PNG", "image_inspect");

            ToolResult visualRead = registry.executeResult("image_inspect", "{\"path\":\"page.png\"}");
            assertThat(visualRead.isSuccess()).isTrue();
            JsonNode descriptor = visualRead.data().path("tool_images").get(0);
            assertThat(descriptor.path("mimeType").asText()).isEqualTo("image/png");
            assertThat(descriptor.path("path").asText()).isEqualTo(screenshot.toRealPath().toString());
            assertThat(visualRead.data().path("width").asInt()).isEqualTo(3);
            assertThat(visualRead.data().path("height").asInt()).isEqualTo(2);
        } finally {
            WorkspaceContext.clearAll();
        }
    }

    @Test
    void malformedUtf8IsAClassifiedTextFormatErrorAndValidTextStillReads() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "hello 世界\nsecond line\n");
        Files.writeString(workspace.resolve("payload.json"), "{\"error\":\"this is document data\"}\n");
        Files.write(workspace.resolve("broken.txt"), new byte[]{(byte) 0xc3, 0x28});
        WorkspaceContext.setRootOverride(workspace);
        ToolRegistry registry = newFileRegistry();
        try {
            ToolResult valid = registry.executeResult("read_file", "{\"path\":\"notes.txt\"}");
            assertThat(valid.isSuccess()).isTrue();
            assertThat(valid.legacyText()).contains("hello 世界", "partial=false");

            ToolResult jsonText = registry.executeResult("read_file", "{\"path\":\"payload.json\"}");
            assertThat(jsonText.isSuccess()).isTrue();
            assertThat(jsonText.legacyText()).contains("this is document data");

            ToolResult malformed = registry.executeResult("read_file", "{\"path\":\"broken.txt\"}");
            assertThat(malformed.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
            assertThat(malformed.message()).contains("UTF-8", "image_inspect");
            assertThat(malformed.message()).doesNotContain("Input length = 1");
        } finally {
            WorkspaceContext.clearAll();
        }
    }

    @Test
    void imageInspectorRejectsWorkspaceEscapeAndNonImages() throws Exception {
        Path outside = workspace.getParent().resolve("outside.png");
        Files.write(outside, new byte[]{1, 2, 3, 4});
        WorkspaceContext.setRootOverride(workspace);
        ToolRegistry registry = newFileRegistry();
        try {
            ToolResult outsideResult = registry.executeResult("image_inspect",
                    JSON.writeValueAsString(Map.of("path", outside.toString())));
            assertThat(outsideResult.isSuccess()).isFalse();
            assertThat(outsideResult.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);

            Files.write(workspace.resolve("fake.png"), new byte[]{1, 2, 3, 4});
            ToolResult fake = registry.executeResult("image_inspect", "{\"path\":\"fake.png\"}");
            assertThat(fake.isSuccess()).isFalse();
            assertThat(fake.message()).contains("PNG/JPEG");
        } finally {
            WorkspaceContext.clearAll();
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void imageInspectionUsesReadOnlyIdempotentPathScopedExecutionContract() {
        assertThat(ToolConcurrencyPolicy.isReadOnly("image_inspect")).isTrue();
        assertThat(ToolConcurrencyPolicy.isIdempotent("image_inspect")).isTrue();
        assertThat(ToolConcurrencyPolicy.isStreamPrefetchSafe("image_inspect")).isTrue();
        assertThat(ToolConcurrencyPolicy.profileOf("image_inspect").concurrencyKeyArgument()).isEqualTo("path");
    }

    private static ToolRegistry newFileRegistry() {
        ToolRegistry registry = new ToolRegistry();
        BuiltinTools tools = new BuiltinTools();
        ReflectionTestUtils.setField(tools, "registry", registry);
        ReflectionTestUtils.invokeMethod(tools, "registerFileTools");
        return registry;
    }
}
