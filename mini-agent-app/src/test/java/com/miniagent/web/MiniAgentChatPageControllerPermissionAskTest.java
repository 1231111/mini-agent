package com.miniagent.web;

import com.miniagent.agent.permission.AskGrant;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.SessionAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限弹窗的两个动作端点：批准必须唤醒原调用，拒绝必须让它停下。
 *
 * <p>这些用例存在的理由：前端按钮只能证明「发了什么请求」，
 * 证明不了「服务端有没有真的把批准绑到那一次调用上」。
 * 之前批准被当成一次新输入，正是因为服务端只写了一条持久授权、
 * 没有可唤醒的调用点。</p>
 */
class MiniAgentChatPageControllerPermissionAskTest {

    private static final String SESSION = "s-perm";
    private static final String ARGS = "{\"command\":\"echo hi\"}";

    @Test
    void approveResumesWaitingInvocationWithoutPersistingReplayableGrant() {
        SessionPermissionStore store = new SessionPermissionStore();
        MiniAgentChatPageController controller = controller(store);
        AskGrant grant = store.issueAskGrant(SESSION, "exec_command", ARGS, 30_000L);
        assertTrue(store.attachAskResume(SESSION, grant.token()));

        ApiResponse<Object> response = controller.putPermissionMode(
                SESSION, grantBody(grant.token()), request(7L));

        assertTrue(response.isSuccess(), response.getMessage());
        assertFalse(store.isAskGranted(SESSION, "exec_command", ARGS),
                "续跑批准只授权这一次执行，不能留下可重放授权");
        assertTrue(store.grantAskTokenForResume(SESSION, grant.token(), "exec_command").isEmpty(),
                "token 单次使用，重复点击不得再次成功");
    }

    @Test
    void approveRequiresTokenAndLiveWaiter() {
        SessionPermissionStore store = new SessionPermissionStore();
        MiniAgentChatPageController controller = controller(store);

        ApiResponse<Object> noToken = controller.putPermissionMode(
                SESSION, grantBody(""), request(7L));
        assertFalse(noToken.isSuccess());
        assertEquals(ErrorCode.CONFIG_INVALID.getCode(), noToken.getCode());

        AskGrant grant = store.issueAskGrant(SESSION, "exec_command", ARGS, 30_000L);
        ApiResponse<Object> noWaiter = controller.putPermissionMode(
                SESSION, grantBody(grant.token()), request(7L));
        assertFalse(noWaiter.isSuccess(), "没有活动等待调用时不能只凭 token 放行");
    }

    @Test
    void denyReleasesWaitingInvocationAndBurnsToken() {
        SessionPermissionStore store = new SessionPermissionStore();
        MiniAgentChatPageController controller = controller(store);
        AskGrant grant = store.issueAskGrant(SESSION, "exec_command", ARGS, 30_000L);
        assertTrue(store.attachAskResume(SESSION, grant.token()));

        ApiResponse<Object> denied = controller.putPermissionMode(
                SESSION, denyBody(grant.token()), request(7L));

        assertTrue(denied.isSuccess(), denied.getMessage());
        assertTrue(store.grantAskTokenForResume(SESSION, grant.token(), "exec_command").isEmpty(),
                "拒绝之后 token 必须失效");
        assertFalse(store.isAskGranted(SESSION, "exec_command", ARGS));
    }

    @Test
    void denyOfUnknownTokenIsRejected() {
        SessionPermissionStore store = new SessionPermissionStore();
        MiniAgentChatPageController controller = controller(store);

        ApiResponse<Object> response = controller.putPermissionMode(
                SESSION, denyBody("not-a-real-token"), request(7L));

        assertFalse(response.isSuccess());
    }

    @Test
    void anonymousCallerCannotApprove() {
        SessionPermissionStore store = new SessionPermissionStore();
        MiniAgentChatPageController controller = controller(store);
        AskGrant grant = store.issueAskGrant(SESSION, "exec_command", ARGS, 30_000L);
        assertTrue(store.attachAskResume(SESSION, grant.token()));

        ApiResponse<Object> response = controller.putPermissionMode(
                SESSION, grantBody(grant.token()), new MockHttpServletRequest());

        assertFalse(response.isSuccess());
        assertEquals(ErrorCode.AUTH_NOT_AUTHENTICATED.getCode(), response.getCode());
    }

