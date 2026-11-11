package com.miniagent.agent.delegate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionProperties;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClientMultiAgentOutcomeTest {
    @TempDir
    Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {"MAX_ITERATIONS", "DUP_TOOLS", "FAILURE", "OUTCOME_UNKNOWN", "CANCELLED",
            "PERM_ASK", "USER_QUESTION", "MEDIA_ABORT", "INCOMPLETE_TODO"})
    void unsuccessfulTerminalReasonIsReturnedToParent(String reason) throws Exception {
        JsonNode result = run(new AgentLoop.LoopOutcome("Partial findings remain available.", reason,
                List.of(), List.of("read_file"), ToolErrorCode.NONE));

        assertFalse(result.path("success").asBoolean());
        assertEquals(reason, result.path("endReason").asText());
        assertEquals("Partial findings remain available.", result.path("summary").asText());
        assertTrue(result.path("error").asText().contains(reason));
    }

    @Test
    void explicitSuccessPreservesSummary() throws Exception {
        JsonNode result = run(new AgentLoop.LoopOutcome("Report complete.", "SUCCESS", List.of(), List.of(), null));

        assertTrue(result.path("success").asBoolean());
        assertEquals("Report complete.", result.path("summary").asText());
        assertEquals("SUCCESS", result.path("endReason").asText());
        assertFalse(result.has("error"));
    }

    @Test
    void emptySuccessfulReplyIsNotACompletedSubtask() throws Exception {
        assertFalse(run(new AgentLoop.LoopOutcome(" ", "SUCCESS", List.of(), List.of(), null))
                .path("success").asBoolean());
    }

    private JsonNode run(AgentLoop.LoopOutcome outcome) throws Exception {
        AgentLoop loop = mock(AgentLoop.class);
        when(loop.runOutcome(any(), anyString(), anyString(), anyList(), anyInt(), any(), isNull()))
                .thenReturn(outcome);
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.toolsFor(CapabilityRegistry.GENERAL)).thenReturn(List.of("read_file"));
        when(capabilities.containsTool(anyString())).thenReturn(true);
        ClientMultiAgent agents = new ClientMultiAgent(new ExecutionControl(60_000, 20, 10_000), mock(RoleLoader.class));
        ReflectionTestUtils.setField(agents, "agentLoop", loop);
        ReflectionTestUtils.setField(agents, "chatModel", mock(ChatModel.class));
        ReflectionTestUtils.setField(agents, "executionProperties", new ExecutionProperties());
        ReflectionTestUtils.setField(agents, "capabilityRegistry", capabilities);
        try (var ignored = mockStatic(BuiltinTools.class, CALLS_REAL_METHODS)) {
            ignored.when(() -> BuiltinTools.prepareSubagentWorkspace(anyString())).thenReturn(workspace);
            return new ObjectMapper().readTree(agents.runWorker("write report", "", "facts", List.of()));
        }
    }
}
