package com.miniagent.agent.browser;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BrowserObservationTest {
    private final BrowserService service = new BrowserService();

    @AfterEach
    void close() { service.shutdown(); }

    @Test
    void unchangedObservedNodeCanBeClickedAndOldRefExpires() {
        var fixture = fixture("- button \"Save\"");
        String first = service.snapshot("chat", false);
        String ref = reference(first);
        assertTrue(service.click("chat", ref, "ref").contains("点击成功"));
        verify(fixture.handle()).click(any(ElementHandle.ClickOptions.class));
        assertTrue(service.click("chat", ref, "ref").contains("STALE_OBSERVATION"));
        verify(fixture.handle(), times(1)).click(any(ElementHandle.ClickOptions.class));
    }

    @Test
    void changingSnapshotCannotRetargetOldRefToAnotherElement() {
        var fixture = fixture("- button \"Save\"");
        String ref = reference(service.snapshot("chat", false));
        fixture.snapshot().set("- button \"Delete\"\n- button \"Save\"");
        assertTrue(service.click("chat", ref, "ref").contains("STALE_OBSERVATION"));
        verify(fixture.handle(), never()).click(any(ElementHandle.ClickOptions.class));
    }

    @Test
    void unrelatedLiveTextDoesNotExpireAnUnchangedControlRef() {
        var fixture = fixture("- text: Clock 10:00 CPU 30%\n- button \"Save\"");
        String ref = reference(service.snapshot("chat", false));
        fixture.snapshot().set("- text: Clock 10:01 CPU 32%\n- button \"Save\"");
        assertTrue(service.click("chat", ref, "ref").contains("点击成功"));
        verify(fixture.handle()).click(any(ElementHandle.ClickOptions.class));
    }

    @Test
    void changedLinkDestinationStillExpiresTheObservation() {
        var fixture = fixture("- button \"Save\"\n- link \"Details\":\n  - /url: /safe");
        String ref = reference(service.snapshot("chat", false));
        fixture.snapshot().set("- button \"Save\"\n- link \"Details\":\n  - /url: /different");
        assertTrue(service.click("chat", ref, "ref").contains("STALE_OBSERVATION"));
        verify(fixture.handle(), never()).click(any(ElementHandle.ClickOptions.class));
    }

    @Test
    void detachedNodeFailsEvenWhenItsReplacementHasIdenticalAccessibleText() {
        var fixture = fixture("- button \"Save\"");
        String ref = reference(service.snapshot("chat", false));
        when(fixture.handle().evaluate(anyString())).thenReturn(false);
        assertTrue(service.click("chat", ref, "ref").contains("STALE_OBSERVATION"));
        verify(fixture.handle(), never()).click(any(ElementHandle.ClickOptions.class));
    }

    @Test
    void numericRefsAndCrossSessionRefsAreRejected() {
        var fixture = fixture("- button \"Save\"");
        String ref = reference(service.snapshot("chat", false));
        pages().put("other", fixture.page());
        assertTrue(service.click("chat", "1", "ref").contains("STALE_OBSERVATION"));
        assertTrue(service.click("other", ref, "ref").contains("STALE_OBSERVATION"));
        verify(fixture.handle(), never()).click(any(ElementHandle.ClickOptions.class));
    }

    @Test
    void playwrightObjectsAlwaysRunOnTheSameDedicatedThread() throws Exception {
        var fixture = fixture("- button \"Save\"");
        AtomicReference<Thread> actor = new AtomicReference<>();
        when(fixture.page().url()).thenAnswer(invocation -> {
            Thread previous = actor.getAndSet(Thread.currentThread());
            if (previous != null) assertSame(previous, Thread.currentThread());
            assertEquals("miniagent-browser", Thread.currentThread().getName());
            return "https://example.test/page";
        });
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.snapshot("chat", false));
            var second = pool.submit(() -> service.snapshot("chat", false));
            assertTrue(first.get().contains("observationId"));
            assertTrue(second.get().contains("observationId"));
        }
        assertEquals("miniagent-browser", actor.get().getName());
    }

    @Test
    void actorUsesApplicationLoaderWhenCallerCannotSeePackagedDriverResources() {
        var fixture = fixture("- button \"Save\"");
        ClassLoader applicationLoader = BrowserService.class.getClassLoader();
        ClassLoader resourceBlindCaller = new ClassLoader(null) {};
        assertNull(resourceBlindCaller.getResource("driver/win32_x64"));
        AtomicReference<ClassLoader> actorLoader = new AtomicReference<>();
        when(fixture.page().url()).thenAnswer(invocation -> {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            actorLoader.set(loader);
            assertSame(applicationLoader, loader);
            assertNotNull(loader.getResource("driver/win32_x64"),
                    "the actor must retain access to Playwright's driver resources");
            return "https://example.test/page";
        });
        Thread caller = Thread.currentThread();
        ClassLoader previous = caller.getContextClassLoader();
        try {
            caller.setContextClassLoader(resourceBlindCaller);
            assertTrue(service.snapshot("chat", false).contains("observationId"));
            assertSame(resourceBlindCaller, caller.getContextClassLoader(), "dispatch must not mutate its caller");
        } finally {
            caller.setContextClassLoader(previous);
        }
        assertSame(applicationLoader, actorLoader.get());
        assertSame(previous, caller.getContextClassLoader());
    }

    @Test
    void interruptedQueuedOperationCannotLaterSendInput() throws Exception {
        var fixture = fixture("- button \"Save\"");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.page().locator("body").ariaSnapshot()).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "- button \"Save\"";
        });
        Thread busy = new Thread(() -> service.snapshot("chat", false));
        AtomicReference<String> result = new AtomicReference<>();
        Thread queued = new Thread(() -> result.set(service.press("chat", "Enter")));
        try {
            busy.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queued.start();
            // Interrupting before or after enqueue must both prevent a queued action from running.
            queued.interrupt();
            queued.join(5_000);
            assertNotNull(result.get());
            assertTrue(result.get().contains("CANCELLED"), result.get());
        } finally {
            release.countDown();
            busy.join(5_000);
        }
        service.snapshot("chat", false); // drain the actor queue
        verify(fixture.page(), never()).keyboard();
    }

    @Test
    void navigationIdentityIncludesOriginQueryAndFragment() {
        assertTrue(BrowserService.sameDocPath("https://a.test/path?q=1#x", "https://a.test/path?q=1#x"));
        assertFalse(BrowserService.sameDocPath("https://a.test/path", "https://b.test/path"));
        assertFalse(BrowserService.sameDocPath("https://a.test/path?q=1", "https://a.test/path?q=2"));
        assertFalse(BrowserService.sameDocPath("https://a.test/path#x", "https://a.test/path#y"));
        assertFalse(BrowserService.sameDocPath("", ""));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Page> pages() { return (Map<String, Page>) ReflectionTestUtils.getField(service, "pages"); }

    private Fixture fixture(String raw) {
        Page page = mock(Page.class);
        Locator body = mock(Locator.class);
        Locator button = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        AtomicReference<String> snapshot = new AtomicReference<>(raw);
        when(page.url()).thenReturn("https://example.test/page");
        when(page.locator("body")).thenReturn(body);
        when(body.ariaSnapshot()).thenAnswer(invocation -> snapshot.get());
        when(page.getByRole(eq(AriaRole.BUTTON), any(Page.GetByRoleOptions.class))).thenReturn(button);
        when(button.count()).thenReturn(1);
        when(button.elementHandle()).thenReturn(handle);
        when(handle.evaluate(anyString())).thenReturn(true);
        pages().put("chat", page);
        return new Fixture(page, handle, snapshot);
    }

    private static String reference(String snapshot) {
        var matcher = Pattern.compile("\\[(b\\d+\\.\\d+)]").matcher(snapshot);
        assertTrue(matcher.find(), snapshot);
        return matcher.group(1);
    }

    private record Fixture(Page page, ElementHandle handle, AtomicReference<String> snapshot) {}
}