    /**
     * 任务运行中打开「自动编辑 + 自动确认」：已经弹出去的等待点必须自己放行。
     *
     * <p>这条用例锁的是最后一环。前面几条只保证"用户点了批准能续跑"；但用户打开两个开关时
     * 根本没有要点的东西 —— 弹窗还在，等着一个不该再出现的点击。后端必须自己唤醒等待点，
     * 并让页面把弹窗收起来。</p>
     */
    @Test
    void switchingToFullAutoReleasesTheAlreadyPoppedAsk() {
        SessionPermissionStore store = new SessionPermissionStore();
        MiniAgentChatPageController controller = controller(store);
        AskGrant grant = store.issueAskGrant(SESSION, "exec_command", ARGS, 30_000L);
        assertTrue(store.attachAskResume(SESSION, grant.token()));

        Map<String, Object> body = new HashMap<>();
        body.put("mode", "accept_edits");
        body.put("confirmPolicy", "auto");

        ApiResponse<Object> response = controller.putPermissionMode(SESSION, body, request(7L));

        assertTrue(response.isSuccess(), response.getMessage());
        assertFalse(store.hasAttachedAskResume(SESSION, grant.token()),
                "全自动生效后，已弹出的等待点必须被放行，不能继续等用户点");
        assertFalse(store.isAskGranted(SESSION, "exec_command", ARGS),
                "放行是这一次的动作，不能落成可重放授权");
    }

    /** 只开一个开关不算全自动：等待点仍然要等用户，否则等于偷偷放权。 */
    @Test
    void singleSwitchDoesNotReleaseTheWaitingAsk() {
        SessionPermissionStore store = new SessionPermissionStore();
        MiniAgentChatPageController controller = controller(store);
        AskGrant grant = store.issueAskGrant(SESSION, "exec_command", ARGS, 30_000L);
        assertTrue(store.attachAskResume(SESSION, grant.token()));

        Map<String, Object> body = new HashMap<>();
        body.put("mode", "accept_edits");
        body.put("confirmPolicy", "dangerous");
        ApiResponse<Object> response = controller.putPermissionMode(SESSION, body, request(7L));

        assertTrue(response.isSuccess(), response.getMessage());
        assertTrue(store.hasAttachedAskResume(SESSION, grant.token()),
                "只开自动编辑时，命令审批仍须用户确认");
        assertTrue(store.grantAskTokenForResume(SESSION, grant.token(), "exec_command").isPresent(),
                "等待点必须仍然可以正常批准");
    }

    private static MiniAgentChatPageController controller(SessionPermissionStore store) {
        MiniAgentChatPageController controller = new MiniAgentChatPageController();
        ReflectionTestUtils.setField(controller, "permissionStore", store);
        ReflectionTestUtils.setField(controller, "execPolicyService",
                new ExecPolicyService("ask", "", store));
        ReflectionTestUtils.setField(controller, "sessionAuthorization",
                mock(SessionAuthorizationService.class));
        ReflectionTestUtils.setField(controller, "eventCenter",
                mock(com.miniagent.agent.core.SessionEventCenter.class));
        JwtSessionService jwt = mock(JwtSessionService.class);
        // Mockito 对包装返回类型（Long）给的是 0 而不是 null。不显式打桩的话，
        // 未登录请求会被当成「用户 0 已认证」，这条用例就失去意义了。
        when(jwt.resolveUserIdAndRefresh(any())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "jwtSessionService", jwt);
        return controller;
    }

    private static MockHttpServletRequest request(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_USER_ID, userId);
        return request;
    }

    private static Map<String, Object> grantBody(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "grant_ask");
        body.put("tool", "exec_command");
        body.put("grantToken", token);
        return body;
    }

    private static Map<String, Object> denyBody(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "deny_ask");
        body.put("tool", "exec_command");
        body.put("grantToken", token);
        return body;
    }
}
