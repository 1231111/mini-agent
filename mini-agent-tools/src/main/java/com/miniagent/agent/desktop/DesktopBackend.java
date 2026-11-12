package com.miniagent.agent.desktop;

import java.awt.image.BufferedImage;
import java.util.List;

/** Native primitives, deliberately independent of the planning/model loop. */
public interface DesktopBackend {
    record Window(String id, long processId, String title, int left, int top, int width, int height) {}
    record Capture(Window window, BufferedImage image) {}
    record Action(String action, Integer x, Integer y, String text, String key, Integer amount, String targetWindowId) {}

    boolean available();
    String unavailableReason();
    List<Window> windows();
    Window foreground();
    Capture capture(Window window);
    void activate(Window window);
    void execute(Window window, Action action) throws InterruptedException;
    void releaseInputs();
}
