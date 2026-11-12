package com.miniagent.agent.desktop;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.image.BufferedImage;
import java.util.*;

/** Windows 10+ visible-window capture and SendInput. No shell commands or clipboard mutation. */
public final class WindowsDesktopBackend implements DesktopBackend {
    private interface DpiUser32 extends StdCallLibrary {
        DpiUser32 INSTANCE = Native.load("user32", DpiUser32.class);
        Pointer SetThreadDpiAwarenessContext(Pointer context);
        HWND WindowFromPoint(POINT.ByValue point);
    }
    @FunctionalInterface interface InputSender { int send(WinUser.INPUT[] inputs); }
    private final InputSender inputSender;
    private final Set<Integer> pressedKeys = new LinkedHashSet<>();
    private int pressedMouse;
    private Character pendingUnicode;

    public WindowsDesktopBackend() {
        this(inputs -> User32.INSTANCE.SendInput(new DWORD(inputs.length), inputs, inputs[0].size()).intValue());
    }
    WindowsDesktopBackend(InputSender inputSender) { this.inputSender = Objects.requireNonNull(inputSender); }

    @Override public boolean available() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
    @Override public String unavailableReason() {
        return "此适配器需要 Windows 10+ 已登录的交互桌面；macOS 原生适配器尚未启用。";
    }
    private static void physicalPixels() {
        DpiUser32.INSTANCE.SetThreadDpiAwarenessContext(Pointer.createConstant(-4));
    }
    @Override public List<Window> windows() {
        physicalPixels();
        List<Window> result = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hwnd, ignored) -> {
            if (result.size() >= 80) return false;
            if (User32.INSTANCE.IsWindowVisible(hwnd) && User32.INSTANCE.GetWindowTextLength(hwnd) > 0) {
                try {
                    Window window = describe(hwnd);
                    if (window.width() > 1 && window.height() > 1) result.add(window);
                } catch (IllegalStateException closedDuringEnumeration) {
                    // A transient window closing must not invalidate an unrelated target.
                }
            }
            return true;
        }, null);
        return List.copyOf(result);
    }
    @Override public Window foreground() {
        physicalPixels();
        HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        if (hwnd == null) throw new IllegalStateException("没有可观察的前台窗口（桌面可能已锁定）");
        return describe(hwnd);
    }
    private static HWND handle(String id) {
        try { return new HWND(Pointer.createConstant(Long.parseUnsignedLong(id, 16))); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("无效窗口句柄"); }
    }
    private static Window describe(HWND hwnd) {
        RECT bounds = new RECT();
        if (!User32.INSTANCE.IsWindow(hwnd) || !User32.INSTANCE.GetWindowRect(hwnd, bounds))
            throw new IllegalStateException("目标窗口已关闭");
        char[] title = new char[1024];
        User32.INSTANCE.GetWindowText(hwnd, title, title.length);
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        return new Window(Long.toUnsignedString(Pointer.nativeValue(hwnd.getPointer()), 16),
                Integer.toUnsignedLong(pid.getValue()), Native.toString(title), bounds.left, bounds.top,
                bounds.right - bounds.left, bounds.bottom - bounds.top);
    }
    @Override public Capture capture(Window window) {
        physicalPixels();
        Window current = describe(handle(window.id()));
        if (!sameTarget(window, current)) throw new IllegalStateException("目标窗口或其位置已改变，请重新观察");
        if (current.width() <= 0 || current.height() <= 0 || (long) current.width() * current.height() > 16_000_000)
            throw new IllegalStateException("窗口尺寸不支持，请调整窗口后重新观察");
        // GetDC(hwnd) starts at the client area, while actions are relative to
        // GetWindowRect. Capture the visible screen rectangle so screenshot (0,0)
        // and action (0,0) have the same physical origin, including the title bar.
        BufferedImage image = captureScreenRectangle(current);
        if (!sameTarget(current, describe(handle(window.id()))))
            throw new IllegalStateException("截图期间目标窗口位置已改变，请重新观察");
        return new Capture(current, image);
    }
    private static BufferedImage captureScreenRectangle(Window window) {
        HDC screen = User32.INSTANCE.GetDC(null);
        if (screen == null) throw new IllegalStateException("无法获取交互桌面截图");
        HDC memory = null;
        HBITMAP bitmap = null;
        HANDLE previous = null;
        try {
            memory = GDI32.INSTANCE.CreateCompatibleDC(screen);
            if (memory == null) throw new IllegalStateException("无法创建截图设备上下文");
            bitmap = GDI32.INSTANCE.CreateCompatibleBitmap(screen, window.width(), window.height());
            if (bitmap == null) throw new IllegalStateException("无法创建截图位图");
            previous = GDI32.INSTANCE.SelectObject(memory, bitmap);
            if (previous == null || Pointer.nativeValue(previous.getPointer()) == -1L)
                throw new IllegalStateException("无法选择截图位图");
            if (!GDI32.INSTANCE.BitBlt(memory, 0, 0, window.width(), window.height(), screen,
                    window.left(), window.top(), 0x00CC0020 | 0x40000000 /* SRCCOPY | CAPTUREBLT */))
                throw new IllegalStateException("复制交互桌面截图失败");
            // GetDIBits requires the bitmap to be deselected from the memory DC.
            GDI32.INSTANCE.SelectObject(memory, previous);
            previous = null;
            WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
            info.bmiHeader.biWidth = window.width();
            info.bmiHeader.biHeight = -window.height(); // top-down, matching input coordinates
            info.bmiHeader.biPlanes = 1;
            info.bmiHeader.biBitCount = 32;
            info.bmiHeader.biCompression = WinGDI.BI_RGB;
            int pixels = Math.multiplyExact(window.width(), window.height());
            try (Memory buffer = new Memory((long) pixels * 4)) {
                if (GDI32.INSTANCE.GetDIBits(screen, bitmap, 0, window.height(), buffer, info,
                        WinGDI.DIB_RGB_COLORS) != window.height())
                    throw new IllegalStateException("读取交互桌面截图像素失败");
                BufferedImage image = new BufferedImage(window.width(), window.height(), BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, window.width(), window.height(), buffer.getIntArray(0, pixels), 0, window.width());
                return image;
            }
        } finally {
            if (previous != null && memory != null) GDI32.INSTANCE.SelectObject(memory, previous);
            if (bitmap != null) GDI32.INSTANCE.DeleteObject(bitmap);
            if (memory != null) GDI32.INSTANCE.DeleteDC(memory);
            User32.INSTANCE.ReleaseDC(null, screen);
        }
    }
    static boolean sameTarget(Window expected, Window actual) {
        return expected.id().equals(actual.id()) && expected.processId() == actual.processId()
                && expected.left() == actual.left() && expected.top() == actual.top()
                && expected.width() == actual.width() && expected.height() == actual.height();
    }
    @Override public void activate(Window window) {
        physicalPixels();
        if (!sameTarget(window, describe(handle(window.id()))))
            throw new IllegalStateException("目标窗口已改变，请重新观察");
        if (!foreground().id().equals(window.id())) User32.INSTANCE.SetForegroundWindow(handle(window.id()));
        // Focus notifications are asynchronous across UI threads. Wait briefly
        // for this one request; never use synthetic Alt/AttachThreadInput bypasses.
        long deadline = System.nanoTime() + 300_000_000L;
        while (!foreground().id().equals(window.id()) && System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("切换窗口已取消");
            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("切换窗口已取消", e); }
        }
        if (!foreground().id().equals(window.id()))
            throw new IllegalStateException("无法取得目标窗口焦点，请手动切换到该窗口后重新观察");
    }
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("桌面操作已中断");
    }
    private void checkTarget(Window window) throws InterruptedException {
        checkInterrupted();
        if (!sameTarget(window, foreground())) throw new IllegalStateException("焦点或窗口位置改变，已停止输入");
    }
    @Override public synchronized void execute(Window window, Action action) throws InterruptedException {
        physicalPixels();
        checkTarget(window);
        try {
            switch (action.action()) {
                case "click", "double_click", "right_click" -> {
                    positionPointer(window, action.x(), action.y());
                    int down = "right_click".equals(action.action()) ? 0x0008 : 0x0002;
                    int up = "right_click".equals(action.action()) ? 0x0010 : 0x0004;
                    for (int i = 0; i < ("double_click".equals(action.action()) ? 2 : 1); i++) {
                        checkTarget(window);
                        pressedMouse = up;
                        sendMouse(down, 0);
                        sendMouse(up, 0);
                        pressedMouse = 0;
                    }
                }
                case "type" -> {
                    for (char character : action.text().toCharArray()) {
                        checkTarget(window);
                        // Both events in one native call prevent a stranded Unicode key on interruption.
                        sendUnicode(character);
                    }
                }
                case "key" -> {
                    for (String key : action.key().split("\\+")) {
                        checkTarget(window);
                        int vk = keyCode(key);
                        pressedKeys.add(vk);
                        sendKey(vk, false);
                    }
                }
                case "scroll" -> {
                    // Windows may scroll the hovered background window. Bind the
                    // pointer as well as keyboard focus to the approved target.
                    positionPointer(window, action.x() == null ? window.width() / 2 : action.x(),
                            action.y() == null ? window.height() / 2 : action.y());
                    checkTarget(window);
                    sendMouse(0x0800, action.amount() * -120);
                }
                case "focus" -> { /* activate() already performed the approved focus action. */ }
                default -> throw new IllegalArgumentException("不支持的桌面动作");
            }
        } finally { releaseInputs(); }
    }
    private void positionPointer(Window window, int x, int y) throws InterruptedException {
        checkTarget(window);
        int screenX = Math.toIntExact((long) window.left() + x);
        int screenY = Math.toIntExact((long) window.top() + y);
        if (!User32.INSTANCE.SetCursorPos(screenX, screenY)) throw new IllegalStateException("鼠标定位失败");
        POINT.ByValue point = new POINT.ByValue(); point.x = screenX; point.y = screenY;
        HWND hit = DpiUser32.INSTANCE.WindowFromPoint(point);
        HWND root = hit == null ? null : User32.INSTANCE.GetAncestor(hit, 2 /* GA_ROOT */);
        if (root == null || !root.equals(handle(window.id())))
            throw new IllegalStateException("目标位置被其他窗口遮挡，已停止输入，请重新观察");
        checkTarget(window);
    }
    static int keyCode(String name) {
        String key = name.trim().toUpperCase(Locale.ROOT);
        if (key.length() == 1 && ((key.charAt(0) >= 'A' && key.charAt(0) <= 'Z')
                || (key.charAt(0) >= '0' && key.charAt(0) <= '9'))) return key.charAt(0);
        return switch (key) {
            case "CTRL", "CONTROL" -> 0x11; case "SHIFT" -> 0x10; case "ALT" -> 0x12;
            case "ENTER", "RETURN" -> 0x0D; case "TAB" -> 0x09; case "ESC", "ESCAPE" -> 0x1B;
            case "BACKSPACE" -> 0x08; case "DELETE" -> 0x2E; case "SPACE" -> 0x20;
            case "LEFT" -> 0x25; case "UP" -> 0x26; case "RIGHT" -> 0x27; case "DOWN" -> 0x28;
            case "HOME" -> 0x24; case "END" -> 0x23; case "PAGEUP" -> 0x21; case "PAGEDOWN" -> 0x22;
            default -> throw new IllegalArgumentException("不支持的按键: " + name);
        };
    }
    private static WinUser.INPUT keyboard(int vk, int scan, int flags) {
        var input = new WinUser.INPUT();
        input.type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        input.input.setType(WinUser.KEYBDINPUT.class);
        input.input.ki = new WinUser.KEYBDINPUT();
        input.input.ki.wVk = new WORD(vk); input.input.ki.wScan = new WORD(scan);
        input.input.ki.dwFlags = new DWORD(flags); input.input.ki.time = new DWORD(0);
        input.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        return input;
    }
    synchronized void sendUnicode(char character) {
        WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(2);
        inputs[0].type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        inputs[0].input.setType(WinUser.KEYBDINPUT.class);
        inputs[0].input.ki = keyboard(0, character, 0x0004).input.ki;
        inputs[1].type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        inputs[1].input.setType(WinUser.KEYBDINPUT.class);
        inputs[1].input.ki = keyboard(0, character, 0x0004 | 0x0002).input.ki;
        pendingUnicode = character;
        send(inputs);
        pendingUnicode = null;
    }
    private void sendKey(int vk, boolean up) { send(new WinUser.INPUT[]{ keyboard(vk, 0,
            (up ? 0x0002 : 0) | (vk >= 0x21 && vk <= 0x2e ? 0x0001 : 0)) }); }
    private void sendMouse(int flags, int data) {
        var input = new WinUser.INPUT(); input.type = new DWORD(WinUser.INPUT.INPUT_MOUSE);
        input.input.setType(WinUser.MOUSEINPUT.class); input.input.mi = new WinUser.MOUSEINPUT();
        input.input.mi.dx = new LONG(0); input.input.mi.dy = new LONG(0);
        input.input.mi.mouseData = new DWORD(data); input.input.mi.dwFlags = new DWORD(flags);
        input.input.mi.time = new DWORD(0); input.input.mi.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        send(new WinUser.INPUT[]{input});
    }
    private void send(WinUser.INPUT[] inputs) {
        if (inputSender.send(inputs) != inputs.length) throw new IllegalStateException(
                "Windows未确认全部输入事件（可能被UAC或桌面权限拦截），请先核验界面");
    }
    @Override public synchronized void releaseInputs() {
        RuntimeException failure = null;
        if (pendingUnicode != null) {
            try { send(new WinUser.INPUT[]{keyboard(0, pendingUnicode, 0x0004 | 0x0002)}); pendingUnicode = null; }
            catch (RuntimeException e) { failure = e; }
        }
        List<Integer> keys = new ArrayList<>(pressedKeys); Collections.reverse(keys);
        for (int key : keys) {
            try { sendKey(key, true); pressedKeys.remove(key); }
            catch (RuntimeException e) { failure = e; }
        }
        if (pressedMouse != 0) {
            try { sendMouse(pressedMouse, 0); pressedMouse = 0; }
            catch (RuntimeException e) { failure = e; }
        }
        if (failure != null) throw failure;
    }
}
