package com.miniagent.agent.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Persistence is auxiliary: it must not replay a committed click/script after a disk failure. */
class BrowserSessionPersistenceTest {
    private static final String ID = "browser-" + "1".repeat(64);
    private BrowserService service;
    private BrowserSessionStateStore store;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = new BrowserService();
        store = mock(BrowserSessionStateStore.class);
        Browser browser = mock(Browser.class);
        context = mock(BrowserContext.class);
        page = mock(Page.class);
        when(browser.isConnected()).thenReturn(true);
        when(context.storageState()).thenReturn("{\"cookies\":[],\"origins\":[]}");
        ReflectionTestUtils.setField(service, "browser", browser);
        ReflectionTestUtils.setField(service, "playwright", mock(Playwright.class));
        ReflectionTestUtils.setField(service, "sessionStateStore", store);
        ((Map<String, Page>) ReflectionTestUtils.getField(service, "pages")).put(ID, page);
        ((Map<String, BrowserContext>) ReflectionTestUtils.getField(service, "sessionContexts")).put(ID, context);
    }

    @AfterEach
    void stop() { if (service != null) service.shutdown(); }

    @Test
    void storageFailureKeepsConfirmedActionSuccessAndReportsRecoveryWarning() throws Exception {
        when(page.evaluate("'committed'")).thenReturn("committed");
        doThrow(new IOException("disk full")).when(store).save(eq(ID), anyString());
        String raw = service.evaluate(ID, "'committed'");
        ToolResult result = ToolResult.fromLegacy(raw);
        assertEquals(ToolStatus.SUCCESS, result.status(), raw);
        assertTrue(raw.contains("committed"));
        assertTrue(raw.contains("登录态保存失败"));
        assertTrue(raw.contains("当前页面操作未回滚"));
        verify(page, times(1)).evaluate("'committed'");
        assertSame(context, contexts().get(ID));
    }

    @Test
    void storageFailureAlsoKeepsUnconfirmedActionUnknownRatherThanMakingItRetryable() throws Exception {
        when(page.evaluate("throw new Error('input uncertain')")).thenThrow(new IllegalStateException("input uncertain"));
        doThrow(new IOException("disk full")).when(store).save(eq(ID), anyString());
        String raw = service.evaluate(ID, "throw new Error('input uncertain')");
        ToolResult result = ToolResult.fromLegacy(raw);
        assertEquals(ToolStatus.UNKNOWN, result.status(), raw);
        assertFalse(result.retriable());
        assertTrue(new ObjectMapper().readTree(raw).has("statePersistenceWarning"));
        verify(page, times(1)).evaluate("throw new Error('input uncertain')");
    }

    @Test
    void failedCloseRetainsContextSoTheExplicitClearCanBeRetried() throws Exception {
        doThrow(new IllegalStateException("close disconnected")).doNothing().when(context).close();
        String failed = service.clearSessionState(ID);
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(failed).status());
        assertSame(context, contexts().get(ID));
        String blocked = service.evaluate(ID, "'must-not-dispatch'");
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(blocked).status(), blocked);
        assertTrue(blocked.contains("LOGIN_STATE_CLEAR_PENDING"), blocked);
        verifyNoInteractions(page);
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(service.clearSessionState(ID)).status());
        assertFalse(contexts().containsKey(ID));
        verify(store, times(2)).clear(ID);
        verify(store, never()).save(anyString(), anyString());
    }

    @Test
    void failedDeleteLeavesTheLiveContextUsableAndDoesNotClaimLogout() throws Exception {
        doThrow(new IOException("access denied")).when(store).clear(ID);
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(service.clearSessionState(ID)).status());
        assertSame(context, contexts().get(ID));
        verify(context, never()).close();
        when(page.evaluate("'still-usable'")).thenReturn("still-usable");
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(service.evaluate(ID, "'still-usable'")).status());
        verify(page).evaluate("'still-usable'");
        verify(store).save(eq(ID), anyString());
    }

    @Test
    void shutdownCannotResurrectAuthenticationAfterDeleteCommittedButCloseFailed() throws Exception {
        doThrow(new IllegalStateException("close disconnected")).when(context).close();
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(service.clearSessionState(ID)).status());
        service.shutdown();
        service = null;
        verify(store).clear(ID);
        verify(store, never()).save(anyString(), anyString());
        verify(context, never()).storageState();
        verify(context, times(2)).close();
    }

    @Test
    void explicitCloseCanFinishPendingClearAndMustNotClaimAuthenticationWasPreserved() throws Exception {
        doThrow(new IllegalStateException("close disconnected")).doNothing().when(context).close();
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(service.clearSessionState(ID)).status());
        String closed = service.close(ID);
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(closed).status(), closed);
        assertTrue(closed.contains("已清除"), closed);
        assertFalse(closed.contains("已保留"), closed);
        assertFalse(contexts().containsKey(ID));
        verify(store, never()).save(anyString(), anyString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, BrowserContext> contexts() {
        return (Map<String, BrowserContext>) ReflectionTestUtils.getField(service, "sessionContexts");
    }
}
