package com.miniagent.agent.tool;

import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.browser.BrowserSessionIdentity;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserSessionToolPolicyTest {
    @AfterEach
    void cleanup() {
        WorkspaceContext.clearSessionOverride();
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test
    void explicitClearUsesTrustedIdentityAndCannotClearAnotherChat() {
        BrowserService browser = mock(BrowserService.class);
        BuiltinTools tools = new BuiltinTools();
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.setField(tools, "registry", registry);
        ReflectionTestUtils.setField(tools, "browserService", browser);
        ReflectionTestUtils.setField(tools, "networkGuard", mock(NetworkGuard.class));
        ReflectionTestUtils.invokeMethod(tools, "registerBrowserTools");
        assertEquals(ToolErrorCode.PERMISSION_DENIED, registry.executeResult("browser_clear_session", "{}").errorCode());
        OwnerContext.setCurrentUser(7L);
        OwnerContext.setCurrentTenant("tenant");
        WorkspaceContext.setSessionOverride("chat-a");
        assertEquals(ToolErrorCode.PERMISSION_DENIED,
                registry.executeResult("browser_clear_session", "{\"sessionId\":\"chat-b\"}").errorCode());
        verifyNoInteractions(browser);
        String expected = BrowserSessionIdentity.resolve(null);
        when(browser.clearSessionState(expected)).thenReturn("cleared");
        assertEquals(ToolStatus.SUCCESS, registry.executeResult("browser_clear_session", "{}").status());
        verify(browser).clearSessionState(expected);
        var descriptor = registry.getDescriptor("browser_clear_session").orElseThrow();
        assertEquals(ToolConcurrencyScope.SESSION, descriptor.concurrencyScope());
        assertEquals(0, descriptor.maxRetries());
        assertEquals(ToolSideEffect.WRITE, ToolConcurrencyPolicy.sideEffectOf("browser_clear_session"));
        assertFalse(ToolConcurrencyPolicy.isIdempotent("browser_clear_session"));
        assertFalse(ToolConcurrencyPolicy.isStreamPrefetchSafe("browser_clear_session"));
        assertEquals(ToolTimeoutRecovery.ABORT, ToolConcurrencyPolicy.timeoutRecoveryOf("browser_clear_session"));
        assertTrue(new CapabilityRegistry(registry).toolsFor("browser").contains("browser_clear_session"));
    }
}
