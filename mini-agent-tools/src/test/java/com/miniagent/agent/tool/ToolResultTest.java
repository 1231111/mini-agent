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

    /**
     * 失败结果必须**原样保留**调用方给的文本。
     *
     * <p>此前这一层只取 {@code error} 字段再重建一份 JSON，于是服务层附带的 {@code hint}
     * 整段消失 —— 而 {@code hint} 里装的正是「HTTP &lt;码&gt;：&lt;响应体前 300 字&gt;」，
     * 是模型唯一的服务端原话来源（2026-10-15 实测）。</p>
     */
    @Test
    void 失败结果保留原文与hint() {
        String raw = "{\"error\":\"歌词生成请求失败\",\"hint\":\"HTTP 500：{\\\"code\\\":\\\"internal\\\"}\"}";
        ToolResult r = ToolResult.fromLegacy(raw);

        assertEquals(ToolStatus.FAILED, r.status());
        assertEquals(raw, r.legacyText(), "rawText 应当就是调用方给的原文，不是重建的");
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
