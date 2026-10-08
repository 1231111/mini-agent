package com.miniagent.web;

import com.miniagent.common.ErrorCode;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.SessionAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MiniAgentChatPageControllerAuthorizationTest {

    @Test
    void accessDeniedUsesUnifiedForbiddenResponse() {
        var response = new GlobalExceptionHandler()
                .handleAccessDenied(new AccessDeniedException("forbidden"));

        assertEquals(403, response.getStatusCode().value());
        assertEquals(ErrorCode.AUTH_FORBIDDEN.getCode(), response.getBody().getCode());
    }

    @Test
    void sessionOwnerIsRequiredBeforeFileOrPermissionAccess() {
        MiniAgentChatPageController controller = new MiniAgentChatPageController();
        SessionAuthorizationService authorization = mock(SessionAuthorizationService.class);
        ReflectionTestUtils.setField(controller, "sessionAuthorization", authorization);
        doThrow(new AccessDeniedException("forbidden"))
                .when(authorization).requireOwner(anyLong(), anyString());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_USER_ID, 7L);
        String sessionId = "another-users-session";

        assertThrows(AccessDeniedException.class,
                () -> controller.uploadFile(mock(MultipartFile.class), sessionId, request));
        assertThrows(AccessDeniedException.class,
                () -> controller.getPermissionMode(sessionId, request));
        assertThrows(AccessDeniedException.class,
                () -> controller.putPermissionMode(sessionId, Map.of(), request));
        verify(authorization, times(3)).requireOwner(7L, sessionId);
    }
}
