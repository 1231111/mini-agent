package com.miniagent.agent.core;

import com.miniagent.agent.execution.AgentToolsProperties;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.execution.ToolInvocation;
import com.miniagent.agent.execution.ToolPipeline;
import com.miniagent.agent.execution.ToolRequest;
import com.miniagent.agent.hook.StopHookChain;
import com.miniagent.agent.hook.TodoPlanStopHook;
import com.miniagent.agent.surface.ToolSurface;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.RunStatus;
import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.repository.AgentTraceStepRepository;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopMediaContinuationTest {
    private static final String SESSION = "media-continuation";
    private static final String IMAGE = "![cover](https://example.com/cover.png)";

    @AfterEach
    void clearThreadContext() {
        AgentLoop.clearCurrentSession();
        AgentLoop.clearCurrentModels();
        LoopTurnContext.clear();
    }

    @Test
    void mediaArtifactDoesNotEndResearchAndReportTask() {
        Fixture fixture = fixture();
        AtomicInteger turns = responses(fixture.model,
                tools("image_generate"), tools("read_file"), tools("write_file"), text("Report generated."));

        AgentLoop.LoopOutcome outcome = run(fixture, 6);

        assertTrue(outcome.successful(), outcome::toString);
        assertEquals(4, turns.get());
        assertEquals(List.of("image_generate", "read_file", "write_file"), outcome.toolsInvoked());
        assertTrue(outcome.text().contains("Report generated."));
        assertTrue(outcome.text().contains(IMAGE));
    }

    @Test
    void imageAndFileInOneBatchStillAllowRemainingWork() {
        Fixture fixture = fixture();
        AtomicInteger turns = responses(fixture.model,
                tools("image_generate", "write_file"), tools("read_file"), text("All requested work finished."));

        AgentLoop.LoopOutcome outcome = run(fixture, 5);

        assertTrue(outcome.successful(), outcome::toString);
        assertEquals(3, turns.get());
        assertTrue(outcome.toolsInvoked().containsAll(List.of("image_generate", "write_file", "read_file")));
        assertTrue(outcome.text().contains("All requested work finished."));
    }

    @Test
    void mediaCannotHideUnfinishedTaskNodes() {
        Fixture fixture = fixture();
        when(fixture.todos.hasPlan(SESSION)).thenReturn(true);
        when(fixture.todos.hasRunnableIncomplete(SESSION)).thenReturn(true);
        when(fixture.todos.render(SESSION)).thenReturn("pending: write report");
        AtomicInteger turns = responses(fixture.model, tools("image_generate"),
                text("Cover ready."), text("Cover ready."), text("Cover ready."));

        AgentLoop.LoopOutcome outcome = run(fixture, 6);

        assertFalse(outcome.successful());
        assertEquals("INCOMPLETE_TODO", outcome.endReason());
        assertEquals(4, turns.get(), "unfinished nodes should receive the existing two continuation nudges");
        assertTrue(outcome.text().contains("pending: write report"));
    }

    @Test
    void repeatedLengthTruncationDoesNotTurnPartialFileIntoSuccess() {
        Fixture fixture = fixture();
        AtomicInteger turns = new AtomicInteger();
        when(fixture.model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            int turn = turns.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                    .id("write-" + turn).name("write_file")
                    .arguments("{\"path\":\"report.md\",\"mode\":\"append\",\"content\":\"part " + turn + "\"}")
                    .build())).finishReason(FinishReason.LENGTH).build();
        });

        AgentLoop.LoopOutcome outcome = run(fixture, 6);

        assertEquals("FAILURE", outcome.endReason());
        assertFalse(outcome.successful());
        assertEquals(4, turns.get());
        assertTrue(outcome.text().contains("已写入的部分"));
    }

    @Test
    void unknownOutcomeStopsEvenWhenToolCanBeVerifiedLater() {
        Fixture fixture = fixture();
        doReturn(ToolInvocation.executed(
                "write_file", ToolResult.unknown("write may have completed", null)))
                .when(fixture.pipeline).invoke(any());
        AtomicInteger turns = responses(fixture.model, tools("write_file"),
                text("must not be reached"));

        AgentLoop.LoopOutcome outcome = runPlain(fixture, 4);

        assertEquals(AgentLoop.OUTCOME_UNKNOWN, outcome.endReason());
        assertEquals(1, turns.get());
        assertEquals(ToolStatus.UNKNOWN, outcome.lastToolResult().status());
    }

    @Test
    void structuredFailureWithPlainPayloadCannotBecomeSuccessfulFinalAnswer() {
        Fixture fixture = fixture();
        Diagnostics diagnostics = diagnostics(fixture);
        doReturn(ToolInvocation.executed(
                "read_file", ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                        "读取失败", false, "读取失败（plain text）")))
                .when(fixture.pipeline).invoke(any());
        AtomicInteger turns = responses(fixture.model, tools("read_file"),
                text("I completed the task"));

        AgentLoop.LoopOutcome outcome = runPlain(fixture, 4);

        assertEquals(RunStatus.FAILURE.name(), outcome.endReason());
        assertFalse(outcome.successful());
        assertEquals(ToolStatus.FAILED, outcome.lastToolResult().status());
        assertEquals(2, turns.get());
        assertEquals(List.of(ToolStatus.FAILED), diagnostics.statuses());
        AgentTraceStep toolStep = diagnostics.toolSteps().getFirst();
        assertEquals(AgentStepNode.TOOL_ERROR.name(), toolStep.getStepType());
        assertEquals(RunStatus.FAILURE.name(), toolStep.getStatus());
        assertEquals("读取失败（plain text）", toolStep.getContent());
        assertTrue(outcome.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).anyMatch(message -> message.text().contains("执行失败")));
    }

    @Test
    void serialBatchStopsBeforeDispatchingNextActionAfterUnknown() {
        Fixture fixture = fixture();
        doReturn(ToolInvocation.executed(
                "write_file", ToolResult.unknown("write may have completed", null)))
                .when(fixture.pipeline).invoke(any());
        responses(fixture.model, tools("write_file", "image_generate"));

        AgentLoop.LoopOutcome outcome = runPlain(fixture, 4);

        assertEquals(AgentLoop.OUTCOME_UNKNOWN, outcome.endReason());
        verify(fixture.pipeline, times(1)).invoke(any());
        assertEquals(List.of("write_file"), outcome.toolsInvoked());
    }

    @Test
    void controlStopWithUnknownSideEffectCannotBecomeSuccess() {
        Fixture fixture = fixture();
        doReturn(ToolInvocation.controlStop(
                "write_file", ToolResult.unknown("cancelled before actual outcome was known", null)))
                .when(fixture.pipeline).invoke(any());
        responses(fixture.model, tools("write_file"));

        AgentLoop.LoopOutcome outcome = runPlain(fixture, 4);

        assertEquals(AgentLoop.OUTCOME_UNKNOWN, outcome.endReason());
        assertEquals(ToolStatus.UNKNOWN, outcome.lastToolResult().status());
    }

    @Test
    void pipelineExceptionAfterWriteDispatchIsUnknownEvenWhenMessageContainsQuotes() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("failed \"after\" dispatch"))
                .when(fixture.pipeline).invoke(any());
        AtomicInteger turns = responses(fixture.model, tools("write_file"), text("must not be reached"));

        AgentLoop.LoopOutcome outcome = runPlain(fixture, 4);

        assertEquals(AgentLoop.OUTCOME_UNKNOWN, outcome.endReason());
        assertEquals(ToolStatus.UNKNOWN, outcome.lastToolResult().status());
        assertEquals(1, turns.get());
        verify(fixture.pipeline, times(1)).invoke(any());
    }

    @Test
    void successfulRetryCanResolveKnownFailure() {
        Fixture fixture = fixture();
        doReturn(
                ToolInvocation.executed("read_file", ToolResult.failure(ToolErrorCode.NOT_FOUND,
                        "missing", false, "missing")),
                ToolInvocation.executed("read_file", ToolResult.success("recovered facts")))
                .when(fixture.pipeline).invoke(any());
        responses(fixture.model, tools("read_file"), tools("read_file"), text("Content inspected."));

        AgentLoop.LoopOutcome outcome = runPlain(fixture, 4);

        assertTrue(outcome.successful());
        assertEquals(ToolStatus.SUCCESS, outcome.lastToolResult().status());
        verify(fixture.pipeline, times(2)).invoke(any());
    }

    @Test
    void successfulTodoBookkeepingCannotHideFailedAction() {
        Fixture fixture = fixture();
        doReturn(
                ToolInvocation.executed("read_file", ToolResult.failure(ToolErrorCode.NOT_FOUND,
                        "missing", false, "missing")),
                ToolInvocation.executed("todo", ToolResult.success("marked complete")))
                .when(fixture.pipeline).invoke(any());
        responses(fixture.model, tools("read_file"), tools("todo"), text("Content inspected."));

        AgentLoop.LoopOutcome outcome = runPlain(fixture, 4);

        assertEquals(RunStatus.FAILURE.name(), outcome.endReason());
        assertEquals(ToolStatus.FAILED, outcome.lastToolResult().status());
    }

    @Test
    void cachedSuccessDoesNotReinterpretBusinessErrorFields() {
        Fixture fixture = fixture();
        Diagnostics diagnostics = diagnostics(fixture);
        doReturn(ToolInvocation.executed(
                "read_file", ToolResult.success("{\"error\":\"documented field\",\"timeout\":30}")))
                .when(fixture.pipeline).invoke(any());
        AtomicInteger turns = responses(fixture.model, tools("read_file"), tools("read_file"),
                tools("read_file"), text("Content inspected."));

        AgentLoop.LoopOutcome outcome = runPlain(fixture, 5);

        assertTrue(outcome.successful());
        assertEquals(ToolStatus.SUCCESS, outcome.lastToolResult().status());
        assertEquals(4, turns.get(), "three successful cached reads must not trigger DUP_TOOLS");
        verify(fixture.pipeline, times(1)).invoke(any());
        assertEquals(List.of(ToolStatus.SUCCESS, ToolStatus.SUCCESS, ToolStatus.SUCCESS), diagnostics.statuses());
        assertEquals(3, diagnostics.toolSteps().size());
        assertTrue(diagnostics.toolSteps().stream().allMatch(step ->
                AgentStepNode.TOOL_RESULT.name().equals(step.getStepType())
                        && RunStatus.SUCCESS.name().equals(step.getStatus())));
        assertTrue(outcome.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).noneMatch(message -> message.text().contains("执行失败")));
    }

    private static Diagnostics diagnostics(Fixture fixture) {
        List<AgentTraceStep> traces = new ArrayList<>();
        List<ToolStatus> statuses = new ArrayList<>();
        AgentTraceStepRepository repository = mock(AgentTraceStepRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            AgentTraceStep step = invocation.getArgument(0);
            traces.add(step);
            return step;
        });
        TraceRecorder recorder = new TraceRecorder();
        ReflectionTestUtils.setField(recorder, "repo", repository);
        fixture.loop.setTraceRecorder(recorder);
        fixture.loop.setToolExecutionSink((sessionId, turn, name, status, text) -> statuses.add(status));
        return new Diagnostics(traces, statuses);
    }

    private static AgentLoop.LoopOutcome run(Fixture fixture, int maxTurns) {
        AgentLoop.setCurrentSession(SESSION);
        return fixture.loop.runOutcome(fixture.model, "Complete every requested deliverable.",
                "Create a cover, read research material, then write the report.", List.of(), maxTurns,
                TaskPlan.of("cover and report"), null);
    }

    private static AgentLoop.LoopOutcome runPlain(Fixture fixture, int maxTurns) {
        AgentLoop.setCurrentSession(SESSION);
        return fixture.loop.runOutcome(fixture.model, "Inspect the provided content.",
                "Inspect the provided content.", List.of(), maxTurns,
                TaskPlan.of("inspect"), null);
    }

    private static Fixture fixture() {
        AgentLoop loop = new AgentLoop();
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of("image_generate", "read_file", "write_file", "todo")) {
            registry.register(name, name, Map.of(), ignored -> "unused");
        }
        ToolSurface surface = new ToolSurface();
        ReflectionTestUtils.setField(surface, "toolRegistry", registry);
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.maybeCompress(anyList(), anyInt(), anyString(), anyInt()))
                .thenAnswer(invocation -> ContextCompressor.CompressResult.untouched(invocation.getArgument(0), 1));
        ToolPipeline pipeline = mock(ToolPipeline.class);
        when(pipeline.askGrantTtlSeconds()).thenReturn(1L);
        when(pipeline.invoke(any())).thenAnswer(invocation -> {
            ToolRequest request = invocation.getArgument(0);
            String result = switch (request.name()) {
                case "image_generate" -> IMAGE;
                case "write_file" -> "{\"success\":true,\"path\":\"report.md\"}";
                default -> "research facts";
            };
            return ToolInvocation.executed(request.name(), ToolResult.success(result));
        });
        TaskTodoStore todos = mock(TaskTodoStore.class);
        ReflectionTestUtils.setField(loop, "toolRegistry", registry);
        ReflectionTestUtils.setField(loop, "toolSurface", surface);
        ReflectionTestUtils.setField(loop, "toolExecutionGuards", new ToolExecutionGuards(registry, new AgentToolsProperties(4)));
        ReflectionTestUtils.setField(loop, "executionProperties", new ExecutionProperties());
        ReflectionTestUtils.setField(loop, "executionControl", new ExecutionControl(60_000, 20, 10_000));
        ReflectionTestUtils.setField(loop, "contextCompressor", compressor);
        ReflectionTestUtils.setField(loop, "taskTodoStore", todos);
        ReflectionTestUtils.setField(loop, "stopHookChain", new StopHookChain(List.of(new TodoPlanStopHook())));
        ReflectionTestUtils.setField(loop, "toolPipeline", pipeline);
        return new Fixture(loop, mock(ChatModel.class), todos, pipeline);
    }

    private static AtomicInteger responses(ChatModel model, ChatResponse... responses) {
        AtomicInteger turn = new AtomicInteger();
        when(model.chat(any(ChatRequest.class))).thenAnswer(ignored -> {
            int index = turn.getAndIncrement();
            if (index >= responses.length) throw new AssertionError("Unexpected model turn " + index);
            return responses[index];
        });
        return turn;
    }

    private static ChatResponse tools(String... names) {
        List<ToolExecutionRequest> requests = java.util.stream.IntStream.range(0, names.length)
                .mapToObj(index -> ToolExecutionRequest.builder().id("tool-" + names[index])
                        .name(names[index]).arguments("{}").build()).toList();
        return ChatResponse.builder().aiMessage(AiMessage.from(requests)).build();
    }

    private static ChatResponse text(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private record Fixture(AgentLoop loop, ChatModel model, TaskTodoStore todos,
                           ToolPipeline pipeline) {}

    private record Diagnostics(List<AgentTraceStep> traces, List<ToolStatus> statuses) {
        List<AgentTraceStep> toolSteps() {
            return traces.stream().filter(step -> AgentStepNode.TOOL_ERROR.name().equals(step.getStepType())
                    || AgentStepNode.TOOL_RESULT.name().equals(step.getStepType())).toList();
        }
    }
}
