package com.miniagent.agent.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class BrowserDirectoryCompatibilityTest {
    @TempDir Path root;

    @Test
    void expectedRevisionComesFromTheCurrentDependencyRatherThanAnyInstalledPrefix() {
        String headed = ReflectionTestUtils.invokeMethod(BrowserService.class, "expectedBrowserDirectory", false);
        String headless = ReflectionTestUtils.invokeMethod(BrowserService.class, "expectedBrowserDirectory", true);
        assertEquals("chromium-1217", headed);
        assertEquals("chromium_headless_shell-1217", headless);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void acceptsBothOfficialWindowsLayoutsAndRejectsIncompleteModernDownloads() throws Exception {
        Path modernShell = root.resolve("modern-shell");
        assertFalse(valid(modernShell, true));
        file(modernShell.resolve("chrome-headless-shell-win64/chrome-headless-shell.exe"), true);
        assertTrue(valid(modernShell, true));
        Path legacyShell = root.resolve("legacy-shell");
        file(legacyShell.resolve("chrome-win/headless_shell.exe"), true);
        assertTrue(valid(legacyShell, true));
        Path modernHeaded = root.resolve("modern-headed");
        file(modernHeaded.resolve("chrome-win64/chrome.exe"), false);
        assertFalse(valid(modernHeaded, false));
        file(modernHeaded.resolve("chrome-win64/chrome.dll"), true);
        assertTrue(valid(modernHeaded, false));
        Path legacyHeaded = root.resolve("legacy-headed");
        file(legacyHeaded.resolve("chrome-win/chrome.exe"), false);
        file(legacyHeaded.resolve("chrome-win/chrome.dll"), true);
        assertTrue(valid(legacyHeaded, false));
    }

    private boolean valid(Path path, boolean headless) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(BrowserService.class, "isBrowserDirValid", path, headless));
    }

    private void file(Path path, boolean fullModule) throws Exception {
        Files.createDirectories(path.getParent());
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            file.position(fullModule ? 10_000_001 : 10);
            file.write(ByteBuffer.wrap(new byte[]{1}));
        }
    }
}
