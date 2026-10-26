package com.miniagent.web;

import com.miniagent.common.ErrorCode;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.SessionAuthorizationService;
import com.miniagent.config.storage.MediaStorage;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void workspaceDownloadRequiresAuthenticationAndSafeFilename() {
        MiniAgentChatPageController controller = new MiniAgentChatPageController();
        MockHttpServletRequest unauthenticated = new MockHttpServletRequest();

        var authResponse = controller.downloadWorkspaceFile("report.docx", unauthenticated);
        assertEquals(401, authResponse.getStatusCode().value());

        MockHttpServletRequest authenticated = new MockHttpServletRequest();
        authenticated.setAttribute(JwtSessionService.ATTR_PRINCIPAL,
                new AuthenticatedUser(7L, 1L, "user", com.miniagent.config.entity.UserRole.USER));
        var invalidResponse = controller.downloadWorkspaceFile("../secret.docx", authenticated);
        assertEquals(400, invalidResponse.getStatusCode().value());
    }

    @Test
    void workspaceDownloadReturnsAttachmentWithUtf8FilenameAndFileBytes(@TempDir Path workspace) throws Exception {
        Path docx = workspace.resolve("冠虹尽头.docx");
        byte[] bytes = "document-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(docx, bytes);

        MiniAgentChatPageController controller = new MiniAgentChatPageController();
        MediaStorage storage = mock(MediaStorage.class);
        org.mockito.Mockito.when(storage.workspaceDir()).thenReturn(workspace);
        ReflectionTestUtils.setField(controller, "mediaStorage", storage);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_PRINCIPAL,
                new AuthenticatedUser(7L, 1L, "user", com.miniagent.config.entity.UserRole.USER));
        ResponseEntity<?> response = controller.downloadWorkspaceFile("冠虹尽头.docx", request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("attachment", response.getHeaders().getContentDisposition().getType());
        assertEquals("冠虹尽头.docx", response.getHeaders().getContentDisposition().getFilename());
        assertEquals("private, no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals(bytes.length, response.getHeaders().getContentLength());
        assertEquals("document-bytes", ((Resource) response.getBody()).getContentAsString(StandardCharsets.UTF_8));
    }

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
