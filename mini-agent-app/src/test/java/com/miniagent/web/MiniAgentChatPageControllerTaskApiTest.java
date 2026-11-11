package com.miniagent.web;

import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.application.ChatStreamingService;
import com.miniagent.common.ApiResponse;
import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.entity.FileUpload;
import com.miniagent.config.repository.AgentTraceStepRepository;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.SessionAuthorizationService;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.storage.MediaStorage;
import com.miniagent.web.dto.resp.InspectorSnapshotDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiniAgentChatPageControllerTaskApiTest {

    @Test
    void userUploadedFilesMapToOwnerScopedDownloadUrls() {
        assertEquals(List.of("/api/user-upload-download/abc123_1760000000000.pdf?name=%E6%8A%A5%E5%91%8A.pdf"),
                MiniAgentChatPageController.toConversationImageUrls(
                        "session-1", "user-uploads/abc123_1760000000000.pdf?name=%E6%8A%A5%E5%91%8A.pdf"));
        assertEquals(List.of("/api/user-upload-media/abc123_1760000000000.png?name=%E7%85%A7%E7%89%87.png"),
                MiniAgentChatPageController.toConversationImageUrls(
                        "session-1", "user-uploads/abc123_1760000000000.png?name=%E7%85%A7%E7%89%87.png"));
        assertEquals(List.of(), MiniAgentChatPageController.toConversationImageUrls(
                "session-1", "user-uploads/../secret.pdf?name=secret.pdf"));
        assertEquals(List.of(
                        "/api/conversation-media/session-1/1760000000000_0.jpg",
                        "/api/conversation-media/session-1/1760000000001_0.png"),
                MiniAgentChatPageController.toConversationImageUrls("session-1",
                        "conversation-images/session-1/1760000000000_0.jpg,conversation-images/session-1/1760000000001_0.png"));
    }

    @Test
    void legacySessionUploadsAreAttachedToTheNearestFollowingTask() {
        LocalDateTime uploadTime = LocalDateTime.of(2026, 11, 11, 9, 59);
        ChatTask task = new ChatTask();
        task.setId(42L);
        task.setCreatedAt(uploadTime.plusMinutes(1));
        task.setImages("conversation-images/session-1/old.png");
        FileUpload upload = new FileUpload();
        upload.setStoredFilename("a1b2_1760000000000.pdf");
        upload.setOriginalFilename("需求文档.pdf");
        upload.setCreatedAt(uploadTime);

        assertEquals(Map.of(42L, List.of("/api/user-upload-download/a1b2_1760000000000.pdf?name=%E9%9C%80%E6%B1%82%E6%96%87%E6%A1%A3.pdf")),
                MiniAgentChatPageController.legacyUploadsByTask(List.of(task), List.of(upload)));
    }

    @Test
    void historicalUploadedSvgUsesSandboxedInlineImageResponse(@TempDir Path uploadRoot) throws Exception {
        Path userRoot = uploadRoot.resolve("7");
        Files.createDirectories(userRoot);
        Files.writeString(userRoot.resolve("uploaded.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"></svg>");

        MiniAgentChatPageController controller = new MiniAgentChatPageController();
        MediaStorage storage = mock(MediaStorage.class);
        when(storage.uploadsDir()).thenReturn(uploadRoot);
        ReflectionTestUtils.setField(controller, "mediaStorage", storage);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_PRINCIPAL,
                new AuthenticatedUser(7L, 1L, "user", UserRole.USER));

        var response = controller.serveUserUploadedImage("uploaded.svg", request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/svg+xml", response.getHeaders().getContentType().toString());
        assertEquals("inline; filename=\"uploaded.svg\"",
                response.getHeaders().getFirst("Content-Disposition"));
        assertEquals("default-src 'none'; sandbox",
                response.getHeaders().getFirst("Content-Security-Policy"));
    }

    @Test
    void todosRequireAuthenticatedSessionOwner() {
        MiniAgentChatPageController controller = new MiniAgentChatPageController();
        TaskTodoStore store = mock(TaskTodoStore.class);
        JwtSessionService jwtSessionService = mock(JwtSessionService.class);
        SessionAuthorizationService authorization = mock(SessionAuthorizationService.class);
        ReflectionTestUtils.setField(controller, "todoStore", store);
        ReflectionTestUtils.setField(controller, "jwtSessionService", jwtSessionService);
        ReflectionTestUtils.setField(controller, "sessionAuthorization", authorization);

        MockHttpServletRequest unauthenticated = new MockHttpServletRequest();
        when(jwtSessionService.resolveUserIdAndRefresh(unauthenticated)).thenReturn(null);
        ApiResponse<?> unauthenticatedResponse = controller.listTodos("session-1", unauthenticated);
        assertEquals(false, unauthenticatedResponse.isSuccess());
        verify(store, never()).get("session-1");

        MockHttpServletRequest authenticated = new MockHttpServletRequest();
        authenticated.setAttribute(JwtSessionService.ATTR_USER_ID, 7L);
        doRequireOwner(authorization, 7L, "session-1");
        ApiResponse<?> forbiddenResponse = controller.listTodos("session-1", authenticated);
        assertEquals(true, forbiddenResponse.isSuccess());
        assertEquals(List.of(), forbiddenResponse.getData());
        verify(authorization).owns(7L, "session-1");
        verify(store, never()).get("session-1");
    }

    @Test
    void todosReturnCurrentTaskItemsAfterOwnershipCheck() {
        MiniAgentChatPageController controller = new MiniAgentChatPageController();
        TaskTodoStore store = mock(TaskTodoStore.class);
        JwtSessionService jwtSessionService = mock(JwtSessionService.class);
        SessionAuthorizationService authorization = mock(SessionAuthorizationService.class);
        ReflectionTestUtils.setField(controller, "todoStore", store);
        ReflectionTestUtils.setField(controller, "jwtSessionService", jwtSessionService);
        ReflectionTestUtils.setField(controller, "sessionAuthorization", authorization);

        var expected = List.of(new TaskTodoStore.TodoItem(
                1, "整理照片", TaskTodoStore.Status.in_progress, ""));
        when(store.get("session-1")).thenReturn(expected);
        when(authorization.owns(7L, "session-1")).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_USER_ID, 7L);
        ApiResponse<?> response = controller.listTodos("session-1", request);

        assertEquals(true, response.isSuccess());
        assertEquals(expected, response.getData());
        verify(authorization).owns(7L, "session-1");
        verify(store).get("session-1");
    }

    @Test
    void inspectorSnapshotRequiresAuthenticatedSessionOwner() {
        MiniAgentChatPageController controller = new MiniAgentChatPageController();
        TaskTodoStore store = mock(TaskTodoStore.class);
        AgentTraceStepRepository traces = mock(AgentTraceStepRepository.class);
        ChatStreamingService streaming = mock(ChatStreamingService.class);
        JwtSessionService jwtSessionService = mock(JwtSessionService.class);
        SessionAuthorizationService authorization = mock(SessionAuthorizationService.class);
        ReflectionTestUtils.setField(controller, "todoStore", store);
        ReflectionTestUtils.setField(controller, "agentTraceStepRepository", traces);
        ReflectionTestUtils.setField(controller, "streamingService", streaming);
        ReflectionTestUtils.setField(controller, "jwtSessionService", jwtSessionService);
        ReflectionTestUtils.setField(controller, "sessionAuthorization", authorization);

        MockHttpServletRequest unauthenticated = new MockHttpServletRequest();
        when(jwtSessionService.resolveUserIdAndRefresh(unauthenticated)).thenReturn(null);
        ApiResponse<?> unauthenticatedResponse = controller.inspectorSnapshot("session-1", unauthenticated);
        assertEquals(false, unauthenticatedResponse.isSuccess());
        verify(store, never()).get("session-1");
        verify(traces, never()).findBySessionIdOrderByTurnIndexAscIdAsc("session-1");

        MockHttpServletRequest authenticated = new MockHttpServletRequest();
        authenticated.setAttribute(JwtSessionService.ATTR_USER_ID, 7L);
        doRequireOwner(authorization, 7L, "session-1");
        ApiResponse<?> forbiddenResponse = controller.inspectorSnapshot("session-1", authenticated);
        assertEquals(true, forbiddenResponse.isSuccess());
        assertEquals(List.of(), ((InspectorSnapshotDTO) forbiddenResponse.getData()).todos());
        verify(authorization).owns(7L, "session-1");
        verify(store, never()).get("session-1");
        verify(traces, never()).findBySessionIdOrderByTurnIndexAscIdAsc("session-1");
    }

    @Test
    void inspectorSnapshotReturnsOnlyLatestExecutionWithTodosAndRunningState() {
        MiniAgentChatPageController controller = new MiniAgentChatPageController();
        TaskTodoStore store = mock(TaskTodoStore.class);
        AgentTraceStepRepository traces = mock(AgentTraceStepRepository.class);
        ChatStreamingService streaming = mock(ChatStreamingService.class);
        JwtSessionService jwtSessionService = mock(JwtSessionService.class);
        SessionAuthorizationService authorization = mock(SessionAuthorizationService.class);
        ReflectionTestUtils.setField(controller, "todoStore", store);
        ReflectionTestUtils.setField(controller, "agentTraceStepRepository", traces);
        ReflectionTestUtils.setField(controller, "streamingService", streaming);
        ReflectionTestUtils.setField(controller, "jwtSessionService", jwtSessionService);
        ReflectionTestUtils.setField(controller, "sessionAuthorization", authorization);

        var todos = List.of(new TaskTodoStore.TodoItem(
                1, "搜索今日 AI 新闻", TaskTodoStore.Status.in_progress, ""));
        when(authorization.owns(7L, "session-1")).thenReturn(true);
        when(store.get("session-1")).thenReturn(todos);
        when(streaming.isTaskRunning("session-1")).thenReturn(true);
        when(traces.findBySessionIdOrderByTurnIndexAscIdAsc("session-1")).thenReturn(List.of(
                trace(1L, "execution-old", 0, "AGENT_LOOP_START", "RUNNING", "old question"),
                trace(2L, "execution-new", 0, "AGENT_LOOP_START", "RUNNING", "今日 AI 新闻"),
                trace(3L, "execution-new", 1, "ANSWER", "SUCCESS", "新闻摘要")));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_USER_ID, 7L);
        ApiResponse<InspectorSnapshotDTO> response = controller.inspectorSnapshot("session-1", request);

        assertEquals(true, response.isSuccess());
        assertEquals(todos, response.getData().todos());
        assertEquals(true, response.getData().running());
        assertEquals("execution-new", response.getData().execution().executionId());
        assertEquals("今日 AI 新闻", response.getData().execution().userQuestion());
        assertEquals(2, response.getData().execution().stepCount());
        assertEquals("SUCCESS", response.getData().execution().status());
        assertEquals(List.of(2L, 3L), response.getData().steps().stream().map(AgentTraceStep::getId).toList());
        verify(store).get("session-1");
        verify(streaming).isTaskRunning("session-1");
        verify(traces).findBySessionIdOrderByTurnIndexAscIdAsc("session-1");
    }

    @Test
    void pauseTaskRequiresOwnerAndRequestsCheckpointPause() {
        MiniAgentChatPageController controller = new MiniAgentChatPageController();
        var service = mock(com.miniagent.application.AgentChatApplicationService.class);
        ChatStreamingService streaming = mock(ChatStreamingService.class);
        JwtSessionService jwtSessionService = mock(JwtSessionService.class);
        SessionAuthorizationService authorization = mock(SessionAuthorizationService.class);
        ReflectionTestUtils.setField(controller, "agentService", service);
        ReflectionTestUtils.setField(controller, "streamingService", streaming);
        ReflectionTestUtils.setField(controller, "jwtSessionService", jwtSessionService);
        ReflectionTestUtils.setField(controller, "sessionAuthorization", authorization);
        when(authorization.owns(7L, "session-1")).thenReturn(true);
        when(streaming.isTaskRunning("session-1")).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_USER_ID, 7L);
        ApiResponse<?> response = controller.pauseChat("session-1", request);

        assertEquals(true, response.isSuccess());
        verify(service).pause("session-1");
    }

    private static AgentTraceStep trace(
            Long id, String executionId, int turn, String type, String status, String content) {
        AgentTraceStep step = new AgentTraceStep();
        step.setId(id);
        step.setSessionId("session-1");
        step.setExecutionId(executionId);
        step.setTurnIndex(turn);
        step.setStepType(type);
        step.setStatus(status);
        step.setContent(content);
        step.setUserQuestion(turn == 0 ? content : "今日 AI 新闻");
        step.setCreatedAt(LocalDateTime.of(2026, 11, 10, 9, 0).plusSeconds(id));
        return step;
    }

    private static void doRequireOwner(
            SessionAuthorizationService authorization, long userId, String sessionId) {
        org.mockito.Mockito.when(authorization.owns(userId, sessionId)).thenReturn(false);
    }
}
