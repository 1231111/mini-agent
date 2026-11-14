package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.tool.CapabilityRegistry;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import com.miniagent.agent.tool.WorkspaceContext;
import java.nio.file.Path;
import java.nio.file.Files;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeOutputBinderTest {
    @TempDir Path workspace;
    @BeforeEach void bindWorkspace() { WorkspaceContext.setRootOverride(workspace); }
    @AfterEach void clearWorkspace() { WorkspaceContext.clearRootOverride(); }

    @Test
    void prefersTodoOverTools() {
        TaskNode n = acquire("source_text");
        NodeOutputBinder.Bound b = NodeOutputBinder.bind(
                n, "todo-body",
                List.of(ToolExecutionResultMessage.from("1", "web_search", "tool-body")),
                "chat");
        assertEquals("todo-body", b.evidence());
        assertEquals("todo-body", b.bindings().get("source_text"));
        assertTrue(b.complete(n));
    }

    @Test
    void hollowTodoFallsThroughToTools() {
        TaskNode n = acquire("source_text");
        NodeOutputBinder.Bound b = NodeOutputBinder.bind(
                n, AgentLoop.STEP_SEGMENT_DONE,
                List.of(ToolExecutionResultMessage.from("1", "web_extract", "extracted")),
                "chat");
        assertEquals("extracted", b.evidence());
        assertEquals("extracted", b.bindings().get("source_text"));
    }

    @Test
    void skipsTodoToolAndDoesNotUseChat() {
        TaskNode n = acquire("source_text");
        NodeOutputBinder.Bound b = NodeOutputBinder.bind(
                n, "",
                List.of(ToolExecutionResultMessage.from(
                        "1", CapabilityRegistry.FALLBACK_TOOL, "updated")),
                "a long chat paragraph pretending to be evidence");
        assertEquals("", b.evidence());
        assertFalse(b.complete(n));
    }

    @Test
    void bindsFilePathToDeclaredPorts() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "actual artifact");
        TaskNode n = new TaskNode("n2", "写入 notes.md", "file_write",
                List.of("n1"), List.of("source_text"), List.of("notes.md"),
                TaskNodeStatus.PENDING, 9, DoneWhen.file("notes.md"),
                "", "", 0, "");
        NodeOutputBinder.Bound b = NodeOutputBinder.bind(n, "", List.of(), "chat");
        assertEquals("notes.md", b.bindings().get("notes.md"));
        assertEquals("notes.md", b.evidence());
        assertTrue(b.complete(n));
    }

    @Test
    void bindsJsonNamedPorts() {
        TaskNode n = acquire("source_text");
        String json = "{\"outputs\":{\"source_text\":\"doc body\"}}";
        NodeOutputBinder.Bound b = NodeOutputBinder.bind(
                n, json, List.of(), "chat");
        assertEquals("doc body", b.bindings().get("source_text"));
        assertTrue(b.complete(n));
    }

    @Test
    void bindsWriteFilePathFromToolJson() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "actual artifact");
        TaskNode n = new TaskNode("n2", "写入", "file_write",
                List.of(), List.of(), List.of("notes.md"),
                TaskNodeStatus.PENDING, 9, DoneWhen.file("notes.md"),
                "", "", 0, "");
        String json = "{\"success\":true,\"path\":\"workspace/notes.md\",\"bytes_written\":12}";
        NodeOutputBinder.Bound b = NodeOutputBinder.bind(
                n, "",
                List.of(ToolExecutionResultMessage.from("1", "write_file", json)),
                "chat");
        assertTrue(b.bindings().get("notes.md").endsWith("notes.md"));
        assertTrue(b.complete(n));
    }

    @Test
    void judgeUsesAnswerWhenNoTools() {
        TaskNode n = new TaskNode("n3", "校验", "deliver",
                List.of("n2"), List.of(), List.of("n3_out"),
                TaskNodeStatus.PENDING, 8, DoneWhen.judge("覆盖章节"),
                "", "", 0, "");
        NodeOutputBinder.Bound b = NodeOutputBinder.bind(
                n, "", List.of(), "文档已覆盖全部章节");
        assertEquals("文档已覆盖全部章节", b.bindings().get("n3_out"));
        assertTrue(b.complete(n));
    }

    @Test
    void failedLastToolCannotBeHiddenByEarlierSuccessfulTool() {
        TaskNode n = new TaskNode("n2", "写入 notes.md", "file_write",
                List.of(), List.of(), List.of("notes.md"), TaskNodeStatus.PENDING,
                9, DoneWhen.file("notes.md"), "", "", 0, "");
        NodeOutputBinder.Bound b = NodeOutputBinder.bind(
                n, "", List.of(
                        ToolExecutionResultMessage.from("1", "read_file", "old good content"),
                        ToolExecutionResultMessage.from("2", "write_file",
                                "{\"success\":false,\"status\":\"failed\",\"error\":\"disk full\"}")),
                "");
        assertFalse(b.complete(n));
        assertTrue(StepEvaluator.looksLikeToolError(b.evidence()));
    }

    @Test
    void validationRetainsTheObservedBeforeAndAfterStatesInsteadOfOnlyTheLastTodoReceipt() {
        TaskNode node = new TaskNode("verify", "验证场景切换", "browser", List.of(), List.of(),
                List.of("验证结论"), TaskNodeStatus.PENDING, 50, DoneWhen.validation("场景确实切换"), "", "", 0, "");
        var receipts = List.<dev.langchain4j.data.message.ChatMessage>of(
                ToolExecutionResultMessage.from("1", "browser_navigate", "当前场景：生产场景"),
                ToolExecutionResultMessage.from("2", "browser_click", "点击成功：切换场景；当前场景：测试场景"),
                ToolExecutionResultMessage.from("3", "browser_click", "点击成功：恢复默认；当前场景：生产场景"),
                ToolExecutionResultMessage.from("4", "todo", "{\"success\":true,\"stats\":{\"completed\":2}}"));
        var bound = NodeOutputBinder.bind(node, "", receipts, AgentLoop.STEP_SEGMENT_DONE,
                com.miniagent.agent.tool.ToolResult.fromLegacy("{\"success\":true}"));
        assertTrue(bound.complete(node));
        assertTrue(bound.evidence().contains("测试场景"));
        assertTrue(bound.evidence().contains("恢复默认"));
        assertFalse(bound.evidence().contains("stats"));
    }

    @Test
    void accumulatedVerificationEvidenceCannotHideAFailedTerminalTool() {
        TaskNode node = new TaskNode("verify", "验证", "browser", List.of(), List.of(),
                List.of("验证结论"), TaskNodeStatus.PENDING, 50, DoneWhen.validation("检查成功"), "", "", 0, "");
        var bound = NodeOutputBinder.bind(node, "", List.of(
                ToolExecutionResultMessage.from("1", "browser_click", "previous success")), "",
                com.miniagent.agent.tool.ToolResult.fromLegacy("{\"success\":false,\"error\":\"not verified\"}"));
        assertFalse(bound.complete(node));
        assertTrue(bound.evidence().contains("not verified"));
    }

    private static TaskNode acquire(String port) {
        return new TaskNode("n1", "获取资料原文", "web", List.of(),
                List.of(), List.of(port), TaskNodeStatus.PENDING, 10,
                DoneWhen.note(), "", "", 0, "");
    }
}
