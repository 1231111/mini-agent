package com.miniagent.agent.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolExecutionDeadline;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.agent.security.NetworkGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BrowserSnapshotBudgetTest {
    private final BrowserService service = new BrowserService();

    @AfterEach void stop() { service.shutdown(); }

    @Test void fullSnapshotWithoutPipelineUsesTheDeclaredSnapshotBudget() {
        Locator body = fixture("chat");
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(service.snapshot("chat", true)).status());
        long declared = TimeUnit.SECONDS.toMillis(
                ToolConcurrencyPolicy.profileOf("browser_snapshot").executionBudgetSeconds());
        verify(body).ariaSnapshot(argThat(options -> options.timeout > 0 && options.timeout <= declared));
    }

    @ParameterizedTest @ValueSource(booleans = { false, true })
    void actorCarriesActualConfiguredBudgetForBothSnapshotModes(boolean full) {
        Locator body = fixture("chat");
        try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(2, TimeUnit.SECONDS))) {
            assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(service.snapshot("chat", full)).status());
        }
        verify(body).ariaSnapshot(argThat(options -> options.timeout > 0 && options.timeout < 2_000));
        assertNull(ToolExecutionDeadline.current());
    }

    @Test void queuedSnapshotCannotRestartItsDeadlineOrCallTheSdkAfterExpiry() throws Exception {
        Locator body = fixture("queued");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FutureTask<String> busy = new FutureTask<>(() -> ReflectionTestUtils.invokeMethod(service, "onActor",
                (Callable<String>) () -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return "busy"; }));
        Thread.ofVirtual().start(busy);
        FutureTask<String> queued = new FutureTask<>(() -> {
            try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(200, TimeUnit.MILLISECONDS))) {
                return service.snapshot("queued", true);
            }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Thread.ofVirtual().start(queued);
            ThreadPoolExecutor actor = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "actor");
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (actor.getQueue().isEmpty() && System.nanoTime() < waitUntil) Thread.sleep(1);
            assertFalse(actor.getQueue().isEmpty());
            Thread.sleep(250);
            release.countDown();
            assertEquals("busy", busy.get(3, TimeUnit.SECONDS));
            assertEquals(ToolStatus.TIMEOUT, ToolResult.fromLegacy(queued.get(3, TimeUnit.SECONDS)).status());
            verify(body, never()).ariaSnapshot(any());
            verify(body, never()).ariaSnapshot();
            assertFalse(service.hasPendingOperations("queued"));
        } finally { release.countDown(); busy.get(3, TimeUnit.SECONDS); }
    }

    @Test void slowReadUsesRemainingBudgetAndReleasesActorForAnotherSession() throws Exception {
        Locator slow = fixture("slow");
        fixture("next");
        AtomicReference<Double> sdkTimeout = new AtomicReference<>();
        when(slow.ariaSnapshot(any())).thenAnswer(call -> {
            double timeout = ((Locator.AriaSnapshotOptions) call.getArgument(0)).timeout;
            sdkTimeout.set(timeout);
            assertTrue(timeout > 0 && timeout < 300, "SDK must inherit the tool budget");
            Thread.sleep((long) timeout);
            throw new TimeoutError("controlled slow snapshot");
        });
        long started = System.nanoTime();
        try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(300, TimeUnit.MILLISECONDS))) {
            assertEquals(ToolStatus.TIMEOUT, ToolResult.fromLegacy(service.snapshot("slow", true)).status());
        }
        assertNotNull(sdkTimeout.get());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000);
        assertFalse(service.hasPendingOperations("slow"));
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(service.snapshot("next", true)).status());
    }

    @Test void deadlineOnOneOperationDoesNotLeakIntoTheNextActorTask() {
        Locator body = fixture("chat");
        try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(2, TimeUnit.SECONDS))) {
            service.snapshot("chat", true);
        }
        service.snapshot("chat", true);
        var timeouts = org.mockito.ArgumentCaptor.forClass(Locator.AriaSnapshotOptions.class);
        verify(body, times(2)).ariaSnapshot(timeouts.capture());
        assertTrue(timeouts.getAllValues().get(0).timeout < 2_000);
        assertTrue(timeouts.getAllValues().get(1).timeout > 10_000d
                && timeouts.getAllValues().get(1).timeout <= 15_000d);
    }

    @Test void alreadyExpiredBudgetCannotInitializeAnUnopenedBrowser() throws Exception {
        var expired = ToolExecutionDeadline.after(1, TimeUnit.MILLISECONDS);
        Thread.sleep(10);
        try (var ignored = ToolExecutionDeadline.bind(expired)) {
            assertEquals(ToolStatus.TIMEOUT, ToolResult.fromLegacy(service.snapshot("unopened", true)).status());
        }
        assertNull(ReflectionTestUtils.getField(service, "playwright"));
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "pages")).isEmpty());
    }

    @Test void disappearingElementCannotStartAnotherDefaultTimeoutDuringRefBinding() {
        Page page = mock(Page.class);
        Locator body = mock(Locator.class), button = mock(Locator.class);
        when(page.url()).thenReturn("https://fixture.test/");
        when(page.locator("body")).thenReturn(body);
        when(body.ariaSnapshot(any())).thenReturn("- button \"Save\"");
        when(page.getByRole(eq(AriaRole.BUTTON), any(Page.GetByRoleOptions.class))).thenReturn(button);
        when(button.count()).thenReturn(1);
        when(button.elementHandle(any())).thenAnswer(call -> {
            double timeout = ((Locator.ElementHandleOptions) call.getArgument(0)).timeout;
            assertTrue(timeout > 0 && timeout < 2_000);
            throw new TimeoutError("element disappeared after count");
        });
        @SuppressWarnings("unchecked") Map<String, Page> pages = (Map<String, Page>) ReflectionTestUtils.getField(service, "pages");
        pages.put("chat", page);
        try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(2, TimeUnit.SECONDS))) {
            assertEquals(ToolStatus.TIMEOUT, ToolResult.fromLegacy(service.snapshot("chat", true)).status());
        }
        verify(button, never()).elementHandle();
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "observations")).isEmpty());
    }

    @Test void failedObservationDisposesHandlesAlreadyCollectedForThatDraft() {
        Page page = mock(Page.class);
        Locator body = mock(Locator.class), button = mock(Locator.class), link = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        when(page.url()).thenReturn("https://fixture.test/");
        when(page.locator("body")).thenReturn(body);
        when(body.ariaSnapshot(any())).thenReturn("- button \"Save\"\n- link \"More\"");
        when(page.getByRole(eq(AriaRole.BUTTON), any(Page.GetByRoleOptions.class))).thenReturn(button);
        when(page.getByRole(eq(AriaRole.LINK), any(Page.GetByRoleOptions.class))).thenReturn(link);
        when(button.count()).thenReturn(1);
        when(button.elementHandle(any())).thenReturn(handle);
        when(link.count()).thenReturn(1);
        when(link.elementHandle(any())).thenThrow(new TimeoutError("second element disappeared"));
        @SuppressWarnings("unchecked") Map<String, Page> pages = (Map<String, Page>) ReflectionTestUtils.getField(service, "pages");
        pages.put("chat", page);
        try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(2, TimeUnit.SECONDS))) {
            assertEquals(ToolStatus.TIMEOUT, ToolResult.fromLegacy(service.snapshot("chat", true)).status());
        }
        verify(handle).dispose();
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "observations")).isEmpty());
    }

    @Test void navigationDispatchedBeforeObservationTimeoutRemainsUnknown() {
        Locator body = fixture("chat");
        ReflectionTestUtils.setField(service, "networkGuard", mock(NetworkGuard.class));
        when(body.ariaSnapshot()).thenReturn("- text: Navigation completed");
        try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(1, TimeUnit.MILLISECONDS))) {
            assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(
                    service.navigate("chat", "https://fixture.test/next")).status());
        }
        @SuppressWarnings("unchecked") Map<String, Page> pages = (Map<String, Page>) ReflectionTestUtils.getField(service, "pages");
        verify(pages.get("chat")).navigate(eq("https://fixture.test/next"), any(Page.NavigateOptions.class));
    }

    @Test void explicitlyBlockedNavigationKeepsItsKnownFailureInsteadOfBecomingUnknown() {
        fixture("chat");
        ReflectionTestUtils.setField(service, "networkGuard", mock(NetworkGuard.class));
        @SuppressWarnings("unchecked") Map<String, Page> pages = (Map<String, Page>) ReflectionTestUtils.getField(service, "pages");
        when(pages.get("chat").navigate(anyString(), any(Page.NavigateOptions.class)))
                .thenThrow(new com.microsoft.playwright.PlaywrightException("net::ERR_BLOCKED_BY_CLIENT"));
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(
                service.navigate("chat", "https://fixture.test/next")).status());
    }

    @SuppressWarnings("unchecked") private Locator fixture(String session) {
        Page page = mock(Page.class);
        Locator body = mock(Locator.class);
        when(page.url()).thenReturn("https://fixture.test/");
        when(page.locator("body")).thenReturn(body);
        when(body.ariaSnapshot()).thenReturn("- text: Fixture");
        when(body.ariaSnapshot(any())).thenReturn("- text: Fixture");
        ((Map<String, Page>) ReflectionTestUtils.getField(service, "pages")).put(session, page);
        return body;
    }
}
