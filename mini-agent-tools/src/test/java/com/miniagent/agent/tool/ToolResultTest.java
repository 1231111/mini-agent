package com.miniagent.agent.tool;

import com.miniagent.common.MessageConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具结果的三种终态：成功 / 失败 / 未执行（等你批准或回答）。
 *
 * <p>第三种的形状必须是 {@code {"status":"awaiting_user", ...}} 且<b>不带 error 键</b> ——
 * 这是它能穿过所有「按文本判失败」的旧逻辑的唯一原因。
 * 一旦有人给它加回 error 键，失败反思提示、连续失败计数、轨迹状态会一起误判，
 * 而这三处都不在编译期可见的调用链上。</p>
 */
class ToolResultTest {

    @Test
    void permissionAskPendingIsAwaitingUserNotFailure() {
        String pending = String.format(
                MessageConstants.AGENT_PERM_ASK_TOOL_PENDING, "write_file", "write_file");
        ToolResult r = ToolResult.fromLegacy(pending);

        assertEquals(ToolStatus.AWAITING_USER, r.status());
        assertFalse(r.isSuccess());
        assertEquals(ToolErrorCode.NONE, r.errorCode());
        assertFalse(r.legacyText().contains("\"error\""));
    }

    @Test
    void userQuestionPendingIsAwaitingUserNotFailure() {
        ToolResult r = ToolResult.fromLegacy(MessageConstants.AGENT_USER_QUESTION_TOOL_PENDING);

        assertEquals(ToolStatus.AWAITING_USER, r.status());
        assertFalse(r.legacyText().contains("\"error\""));
    }

    @Test
    void realErrorIsStillFailure() {
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy("{\"error\":\"boom\"}").status());
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy("{\"success\":false}").status());
    }

    @Test
    void plainJsonResultIsStillSuccess() {
        assertTrue(ToolResult.fromLegacy("{\"path\":\"a.txt\"}").isSuccess());
    }

    @Test
    void successfulTextMayDiscussUnavailableAndTimeout() {
        assertTrue(ToolResult.fromLegacy("Mark unavailable fields as unknown.").isSuccess());
        assertTrue(ToolResult.fromLegacy("The guide explains what to do after a request timeout.").isSuccess());
        assertTrue(ToolResult.fromLegacy("Build output mentions HTTP 503 in a test fixture.").isSuccess());
    }

    @Test
    void explicitLegacyHttpErrorLineRemainsClassified() {
        ToolResult result = ToolResult.fromLegacy("HTTP 503 Service Unavailable");
        assertEquals(ToolStatus.FAILED, result.status());
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, result.errorCode());
        assertTrue(result.retriable());
    }

    /** Legacy errors gain the shared machine contract without losing diagnostics. */
    @Test
    void legacyFailureIsNormalizedAndKeepsHint() {
        String raw = "{\"error\":\"歌词生成请求失败\",\"hint\":\"HTTP 500：{\\\"code\\\":\\\"internal\\\"}\"}";
        ToolResult r = ToolResult.fromLegacy(raw);

        assertEquals(ToolStatus.FAILED, r.status());
        assertTrue(r.legacyText().contains("\"errorCode\":\"DEPENDENCY_UNAVAILABLE\""));
        assertTrue(r.legacyText().contains("\"retriable\":true"));
        assertTrue(r.legacyText().contains("\"hint\""));
        assertTrue(r.message().contains("HTTP 500"),
                "message 要带出 hint，否则模型只看到「歌词生成请求失败」这种笼统文案");
    }

    /** 能看到状态码时，错误码与可重试性都要判对 —— 上游 RecoveryEngine 靠它选恢复档。 */
    @Test
    void 带429的失败被判为限流且可重试() {
        ToolResult r = ToolResult.fromLegacy(
                "{\"error\":\"提交失败\",\"hint\":\"HTTP 429：too many requests\"}");

        assertEquals(ToolErrorCode.RATE_LIMITED, r.errorCode(),
                "读得到 429 就不该降级成 EXECUTION_FAILED");
        assertTrue(r.retriable());
    }

    /** 产错方显式声明的 errorCode 优先于文案猜测。 */
    @Test
    void 显式errorCode优先于文案猜测() {
        ToolResult r = ToolResult.fromLegacy(
                "{\"errorCode\":\"INVALID_ARGUMENT\",\"error\":\"参数不对\"}");

        assertEquals(ToolErrorCode.INVALID_ARGUMENT, r.errorCode());
        assertFalse(r.retriable(), "参数错误不该被判成可重试");
    }

    /** 没有状态码也没有 errorCode 时，仍按文案兜底（兼容老工具）。 */
    @Test
    void 无状态码时按文案兜底() {
        ToolResult r = ToolResult.fromLegacy("{\"error\":\"连接超时\"}");

        assertEquals(ToolErrorCode.TIMEOUT, r.errorCode());
        assertTrue(r.retriable());
    }
}
