package com.miniagent.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.ChatMessageTexts;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ToolObservationProjectorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @AfterEach
    void clearWorkspace() { WorkspaceContext.clearAll(); }

    @Test
    void projectsActualPngBytesAndObservationIdentity() throws Exception {
        Path png = png(temporary.resolve("screen.png"), 4, 3);
        WorkspaceContext.setRootOverride(temporary);
        UserMessage projected = ToolObservationProjector.project(List.of(observation("browser_screenshot", png)));
        assertNotNull(projected);
        assertTrue(ToolObservationProjector.isObservation(projected));
        assertTrue(ChatMessageTexts.userPlain(projected).contains("observation=screen-1"));
        ImageContent image = projected.contents().stream().filter(ImageContent.class::isInstance)
                .map(ImageContent.class::cast).findFirst().orElseThrow();
        assertArrayEquals(Files.readAllBytes(png), Base64.getDecoder().decode(image.image().base64Data()));
        assertEquals("image/png", image.image().mimeType());
    }

    @Test
    void rejectsOutsideWorkspaceAndRelativePaths() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = png(temporary.resolve("private.png"), 2, 2);
        assertThrows(IOException.class, () -> ToolObservationProjector.readImage(descriptor(outside, "image/png"), root));
        assertThrows(IOException.class, () -> ToolObservationProjector.readImage(
                descriptor(Path.of("private.png"), "image/png"), root));
        Path traversal = root.resolve("../private.png");
        assertThrows(IOException.class, () -> ToolObservationProjector.readImage(descriptor(traversal, "image/png"), root));
    }

    @Test
    void rejectsSymlinkEscapeWhenPlatformPermitsSymlinks() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = png(temporary.resolve("private.png"), 2, 2);
        Path link = root.resolve("screen.png");
        try { Files.createSymbolicLink(link, outside); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            assumeTrue(false, "Symlink creation unavailable on this host");
        }
        assertThrows(IOException.class, () -> ToolObservationProjector.readImage(descriptor(link, "image/png"), root));
    }

    @Test
    void rejectsForgedMimeTruncatedDataAndHugeFiles() throws Exception {
        Path png = png(temporary.resolve("screen.png"), 2, 2);
        assertThrows(IOException.class, () -> ToolObservationProjector.readImage(descriptor(png, "image/jpeg"), temporary));
        Path text = Files.writeString(temporary.resolve("secret.png"), "not an image");
        assertThrows(IOException.class, () -> ToolObservationProjector.readImage(descriptor(text, "image/png"), temporary));
        Path truncated = Files.write(temporary.resolve("truncated.png"), java.util.Arrays.copyOf(Files.readAllBytes(png), 33));
        assertThrows(IOException.class, () -> ToolObservationProjector.readImage(descriptor(truncated, "image/png"), temporary));
        Path huge = temporary.resolve("huge.png");
        try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
            file.setLength(ToolObservationProjector.MAX_IMAGE_BYTES + 1);
        }
        assertThrows(IOException.class, () -> ToolObservationProjector.readImage(descriptor(huge, "image/png"), temporary));
    }

    @Test
    void rejectsExcessiveImageDimensionsBeforeDecoding() throws Exception {
        Path tooWide = png(temporary.resolve("wide.png"), 8193, 1);
        assertThrows(IOException.class, () -> ToolObservationProjector.readImage(descriptor(tooWide, "image/png"), temporary));
    }

    @Test
    void onlyAcceptsSuccessfulFirstPartyObservationToolsAndLimitsImages() throws Exception {
        WorkspaceContext.setRootOverride(temporary);
        Path png = png(temporary.resolve("screen.png"), 2, 2);
        var normal = observation("browser_screenshot", png);
        var failed = new ToolObservationProjector.Observation("desktop_observe", "failed",
                ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "failed", false, normal.result().rawText()));
        assertNull(ToolObservationProjector.project(List.of(failed)));
        assertNull(ToolObservationProjector.project(List.of(observation("web_extract", png))));
        UserMessage projected = ToolObservationProjector.project(List.of(normal, observation("desktop_observe", png),
                observation("desktop_act", png)));
        assertEquals(2, projected.contents().stream().filter(ImageContent.class::isInstance).count());
    }

    @Test
    void rejectedScreenshotIsExplicitlyReportedWithoutReadingItsContents() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        WorkspaceContext.setRootOverride(root);
        Path outside = Files.writeString(temporary.resolve("private.png"), "TOP_SECRET");
        UserMessage projected = ToolObservationProjector.project(List.of(observation("desktop_observe", outside)));
        assertNotNull(projected);
        assertFalse(ToolObservationProjector.hasImages(List.of(projected)));
        assertTrue(ChatMessageTexts.userPlain(projected).contains("not observed"));
        assertFalse(ChatMessageTexts.userPlain(projected).contains("TOP_SECRET"));
        assertFalse(ChatMessageTexts.userPlain(projected).contains(outside.toString()));
        assertTrue(ToolObservationProjector.projectBatch(List.of(observation("desktop_observe", outside))).allImagesRejected());
    }

    @Test
    void partialImageValidationCannotAuthorizeDesktopFromOnlyABrowserImage() throws Exception {
        WorkspaceContext.setRootOverride(temporary);
        Path invalid = Files.writeString(temporary.resolve("desktop.png"), "invalid");
        Path browser = png(temporary.resolve("browser.png"), 2, 2);
        var projection = ToolObservationProjector.projectBatch(List.of(observation("desktop_observe", invalid),
                observation("browser_screenshot", browser)));
        assertFalse(projection.allImagesRejected());
        assertTrue(projection.observedTools().contains("browser_screenshot"));
        assertFalse(projection.observedTools().contains("desktop_observe"));
    }

    @Test
    void historyStrippingIsIdempotentAndDoesNotTouchUserUploadedImages() throws Exception {
        WorkspaceContext.setRootOverride(temporary);
        UserMessage projected = ToolObservationProjector.project(List.of(observation("desktop_observe",
                png(temporary.resolve("screen.png"), 2, 2))));
        UserMessage uploaded = UserMessage.from(List.of(
                dev.langchain4j.data.message.TextContent.from(ToolObservationProjector.MARKER + " user content"),
                ImageContent.from("aGVsbG8=", "image/png")));
        List<ChatMessage> history = new ArrayList<>(List.of(uploaded, projected));
        ToolObservationProjector.stripImages(history);
        assertSame(uploaded, history.getFirst());
        assertFalse(ToolObservationProjector.hasImages(history));
        String stripped = ChatMessageTexts.userPlain((UserMessage) history.getLast());
        assertTrue(stripped.contains("Historical tool image omitted"));
        ToolObservationProjector.stripImages(history);
        assertEquals(stripped, ChatMessageTexts.userPlain((UserMessage) history.getLast()));
    }

    static Path png(Path path, int width, int height) throws IOException {
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        return path;
    }

    private static JsonNode descriptor(Path path, String mime) {
        return JSON.valueToTree(Map.of("path", path.toString(), "mimeType", mime));
    }

    private static ToolObservationProjector.Observation observation(String tool, Path image) throws Exception {
        return new ToolObservationProjector.Observation(tool, "call-1", ToolResult.success(JSON.writeValueAsString(
                Map.of("success", true, "observationId", "screen-1", "tool_images", List.of(
                        Map.of("path", image.toString(), "mimeType", "image/png"))))));
    }
}
