package com.miniagent.agent.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Browser failures must remain failures across the legacy string tool boundary. */
class BrowserFailureTest {
    @TempDir Path root;
    private BrowserService service;
    private Browser browser;

    @BeforeEach void setup() {
        service = new BrowserService();
        browser = mock(Browser.class);
        ReflectionTestUtils.setField(service, "networkGuard", mock(NetworkGuard.class));
        ReflectionTestUtils.setField(service, "playwright", mock(Playwright.class));
        ReflectionTestUtils.setField(service, "browser", browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenThrow(new IllegalStateException("Failed to create driver"));
    }

    @AfterEach void close() { service.shutdown(); }

    @Test void navigationInitializationFailureCannotBeReportedAsToolSuccess() throws Exception {
        assertFailure(service.navigate("chat", "https://example.test/"), ToolErrorCode.EXECUTION_FAILED);
    }

    @Test void snapshotInitializationFailureCannotBeReportedAsToolSuccess() throws Exception {
        assertFailure(service.snapshot("chat", false), ToolErrorCode.EXECUTION_FAILED);
    }

    @Test void screenshotInitializationFailureCannotBeReportedAsToolSuccess() throws Exception {
        assertFailure(service.screenshot("chat", root), ToolErrorCode.EXECUTION_FAILED);
    }

    @Test void extractInitializationFailureCannotBeReportedAsToolSuccess() throws Exception {
        assertFailure(service.extractArticleText("chat"), ToolErrorCode.EXECUTION_FAILED);
    }

    @Test void unconfirmedInteractiveFailuresRemainUnknownInsteadOfSuccessful() throws Exception {
        List<Supplier<String>> actions = List.of(
                () -> service.type("chat", "Name", "Fixture"),
                () -> service.press("chat", "Enter"),
                () -> service.scroll("chat", "down"),
                () -> service.evaluate("chat", "document.title"));
        for (var action : actions) {
            var result = assertFailure(action.get(), ToolErrorCode.OUTCOME_UNKNOWN);
            assertEquals(ToolStatus.UNKNOWN, result.status());
            assertFalse(result.retriable(), "an unconfirmed script/input must not be automatically replayed");
        }
    }

    @Test void clickInitializationFailureIsKnownNotToHaveSentInput() throws Exception {
        var result = assertFailure(service.click("chat", "Save", "text"), ToolErrorCode.CONFLICT);
        assertEquals(ToolStatus.FAILED, result.status());
        assertFalse(new ObjectMapper().readTree(result.legacyText()).path("inputDispatched").asBoolean(true));
        assertFalse(result.message().contains("Failed to create driver"), "SDK details belong to server diagnostics");
    }

    @Test void emptyTargetAndUnknownStrategyAreStructuredInvalidArguments() throws Exception {
        assertFailure(service.click("chat", "", "ref"), ToolErrorCode.INVALID_ARGUMENT);
        verify(browser, never()).newContext(any(Browser.NewContextOptions.class));
        putPage(mock(Page.class));
        assertFailure(service.click("chat", "Save", "invalid-strategy"), ToolErrorCode.INVALID_ARGUMENT);
    }

    @Test void missingObservedReferenceIsAConflictWithoutDispatchingInput() throws Exception {
        Page page = mock(Page.class);
        putPage(page);
        assertFailure(service.click("chat", "b9.1", "ref"), ToolErrorCode.CONFLICT);
        assertFailure(service.type("chat", "b9.1", "Fixture"), ToolErrorCode.CONFLICT);
        verify(page, times(2)).isClosed();
        verifyNoMoreInteractions(page);
    }

    @Test void readableSnapshotKeepsTheExistingSuccessTextContract() {
        Page page = mock(Page.class);
        Locator body = mock(Locator.class);
        when(page.url()).thenReturn("https://example.test/");
        when(page.locator("body")).thenReturn(body);
        when(body.ariaSnapshot()).thenReturn("- heading \"Fixture ready\" [level=1]");
        putPage(page);
        String snapshot = service.snapshot("chat", false);
        assertTrue(ToolResult.fromLegacy(snapshot).isSuccess());
        assertTrue(snapshot.contains("observationId=b"));
        assertTrue(snapshot.contains("Fixture ready"));
    }

    private ToolResult assertFailure(String raw, ToolErrorCode expectedCode) throws Exception {
        var json = new ObjectMapper().readTree(raw);
        assertTrue(json.isObject(), raw);
        assertFalse(json.path("success").asBoolean(true), raw);
        var result = ToolResult.fromLegacy(raw);
        assertFalse(result.isSuccess(), raw);
        assertEquals(expectedCode, result.errorCode(), raw);
        assertFalse(result.message().isBlank(), raw);
        return result;
    }

    @SuppressWarnings("unchecked") private void putPage(Page page) {
        ((Map<String, Page>) ReflectionTestUtils.getField(service, "pages")).put("chat", page);
    }
}
