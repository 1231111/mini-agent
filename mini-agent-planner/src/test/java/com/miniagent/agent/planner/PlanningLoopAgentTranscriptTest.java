package com.miniagent.agent.planner;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanningLoopAgentTranscriptTest {

    @Test
    void dagTranscriptKeepsOnlyToolPairsAfterTheCurrentUserTurn() {
        AiMessage oldCall = AiMessage.from(call("old", "read_file"));
        ToolExecutionResultMessage oldResult = ToolExecutionResultMessage.from("old", "read_file", "old");
        AiMessage call = AiMessage.from(call("current", "write_file"));
        ToolExecutionResultMessage result = ToolExecutionResultMessage.from("current", "write_file", "done");
        List<ChatMessage> messages = List.of(
                UserMessage.from("previous request"), oldCall, oldResult,
                UserMessage.from("current request"), new SystemMessage("node focus"),
                call, result, AiMessage.from("final answer"));

        List<ChatMessage> transcript = PlanningLoop.toolTranscriptSinceLatestUser(messages);

        assertEquals(List.of(call, result), transcript);
        assertInstanceOf(AiMessage.class, transcript.getFirst());
        assertEquals("current", assertInstanceOf(ToolExecutionResultMessage.class,
                transcript.getLast()).id());
    }

    private static ToolExecutionRequest call(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }
}
