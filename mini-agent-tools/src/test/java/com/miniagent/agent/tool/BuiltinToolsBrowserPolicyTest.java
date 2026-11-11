package com.miniagent.agent.tool;

import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.security.NetworkGuard;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuiltinToolsBrowserPolicyTest {

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
