package com.miniagent.agent.core;

import com.miniagent.agent.execution.*;
import com.miniagent.agent.hook.StopHookChain;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.*;
import com.miniagent.agent.surface.ToolSurface;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real registry, journal and AgentLoop with an acknowledged local substitute for a remote commit. */
class AgentLoopUncertainOutcomeTest {
    private static final String SESSION = "uncertain-write";

    @AfterEach
    void clearContexts() {
        AgentLoop.clearCurrentSession();
        AgentLoop.clearCurrentModels();
        LoopTurnContext.clear();
        PermissionContext.clear();
        PermissionContext.bindStore(null);
        ExecutionTurnContext.clear();
    }

    @Test
    void committedWriteThenExceptionStopsBatchAndCannotReplayJournalAction() {
        assertUncertainWrite(false);
    }

    @Test
    void committedWriteWithoutAcknowledgementStopsBatchAndCannotReplayJournalAction() {
        assertUncertainWrite(true);
    }

    private void assertUncertainWrite(boolean emptyAcknowledgement) {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger followingActions = new AtomicInteger();
        AtomicInteger modelTurns = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(Tool.builder().name("write_file").description("write")
                .sideEffect(ToolSideEffect.EXTERNAL_WRITE).handler(args -> {
                    writes.incrementAndGet();
                    if (emptyAcknowledgement) return null;
                    throw new IllegalStateException("response lost after remote commit");
                }).build());
        registry.register("todo", "following action", Map.of(), args -> {
            followingActions.incrementAndGet();
            return "{\"success\":true}";
        });
        ExecutionControl control = new ExecutionControl(60_000, 20, 10_000);
        ToolExecutionGuards guards = new ToolExecutionGuards(registry, new AgentToolsProperties(4));
        RecordingJournal journal = new RecordingJournal();
        SessionPermissionStore permissions = new SessionPermissionStore();
        ToolPipeline pipeline = new ToolPipeline(control, new ToolHookChain(new ArrayList<>()), permissions,
                journal, guards, registry, new ExecPolicyService("allow", "", permissions));
        AgentLoop loop = new AgentLoop();
        ToolSurface surface = new ToolSurface();
        ReflectionTestUtils.setField(surface, "toolRegistry", registry);
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.maybeCompress(anyList(), anyInt(), anyString(), anyInt()))
                .thenAnswer(invocation -> ContextCompressor.CompressResult.untouched(invocation.getArgument(0), 1));
        ReflectionTestUtils.setField(loop, "toolRegistry", registry);
        ReflectionTestUtils.setField(loop, "toolSurface", surface);
        ReflectionTestUtils.setField(loop, "toolExecutionGuards", guards);
        ReflectionTestUtils.setField(loop, "toolPipeline", pipeline);
        ReflectionTestUtils.setField(loop, "executionControl", control);
        ReflectionTestUtils.setField(loop, "executionProperties", new ExecutionProperties());
        ReflectionTestUtils.setField(loop, "contextCompressor", compressor);
        ReflectionTestUtils.setField(loop, "taskTodoStore", mock(TaskTodoStore.class));
        ReflectionTestUtils.setField(loop, "stopHookChain", new StopHookChain(new ArrayList<>()));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            modelTurns.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from(List.of(
                    ToolExecutionRequest.builder().id("write").name("write_file").arguments("{}").build(),
                    ToolExecutionRequest.builder().id("following").name("todo").arguments("{}").build()))).build();
        });
        AgentLoop.setCurrentSession(SESSION);
        PermissionContext.force(SESSION, PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO, true);
        var binding = new ExecutionTurnContext.ActionBinding("write", "write-node", "write_file", "write-once");
        ExecutionTurnContext.open(SESSION, 7L, List.of(binding), () -> true);

        AgentLoop.LoopOutcome outcome = loop.runOutcome(model, "Complete the requested action.",
                "Send the update, then record completion.", List.of(), 3, TaskPlan.of("update"), null);

        assertEquals(AgentLoop.OUTCOME_UNKNOWN, outcome.endReason());
        assertEquals(ToolStatus.UNKNOWN, outcome.lastToolResult().status());
        assertFalse(outcome.successful());
        assertEquals(1, modelTurns.get(), "must not let the model submit another write after lost acknowledgement");
        assertEquals(1, writes.get());
        assertEquals(0, followingActions.get(), "unconfirmed write must stop subsequent actions in the same batch");
        ActionJournalEntry recorded = journal.entries.values().iterator().next();
        assertEquals(ActionExecutionStatus.UNKNOWN, recorded.status());
        ToolInvocation retry = pipeline.invoke(ToolRequest.of(SESSION, "write_file", "{}", 0, "replay")
                .withActionBinding(binding));
        assertEquals(ToolStatus.UNKNOWN, retry.result().status());
        assertEquals(1, writes.get(), "journal must forbid retrying the unresolved action");
    }

    private static class RecordingJournal implements ActionJournal {
        final Map<ActionJournalKey, ActionJournalEntry> entries = new ConcurrentHashMap<>();
        public void append(ActionJournalEntry entry) { entries.put(entry.key(), entry); }
        public Optional<ActionJournalEntry> latest(ActionJournalKey key) { return Optional.ofNullable(entries.get(key)); }
        public List<ActionJournalEntry> unresolved() { return entries.values().stream()
                .filter(entry -> entry.status() == ActionExecutionStatus.UNKNOWN).toList(); }
    }
}
