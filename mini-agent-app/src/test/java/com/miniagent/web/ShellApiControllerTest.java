package com.miniagent.web;

import com.miniagent.agent.mcp.McpToolBridge;
import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.entity.AgentSemanticFactEntity;
import com.miniagent.agent.memory.repository.AgentEpisodeRepository;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.memory.repository.AgentSemanticFactRepository;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.OpenAiModelProperties;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.SessionAuthorizationService;
import com.miniagent.config.service.UserModelConfigService;
import com.miniagent.memory.MemoryKeys;
import com.miniagent.memory.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ShellApiController 直接调用测试（不加载完整 Spring 上下文）。 */
class ShellApiControllerTest {

    private ShellApiController controller;
    private JwtSessionService jwtSessionService;
    private SessionAuthorizationService sessionAuthorization;
    /** 用真实内存版 Store，验证 controller → Store 的端到端行为。 */
    private SessionPermissionStore permissionStore;
    private UserModelConfigService userModelConfigService;
    private OpenAiModelProperties openAiModelProperties;
    private ToolRegistry toolRegistry;
    private McpToolBridge mcpToolBridge;
    private AgentMemoryEntryRepository memoryEntryRepository;
    private AgentSemanticFactRepository semanticFactRepository;
    private AgentProcedureRepository procedureRepository;
    private AgentEpisodeRepository episodeRepository;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        controller = new ShellApiController();
        jwtSessionService = mock(JwtSessionService.class);
        // Mockito 对 Long 返回值默认给 0L（不是 null），会伪装成「userId=0 已登录」。
        // 显式声明「无 Bearer 时解析不到用户」，未登录用例才成立。
        when(jwtSessionService.resolveUserIdAndRefresh(any())).thenReturn(null);
        sessionAuthorization = mock(SessionAuthorizationService.class);
        permissionStore = new SessionPermissionStore();
        userModelConfigService = mock(UserModelConfigService.class);
        openAiModelProperties = mock(OpenAiModelProperties.class);
        toolRegistry = mock(ToolRegistry.class);
        mcpToolBridge = mock(McpToolBridge.class);
        memoryEntryRepository = mock(AgentMemoryEntryRepository.class);
        semanticFactRepository = mock(AgentSemanticFactRepository.class);
        procedureRepository = mock(AgentProcedureRepository.class);
        episodeRepository = mock(AgentEpisodeRepository.class);
        memoryService = mock(MemoryService.class);
        when(memoryService.read(MemoryKeys.TARGET_USER)).thenReturn(List.of());

        ReflectionTestUtils.setField(controller, "jwtSessionService", jwtSessionService);
        ReflectionTestUtils.setField(controller, "sessionAuthorization", sessionAuthorization);
        ReflectionTestUtils.setField(controller, "permissionStore", permissionStore);
        ReflectionTestUtils.setField(controller, "userModelConfigService", userModelConfigService);
        ReflectionTestUtils.setField(controller, "openAiModelProperties", openAiModelProperties);
        ReflectionTestUtils.setField(controller, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(controller, "mcpToolBridge", mcpToolBridge);
        ReflectionTestUtils.setField(controller, "memoryEntryRepository", memoryEntryRepository);
        ReflectionTestUtils.setField(controller, "semanticFactRepository", semanticFactRepository);
        ReflectionTestUtils.setField(controller, "procedureRepository", procedureRepository);
        ReflectionTestUtils.setField(controller, "episodeRepository", episodeRepository);
        ReflectionTestUtils.setField(controller, "memoryService", memoryService);
    }

