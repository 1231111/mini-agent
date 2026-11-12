package com.miniagent.agent.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in native test; only targets a temporary window owned by this test JVM. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "miniagent.desktop.integration", matches = "true")
class WindowsDesktopIntegrationTest {
    @TempDir Path root;
    @Test void capturesClicksTypesUnicodeAndVerifiesOnlyItsOwnWindow() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "run with -Djava.awt.headless=false");
        var backend = new WindowsDesktopBackend();
        var service = new DesktopInteractionService(backend, Clock.systemUTC(), true);
        JFrame frame = new JFrame("MiniAgent desktop test " + ProcessHandle.current().pid());
        JTextField field = new JTextField(20); JButton button = new JButton("Verify"); JLabel result = new JLabel("Ready");
        button.addActionListener(e -> result.setText(field.getText()));
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.setLayout(new FlowLayout()); frame.add(field); frame.add(button); frame.add(result);
                frame.setSize(640, 220); frame.setLocation(180, 160); frame.setAlwaysOnTop(true);
                frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); frame.setVisible(true); field.requestFocusInWindow();
            });
            var window = backend.windows().stream().filter(w -> w.processId() == ProcessHandle.current().pid()
                    && w.title().equals(frame.getTitle())).findFirst().orElseThrow();
            backend.activate(window); assertEquals(window.id(), backend.foreground().id());
            OwnerContext.setCurrentUser(999L); OwnerContext.setCurrentTenant("integration-fixture");
            WorkspaceContext.setSessionOverride("native-test"); WorkspaceContext.setRootOverride(root);
            String id = new ObjectMapper().readTree(service.observe().legacyText()).path("observationId").asText();
            assertFalse(id.isBlank());
            int[] position = relativeCenter(field, frame, window);
            var click = service.act(id, new DesktopBackend.Action("click", position[0], position[1], null, null, null, null));
            assertTrue(click.isSuccess(), click.legacyText());
            id = new ObjectMapper().readTree(click.legacyText()).path("observationId").asText();
            var typed = service.act(id, new DesktopBackend.Action("type", null, null, "MiniAgent 中文验证", null, null, null));
            assertTrue(typed.isSuccess(), typed.legacyText());
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals("MiniAgent 中文验证", field.getText());
            id = new ObjectMapper().readTree(typed.legacyText()).path("observationId").asText();
            position = relativeCenter(button, frame, window);
            var verify = service.act(id, new DesktopBackend.Action("click", position[0], position[1], null, null, null, null));
            assertTrue(verify.isSuccess(), verify.legacyText());
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals("MiniAgent 中文验证", result.getText());
            assertTrue(service.release().isSuccess());
        } finally {
            service.close(); SwingUtilities.invokeAndWait(frame::dispose);
            OwnerContext.clearCurrentUser(); OwnerContext.clearCurrentTenant();
            WorkspaceContext.clearRootOverride(); WorkspaceContext.clearSessionOverride();
        }
    }
    @Test void scrollTargetsTheObservedWindowEvenWhenPointerWasOverAnotherFixture() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        var backend = new WindowsDesktopBackend();
        var service = new DesktopInteractionService(backend, Clock.systemUTC(), true);
        JFrame target = new JFrame("MiniAgent scroll target " + ProcessHandle.current().pid());
        JFrame other = new JFrame("MiniAgent scroll other " + ProcessHandle.current().pid());
        JScrollPane targetScroll = new JScrollPane(new JTextArea("fixture line\n".repeat(200)));
        JScrollPane otherScroll = new JScrollPane(new JTextArea("other line\n".repeat(200)));
        try {
            SwingUtilities.invokeAndWait(() -> {
                target.add(targetScroll); other.add(otherScroll);
                target.setBounds(120, 120, 360, 300); other.setBounds(520, 120, 360, 300);
                target.setAlwaysOnTop(true); other.setAlwaysOnTop(true);
                target.setVisible(true); other.setVisible(true);
            });
            var window = ownedWindow(backend, target);
            // Drain initial activation of both Swing fixtures before requesting
            // a native focus switch; startup events must not steal it back.
            var background = ownedWindow(backend, other);
            awaitSwing(() -> backend.foreground().id().equals(background.id()));
            backend.activate(window);
            // This pointer movement intentionally targets only this test's second window.
            com.sun.jna.platform.win32.User32.INSTANCE.SetCursorPos(
                    background.left() + background.width() / 2L, background.top() + background.height() / 2L);
            bindFixture();
            var observed = service.observe(); assertTrue(observed.isSuccess(), observed.legacyText());
            String id = new ObjectMapper().readTree(observed.legacyText()).path("observationId").asText();
            var scrolled = service.act(id, new DesktopBackend.Action("scroll", null, null, null, null, 3, null));
            assertTrue(scrolled.isSuccess(), scrolled.legacyText());
            awaitSwing(() -> targetScroll.getVerticalScrollBar().getValue() > 0);
            SwingUtilities.invokeAndWait(() -> assertEquals(0, otherScroll.getVerticalScrollBar().getValue()));
        } finally {
            service.close(); SwingUtilities.invokeAndWait(() -> { target.dispose(); other.dispose(); }); clearFixture();
        }
    }
    @Test void refusesClicksInterceptedByAnotherFixtureWindow() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        var backend = new WindowsDesktopBackend();
        var service = new DesktopInteractionService(backend, Clock.systemUTC(), true);
        JFrame frame = new JFrame("MiniAgent covered target " + ProcessHandle.current().pid());
        JWindow cover = new JWindow(frame);
        JButton button = new JButton("Should not click"); AtomicInteger clicks = new AtomicInteger();
        button.addActionListener(e -> clicks.incrementAndGet());
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.add(button); frame.setBounds(140, 140, 420, 240); frame.setAlwaysOnTop(true); frame.setVisible(true);
                cover.setFocusableWindowState(false); cover.add(new JLabel("Fixture obstruction"));
                cover.setBounds(200, 200, 260, 100); cover.setAlwaysOnTop(true); cover.setVisible(true);
            });
            var window = ownedWindow(backend, frame); backend.activate(window); bindFixture();
            var observed = service.observe(); assertTrue(observed.isSuccess(), observed.legacyText());
            String id = new ObjectMapper().readTree(observed.legacyText()).path("observationId").asText();
            int[] position = relativeCenter(button, frame, window);
            var clicked = service.act(id, new DesktopBackend.Action("click", position[0], position[1], null, null, null, null));
            assertEquals(com.miniagent.agent.tool.ToolStatus.UNKNOWN, clicked.status(), clicked.legacyText());
            SwingUtilities.invokeAndWait(() -> assertEquals(0, clicks.get()));
        } finally {
            service.close(); SwingUtilities.invokeAndWait(() -> { cover.dispose(); frame.dispose(); }); clearFixture();
        }
    }
    private static DesktopBackend.Window ownedWindow(WindowsDesktopBackend backend, JFrame frame) {
        return backend.windows().stream().filter(w -> w.processId() == ProcessHandle.current().pid()
                && w.title().equals(frame.getTitle())).findFirst().orElseThrow();
    }
    private void bindFixture() {
        OwnerContext.setCurrentUser(999L); OwnerContext.setCurrentTenant("integration-fixture");
        WorkspaceContext.setSessionOverride("native-test"); WorkspaceContext.setRootOverride(root);
    }
    private static void clearFixture() {
        OwnerContext.clearCurrentUser(); OwnerContext.clearCurrentTenant();
        WorkspaceContext.clearRootOverride(); WorkspaceContext.clearSessionOverride();
    }
    private static void awaitSwing(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            boolean[] passed = new boolean[1]; SwingUtilities.invokeAndWait(() -> passed[0] = condition.getAsBoolean());
            if (passed[0]) return;
            Thread.sleep(10);
        }
        fail("Native input was not reflected in the fixture UI");
    }
    private static int[] relativeCenter(Component child, JFrame frame, DesktopBackend.Window nativeWindow) throws Exception {
        int[] result = new int[2];
        SwingUtilities.invokeAndWait(() -> {
            Point point = child.getLocationOnScreen(); Point origin = frame.getLocationOnScreen();
            double sx = (double) nativeWindow.width() / frame.getWidth(), sy = (double) nativeWindow.height() / frame.getHeight();
            result[0] = (int) Math.round((point.x - origin.x + child.getWidth() / 2.0) * sx);
            result[1] = (int) Math.round((point.y - origin.y + child.getHeight() / 2.0) * sy);
        });
        return result;
    }
}
