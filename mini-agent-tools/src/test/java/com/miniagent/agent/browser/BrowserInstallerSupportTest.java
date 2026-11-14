package com.miniagent.agent.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowserInstallerSupportTest {
    private static final String VERSION = "1.59.0";
    @TempDir Path root;

    @Test
    void resolvesAllThreeExactComponentsWithoutConfusingDriverBundleForPlaywright() throws Exception {
        Path space = root.resolve("path with spaces"); Files.createDirectories(space);
        Path bundle = Files.createFile(space.resolve("driver-bundle-" + VERSION + ".jar"));
        Path playwright = Files.createFile(space.resolve("playwright-" + VERSION + ".jar"));
        Path driver = Files.createFile(space.resolve("driver-" + VERSION + ".jar"));
        String input = String.join(File.pathSeparator, bundle.toString(), driver.toString(), playwright.toString());
        String resolved = BrowserInstallerSupport.resolveClasspath(VERSION, input, List.of());
        assertEquals(String.join(File.pathSeparator, playwright.toString(), driver.toString(), bundle.toString()), resolved);
    }

    @Test
    void refusesMissingOrMixedVersionComponents() throws Exception {
        Path playwright = Files.createFile(root.resolve("playwright-" + VERSION + ".jar"));
        Path oldDriver = Files.createFile(root.resolve("driver-1.49.0.jar"));
        Path bundle = Files.createFile(root.resolve("driver-bundle-" + VERSION + ".jar"));
        assertNull(BrowserInstallerSupport.resolveClasspath(VERSION, playwright.toString(), List.of()));
        assertNull(BrowserInstallerSupport.resolveClasspath(VERSION,
                String.join(File.pathSeparator, playwright.toString(), oldDriver.toString(), bundle.toString()), List.of()));
    }

    @Test
    void repositoryFallbackMustAlsoContainAllExactComponents() throws Exception {
        Path repository = root.resolve("m2");
        for (String artifact : List.of("playwright", "driver", "driver-bundle")) {
            Path jar = repository.resolve("com/microsoft/playwright").resolve(artifact).resolve(VERSION).resolve(artifact + "-" + VERSION + ".jar");
            Files.createDirectories(jar.getParent()); Files.createFile(jar);
        }
        String resolved = BrowserInstallerSupport.resolveClasspath(VERSION, "", List.of(repository));
        assertNotNull(resolved); assertTrue(resolved.contains("driver-bundle-" + VERSION));
        Files.delete(repository.resolve("com/microsoft/playwright/driver").resolve(VERSION).resolve("driver-" + VERSION + ".jar"));
        assertNull(BrowserInstallerSupport.resolveClasspath(VERSION, "", List.of(repository)));
    }

    @Test
    void npxPinsTheSameVersionAndOnlyInstallsTheRequestedMode() {
        assertEquals(List.of("npx.cmd", "--yes", "playwright@1.59.0", "install", "chromium", "--only-shell"),
                BrowserInstallerSupport.npxCommand(VERSION, true, true));
        assertEquals(List.of("npx", "--yes", "playwright@1.59.0", "install", "chromium"),
                BrowserInstallerSupport.npxCommand(VERSION, false, false));
    }

    @Test
    void zeroExitCannotClaimSuccessForAnotherRevisionOrAnIncompleteDownload() throws Exception {
        String expected = "chromium_headless_shell-1217";
        Files.createDirectories(root.resolve("chromium_headless_shell-1148"));
        assertFalse(BrowserInstallerSupport.installationSucceeded(0, root, expected, Files::isDirectory));
        Files.createDirectories(root.resolve(expected));
        assertFalse(BrowserInstallerSupport.installationSucceeded(0, root, expected, path -> Files.exists(path.resolve("complete"))));
        Files.createFile(root.resolve(expected).resolve("complete"));
        assertTrue(BrowserInstallerSupport.installationSucceeded(0, root, expected, path -> Files.exists(path.resolve("complete"))));
        assertFalse(BrowserInstallerSupport.installationSucceeded(1, root, expected, path -> true));
    }
}