    private MockHttpServletRequest authed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_USER_ID, 7L);
        request.setAttribute(JwtSessionService.ATTR_PRINCIPAL,
                new AuthenticatedUser(7L, 1L, "alice", UserRole.USER));
        return request;
    }

    private MockHttpServletRequest anonymous() {
        return new MockHttpServletRequest();
    }

    private static void assertFail(ApiResponse<?> resp, ErrorCode expected) {
        assertFalse(resp.isSuccess());
        assertEquals(expected.getCode(), resp.getCode());
    }

    // ==================== 鉴权 ====================

    @Test
    void everyEndpointRequiresAuthentication() {
        assertFail(controller.status(anonymous()), ErrorCode.AUTH_NOT_AUTHENTICATED);
        assertFail(controller.alwaysAllow(
                        Map.of("sessionId", "s", "tool", "x"), anonymous()),
                ErrorCode.AUTH_NOT_AUTHENTICATED);
        assertFail(controller.revokeAlwaysAllow(
                        Map.of("sessionId", "s", "tool", "x"), anonymous()),
                ErrorCode.AUTH_NOT_AUTHENTICATED);
        assertFail(controller.getAlways("s", anonymous()),
                ErrorCode.AUTH_NOT_AUTHENTICATED);
        assertFail(controller.memoryInsights("s", 30, anonymous()),
                ErrorCode.AUTH_NOT_AUTHENTICATED);
        // 未登录时不应走到会话归属校验
        verify(sessionAuthorization, never()).requireOwner(any(), any());
        verify(memoryService, never()).read(any());
    }

    /** 「始终允许」三个端点仍然要求 sessionId；memory-insights 不要求（租户视图）。 */
    @Test
    void blankSessionIdOnPermissionEndpointsReturnsConfigInvalid() {
        assertFail(controller.alwaysAllow(
                        Map.of("sessionId", "", "tool", "x"), authed()),
                ErrorCode.CONFIG_INVALID);
        assertFail(controller.getAlways(null, authed()),
                ErrorCode.CONFIG_INVALID);
        assertFail(controller.alwaysAllow(
                        Map.of("sessionId", "s1", "tool", ""), authed()),
                ErrorCode.CONFIG_INVALID);
    }

    @Test
    void notOwnedSessionIsRejected() {
        doThrow(new AccessDeniedException("forbidden"))
                .when(sessionAuthorization).requireOwner(eq(7L), eq("not-mine"));

        assertThrows(AccessDeniedException.class,
                () -> controller.alwaysAllow(
                        Map.of("sessionId", "not-mine", "tool", "x"), authed()));
        assertThrows(AccessDeniedException.class,
                () -> controller.revokeAlwaysAllow(
                        Map.of("sessionId", "not-mine", "tool", "x"), authed()));
        assertThrows(AccessDeniedException.class,
                () -> controller.getAlways("not-mine", authed()));
        assertThrows(AccessDeniedException.class,
                () -> controller.memoryInsights("not-mine", 30, authed()));

        // 该异常由全局异常处理器映射成 403 / AUTH_FORBIDDEN（与现有端点一致）
        var handled = new GlobalExceptionHandler()
                .handleAccessDenied(new AccessDeniedException("forbidden"));
        assertEquals(403, handled.getStatusCode().value());
        assertEquals(ErrorCode.AUTH_FORBIDDEN.getCode(), handled.getBody().getCode());
        // 越权时没有写入任何 always 数据
        assertTrue(permissionStore.alwaysAllowedTools("not-mine").isEmpty());
        verify(memoryService, never()).read(any());
    }

    // ==================== /api/shell/status ====================

    @Test
    void statusReturnsRealStructure() {
        when(userModelConfigService.getView(7L)).thenReturn(
                Map.of("current", Map.of("modelName", "qwen-plus")));
        when(openAiModelProperties.getChatModelName()).thenReturn("global-model");
        when(toolRegistry.getToolNames()).thenReturn(
                Set.of("read_file", "write_file", "exec_command"));
        when(mcpToolBridge.registeredToolNames()).thenReturn(
                List.of("mcp_search", "read_file"));

        ApiResponse<Map<String, Object>> resp = controller.status(authed());
        assertTrue(resp.isSuccess());
        Map<String, Object> data = resp.getData();
        assertEquals(4, data.size());
        // 模型名与 GET /api/model 同源（getView.current.modelName）
        assertEquals("qwen-plus", data.get("modelName"));
        // 3 内置 + 1 MCP，去掉重复的 read_file
        assertEquals(4, data.get("toolsAvailable"));
        assertTrue(Set.of("正常", "偏高", "紧张")
                .contains(String.valueOf(data.get("memoryState"))));
        assertEquals("运行正常", data.get("status"));
    }

    @Test
    void modelNameFallsBackToOpenAiProperties() {
        when(userModelConfigService.getView(7L)).thenReturn(Map.of());
        when(openAiModelProperties.getChatModelName()).thenReturn("real-global-model");

        ApiResponse<Map<String, Object>> resp = controller.status(authed());
        assertEquals("real-global-model", resp.getData().get("modelName"));
    }

    // ==================== 「始终允许」====================

    @Test
    void alwaysAllowRevokeGetHappyPath() {
        ApiResponse<List<String>> a = controller.alwaysAllow(
                Map.of("sessionId", "s1", "tool", "exec_command"), authed());
        assertTrue(a.isSuccess());
        assertEquals(List.of("exec_command"), a.getData());

        ApiResponse<List<String>> b = controller.alwaysAllow(
                Map.of("sessionId", "s1", "tool", "read_file"), authed());
        assertEquals(List.of("exec_command", "read_file"), b.getData());

        ApiResponse<List<String>> g = controller.getAlways("s1", authed());
        assertTrue(g.isSuccess());
        assertEquals(List.of("exec_command", "read_file"), g.getData());

        ApiResponse<List<String>> d = controller.revokeAlwaysAllow(
                Map.of("sessionId", "s1", "tool", "exec_command"), authed());
        assertTrue(d.isSuccess());
        assertEquals(List.of("read_file"), d.getData());

        assertTrue(permissionStore.isAlwaysAllowed("s1", "read_file"));
        assertFalse(permissionStore.isAlwaysAllowed("s1", "exec_command"));
        assertTrue(permissionStore.isAskGranted("s1", "read_file", "任意参数"));
    }

    // ==================== /api/shell/memory-insights ====================

    @Test
    @SuppressWarnings("unchecked")
    void memoryInsightsReturnsCompleteEmptyStructure() {
        // 所有 repository 由 Mockito 默认返回空集合 / 0，模拟空库
        ApiResponse<Map<String, Object>> resp =
                controller.memoryInsights("s-empty", 30, authed());
        assertTrue(resp.isSuccess());
        Map<String, Object> data = resp.getData();
        assertEquals(9, data.size());
        assertEquals("s-empty", data.get("sessionId"));
        assertEquals(Boolean.FALSE, data.get("saved"));
        assertEquals(Boolean.TRUE, data.get("preferencesAvailable"));
        assertTrue(((List<?>) data.get("userPreferences")).isEmpty());
        assertTrue(((List<?>) data.get("userMemories")).isEmpty());
        assertTrue(((List<?>) data.get("related")).isEmpty());
        assertTrue(((List<?>) data.get("tags")).isEmpty());
        Map<String, Object> stats = (Map<String, Object>) data.get("stats");
        assertEquals(5, stats.size());
        assertEquals(0L, stats.get("memoryCount"));
        assertEquals(0L, stats.get("factCount"));
        assertEquals(0L, stats.get("procedureCount"));
        assertEquals(0L, stats.get("episodeCount"));
        assertEquals(0L, stats.get("relatedCount"));
        Map<String, Object> userStats = (Map<String, Object>) data.get("userStats");
        assertEquals(5, userStats.size());
        assertEquals(0L, userStats.get("memoryCount"));
        assertEquals(0L, userStats.get("memoryEntryCount"));
        assertEquals(0L, userStats.get("preferenceCount"));
        assertEquals(0L, userStats.get("factCount"));
        assertEquals(0L, userStats.get("procedureCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void memoryInsightsReturnsRealData() {
        AgentEpisodeEntity ep = new AgentEpisodeEntity();
        ep.setSessionId("s-real");
        ep.setTaskSummary("完成重构");
        ep.setOutcome(AgentEpisodeEntity.Outcome.SUCCESS);

        AgentSemanticFactEntity fact = new AgentSemanticFactEntity();
        fact.setSubject("语言");
        fact.setPredicate("是");
        fact.setObjectValue("Java");

        AgentProcedureEntity proc = new AgentProcedureEntity();
        proc.setName("部署流程");
        proc.setProcedureVersion(2);
        AgentMemoryEntryEntity memoryEntry = new AgentMemoryEntryEntity();
        memoryEntry.setMemoryType(AgentMemoryEntryEntity.MemoryType.USER);
        memoryEntry.setSummary("偏好摘要");
        memoryEntry.setContent("用户偏好内容");

        when(episodeRepository.findBySessionId("s-real")).thenReturn(List.of(ep));
        when(memoryEntryRepository.countByTenantIdAndStatus(
                eq("1"), eq(AgentMemoryEntryEntity.Status.ACTIVE))).thenReturn(5L);
        when(semanticFactRepository.countByTenantId("1")).thenReturn(3L);
        when(procedureRepository.countByTenantId("1")).thenReturn(2L);
        when(memoryEntryRepository.countByTenantIdAndScopeTypeAndScopeIdAndStatus(
                eq("1"), eq(AgentMemoryEntryEntity.ScopeType.USER), eq("7"),
                eq(AgentMemoryEntryEntity.Status.ACTIVE))).thenReturn(1L);
        when(memoryEntryRepository.findByTenantIdAndScopeTypeAndScopeIdAndStatusOrderByUpdatedAtDesc(
                eq("1"), eq(AgentMemoryEntryEntity.ScopeType.USER), eq("7"),
                eq(AgentMemoryEntryEntity.Status.ACTIVE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(memoryEntry)));
        when(semanticFactRepository.countActiveFacts(
                eq("1"), eq(AgentMemoryEntryEntity.ScopeType.USER), eq("7"))).thenReturn(1L);
        when(procedureRepository.countByTenantIdAndScopeTypeAndScopeIdAndStatus(
                eq("1"), eq(AgentMemoryEntryEntity.ScopeType.USER), eq("7"),
                eq(AgentMemoryEntryEntity.Status.ACTIVE))).thenReturn(1L);
        when(semanticFactRepository.findActiveFacts(
                eq("1"), eq(AgentMemoryEntryEntity.ScopeType.USER), eq("7")))
                .thenReturn(List.of(fact));
        when(procedureRepository.findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                eq("1"), eq(AgentMemoryEntryEntity.ScopeType.USER), eq("7"),
                eq(AgentMemoryEntryEntity.Status.ACTIVE)))
                .thenReturn(List.of(proc));

        ApiResponse<Map<String, Object>> resp =
                controller.memoryInsights("s-real", 30, authed());
        assertTrue(resp.isSuccess());
        Map<String, Object> data = resp.getData();
        assertEquals(Boolean.TRUE, data.get("saved"));
        assertEquals(3, ((List<?>) data.get("related")).size());
        assertEquals(1, ((List<?>) data.get("userMemories")).size());
        List<?> tags = (List<?>) data.get("tags");
        assertTrue(tags.contains("SUCCESS"));
        assertTrue(tags.contains("语言"));
        assertTrue(tags.contains("部署流程"));

        Map<String, Object> stats = (Map<String, Object>) data.get("stats");
        assertEquals(5L, stats.get("memoryCount"));
        assertEquals(3L, stats.get("factCount"));
        assertEquals(2L, stats.get("procedureCount"));
        assertEquals(1L, stats.get("episodeCount"));
        assertEquals(3L, stats.get("relatedCount"));
        Map<String, Object> userStats = (Map<String, Object>) data.get("userStats");
        assertEquals(1L, userStats.get("memoryCount"));
        assertEquals(1L, userStats.get("memoryEntryCount"));
        assertEquals(0L, userStats.get("preferenceCount"));
        assertEquals(1L, userStats.get("factCount"));
        assertEquals(1L, userStats.get("procedureCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void memoryTotalIncludesAllDistinctPersistedPreferencesBeyondDisplayLimit() {
        when(memoryService.read(MemoryKeys.TARGET_USER))
                .thenReturn(List.of("偏好 A", "偏好 B", "偏好 A"));
        when(memoryEntryRepository.countByTenantIdAndScopeTypeAndScopeIdAndStatus(
                eq("1"), eq(AgentMemoryEntryEntity.ScopeType.USER), eq("7"),
                eq(AgentMemoryEntryEntity.Status.ACTIVE))).thenReturn(2L);

        ApiResponse<Map<String, Object>> response = controller.memoryInsights(null, 1, authed());
        assertTrue(response.isSuccess());
        Map<String, Object> data = response.getData();
        Map<String, Object> userStats = (Map<String, Object>) data.get("userStats");
        assertEquals(4L, userStats.get("memoryCount"));
        assertEquals(2L, userStats.get("memoryEntryCount"));
        assertEquals(2L, userStats.get("preferenceCount"));
        assertEquals(1, ((List<?>) data.get("userPreferences")).size());
    }

    /**
     * 不带 sessionId 时是「租户视图」：统计照给，情节为空。
     * 这种情况不需要会话归属校验 —— 没有会话可越权，数据仍按 tenant 隔离。
     */
    @Test
    @SuppressWarnings("unchecked")
    void memoryInsightsWithoutSessionIdIsTenantWideView() {
        when(memoryEntryRepository.countByTenantIdAndStatus(
                eq("1"), eq(AgentMemoryEntryEntity.Status.ACTIVE))).thenReturn(2L);
        when(semanticFactRepository.countByTenantId("1")).thenReturn(4L);
        when(procedureRepository.countByTenantId("1")).thenReturn(1L);

        ApiResponse<Map<String, Object>> resp = controller.memoryInsights(null, 30, authed());
        assertTrue(resp.isSuccess());
        Map<String, Object> data = resp.getData();
        assertEquals("", data.get("sessionId"));
        assertEquals(Boolean.FALSE, data.get("saved"));
        Map<String, Object> stats = (Map<String, Object>) data.get("stats");
        assertEquals(2L, stats.get("memoryCount"));
        assertEquals(4L, stats.get("factCount"));
        assertEquals(1L, stats.get("procedureCount"));
        assertEquals(0L, stats.get("episodeCount"));
        // 租户视图不查 episode，也不做归属校验
        verify(episodeRepository, never()).findBySessionId(any());
        verify(sessionAuthorization, never()).requireOwner(any(), any());
    }

    /** limit 会被夹到 1..500：传 0 与传超大值都不该让响应失控。 */
    @Test
    @SuppressWarnings("unchecked")
    void memoryInsightsClampsLimit() {
        List<AgentSemanticFactEntity> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            AgentSemanticFactEntity f = new AgentSemanticFactEntity();
            f.setSubject("s" + i);
            f.setPredicate("p");
            f.setObjectValue("o");
            many.add(f);
        }
        when(semanticFactRepository.findActiveFacts(
                eq("1"), eq(AgentMemoryEntryEntity.ScopeType.USER), eq("7")))
                .thenReturn(many);

        ApiResponse<Map<String, Object>> zero = controller.memoryInsights(null, 0, authed());
        assertEquals(1, ((List<?>) zero.getData().get("related")).size());

        ApiResponse<Map<String, Object>> huge = controller.memoryInsights(null, 100000, authed());
        assertEquals(40, ((List<?>) huge.getData().get("related")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistedPreferencesHaveIndependentLimitAndNewestFirstOrder() {
        AgentEpisodeEntity episode = new AgentEpisodeEntity();
        episode.setTaskSummary("An old task summary");
        when(episodeRepository.findBySessionId("s-memory"))
                .thenReturn(List.of(episode, episode));
        when(memoryService.read(MemoryKeys.TARGET_USER))
                .thenReturn(List.of("旧偏好", "人物简介须包含真实照片与来源链接"));

        Map<String, Object> data = controller.memoryInsights("s-memory", 1, authed()).getData();
        assertEquals(1, ((List<?>) data.get("related")).size());
        List<Map<String, Object>> preferences =
                (List<Map<String, Object>>) data.get("userPreferences");
        assertEquals(List.of(Map.of("content", "人物简介须包含真实照片与来源链接",
                "target", "user", "persisted", true)), preferences);
        assertEquals(Boolean.TRUE, data.get("preferencesAvailable"));
    }

    @Test
    void preferencesReadUsesAuthenticatedOwnerAndRestoresAmbientContext() {
        try (var ignored = OwnerContext.bind(new OwnerContext(99L, "other-tenant"))) {
            OwnerContext.setCurrentProjectId("ambient-project");
            when(memoryService.read(MemoryKeys.TARGET_USER)).thenAnswer(call -> {
                assertEquals(7L, OwnerContext.currentUserId());
                assertEquals("1", OwnerContext.currentTenantId());
                assertEquals(null, OwnerContext.currentProjectId());
                return List.of("当前用户偏好");
            });

            Map<String, Object> data = controller.memoryInsights(null, 30, authed()).getData();
            assertEquals(Boolean.TRUE, data.get("preferencesAvailable"));
            assertEquals(99L, OwnerContext.currentUserId());
            assertEquals("other-tenant", OwnerContext.currentTenantId());
            assertEquals("ambient-project", OwnerContext.currentProjectId());
        }
    }

    @Test
    void preferenceReadFailureIsExplicitAndRestoresAmbientContext() {
        when(memoryService.read(MemoryKeys.TARGET_USER))
                .thenThrow(new IllegalStateException("private storage failure details"));
        try (var ignored = OwnerContext.bind(new OwnerContext(99L, "other-tenant"))) {
            OwnerContext.setCurrentProjectId("ambient-project");
            Map<String, Object> data = controller.memoryInsights(null, 30, authed()).getData();
            assertEquals(Boolean.FALSE, data.get("preferencesAvailable"));
            assertTrue(((List<?>) data.get("userPreferences")).isEmpty());
            assertFalse(data.toString().contains("private storage"));
            assertEquals(99L, OwnerContext.currentUserId());
            assertEquals("other-tenant", OwnerContext.currentTenantId());
            assertEquals("ambient-project", OwnerContext.currentProjectId());
        }
    }

    @Test
    void absentPreferenceServiceAndNullReadAreUnavailableRatherThanEmptySuccess() {
        ReflectionTestUtils.setField(controller, "memoryService", null);
        Map<String, Object> absent = controller.memoryInsights(null, 30, authed()).getData();
        assertEquals(Boolean.FALSE, absent.get("preferencesAvailable"));
        assertTrue(((List<?>) absent.get("userPreferences")).isEmpty());

        ReflectionTestUtils.setField(controller, "memoryService", memoryService);
        when(memoryService.read(MemoryKeys.TARGET_USER)).thenReturn(null);
        Map<String, Object> nullRead = controller.memoryInsights(null, 30, authed()).getData();
        assertEquals(Boolean.FALSE, nullRead.get("preferencesAvailable"));
        assertTrue(((List<?>) nullRead.get("userPreferences")).isEmpty());
    }
}
