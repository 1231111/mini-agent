package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.ExecCommandParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Exercises real local process trees through the same builtin entrypoint used by agent calls. */
class ExecCommandProcessLifecycleContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @BeforeEach void compileLocalFixture() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Path source = workspace.resolve("LocalProcessFixture.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class LocalProcessFixture {
                  public static void main(String[] args) throws Exception {
                    Path receipt = Path.of(args[1]);
                    String mode = args[0];
                    if (mode.equals("tree")) {
                      String executable = Path.of(java.lang.System.getProperty("java.home"), "bin",
                          java.lang.System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
                      Process child = new ProcessBuilder(executable, "-cp", ".", "LocalProcessFixture", "leaf", "leaf.pid")
                          .inheritIO().start();
                      Files.writeString(receipt, ProcessHandle.current().pid() + "," + child.pid());
                    } else Files.writeString(receipt, Long.toString(ProcessHandle.current().pid()));
                    if (mode.equals("normal")) { java.lang.System.out.println("completed"); return; }
                    if (mode.equals("failure")) { java.lang.System.out.println("fixture failure"); java.lang.System.exit(7); }
                    if (mode.equals("overflow")) {
                      byte[] bytes = new byte[8192]; java.util.Arrays.fill(bytes, (byte)'x');
                      for (int i=0; i<4096; i++) java.lang.System.out.write(bytes);
                      java.lang.System.out.flush();
                    }
                    Thread.sleep(60000);
                  }
                }
                """, StandardCharsets.UTF_8);
        assertThat(ToolProvider.getSystemJavaCompiler()).as("a JDK is required for process lifecycle acceptance").isNotNull();
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-encoding", "UTF-8", "-d", workspace.toString(), source.toString())).isZero();
    }

    @AfterEach void clear() throws Exception {
        // Always clean fixtures, including after a failed assertion, without touching unrelated processes.
        try (var files = Files.list(workspace)) {
            for (Path receipt : files.filter(p -> p.getFileName().toString().endsWith(".pid")).toList()) {
                for (String value : Files.readString(receipt).trim().split(",")) {
                    if (!value.isBlank()) ProcessHandle.of(Long.parseLong(value)).ifPresent(p -> { if (p.isAlive()) p.destroyForcibly(); });
                }
            }
        } finally { WorkspaceContext.clearAll(); Thread.interrupted(); }
    }

    @Test void timeoutTerminatesActualDescendantsAndReportsPossibleSideEffects() throws Exception {
        JsonNode result = JSON.readTree(invoke("tree", "tree.pid", 3));
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("errorCode").asText()).isEqualTo("TIMEOUT");
        assertThat(result.path("retriable").asBoolean()).isFalse();
        assertThat(result.path("error").asText()).contains("可能已产生").doesNotContain("未产生后续副作用");
        assertProcessesExited(workspace.resolve("tree.pid"));
    }

    @Test void interruptionPreservesCallerSignalAndTerminatesActualProcessTree() throws Exception {
        AtomicReference<String> returned = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            WorkspaceContext.setRootOverride(workspace);
            try { returned.set(invoke("tree", "cancel.pid", 30)); interrupted.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable problem) { failure.set(problem); }
            finally { WorkspaceContext.clearAll(); }
        }, "exec-lifecycle-caller");
        caller.start();
        try {
            awaitReceipt(workspace.resolve("cancel.pid"));
            caller.interrupt();
            caller.join(6000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(JSON.readTree(returned.get()).path("errorCode").asText()).isEqualTo("CANCELLED");
            assertThat(interrupted).isTrue();
            assertProcessesExited(workspace.resolve("cancel.pid"));
        } finally { caller.interrupt(); caller.join(3000); }
    }

    @Test void outputOverflowTerminatesTheActualWriterWithoutWaitingForDeclaredTimeout() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            WorkspaceContext.setRootOverride(workspace);
            try {
                JsonNode result = JSON.readTree(invoke("overflow", "overflow.pid", 30));
                assertThat(result.path("errorCode").asText()).isEqualTo("EXECUTION_FAILED");
                assertThat(result.path("error").asText()).contains("硬上限");
                assertProcessesExited(workspace.resolve("overflow.pid"));
            } finally { WorkspaceContext.clearAll(); }
        });
    }

    @Test void normalCompletionRetainsSuccessReceiptAndLeavesNoDrainerRunning() throws Exception {
        long before = drainerCount();
        assertThat(invoke("normal", "normal.pid", 10)).startsWith("exit_code=0\n").contains("completed");
        assertProcessesExited(workspace.resolve("normal.pid"));
        assertThat(drainerCount()).isLessThanOrEqualTo(before);
    }

    @Test void nonzeroExitAndArgumentErrorsUseValidStructuredFailures() throws Exception {
        JsonNode failed = JSON.readTree(invoke("failure", "failure.pid", 10));
        assertThat(failed.path("errorCode").asText()).isEqualTo("EXECUTION_FAILED");
        assertThat(failed.path("exit_code").asInt()).isEqualTo(7);
        assertThat(failed.path("output").asText()).contains("fixture failure");
        ExecCommandParams empty = new ExecCommandParams();
        String invalid = ReflectionTestUtils.invokeMethod(new BuiltinTools(), "execCommand", empty);
        assertThat(JSON.readTree(invalid).path("errorCode").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    private String invoke(String mode, String receipt, int timeout) {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        String executable = isWindows() ? '"' + java.toString() + '"' : "'" + java.toString().replace("'", "'\\''") + "'";
        ExecCommandParams params = new ExecCommandParams();
        params.setCommand(executable + " -cp . LocalProcessFixture " + mode + " " + receipt);
        params.setTimeout(timeout); params.setOutputEncoding("UTF-8");
        return ReflectionTestUtils.invokeMethod(new BuiltinTools(), "execCommand", params);
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    private static long drainerCount() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().equals("exec-cmd-drainer")).count();
    }
    private static void awaitReceipt(Path receipt) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while ((!Files.exists(receipt) || Files.size(receipt) == 0) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(receipt).exists();
        assertThat(Files.readString(receipt)).isNotBlank();
    }
    private static void assertProcessesExited(Path receipt) throws Exception {
        awaitReceipt(receipt);
        for (String value : Files.readString(receipt).split(",")) {
            long pid = Long.parseLong(value.trim());
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as("fixture process %s must exit", pid).isFalse();
        }
    }
}
