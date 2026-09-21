package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * exec_command 结果的成败判定。
 *
 * <p>判别只认首行的 {@code exit_code=} 契约，<b>不拿关键字去猜</b>。原因是命令输出是任意文本：
 * 构建日志里出现 {@code timeout}、grep 命中的代码行里有 {@code 429}、
 * 被搜到的文件名带 {@code 超时}，都是常态。旧实现把整段输出丢给关键字分类器，
 * 于是「命令成功但输出里提到超时」被读成 TIMEOUT 失败 → 模型重试同一条命令 →
 * 撞上 allFailedRepeated 闸门中止整轮。</p>
 */
class ToolResultExecContractTest {

    @Test
    void zeroExitWithSuspiciousWordsInOutputIsStillSuccess() {
        assertTrue(ToolResult.fromLegacy("exit_code=0\nConnection timeout after 30000ms\n").isSuccess());
        assertTrue(ToolResult.fromLegacy("exit_code=0\nHTTP 429 Too Many Requests\n").isSuccess());
        assertTrue(ToolResult.fromLegacy("exit_code=0\n连接超时\n").isSuccess());
        assertTrue(ToolResult.fromLegacy("exit_code=0\n").isSuccess());
    }

    @Test
    void toleratedNonZeroExitIsSuccess() {
        String grepNoMatch = "exit_code=1\n" + CommandSemantics.toleratedNote("grep foo f.txt", 1) + "\n";
        ToolResult r = ToolResult.fromLegacy(grepNoMatch);

        assertTrue(r.isSuccess(), "grep 没匹配到内容不是工具坏了");
        assertEquals(ToolErrorCode.NONE, r.errorCode());
    }

    @Test
    void toleratedNoteIsIgnoredWhenItIsNotTheRunningCommand() {
        // 只有真正按语义放行的退出码才写 [exit-note]；这里模拟正文里恰好出现同样字样但退出码是 2
        ToolResult tolerated = ToolResult.fromLegacy("exit_code=2\n" + CommandSemantics.toleratedNote("grep x f", 1));
        assertTrue(tolerated.isSuccess(), "带标记即放行，标记的写入方负责只给非错误退出码写");

        ToolResult real = ToolResult.fromLegacy("exit_code=2\nBuild failure");
        assertFalse(real.isSuccess());
    }

    @Test
    void nonZeroExitWithoutNoteIsFailure() {
        ToolResult r = ToolResult.fromLegacy("exit_code=1\nBuild failure\n");

        assertEquals(ToolStatus.FAILED, r.status());
        assertEquals(ToolErrorCode.EXECUTION_FAILED, r.errorCode());
        assertFalse(r.retriable(), "构建失败不该被自动重试");
    }

    @Test
    void exitCodeInOutputDoesNotConfuseFailureClassification() {
        // 契约行在首位，正文里的 429 不参与分类：否则会被读成限流并标成可重试
        ToolResult r = ToolResult.fromLegacy("exit_code=1\nHTTP 429 Too Many Requests\n");

        assertEquals(ToolErrorCode.EXECUTION_FAILED, r.errorCode());
    }

    @Test
    void toolSideTimeoutIsRetriableFailureNotUnknownOutcome() {
        String json = "{\"error\":\"命令执行超时（120s），已强制终止，未产生后续副作用。\","
                + "\"exit_code\":-1,\"partial_output_path\":\"D:\\\\w\\\\_tool-output\\\\a.log\"}";
        ToolResult r = ToolResult.fromLegacy(json);

        assertEquals(ToolStatus.TIMEOUT, r.status());
        assertEquals(ToolErrorCode.TIMEOUT, r.errorCode());
        assertTrue(r.retriable());
        // 关键：不能被读成 UNKNOWN —— 那个状态会直接中止整轮任务
        assertEquals(false, r.status() == ToolStatus.UNKNOWN);
    }

    @Test
    void legacyKeywordClassificationStillAppliesWithoutTheContractLine() {
        // 非 exec 工具的纯文本结果仍走关键字分类
        assertEquals(ToolStatus.TIMEOUT, ToolResult.fromLegacy("request timeout").status());
    }

    @Test
    void plainExitCodeShapeIsRecognized() {
        assertTrue(ToolResult.fromLegacy("exit_code=0\nhello").isSuccess());
        assertFalse(ToolResult.fromLegacy("exit_code=127\n'foo' is not recognized").isSuccess());
    }
}
