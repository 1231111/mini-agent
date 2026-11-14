package com.miniagent.agent.browser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Exact installer selection and bounded execution shared by every installation backend. */
final class BrowserInstallerSupport {
    private static final List<String> COMPONENTS = List.of("playwright", "driver", "driver-bundle");
    private BrowserInstallerSupport() { }

    static String resolveClasspath(String version, String classpath, List<Path> repositories) {
        List<String> resolved = new ArrayList<>();
        for (String artifact : COMPONENTS) {
            String filename = artifact + "-" + version + ".jar";
            Path found = null;
            if (classpath != null) {
                for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
                    try {
                        Path candidate = Path.of(entry);
                        if (filename.equals(String.valueOf(candidate.getFileName())) && Files.isRegularFile(candidate)) {
                            found = candidate; break;
                        }
                    } catch (InvalidPathException invalidEntry) { /* Ignore non-file classpath entries. */ }
                }
            }
            if (found == null) {
                for (Path repository : repositories) {
                    Path candidate = repository.resolve("com/microsoft/playwright").resolve(artifact).resolve(version).resolve(filename);
                    if (Files.isRegularFile(candidate)) { found = candidate; break; }
                }
            }
            if (found == null) return null;
            resolved.add(found.toAbsolutePath().normalize().toString());
        }
        return String.join(File.pathSeparator, resolved);
    }

    static List<String> npxCommand(String version, boolean headless, boolean windows) {
        List<String> command = new ArrayList<>(List.of(windows ? "npx.cmd" : "npx", "--yes",
                "playwright@" + version, "install", "chromium"));
        if (headless) command.add("--only-shell");
        return List.copyOf(command);
    }

    static boolean installationSucceeded(int exitCode, Path browserRoot, String expectedDirectory, Predicate<Path> validDirectory) {
        return exitCode == 0 && validDirectory.test(browserRoot.resolve(expectedDirectory));
    }

    /**
     * Drain output while waiting for exit, without ever waiting for a pipe's EOF.
     * An installer (or its child) can keep stdout open forever, so reading to EOF
     * before waitFor would prevent the process deadline from starting at all.
     */
    static int runInstaller(ProcessBuilder command, Duration timeout, Consumer<String> output)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(output, "output");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Installer timeout must be positive");
        checkInterrupted(); // Do not start another backend after cancellation.
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = command.redirectErrorStream(true).start();
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        try {
            process.getOutputStream().close(); // The installers are deliberately non-interactive.
            InputStream input = process.getInputStream();
            byte[] buffer = new byte[4096];
            while (true) {
                checkInterrupted();
                long remaining = remainingNanos(deadline);
                rememberDescendants(process, descendants);
                boolean drained = drainAvailable(input, buffer, deadline, output);
                if (!process.isAlive()) {
                    // Consume buffered trailing diagnostics, but not an inherited pipe's EOF.
                    while (drainAvailable(input, buffer, deadline, output)) { }
                    return process.exitValue();
                }
                if (!drained) process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(5)),
                        TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw cancelled;
        } finally {
            stopInstaller(process, descendants);
        }
    }

    private static boolean drainAvailable(InputStream input, byte[] buffer, long deadline, Consumer<String> output)
            throws IOException, InterruptedException, TimeoutException {
        int total = 0;
        int available;
        // A bounded batch also prevents a noisy child from starving deadline/tree checks.
        while (total < 64 * 1024 && (available = input.available()) > 0) {
            checkInterrupted();
            remainingNanos(deadline);
            int read = input.read(buffer, 0, Math.min(buffer.length, available));
            if (read < 0) break;
            total += read;
            output.accept(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return total > 0;
    }

    private static long remainingNanos(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("Browser installer exceeded its process deadline");
        return remaining;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Browser installer cancelled");
    }

    private static void rememberDescendants(Process process, Map<Long, ProcessHandle> descendants) {
        process.descendants().forEach(handle -> descendants.putIfAbsent(handle.pid(), handle));
    }

    private static void stopInstaller(Process process, Map<Long, ProcessHandle> descendants) {
        // Capture before killing the parent; afterward surviving children can be reparented.
        rememberDescendants(process, descendants);
        List<ProcessHandle> handles = new ArrayList<>(descendants.values());
        Collections.reverse(handles);
        handles.forEach(BrowserInstallerSupport::destroy);
        if (process.isAlive()) process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            // A short bounded reap also releases the OS pipe handles on Windows.
            while (process.isAlive() || handles.stream().anyMatch(ProcessHandle::isAlive)) {
                if (System.nanoTime() >= cleanupDeadline) break;
                try { TimeUnit.MILLISECONDS.sleep(10); }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
        } finally {
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void destroy(ProcessHandle handle) {
        if (handle.isAlive()) handle.destroyForcibly();
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try { stream.close(); }
        catch (IOException ignored) { }
    }
}
