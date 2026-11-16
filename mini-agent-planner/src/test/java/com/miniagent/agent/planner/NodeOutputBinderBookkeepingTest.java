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
