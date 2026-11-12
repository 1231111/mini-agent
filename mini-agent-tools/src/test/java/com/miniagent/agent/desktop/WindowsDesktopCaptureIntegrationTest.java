package com.miniagent.agent.desktop;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native physical-coordinate oracle: no Swing/native width ratio is used.
 * Only paints a temporary decorated window belonging to this test JVM.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "miniagent.desktop.integration", matches = "true")
class WindowsDesktopCaptureIntegrationTest {
    private interface NativePaint extends StdCallLibrary {
        NativePaint USER32 = Native.load("user32", NativePaint.class);
        Pointer SetThreadDpiAwarenessContext(Pointer context);
        boolean ClientToScreen(HWND hwnd, POINT point);
        int GetDpiForWindow(HWND hwnd);
        int FillRect(HDC dc, RECT rectangle, HBRUSH brush);
    }
    private interface NativeBrush extends StdCallLibrary {
        NativeBrush GDI32 = Native.load("gdi32", NativeBrush.class);
        HBRUSH CreateSolidBrush(int color);
        boolean DeleteObject(HBRUSH brush);
        boolean GdiFlush();
    }
    private interface NativeComposition extends StdCallLibrary {
        NativeComposition DWMAPI = Native.load("dwmapi", NativeComposition.class);
        int DwmFlush();
        int DwmSetWindowAttribute(HWND hwnd, int attribute, IntByReference value, int size);
    }
    private static final int MARKER_RGB = 0xFF00FF;

