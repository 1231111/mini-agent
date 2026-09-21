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
}
