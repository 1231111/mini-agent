package com.miniagent.agent.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

class BrowserClickFailureTest {
    private final BrowserService service = new BrowserService();
    private final ElementHandle target = mock(ElementHandle.class);
    private final Locator body = mock(Locator.class);
    private final AtomicReference<String> snapshot = new AtomicReference<>("- button \"Save\"");
    private String ref;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        Page page = mock(Page.class);
        Locator button = mock(Locator.class);
        when(page.url()).thenReturn("https://fixture.test/");
        when(page.locator("body")).thenReturn(body);
        when(body.ariaSnapshot()).thenAnswer(call -> snapshot.get());
        when(body.ariaSnapshot(any())).thenAnswer(call -> snapshot.get());
        when(page.getByRole(eq(AriaRole.BUTTON), any(Page.GetByRoleOptions.class))).thenReturn(button);
        when(button.count()).thenReturn(1);
        when(button.elementHandle()).thenReturn(target);
        when(target.evaluate(anyString())).thenReturn(true);
        ((Map<String, Page>) ReflectionTestUtils.getField(service, "pages")).put("chat", page);
        var match = Pattern.compile("\\[(b\\d+\\.\\d+)]").matcher(service.snapshot("chat", false));
        assertTrue(match.find()); ref = match.group(1);
    }

    @AfterEach void stop() { service.shutdown(); }

    @Test void failedTrialIsKnownNotToHaveClickedEvenIfTheErrorTextClaimsOtherwise() throws Exception {
        doThrow(new TimeoutError("click dispatched; misleading SDK text and stack"))
                .when(target).click(argThat(options -> Boolean.TRUE.equals(options.trial)));
        String raw = service.click("chat", ref, "ref");
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(raw).status(), raw);
        var json = new ObjectMapper().readTree(raw);
        assertFalse(json.path("inputDispatched").asBoolean(true));
        assertEquals("actionability", json.path("phase").asText());
        assertTrue(json.path("observation").asText().contains("observationId="));
        assertFalse(json.path("error").asText().contains("misleading"));
        verify(target, never()).click(argThat(options -> !Boolean.TRUE.equals(options.trial)));
    }

    @Test void dispatchTimeoutRemainsUnknownAndNeverFallsBackToAnotherClick() throws Exception {
        doThrow(new TimeoutError("timeout without dispatch wording"))
                .when(target).click(argThat(options -> !Boolean.TRUE.equals(options.trial)));
        String raw = service.click("chat", ref, "ref");
        assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(raw).status(), raw);
        var json = new ObjectMapper().readTree(raw);
        assertTrue(json.path("inputDispatched").asBoolean());
        assertEquals("dispatch", json.path("phase").asText());
        assertFalse(json.path("retriable").asBoolean(true));
        verify(target, times(1)).click(argThat(options -> !Boolean.TRUE.equals(options.trial)));
        assertTrue(service.click("chat", ref, "ref").contains("STALE_OBSERVATION"));
        verify(target, times(1)).click(argThat(options -> !Boolean.TRUE.equals(options.trial)));
    }

    @Test void observationFailureAfterAcceptedInputCannotBeDowngradedToNotExecuted() throws Exception {
        when(body.ariaSnapshot(any())).thenThrow(new TimeoutError("browser internals call log and stack"));
        String raw = service.click("chat", ref, "ref");
        assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(raw).status(), raw);
        var json = new ObjectMapper().readTree(raw);
        assertTrue(json.path("inputDispatched").asBoolean());
        assertEquals("observation", json.path("phase").asText());
        assertFalse(raw.contains("browser internals"));
        verify(target, times(1)).click(argThat(options -> !Boolean.TRUE.equals(options.trial)));
    }

    @Test void refChangedByTrialScrollingCannotBeUsedForActualInput() {
        doAnswer(call -> { snapshot.set("- button \"Delete\""); return null; })
                .when(target).click(argThat(options -> Boolean.TRUE.equals(options.trial)));
        assertTrue(service.click("chat", ref, "ref").contains("STALE_OBSERVATION"));
        verify(target, never()).click(argThat(options -> !Boolean.TRUE.equals(options.trial)));
    }

    @Test void interruptionDuringTrialStopsInputWithoutInterruptingTheSharedActor() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> actorInterrupted = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        doAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            actorInterrupted.set(Thread.currentThread().isInterrupted()); return null;
        }).when(target).click(argThat(options -> Boolean.TRUE.equals(options.trial)));
        Thread caller = Thread.ofPlatform().start(() -> result.set(service.click("chat", ref, "ref")));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            caller.interrupt(); caller.join(2_000);
            assertFalse(caller.isAlive());
            assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(result.get()).status());
            release.countDown();
            service.snapshot("chat", false); // The cancelled operation must finish before this observation.
            assertEquals(Boolean.FALSE, actorInterrupted.get());
            verify(target, never()).click(argThat(options -> !Boolean.TRUE.equals(options.trial)));
        } finally {
            release.countDown(); caller.interrupt(); caller.join(5_000);
        }
    }

    @Test void interruptionAfterInputStartedKeepsUnknownAndDoesNotReplay() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        doAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            assertFalse(Thread.currentThread().isInterrupted()); return null;
        }).when(target).click(argThat(options -> !Boolean.TRUE.equals(options.trial)));
        Thread caller = Thread.ofPlatform().start(() -> result.set(service.click("chat", ref, "ref")));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            caller.interrupt(); caller.join(2_000);
            assertFalse(caller.isAlive());
            assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(result.get()).status());
            release.countDown(); service.snapshot("chat", false);
            assertTrue(service.click("chat", ref, "ref").contains("STALE_OBSERVATION"));
            verify(target, times(1)).click(argThat(options -> !Boolean.TRUE.equals(options.trial)));
        } finally {
            release.countDown(); caller.interrupt(); caller.join(5_000);
        }
    }
}
