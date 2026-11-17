package com.miniagent.agent.planner;

import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeOutputBinderBookkeepingTest {
    private static final String SUBMISSION = "{\"success\":true,\"pendingValidation\":true,\"completionOwner\":\"planner\","
            + "\"action\":\"submit\",\"items\":[{\"id\":1,\"evidence\":\"submitted facts\"}]}";

    @Test void bookkeepingOnlySynthesisUsesTheActualModelAnswerInsteadOfThePendingReceipt() {
        TaskNode node = synthesis();
        String actualAnswer = "Verified user-provided numbers: January 10, February 20; total 30.";
        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(node, "", List.of(submission()), actualAnswer, null);

        assertTrue(bound.complete(node));
        assertEquals(actualAnswer, bound.evidence());
        assertEquals(actualAnswer, bound.bindings().get("answer"));
        assertFalse(bound.evidence().contains("pendingValidation"));
    }

    @Test void successfulBookkeepingKeepsActualActionObservationsAsJudgeEvidence() {
        TaskNode node = synthesis();
        ToolResult observation = ToolResult.success("Actual checked source: https://example.test/news with publication date 2026-11-16");
        List<ChatMessage> messages = List.of(ToolExecutionResultMessage.from("search", "web_search", observation.legacyText()), submission());
        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(node, "", messages, "Model summary", observation);

        assertTrue(bound.complete(node));
        assertTrue(bound.evidence().contains("https://example.test/news"));
        assertFalse(bound.evidence().contains("pendingValidation"));
        assertFalse(bound.evidence().contains("submitted facts"));
    }

    @Test void successfulBookkeepingCannotHideTheLastRealActionFailure() {
        TaskNode node = synthesis();
        ToolResult failure = ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "Real search request was rejected", false);
        List<ChatMessage> messages = List.of(ToolExecutionResultMessage.from("search", "web_search", failure.legacyText()), submission());
        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(node, "", messages, "An unsupported model answer", failure);

        assertFalse(bound.complete(node));
        assertEquals(failure.legacyText(), bound.evidence());
    }

    @Test void submitOnlyDeliveryBindsTheSubmittedArtifactAndIgnoresDuplicateReceipts() {
        TaskNode node = new TaskNode("deliver", "交付页面链接", "deliver", List.of("verify"),
                List.of("verification"), List.of("page_link"), TaskNodeStatus.PENDING, 10,
                new DoneWhen(DoneWhen.NOTE, "交付可打开的页面链接", ""), "", "", 0, "");
        String receipt = "{\"success\":true,\"action\":\"submit\","
                + "\"status\":\"in_progress\",\"pendingValidation\":true,"
                + "\"evidence\":\"http://127.0.0.1:52848/index.html\"}";
        List<ChatMessage> messages = List.of(
                ToolExecutionResultMessage.from("todo-1", "todo", receipt),
                ToolExecutionResultMessage.from("todo-2", "todo", receipt));

        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(node, "", messages, "", null);

        assertTrue(bound.complete(node));
        assertEquals("http://127.0.0.1:52848/index.html", bound.evidence());
        assertEquals("http://127.0.0.1:52848/index.html", bound.bindings().get("page_link"));
    }

    @Test void failedLatestSubmitCannotFallBackToAnOlderSuccessfulDelivery() {
        TaskNode node = new TaskNode("deliver", "交付页面链接", "deliver", List.of("verify"),
                List.of("verification"), List.of("page_link"), TaskNodeStatus.PENDING, 10,
                new DoneWhen(DoneWhen.NOTE, "交付可打开的页面链接", ""), "", "", 0, "");
        String success = "{\"success\":true,\"action\":\"submit\",\"evidence\":\"https://example.test/old\"}";
        String failure = "{\"success\":false,\"error\":\"submit rejected\"}";

        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(node, "", List.of(
                ToolExecutionResultMessage.from("todo-1", "todo", success),
                ToolExecutionResultMessage.from("todo-2", "todo", failure)), "", null);

        assertFalse(bound.complete(node));
        assertTrue(bound.evidence().contains("submit rejected"));
        assertFalse(bound.evidence().contains("https://example.test/old"));
    }

    @Test void submitOnlyDeliveryBindsCommittedEvidenceFromItsPredecessor() {
        TaskNode node = new TaskNode("deliver", "交付可打开的页面链接", "deliver", List.of("verify"),
                List.of("浏览器验证记录"), List.of("可打开的页面链接"), TaskNodeStatus.PENDING, 10,
                new DoneWhen(DoneWhen.NOTE, "交付可打开的页面链接", ""), "", "", 0, "");
        String predecessor = "本节点工具执行记录：\n[browser_navigate] url=http://127.0.0.1:53112/index.html";

        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(node, "", List.of(), "", null, predecessor);

        assertTrue(bound.complete(node));
        assertEquals(predecessor, bound.evidence());
        assertEquals("http://127.0.0.1:53112/index.html", bound.bindings().get("可打开的页面链接"));
    }

    @Test void legacyHistoryStillDetectsARealFailureBeforeTheTodoSubmission() {
        TaskNode node = synthesis();
        String failure = "{\"success\":false,\"error\":\"Real request failed\"}";
        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(node, "", List.of(
                ToolExecutionResultMessage.from("search", "web_search", failure), submission()), "Unsupported answer", null);

        assertFalse(bound.complete(node));
        assertEquals(failure, bound.evidence());
    }

    private static ToolExecutionResultMessage submission() {
        return ToolExecutionResultMessage.from("todo-submit", "todo", SUBMISSION);
    }

    private static TaskNode synthesis() {
        return new TaskNode("summary", "Summarize the verified input", "synthesis", List.of(), List.of(), List.of("answer"),
                TaskNodeStatus.PENDING, 0, DoneWhen.judge("all amounts and sources are supported"), "", "", 0, "");
    }
}
