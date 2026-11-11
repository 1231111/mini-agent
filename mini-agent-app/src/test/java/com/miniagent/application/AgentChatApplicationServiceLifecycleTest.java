package com.miniagent.application;

import com.miniagent.agent.context.ContextLoader;
import com.miniagent.agent.context.LoadedContext;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.core.ContextCompressor;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionProperties;
import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.agent.core.TaskExecutionContext;
import com.miniagent.agent.delegate.ClientMultiAgent;
import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.agent.planner.PlanningLoop;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskPlanFactory;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.todo.TaskTodoContext;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.config.entity.AgentTaskRun;
import com.miniagent.config.entity.User;
import com.miniagent.config.model.EffectiveModelSettings;
import com.miniagent.config.model.ModelClientFactory;
import com.miniagent.config.repository.AgentTaskRunRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.service.TaskRunService;
import com.miniagent.config.service.TaskRunLeaseRenewer;
import com.miniagent.memory.MemoryService;
import com.miniagent.memory.MemoryStore;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentChatApplicationServiceLifecycleTest {
    private static final String ANSWER = "任务尚未完成，保留已取得的结果。";
    private AgentChatApplicationService app;
    private AgentLoop loop;
    private PlanningLoop planner;
    private TaskRunService runs;
    private TaskRunService.RunHandle run;
    private TraceRecorder trace;
    private ConversationProjectService projects;
    private ClientMultiAgent workers;
    private ChatStreamingService streams;
    private SessionEventCenter events;

    @BeforeEach
    void setUp() {
        app = new AgentChatApplicationService();
        loop = mock(AgentLoop.class);
        planner = mock(PlanningLoop.class);
        runs = mock(TaskRunService.class);
        run = new TaskRunService.RunHandle(1L, 7L, "s1", "fence");
        when(runs.tryStart(7L, "s1")).thenReturn(new TaskRunService.StartResult(run, null));
        when(runs.activeRunToken("s1")).thenReturn(Optional.of("fence"));
        when(runs.renewSessionLock("s1", "fence")).thenReturn(true);
        UserRepository users = mock(UserRepository.class);
        User user = new User();
        user.setId(7L);
        user.setTenantId(1L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        projects = mock(ConversationProjectService.class);
        workers = mock(ClientMultiAgent.class);
        events = spy(new SessionEventCenter());
        streams = spy(new ChatStreamingService());
        put(streams, "eventCenter", events);
        put(streams, "taskRunService", runs);
        put(streams, "sseTimeoutMs", 45_000L);
        trace = mock(TraceRecorder.class);
        when(trace.isActive()).thenReturn(true);

        ModelClientFactory models = mock(ModelClientFactory.class);
        var resolved = new ModelClientFactory.ResolvedModels(mock(ChatModel.class), null,
                new EffectiveModelSettings("test", "test", "http://example.invalid", "test", "test", 32_000));
        when(models.resolve(7L)).thenReturn(resolved);
        when(models.resolveForMultimodal(7L)).thenReturn(resolved);
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.messages()).thenReturn(List.of());
        TaskPlanFactory plans = mock(TaskPlanFactory.class);
        when(plans.build(anyString())).thenReturn(TaskPlan.of("goal"));
        ContextLoader contexts = mock(ContextLoader.class);
        when(contexts.load(eq("s1"), eq("goal"), anyBoolean(), anyList()))
                .thenReturn(new LoadedContext("system", List.of(), null, "s1", 1L, Map.of()));
        ConversationWorkspaceService workspace = mock(ConversationWorkspaceService.class);
        when(workspace.sessionRoot("s1", 7L)).thenReturn(Optional.empty());
        MultimodalMessageBuilder multimodal = mock(MultimodalMessageBuilder.class);
        when(multimodal.buildMultimodalUserMessage(eq(7L), eq("goal"), anyList(), anyList(), anyList(), anyList()))
                .thenReturn(UserMessage.from("goal"));

        put(app, "agentLoop", loop);
        put(app, "planningLoop", planner);
        put(app, "taskRunService", runs);
        put(app, "userRepository", users);
        put(app, "conversationProjectService", projects);
        put(app, "executionControl", new ExecutionControl(60_000, 20, 10_000));
        put(app, "executionProperties", new ExecutionProperties());
        put(app, "clientMultiAgent", workers);
        put(app, "memoryStore", mock(MemoryStore.class));
        put(app, "memoryService", mock(MemoryService.class));
        put(app, "conversationWorkspaceService", workspace);
        put(app, "streamingService", streams);
        put(app, "contextCompressor", mock(ContextCompressor.class));
        put(app, "builtinTools", mock(BuiltinTools.class));
        put(app, "modelClientFactory", models);
        put(app, "chatMemoryProvider", (ChatMemoryConfig.ChatMemoryProvider) ignored -> memory);
        put(app, "taskPlanFactory", plans);
        put(app, "contextLoader", contexts);
        put(app, "multimodalBuilder", multimodal);
        put(app, "taskTodoStore", mock(TaskTodoStore.class));
        put(app, "chatTaskRepository", mock(ChatTaskRepository.class));
        put(app, "conversationStore", mock(DatabaseConversationStore.class));
        put(app, "traceRecorder", trace);
    }

    @AfterEach
    void shutdownEventCenter() {
        ReflectionTestUtils.invokeMethod(events, "shutdown");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAX_ITERATIONS", "DUP_TOOLS", "FAILURE", "MEDIA_ABORT", "OUTCOME_UNKNOWN", "INCOMPLETE_TODO"})
    void unfinishedLoopDoesNotBecomeACompletedTask(String reason) {
        stubLoop(reason, ANSWER);

        assertEquals(ANSWER, app.chat(7L, "s1", "goal"));

        verify(runs).markFailed(eq(run), contains(reason));
        verify(runs, never()).markCompleted(any());
        verify(trace).endExecution("FAILURE");
    }

    @Test
    void cancelAfterAdmissionBeforeWorkerStartNeverExecutesAgent() {
        when(runs.tryStart(7L, "s1")).thenAnswer(invocation -> {
            app.cancel(7L, "s1");
            return new TaskRunService.StartResult(run, null);
        });
        assertEquals("任务已取消。", app.chat(7L, "s1", "goal"));
        verifyNoInteractions(loop, planner, workers);
        verify(runs).markCancelled(eq(run), anyString());
        verify(runs, never()).markCompleted(any());
    }

    @Test
    void workerCannotStartAfterItsAdmissionLeaseWasLost() {
        when(runs.renewSessionLock("s1", "fence")).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal"));

        verifyNoInteractions(loop, planner, workers);
        verify(runs).markFailed(eq(run), contains("lease is no longer owned"));
        verify(runs, never()).markCompleted(any());
    }

    @Test
    void failedWorkerOpenStillClosesPartiallyInitializedWorkers() {
        doThrow(new IllegalStateException("worker initialization failed")).when(workers).open("s1");

        assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal"));

        verify(workers).close("s1");
        verifyNoInteractions(loop, planner);
        verify(runs).markFailed(eq(run), contains("worker initialization failed"));
    }

    @Test
    void runIdentitySurvivesInnerExecutionUntilTerminalTransition() {
        stubLoop("SUCCESS", "已交付全部结果。");
        doAnswer(invocation -> {
            assertEquals("fence", TaskExecutionContext.currentRunToken());
            return null;
        }).when(runs).markCompleted(run);

        try (var ignored = TaskExecutionContext.bindRun("caller-token")) {
            app.chat(7L, "s1", "goal");
            assertEquals("caller-token", TaskExecutionContext.currentRunToken());
        }
    }

    @Test
    void cleanupFailureCannotLeakRunThreadLocalsOrKeepStreamBusy() {
        stubLoop("SUCCESS", "已交付全部结果。");
        ExecutionControl brokenCleanup = mock(ExecutionControl.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(brokenCleanup).finish("s1", "fence");
        put(app, "executionControl", brokenCleanup);
        TaskTodoContext.set("stale-session");
        WorkspaceContext.setRootOverride(Path.of("D:/stale-workspace"));
        WorkspaceContext.setSessionOverride("stale-session");

        assertEquals("已交付全部结果。", app.chat(7L, "s1", "goal"));

        assertEquals("default", TaskTodoContext.currentSessionId());
        assertNull(WorkspaceContext.getRootOverride());
        assertNull(WorkspaceContext.getSessionOverride());
        verify(streams).markIdle("s1");
    }

    @Test
    void successfulLoopCompletesNormally() {
        stubLoop("SUCCESS", "已交付全部结果。");

        assertEquals("已交付全部结果。", app.chat(7L, "s1", "goal"));

        verify(runs).markCompleted(run);
        verify(runs, never()).markFailed(any(), anyString());
        verify(trace).endExecution("SUCCESS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "MAX_ITERATIONS", "USER_QUESTION", "CANCELLED"})
    void leaseRenewalStopsBeforeAnyTerminalTransitionReleasesTheLock(String reason) throws Exception {
        TaskRunLeaseRenewer renewer = mock(TaskRunLeaseRenewer.class);
        TaskRunLeaseRenewer.Guard renewal = mock(TaskRunLeaseRenewer.Guard.class);
        when(renewer.start(run)).thenReturn(renewal);
        put(app, "taskRunLeaseRenewer", renewer);
        stubLoop(reason, ANSWER);

        app.chat(7L, "s1", "goal");

        var order = inOrder(renewer, renewal, runs);
        order.verify(renewer).start(run);
        order.verify(renewal).close();
        switch (reason) {
            case "SUCCESS" -> order.verify(runs).markCompleted(run);
            case "USER_QUESTION" -> order.verify(runs).markWaiting(run, reason);
            case "CANCELLED" -> order.verify(runs).markCancelled(eq(run), anyString());
            default -> order.verify(runs).markFailed(eq(run), contains(reason));
        }
        verify(renewal, times(1)).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "USER_QUESTION", "MAX_ITERATIONS"})
    void cancellationDiscoveredWhileClosingRenewalOverridesTheLoopOutcome(String reason) {
        TaskRunLeaseRenewer renewer = mock(TaskRunLeaseRenewer.class);
        TaskRunLeaseRenewer.Guard renewal = mock(TaskRunLeaseRenewer.Guard.class);
        ExecutionControl control = (ExecutionControl) ReflectionTestUtils.getField(app, "executionControl");
        when(renewer.start(run)).thenReturn(renewal);
        doAnswer(invocation -> {
            // Model an in-flight renewal tick discovering lease loss before
            // close returns and the application selects its terminal state.
            control.cancel("s1", "fence");
            return null;
        }).when(renewal).close();
        put(app, "taskRunLeaseRenewer", renewer);
        stubLoop(reason, ANSWER);

        assertEquals(ANSWER, app.chat(7L, "s1", "goal"));

        var order = inOrder(renewal, runs);
        order.verify(renewal).close();
        order.verify(runs).markCancelled(eq(run), anyString());
        verify(renewal, times(1)).close();
        verify(runs, never()).markCompleted(any());
        verify(runs, never()).markWaiting(any(), anyString());
        verify(runs, never()).markFailed(any(), anyString());
    }

    @Test
    void failedWorkerStartupClosesLeaseRenewalBeforeReleasingTheRun() throws Exception {
        TaskRunLeaseRenewer renewer = mock(TaskRunLeaseRenewer.class);
        TaskRunLeaseRenewer.Guard renewal = mock(TaskRunLeaseRenewer.Guard.class);
        when(renewer.start(run)).thenReturn(renewal);
        put(app, "taskRunLeaseRenewer", renewer);
        doThrow(new IllegalStateException("worker initialization failed")).when(workers).open("s1");

        assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal"));

        var order = inOrder(renewal, runs);
        order.verify(renewal).close();
        order.verify(runs).markFailed(eq(run), contains("worker initialization failed"));
        verify(renewal, times(1)).close();
    }

    @Test
    void renewalStartupFailureStillReleasesTheAdmittedRun() {
        TaskRunLeaseRenewer renewer = mock(TaskRunLeaseRenewer.class);
        when(renewer.start(run)).thenThrow(new IllegalStateException("scheduler unavailable"));
        put(app, "taskRunLeaseRenewer", renewer);

        assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal"));

        verify(runs).markFailed(eq(run), contains("scheduler unavailable"));
        verifyNoInteractions(workers, loop, planner);
    }

    @Test
    void oldPlanWaitingStateCannotOverrideACompletedDirectTurn() {
        stubLoop("SUCCESS", "直接执行已完成。");
        when(planner.isAwaitingConfirm("s1")).thenReturn(true);

        app.chat(7L, "s1", "goal");

        verify(runs).markCompleted(run);
        verify(runs, never()).markWaiting(any(), anyString());
    }

    @Test
    void emptySuccessCannotFinishTheTaskWithoutAResponse() {
        stubLoop("SUCCESS", " ");

        assertEquals(AgentChatApplicationService.ensureFinalAnswer(""), app.chat(7L, "s1", "goal"));

        verify(runs).markFailed(eq(run), contains("EMPTY_FINAL_ANSWER"));
        verify(runs, never()).markCompleted(any());
        verify(trace).endExecution("FAILURE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_QUESTION", "PERM_ASK"})
    void humanWaitYieldsWithoutRecordingGoalCompletion(String reason) {
        stubLoop(reason, "需要你补充一个信息。");

        app.chat(7L, "s1", "goal");

        verify(runs).markWaiting(run, reason);
        verify(runs, never()).markCompleted(any());
        verify(runs, never()).markFailed(any(), anyString());
        verify(trace).endExecution("WAITING");
    }

    @Test
    void cancelledLoopIsRecordedAsCancelled() {
        stubLoop("CANCELLED", "任务已取消。");

        app.chat(7L, "s1", "goal");

        verify(runs).markCancelled(eq(run), anyString());
        verify(runs, never()).markCompleted(any());
        verify(trace).endExecution("CANCELED");
    }

    @Test
    void planApprovalDoesNotCountAsACompletedGoal() {
        when(planner.shouldHandle(any(), eq("s1"), eq("goal"))).thenReturn(true);
        when(planner.runWithOutcome(any(), anyString(), anyString(), any(), anyList(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new PlanningLoop.RunOutcome("请确认计划。", PlanningLoop.Outcome.BLOCKED_ON_HUMAN, "PLAN_APPROVAL_REQUIRED"));
        when(trace.currentExecutionId()).thenReturn("execution-1");

        app.chat(7L, "s1", "goal");

        verify(runs).markWaiting(run, "PLAN_APPROVAL_REQUIRED");
        verify(runs, never()).markCompleted(any());
    }

    @Test
    void failedStreamEmitsErrorAndDoesNotEmitSuccessfulEnd() {
        stubLoop("MAX_ITERATIONS", ANSWER);

        startStream(List.of());

        verify(events, timeout(5_000)).error(eq("s1"), eq(ANSWER), anyString());
        verify(streams, timeout(5_000)).markIdle("s1");
        verify(events, never()).complete(anyString(), anyString());
        verify(events, never()).complete(anyString(), anyString(), anyString());
        verify(runs).markFailed(eq(run), contains("MAX_ITERATIONS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_QUESTION", "CANCELLED"})
    void streamPublishesExplicitHumanWaitOrCancellationBeforeEnding(String reason) {
        stubLoop(reason, ANSWER);

        startStream(List.of());

        verify(streams, timeout(5_000)).markIdle("s1");
        String status = "USER_QUESTION".equals(reason) ? "WAITING" : "CANCELED";
        var order = inOrder(events);
        order.verify(events).publish(eq("s1"), eq("task_outcome"),
                eq(Map.of("status", status, "reason", reason)), anyString());
        order.verify(events).complete(eq("s1"), eq(ANSWER), anyString());
        verify(runs, never()).markCompleted(any());
    }

    @Test
    void multimodalLoopRetainsItsFailureOutcome() {
        when(loop.runWithMultimodalOutcome(any(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(outcome("MAX_ITERATIONS", ANSWER));

        startStream(List.of("data:image/png;base64,placeholder"));

        verify(events, timeout(5_000)).error(eq("s1"), eq(ANSWER), anyString());
        verify(streams, timeout(5_000)).markIdle("s1");
        verify(runs).markFailed(eq(run), contains("MAX_ITERATIONS"));
        verify(runs, never()).markCompleted(any());
    }

    @Test
    void rejectedDuplicateCannotStartAWorkerOrTerminateTheOriginalChannel() {
        streams.createStream("s1", "original", 7L);
        streams.markRunning("s1");
        when(runs.tryStart(7L, "s1")).thenReturn(new TaskRunService.StartResult(null, "already running"));

        var rejected = startStream(List.of());

        assertEquals(0L, rejected.getTimeout());
        assertTrue(events.hasRunningSession("s1"));
        verify(runs).tryStart(7L, "s1");
        verify(runs, never()).markFailed(any(), anyString());
        verify(events, never()).error(anyString(), anyString());
        verify(events, never()).complete(anyString(), anyString());
        verify(streams, never()).markIdle(anyString());
    }

    @Test
    void rejectedDurableAdmissionNeverCreatesOrBroadcastsAChannel() {
        when(runs.tryStart(7L, "s1")).thenReturn(new TaskRunService.StartResult(null, "owned by another replica"));

        var rejected = startStream(List.of());

        assertEquals(0L, rejected.getTimeout());
        verify(events, never()).startRun(anyString(), anyString());
        verifyNoInteractions(loop, workers);
    }

    @Test
    void localReservationRejectionReleasesOnlyTheNewlyAllocatedRun() {
        streams.createStream("s1", "original", 7L);
        streams.markRunning("s1");
        streams.markCompleting("s1");

        var rejected = startStream(List.of());

        assertEquals(0L, rejected.getTimeout());
        verify(runs).markFailed(run, "SSE channel already reserved");
        assertTrue(events.hasRunningSession("s1"));
        verify(events, never()).error(anyString(), anyString(), anyString());
        verify(streams, never()).markIdle(anyString());
    }

    @Test
    void streamSetupFailureReleasesTheAllocatedRun() {
        doThrow(new IllegalStateException("channel setup failed")).when(events).startRun("s1", "goal");

        assertEquals(0L, startStream(List.of()).getTimeout());

        verify(runs).markFailed(run, "channel setup failed");
        verifyNoInteractions(loop, workers);
    }

    @Test
    void projectAuthorizationFailureCannotOccupyRunResources() {
        when(projects.resolveAuthorized(7L, "1", "s1"))
                .thenThrow(new SecurityException("project membership revoked"));

        assertThrows(SecurityException.class, () -> app.chat(7L, "s1", "goal"));

        verify(runs, never()).tryStart(anyLong(), anyString());
        verifyNoInteractions(loop, workers);
    }

    @Test
    void startupExceptionAfterLockAcquisitionReleasesTheUserQuota() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        when(repository.save(any(AgentTaskRun.class))).thenAnswer(call -> {
            AgentTaskRun entity = call.getArgument(0);
            entity.setId(1L);
            return entity;
        });
        TaskRunService realRuns = new TaskRunService();
        put(realRuns, "repository", repository);
        put(realRuns, "maxPerUser", 1);
        put(app, "taskRunService", realRuns);
        doThrow(new IllegalStateException("worker initialization failed")).when(workers).open("s1");

        assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal"));

        verify(repository).finishIfRunning(eq(1L), eq(AgentTaskRun.Status.RUNNING), eq(AgentTaskRun.Status.FAILED),
                any(), contains("worker initialization failed"));
        var next = realRuns.tryStart(7L, "s2");
        assertTrue(next.accepted(), "a failed startup must release the user's only execution slot");
        realRuns.markCancelled(next.handle(), "test cleanup");
    }

    private void stubLoop(String reason, String answer) {
        when(loop.runOutcome(any(), anyString(), anyString(), anyList(), anyInt(), any(), nullable(AgentStreamSink.class)))
                .thenReturn(outcome(reason, answer));
    }

    private static AgentLoop.LoopOutcome outcome(String reason, String answer) {
        return new AgentLoop.LoopOutcome(answer, reason, List.of(), List.of(), ToolErrorCode.NONE);
    }

    private org.springframework.web.servlet.mvc.method.annotation.SseEmitter startStream(List<String> images) {
        return app.chatStreamMultimodal(7L, "s1", "goal", "goal", images, List.of(), List.of(), List.of(), null);
    }

    private static void put(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }
}
