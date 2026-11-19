package com.miniagent.agent.planner;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningLoopToolReceiptTest {

    @Test
    void capturesArgumentsOnlyFromACompletedMatchingToolCall() {
        String args = "{\"command\":\"mvnw -pl mini-agent-tools test\"}";
        List<ChatMessage> messages = List.of(
                AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-1").name("exec_command").arguments(args).build()),
                ToolExecutionResultMessage.from("call-1", "exec_command", "exit_code=0\nTests passed"),
                AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-2").name("todo").arguments("{\"action\":\"submit\"}").build()),
                ToolExecutionResultMessage.from("call-2", "todo", "submitted"));

        List<StepEvaluator.CommandReceipt> receipts = PlanningLoop.toolExecutionReceipts(messages);
        assertEquals(2, receipts.size());
        StepEvaluator.CommandReceipt command = StepEvaluator.matchingCommandReceipt(
                receipts, "mvnw -pl mini-agent-tools test");
        assertEquals("exec_command", command.toolName());
        assertEquals(args, command.arguments());
    }

    @Test
    void ignoresUnmatchedOrUncompletedCalls() {
        String args = "{\"command\":\"mvnw -pl mini-agent-tools test\"}";
        List<ChatMessage> messages = List.of(
                AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-1").name("exec_command").arguments(args).build()),
                ToolExecutionResultMessage.from("other-call", "exec_command", "exit_code=0"));

        assertTrue(PlanningLoop.toolExecutionReceipts(messages).isEmpty());
    }
}
