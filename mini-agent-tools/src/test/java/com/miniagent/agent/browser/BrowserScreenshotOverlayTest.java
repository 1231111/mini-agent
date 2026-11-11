package com.miniagent.agent.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserScreenshotOverlayTest {

    @TempDir
    Path workspace;

    @Test
    void addsBluePointerSpotlightAtTheLastClickPosition() throws IOException {
        BufferedImage source = new BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB);
        source.createGraphics().dispose();
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) source.setRGB(x, y, Color.WHITE.getRGB());
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);

        byte[] highlighted = BrowserService.applyPointerSpotlight(encoded.toByteArray(), 120, 70, 240, 160);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(highlighted));

        assertEquals(240, result.getWidth());
        assertEquals(160, result.getHeight());
        assertNotEquals(Color.WHITE.getRGB(), result.getRGB(142, 70), "the halo should be visible around the cursor");
        assertEquals(Color.WHITE.getRGB(), result.getRGB(5, 5), "the rest of the screenshot should remain unchanged");
    }

    @Test
    void keepsAnUnrecognizedImageUntouched() throws IOException {
        byte[] invalidImage = "not a png".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertSame(invalidImage, BrowserService.applyPointerSpotlight(invalidImage, 5, 5, 100, 100));
    }

    @Test
    void screenshotPathStaysInWorkspaceAndUsesASafeSessionId() throws IOException {
        Path target = BrowserService.screenshotOutputPath(workspace, "../../outside");

        assertTrue(target.startsWith(workspace.toAbsolutePath().normalize()));
        assertEquals("screenshot_.._.._outside.png", target.getFileName().toString());
    }
}
