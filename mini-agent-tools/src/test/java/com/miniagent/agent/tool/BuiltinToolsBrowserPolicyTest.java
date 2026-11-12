package com.miniagent.agent.tool;

import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.browser.BrowserSessionIdentity;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import com.miniagent.agent.security.NetworkGuard;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class BuiltinToolsBrowserPolicyTest {

    @AfterEach
    void cleanup() {
        WorkspaceContext.clearSessionOverride();
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test
    void browserEvaluateRegistrationFollowsConfiguration() {
        ToolRegistry disabled = registerBrowserTools(false);
        ToolRegistry enabled = registerBrowserTools(true);

        assertFalse(disabled.getToolNames().contains("browser_evaluate"));
        assertTrue(enabled.getToolNames().contains("browser_evaluate"));
    }

    @Test
    void blockedLocalNavigationExplainsSafeAlternativeAndDoesNotRetry() {
        NetworkGuard guard = mock(NetworkGuard.class);
        BrowserService browser = mock(BrowserService.class);
        when(guard.validateUrl("file:///workspace/page.html")).thenReturn("仅允许 http/https 协议");
        ToolRegistry registry = registerBrowserTools(false, guard, browser);

        ToolResult result = registry.executeResult(
                "browser_navigate", "{\"url\":\"file:///workspace/page.html\"}");

        assertEquals(ToolStatus.FAILED, result.status());
        assertEquals(ToolErrorCode.PERMISSION_DENIED, result.errorCode());
        assertFalse(result.retriable());
        assertTrue(result.message().contains("停止更换端口重试"));
        assertTrue(result.message().contains("exec_command"));
        verifyNoInteractions(browser);
    }

    @Test
    void browserSessionComesFromTrustedOwnerAndChatRatherThanDefaultModelArgument() {
        OwnerContext.setCurrentUser(7L);
        OwnerContext.setCurrentTenant("tenant");
        WorkspaceContext.setSessionOverride("chat-a");
        BrowserService browser = mock(BrowserService.class);
        String expected = BrowserSessionIdentity.resolve(null);
        when(browser.snapshot(expected, false)).thenReturn("observed");
        ToolRegistry registry = registerBrowserTools(false, mock(NetworkGuard.class), browser);
        assertEquals(ToolStatus.SUCCESS, registry.executeResult("browser_snapshot", "{}").status());
        verify(browser).snapshot(expected, false);
    }

    @Test
    void anotherSessionOrMissingRunCannotDispatchBrowserAction() {
        BrowserService browser = mock(BrowserService.class);
        ToolRegistry registry = registerBrowserTools(false, mock(NetworkGuard.class), browser);
        assertEquals(ToolErrorCode.PERMISSION_DENIED, registry.executeResult("browser_close", "{}").errorCode());
        OwnerContext.setCurrentUser(7L);
        OwnerContext.setCurrentTenant("tenant");
        WorkspaceContext.setSessionOverride("chat-a");
        assertEquals(ToolErrorCode.PERMISSION_DENIED,
                registry.executeResult("browser_click", "{\"ref\":\"b1.1\",\"sessionId\":\"chat-b\"}").errorCode());
        verifyNoInteractions(browser);
    }

    private static ToolRegistry registerBrowserTools(boolean evaluateEnabled) {
        return registerBrowserTools(evaluateEnabled, mock(NetworkGuard.class), mock(BrowserService.class));
    }

    private static ToolRegistry registerBrowserTools(boolean evaluateEnabled, NetworkGuard guard,
                                                     BrowserService browser) {
        BuiltinTools tools = new BuiltinTools();
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.setField(tools, "registry", registry);
        ReflectionTestUtils.setField(tools, "browserService", browser);
        ReflectionTestUtils.setField(tools, "networkGuard", guard);
        ReflectionTestUtils.setField(tools, "browserEvaluateEnabled", evaluateEnabled);
        ReflectionTestUtils.invokeMethod(tools, "registerBrowserTools");
        return registry;
    }
}
