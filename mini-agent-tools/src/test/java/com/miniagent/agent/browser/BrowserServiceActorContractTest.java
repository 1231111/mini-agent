package com.miniagent.agent.browser;

import com.miniagent.agent.tool.ToolExecutionDeadline;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Service-level lifecycle, cancellation semantics, and native-process ownership checks. */
class BrowserServiceActorContractTest {
    @Test
    void workersAreIsolatedAndRemainTrackedAfterTheirCallerTimesOut() throws Exception {
        BrowserService service = new BrowserService();
        ReflectionTestUtils.setField(service, "actorOperationTimeoutMillis", 200L);
        BrowserService first = worker(service, "one");
        BrowserService second = worker(service, "two");
        assertThat(first).isNotSameAs(second);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> pending = CompletableFuture.supplyAsync(() -> tracked(first, "one", () -> {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "late";
            }));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(tracked(second, "two", () -> "independent")).isEqualTo("independent");
            assertThat(pending.get(2, TimeUnit.SECONDS)).contains("TIMEOUT");
            assertThat(service.hasPendingOperations("one")).isTrue();
            assertThat(worker(service, "one")).isSameAs(first);
            release.countDown();
            awaitNoPending(service, "one");
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void startedInputTimeoutIsUnknownInsteadOfASafeRetryableFailure() throws Exception {
        BrowserService service = new BrowserService(true);
        ReflectionTestUtils.setField(service, "actorOperationTimeoutMillis", 50L);
        CountDownLatch release = new CountDownLatch(1);
        try {
            String result = tracked(service, "session", () -> {
                ReflectionTestUtils.invokeMethod(service, "requireActorInput");
                release.await(5, TimeUnit.SECONDS);
                return "late";
            });
            assertThat(result).contains("OUTCOME_UNKNOWN").contains("unknown");
            assertThat(result).doesNotContain("已隔离并终止");
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void expiredEnclosingToolDeadlineDoesNotDispatchAnOperation() throws Exception {
        BrowserService service = new BrowserService(true);
        try {
            ToolExecutionDeadline.Deadline deadline = ToolExecutionDeadline.after(1, TimeUnit.MILLISECONDS);
            Thread.sleep(5);
            try (var ignored = ToolExecutionDeadline.bind(deadline)) {
                String result = tracked(service, "session", () -> { throw new AssertionError("must not execute"); });
                assertThat(result).contains("TIMEOUT").contains("排队前");
                assertThat(service.hasPendingOperations("session")).isFalse();
            }
        } finally {
            service.shutdown();
        }
    }

    @Test
    void capacityIsBoundedAndShutdownRejectsNewWorkers() {
        BrowserService service = new BrowserService();
        ReflectionTestUtils.setField(service, "maxActiveSessions", 1);
        try {
            assertThat(worker(service, "one")).isNotNull();
            assertThat(worker(service, "two")).isNull();
            assertThat(service.snapshot("two", false)).contains("RESOURCE_BUSY");
            service.shutdown();
            assertThat(worker(service, "three")).isNull();
            assertThat(service.snapshot("three", false)).contains("DEPENDENCY_UNAVAILABLE");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void reusedOrUnidentifiableProcessHandlesAreNeverTerminated() throws Exception {
        BrowserService service = new BrowserService(true);
        ProcessHandle handle = mock(ProcessHandle.class);
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(handle.isAlive()).thenReturn(true);
        when(handle.info()).thenReturn(info);
        Instant expected = Instant.parse("2026-01-01T00:00:00Z");
        try {
            setOwnedProcess(service, handle, expected);
            when(info.startInstant()).thenReturn(Optional.of(expected.plusSeconds(1)));
            ReflectionTestUtils.invokeMethod(service, "terminateOwnedBrowserProcess");
            when(info.startInstant()).thenReturn(Optional.empty());
            ReflectionTestUtils.invokeMethod(service, "terminateOwnedBrowserProcess");
            setOwnedProcess(service, handle, null);
            ReflectionTestUtils.invokeMethod(service, "terminateOwnedBrowserProcess");
            verify(handle, never()).destroyForcibly();
            verify(handle, never()).descendants();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void ownedProcessCleanupLeavesAnUnrelatedRealProcessRunning() throws Exception {
        BrowserService service = new BrowserService(true);
        Process owned = sleepingProcess();
        Process unrelated = sleepingProcess();
        try {
            Instant startedAt = owned.info().startInstant().orElseThrow();
            setOwnedProcess(service, owned.toHandle(), startedAt);
            ReflectionTestUtils.invokeMethod(service, "terminateOwnedBrowserProcess");
            assertThat(owned.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(unrelated.isAlive()).isTrue();
        } finally {
            owned.destroyForcibly();
            unrelated.destroyForcibly();
            owned.waitFor(5, TimeUnit.SECONDS);
            unrelated.waitFor(5, TimeUnit.SECONDS);
            service.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void closeFailureRetainsLiveHandlesForExplicitRetry() {
        BrowserService service = new BrowserService(true);
        Page page = mock(Page.class);
        when(page.isClosed()).thenReturn(false);
        doThrow(new IllegalStateException("close failed")).doNothing().when(page).close();
        Map<String, Page> pages = (Map<String, Page>) ReflectionTestUtils.getField(service, "pages");
        pages.put("session", page);
        BoundedBrowserActor actor = (BoundedBrowserActor) ReflectionTestUtils.getField(service, "actor");
        try {
            assertThat(service.close("session")).contains("EXECUTION_FAILED");
            assertThat(pages).containsKey("session");
            assertThat(actor.isQuarantined()).isFalse();

            assertThat(service.close("session")).contains("浏览器页面已关闭");
            assertThat(pages).doesNotContainKey("session");
            assertThat(actor.isQuarantined()).isTrue();
        } finally {
            service.shutdown();
        }
    }

    private static Process sleepingProcess() throws IOException {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), ProcessSleeper.class.getName())
                .redirectErrorStream(true).start();
    }

    public static final class ProcessSleeper {
        public static void main(String[] args) throws InterruptedException { Thread.sleep(30_000); }
    }

    private static void setOwnedProcess(BrowserService service, ProcessHandle handle, Instant startedAt) throws Exception {
        Class<?> type = Class.forName(BrowserService.class.getName() + "$OwnedBrowserProcess");
        Constructor<?> constructor = type.getDeclaredConstructor(ProcessHandle.class, Instant.class);
        constructor.setAccessible(true);
        ReflectionTestUtils.setField(service, "ownedBrowserProcess", constructor.newInstance(handle, startedAt));
    }

    private static BrowserService worker(BrowserService service, String session) {
        return ReflectionTestUtils.invokeMethod(service, "workerFor", session);
    }

    private static String tracked(BrowserService service, String session, Callable<String> operation) {
        return ReflectionTestUtils.invokeMethod(service, "onTrackedSessionActor", session, operation);
    }

    private static void awaitNoPending(BrowserService service, String session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (service.hasPendingOperations(session) && System.nanoTime() < deadline) Thread.sleep(1);
        assertThat(service.hasPendingOperations(session)).isFalse();
    }
}
