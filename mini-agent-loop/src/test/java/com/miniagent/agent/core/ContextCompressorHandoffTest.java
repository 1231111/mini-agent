package com.miniagent.agent.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文压缩的**结构化交接**回归测试。
 *
 * <p>背景：默认配置（{@code llm-summary-enabled=false}）下，压缩会把中段消息整体丢弃、
 * 只留一句"已硬截断移除中间 N 条消息"。模型于是不知道自己已经做过什么 ——
 * 常见后果是重跑同一批命令（烧预算）或重新写一遍已有文件。
 * 现在从被丢弃的消息里抽出低幻觉风险的事实（写过/改过哪些文件、执行过哪些命令、哪些失败了），
 * 成本只是字符串处理，不引入 LLM 调用。</p>
 */
class ContextCompressorHandoffTest {

    private static ToolExecutionRequest call(String id, String name, String args) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(args).build();
    }

    @Test
    void handoffListsWrittenFilesCommandsAndFailures() {
        List<ChatMessage> removed = new ArrayList<>();
        removed.add(AiMessage.from(List.of(
                call("c1", "write_file", "{\"path\":\"workspace/report.md\",\"content\":\"x\"}"),
                call("c2", "exec_command", "{\"command\":\"mvnw -q test\"}"))));
        removed.add(ToolExecutionResultMessage.from("c1", "write_file", "{\"success\":true}"));
        removed.add(ToolExecutionResultMessage.from("c2", "exec_command",
                "{\"error\":\"exit 1: 测试失败\"}"));
        removed.add(UserMessage.from("继续"));

        String stub = ContextCompressor.buildHandoffStub(removed);

        assertTrue(stub.contains("workspace/report.md"), "必须记住写过哪些文件: " + stub);
        assertTrue(stub.contains("mvnw -q test"), "必须记住执行过哪些命令: " + stub);
        assertTrue(stub.contains("已失败的操作"), "失败过的操作要单独列出，避免原样重试: " + stub);
        assertTrue(stub.contains("不要重复已完成的工作"), "要明确要求不重复已完成的工作");
        assertTrue(stub.contains("不要凭记忆编造"), "要说清细节来源，抑制编造中段内容");
        assertFalse(stub.contains("已硬截断移除中间"), "旧的空占位不该再出现");
    }

    @Test
    void handoffSeparatesEditsFromCreates() {
        List<ChatMessage> removed = new ArrayList<>();
        removed.add(AiMessage.from(List.of(
                call("c1", "write_file", "{\"path\":\"a.md\"}"),
                call("c2", "edit_file", "{\"path\":\"b.java\"}"))));

        String stub = ContextCompressor.buildHandoffStub(removed);

        assertTrue(stub.contains("已写入/创建的文件"), stub);
        assertTrue(stub.contains("a.md"), stub);
        assertTrue(stub.contains("已修改的文件"), "编辑与新建要分开，语义不同: " + stub);
        assertTrue(stub.contains("b.java"), stub);
    }

    @Test
    void handoffDoesNotFabricateWhenNothingHappened() {
        // 只有闲聊、没有工具调用：交接不该编出"已写入文件"之类的事实
        String stub = ContextCompressor.buildHandoffStub(List.of(UserMessage.from("你好")));
        assertTrue(stub.contains("0 条工具结果"), "要如实说明没有工具结果: " + stub);
        assertFalse(stub.contains("已写入/创建的文件"), "没写过文件就不能声称写过: " + stub);
    }

    @Test
    void handoffIsBounded() {
        // 长任务里工具调用可能成百上千：交接必须有上限，否则压缩本身又变成新的上下文膨胀
        List<ChatMessage> removed = new ArrayList<>();
        List<ToolExecutionRequest> calls = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            calls.add(call("c" + i, "read_file", "{\"path\":\"f" + i + ".java\"}"));
        }
        removed.add(AiMessage.from(calls));

        String stub = ContextCompressor.buildHandoffStub(removed);

        long listed = stub.lines().filter(l -> l.startsWith("- ")).count();
        assertTrue(listed <= 12, "列表项必须有上限（当前 " + listed + " 项）");
    }

    @Test
    void emptyMiddleProducesPlainNotice() {
        String stub = ContextCompressor.buildHandoffStub(List.of());
        assertTrue(stub.contains("中间对话为空"), stub);
    }
}
