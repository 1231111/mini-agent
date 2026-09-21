package com.miniagent.agent.trace;

import com.miniagent.common.MessageConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「等你批准 / 等你回答」不能被判成工具失败。
 *
 * <p>这个判定是文本级的，而且被三处共用：轨迹状态、失败反思提示、连续失败计数。
 * 判错的后果不是显示难看 —— 那句「工具 X 执行失败，请换策略，不要用相同参数重试」
 * 会作为工具结果进入对话历史，模型下一轮会绕开本来正确的路径。</p>
 */
class FailedResultDetectionTest {

    @Test
    void awaitingUserIsNotFailure() {
        String pending = String.format(
                MessageConstants.AGENT_PERM_ASK_TOOL_PENDING, "write_file", "write_file");
        assertFalse(TraceRecorder.isFailedResult(pending));
        assertFalse(TraceRecorder.isFailedResult(MessageConstants.AGENT_USER_QUESTION_TOOL_PENDING));
    }

    @Test
    void realFailureIsStillFailure() {
        assertTrue(TraceRecorder.isFailedResult("{\"error\":\"boom\"}"));
        assertTrue(TraceRecorder.isFailedResult("{\"success\":false}"));
    }

    @Test
    void blankAndSuccessAreNotFailure() {
        assertFalse(TraceRecorder.isFailedResult(null));
        assertFalse(TraceRecorder.isFailedResult(""));
        assertFalse(TraceRecorder.isFailedResult("{\"path\":\"a.txt\"}"));
    }
}
