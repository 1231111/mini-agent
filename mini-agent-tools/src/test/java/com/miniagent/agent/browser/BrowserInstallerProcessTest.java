package com.miniagent.agent.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real local child processes; no browser downloads, network, shells or long production deadlines. */
class BrowserInstallerProcessTest {
    private static final Pattern PID = Pattern.compile("pid:(\\d+)");

    @Test
    void hangingProcessCannotPostponeItsDeadlineByKeepingStdoutOpen() throws Exception {
        ProcessBuilder command = fixture("silent");
        StringBuilder output = new StringBuilder();
        long started = System.nanoTime();
        assertThrows(TimeoutException.class, () -> BrowserInstallerSupport.runInstaller(
                command, Duration.ofSeconds(2), output::append));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(7)) < 0);
        assertStopped(output.toString(), 1);
    }

    @Test
    void drainsLargeStdoutAndStderrWithoutFillingThePipeAndPreservesNonzeroExit() throws Exception {
        StringBuilder output = new StringBuilder();
        int code = BrowserInstallerSupport.runInstaller(fixture("noisy"), Duration.ofSeconds(15), output::append);
        assertEquals(7, code);
        assertTrue(output.length() >= 2 * 1024 * 1024, "all output must be drained without buffering in production");
        assertTrue(output.toString().endsWith("stderr-finished\n"), "trailing stderr must also be collected");
        assertStopped(output.toString(), 1);
    }

    @Test
    void deadlineTerminatesTheInstallersChildAndGrandchildToo() throws Exception {
        StringBuilder output = new StringBuilder();
        assertThrows(TimeoutException.class, () -> BrowserInstallerSupport.runInstaller(
                fixture("tree"), Duration.ofSeconds(2), output::append));
        assertStopped(output.toString(), 3);
    }

    @Test
    void interruptionCleansTheTreeAndPreservesTheCallersInterruptFlag() throws Exception {
        CountDownLatch treeReady = new CountDownLatch(1);
        StringBuffer output = new StringBuffer();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                BrowserInstallerSupport.runInstaller(fixture("tree"), Duration.ofSeconds(30), chunk -> {
                    output.append(chunk);
                    if (pids(output.toString()).size() == 3) treeReady.countDown();
                });
            } catch (Throwable caught) {
                error.set(caught);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertTrue(treeReady.await(10, TimeUnit.SECONDS), output.toString());
            caller.interrupt();
            caller.join(5_000);
            assertFalse(caller.isAlive(), "interruption must release the installer caller");
            assertInstanceOf(InterruptedException.class, error.get());
            assertTrue(interrupted.get(), "a fallback must see the cancellation flag");
            assertStopped(output.toString(), 3);
        } finally {
            caller.interrupt();
            caller.join(5_000);
        }
    }

    @Test
    void preInterruptedCallerCannotStartAnotherInstaller() {
        ProcessBuilder neverStarted = mock(ProcessBuilder.class);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> BrowserInstallerSupport.runInstaller(
                    neverStarted, Duration.ofSeconds(2), ignored -> { }));
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(neverStarted);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void completedParentDoesNotWaitForEofHeldOpenByItsChild() throws Exception {
        StringBuilder output = new StringBuilder();
        assertEquals(0, BrowserInstallerSupport.runInstaller(
                fixture("inherited-pipe"), Duration.ofSeconds(5), output::append));
        assertStopped(output.toString(), 2);
    }

    private static ProcessBuilder fixture(String mode) throws Exception {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classes = Path.of(InstallerFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new ProcessBuilder(java, "-cp", classes, InstallerFixture.class.getName(), mode);
    }

    private static List<Long> pids(String output) {
        return PID.matcher(output).results().map(match -> Long.parseLong(match.group(1))).distinct().toList();
    }

    private static void assertStopped(String output, int expectedProcesses) {
        List<Long> ids = pids(output);
        assertEquals(expectedProcesses, ids.size(), output);
        for (Long id : ids) assertFalse(ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false),
                () -> "installer process still alive: " + id + "\n" + output);
    }

    public static class InstallerFixture {
        public static void main(String[] args) throws Exception {
            System.out.println("pid:" + ProcessHandle.current().pid());
            System.out.flush();
            switch (args[0]) {
                case "noisy" -> {
                    byte[] block = new byte[8192];
                    Arrays.fill(block, (byte) 'x');
                    for (int i = 0; i < 128; i++) {
                        System.out.write(block);
                        System.err.write(block);
                    }
                    System.err.print("stderr-finished\n");
                    System.err.flush();
                    System.exit(7);
                }
                case "tree", "branch", "inherited-pipe" -> {
                    String childMode = "tree".equals(args[0]) ? "branch" : "silent";
                    String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
                    new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                            "-cp", System.getProperty("java.class.path"), InstallerFixture.class.getName(), childMode)
                            .inheritIO().start();
                    Thread.sleep("inherited-pipe".equals(args[0]) ? 750 : 60_000);
                }
                case "silent" -> Thread.sleep(60_000);
                default -> throw new IllegalArgumentException("Unknown local installer fixture");
            }
        }
    }
}