    @ParameterizedTest(name = "physical marker at client ({0}, {1})")
    @CsvSource({"96,64,48", "280,140,48"})
    void capturedMarkerMatchesItsPhysicalScreenPositionIncludingWindowDecorations(int left, int top, int side)
            throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "run with -Djava.awt.headless=false");
        var backend = new WindowsDesktopBackend();
        JFrame fixture = new JFrame("MiniAgent physical capture fixture " + ProcessHandle.current().pid());
        Pointer previousDpi = NativePaint.USER32.SetThreadDpiAwarenessContext(Pointer.createConstant(-4));
        try {
            SwingUtilities.invokeAndWait(() -> {
                fixture.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                fixture.setContentPane(new JPanel());
                fixture.getContentPane().setBackground(new Color(0x204060));
                fixture.setBounds(90, 90, 450, 280);
                fixture.setAlwaysOnTop(true);
                fixture.addNotify();
                HWND hiddenHandle = new HWND(Native.getComponentPointer(fixture));
                // A first-show DWM transition can scale/translate the visible
                // pixels while GetWindowRect already reports their final bounds.
                assertEquals(0, NativeComposition.DWMAPI.DwmSetWindowAttribute(hiddenHandle,
                        3 /* DWMWA_TRANSITIONS_FORCEDISABLED */, new IntByReference(1), Integer.BYTES));
                fixture.setVisible(true);
                fixture.validate();
                ((JPanel) fixture.getContentPane()).paintImmediately(0, 0,
                        fixture.getContentPane().getWidth(), fixture.getContentPane().getHeight());
            });
            Toolkit.getDefaultToolkit().sync();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            // Flush queued Swing painting, then paint + compose + capture within
            // one EDT callback. A delayed WM_PAINT must not erase the native oracle
            // between drawing and capture; no coordinate tolerance is introduced.
            SwingUtilities.invokeAndWait(() -> {
                Pointer edtDpi = NativePaint.USER32.SetThreadDpiAwarenessContext(Pointer.createConstant(-4));
                try {
                    RepaintManager.currentManager(fixture).paintDirtyRegions();
                    fixture.setIgnoreRepaint(true);
                    fixture.getContentPane().setIgnoreRepaint(true);
                    var window = backend.windows().stream()
                            .filter(w -> w.processId() == ProcessHandle.current().pid() && w.title().equals(fixture.getTitle()))
                            .findFirst().orElseThrow();
                    HWND hwnd = new HWND(Pointer.createConstant(Long.parseUnsignedLong(window.id(), 16)));
                    backend.activate(window);
                    int dpi = NativePaint.USER32.GetDpiForWindow(hwnd);
                    assertTrue(dpi > 0);
                    int expectedDpi = Integer.getInteger("miniagent.desktop.expectedDpi", 0);
                    if (expectedDpi > 0) assertEquals(expectedDpi, dpi, "verify the actual display DPI rather than simulating scale");
                    POINT clientOrigin = new POINT();
                    assertTrue(NativePaint.USER32.ClientToScreen(hwnd, clientOrigin));
                    assertTrue(clientOrigin.y > window.top(), "the fixture must include a real title bar");
                    RECT client = new RECT();
                    assertTrue(User32.INSTANCE.GetClientRect(hwnd, client));
                    assertTrue(left + side < client.right && top + side < client.bottom,
                            "the physical marker must fit inside the fixture client area");
                    RECT marker = new RECT();
                    marker.left = left; marker.top = top; marker.right = left + side; marker.bottom = top + side;
                    HDC dc = User32.INSTANCE.GetDC(hwnd);
                    assertNotNull(dc);
                    HBRUSH brush = NativeBrush.GDI32.CreateSolidBrush(MARKER_RGB);
                    assertNotNull(brush);
                    try {
                        assertTrue(NativePaint.USER32.FillRect(dc, marker, brush) != 0);
                        assertTrue(NativeBrush.GDI32.GdiFlush());
                    } finally {
                        NativeBrush.GDI32.DeleteObject(brush);
                        User32.INSTANCE.ReleaseDC(hwnd, dc);
                    }
                    assertEquals(0, NativeComposition.DWMAPI.DwmFlush());
                    BufferedImage image = backend.capture(window).image();
                    int expectedX = clientOrigin.x + left - window.left();
                    int expectedY = clientOrigin.y + top - window.top();
                    Path evidence = evidenceDirectory();
                    Files.createDirectories(evidence);
                    String name = "capture-marker-" + left + "-" + top;
                    ImageIO.write(image, "png", evidence.resolve(name + ".png").toFile());
                    Files.writeString(evidence.resolve(name + ".txt"), "dpi=" + dpi + "\nwindow=" + window
                            + "\nclientOrigin=" + clientOrigin.x + "," + clientOrigin.y
                            + "\nexpectedMarker=" + expectedX + "," + expectedY + "," + side + "\n");
                    assertEquals(window.width(), image.getWidth());
                    assertEquals(window.height(), image.getHeight());
                    int[] observed = markerBounds(image);
                    assertArrayEquals(new int[]{expectedX, expectedY, expectedX + side - 1, expectedY + side - 1}, observed,
                            "DPI=" + dpi + "; screenshot pixels must map directly to WindowRect-relative input coordinates");
                    assertEquals(MARKER_RGB, image.getRGB(expectedX + side / 2, expectedY + side / 2) & 0xFFFFFF);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    if (edtDpi != null) NativePaint.USER32.SetThreadDpiAwarenessContext(edtDpi);
                }
            });
            if (failure.get() != null) {
                if (failure.get() instanceof Error error) throw error;
                if (failure.get() instanceof Exception error) throw error;
                throw new AssertionError(failure.get());
            }
        } finally {
            SwingUtilities.invokeAndWait(fixture::dispose);
            if (previousDpi != null) NativePaint.USER32.SetThreadDpiAwarenessContext(previousDpi);
        }
    }

    private static Path evidenceDirectory() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (cwd.getFileName().toString().equals("mini-agent-tools")) cwd = cwd.getParent();
        return cwd.resolve(".verify/live-control-2026-11-12/capture-coordinate-evidence");
    }

    private static int[] markerBounds(BufferedImage image) {
        int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == MARKER_RGB) {
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
            }
        }
        assertTrue(maxX >= 0, "native marker was not present in the captured image");
        return new int[]{minX, minY, maxX, maxY};
    }
}
