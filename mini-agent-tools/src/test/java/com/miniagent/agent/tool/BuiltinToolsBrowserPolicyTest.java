package com.miniagent.agent.tool;

import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.security.NetworkGuard;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BuiltinToolsBrowserPolicyTest {

    @Test
    void browserEvaluateRegistrationFollowsConfiguration() {
        ToolRegistry disabled = registerBrowserTools(false);
        ToolRegistry enabled = registerBrowserTools(true);

        assertFalse(disabled.getToolNames().contains("browser_evaluate"));
        assertTrue(enabled.getToolNames().contains("browser_evaluate"));
    }

    private static ToolRegistry registerBrowserTools(boolean evaluateEnabled) {
        BuiltinTools tools = new BuiltinTools();
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.setField(tools, "registry", registry);
        ReflectionTestUtils.setField(tools, "browserService", mock(BrowserService.class));
        ReflectionTestUtils.setField(tools, "networkGuard", mock(NetworkGuard.class));
        ReflectionTestUtils.setField(tools, "browserEvaluateEnabled", evaluateEnabled);
        ReflectionTestUtils.invokeMethod(tools, "registerBrowserTools");
        return registry;
    }
}
