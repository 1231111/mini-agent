package com.miniagent.web;

import com.miniagent.config.entity.UserRole;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.service.UserModelConfigService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelSettingsContextWindowApiTest {
    private MiniAgentChatPageController controller;
    private UserModelConfigService service;
    private MockHttpServletRequest request;
    @BeforeEach void setup() {
        controller = new MiniAgentChatPageController(); service = mock(UserModelConfigService.class);
        ReflectionTestUtils.setField(controller, "userModelConfigService", service);
        request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_PRINCIPAL, new AuthenticatedUser(7L, 1L, "user", UserRole.USER));
        JwtSessionService jwt = mock(JwtSessionService.class);
        when(jwt.resolveUserIdAndRefresh(request)).thenReturn(7L);
        ReflectionTestUtils.setField(controller, "jwtSessionService", jwt);
        when(service.save(eq(7L), any(), any(), any(), any(), any())).thenReturn(Map.of("success", true));
    }

    @Test void acceptsIntegerWindowsAndPassesAbsentWindowAsNullForOldClients() {
        assertEquals(true, controller.putModelConfig(Map.of("contextWindowTokens", 32000), request).get("success"));
        verify(service).save(7L, null, null, null, null, 32000);
        assertEquals(true, controller.putModelConfig(Map.of("contextWindowTokens", "128000"), request).get("success"));
        verify(service).save(7L, null, null, null, null, 128000);
        controller.putModelConfig(Map.of(), request);
        verify(service).save(7L, null, null, null, null, null);
    }

    @Test void rejectsFractionalOverflowAndNonNumericWindowsWithoutCallingSave() {
        for (Object invalid : List.of(32000.5, Long.MAX_VALUE, true, "abc", "99999999999999999999999", List.of(32000))) {
            Map<String, Object> result = controller.putModelConfig(Map.of("contextWindowTokens", invalid), request);
            assertEquals(false, result.get("success")); assertEquals("INVALID_CONTEXT_WINDOW", result.get("errorCode"));
        }
        verify(service, never()).save(any(), any(), any(), any(), any(), any());
    }

    @Test void explicitZeroAndNullRemainValidAutomaticWindowChoices() {
        assertEquals(true, controller.putModelConfig(Map.of("contextWindowTokens", 0), request).get("success"));
        verify(service).save(7L, null, null, null, null, 0);
        Map<String, Object> nullable = new HashMap<>(); nullable.put("contextWindowTokens", null);
        assertEquals(true, controller.putModelConfig(nullable, request).get("success"));
        verify(service).save(7L, null, null, null, null, null);
    }
}
