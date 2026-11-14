package com.miniagent.application;

import com.miniagent.agent.context.ContextLoader;
import com.miniagent.agent.context.LoadedContext;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.core.ContextCompressor;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionProperties;
import com.miniagent.agent.core.ExecutionSignalStore;
import com.miniagent.agent.core.ModelCallControl;
import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.agent.core.TaskExecutionContext;
import com.miniagent.agent.delegate.ClientMultiAgent;
import com.miniagent.agent.desktop.DesktopInteractionService;
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
import com.miniagent.agent.web.SearchDateContext;
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
import com.miniagent.config.service.TaskAdmissionGuard;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.browser.BrowserService;
import org.springframework.beans.factory.ObjectProvider;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private DesktopInteractionService desktop;

    @BeforeEach
    void setUp() {
        app = new AgentChatApplicationService();
        loop = mock(AgentLoop.class);
        planner = mock(PlanningLoop.class);
        when(planner.selectRoute(any(), any(), anyString(), anyString(), any(), anyList()))
                .thenReturn(com.miniagent.agent.planner.PlanningRoute.direct("test", "single goal"));
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
        desktop = mock(DesktopInteractionService.class);
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
        when(contexts.load(eq("s1"), anyString(), anyBoolean(), anyList()))
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
        put(app, "desktopInteractionService", desktop);
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
    void persistsActualTurnExecutionIdentityAndNodeStatesWithItsReply() {
        stubLoop("SUCCESS", "节点任务已完成。");
        when(trace.currentExecutionId()).thenReturn("exec-persisted-turn");
        TaskTodoStore todos = mock(TaskTodoStore.class);
        when(todos.get("s1")).thenAnswer(invocation -> {
            assertEquals(1L, TaskExecutionContext.currentTaskId());
            assertEquals("fence", TaskExecutionContext.currentRunToken());
            return List.of(new TaskTodoStore.TodoItem(2, "核验生成页面", TaskTodoStore.Status.completed,
                    "浏览器验证通过", "", "", "", List.of(1)));
        });
        ChatTaskRepository savedTasks = mock(ChatTaskRepository.class);
        put(app, "taskTodoStore", todos);
        put(app, "chatTaskRepository", savedTasks);

        app.chat(7L, "s1", "goal");

        var captured = org.mockito.ArgumentCaptor.forClass(com.miniagent.config.entity.ChatTask.class);
        verify(savedTasks).save(captured.capture());
        var saved = captured.getValue();
        var progress = TaskProgressSnapshot.fromJson(saved.getTaskProgressJson());
        assertNotNull(progress);
        assertEquals("s1", saved.getSessionId());
        assertEquals("节点任务已完成。", saved.getAnswer());
        assertEquals("exec-persisted-turn", progress.executionId());
        assertEquals(1L, progress.taskId());
        assertEquals("SUCCESS", progress.status());
        assertEquals("核验生成页面", progress.items().getFirst().content());
        assertEquals("completed", progress.items().getFirst().status());
        assertEquals(List.of(1), progress.items().getFirst().dependsOn());
    }

    @Test
    void resumedDagProgressKeepsOriginalExecutionIdentityInsteadOfNewTurnTrace() {
        when(trace.currentExecutionId()).thenReturn("new-turn-trace");
        when(planner.selectRoute(any(), any(), anyString(), anyString(), any(), anyList()))
                .thenReturn(com.miniagent.agent.planner.PlanningRoute.dag("model", "resume"));
        when(planner.runWithOutcome(any(), anyString(), anyString(), any(), anyList(), any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new PlanningLoop.RunOutcome("需要核验原操作。", PlanningLoop.Outcome.FAILED, "OUTCOME_UNKNOWN"));
        var states = new com.miniagent.agent.planner.PlannerStateStore();
        states.init("s1", "original-dag-execution", null, new com.miniagent.agent.planner.TaskGraph(List.of()));
        put(app, "plannerStates", states);
        var savedTasks = mock(ChatTaskRepository.class);
        put(app, "chatTaskRepository", savedTasks);

        app.chat(7L, "s1", "goal");

        var captured = org.mockito.ArgumentCaptor.forClass(com.miniagent.config.entity.ChatTask.class);
        verify(savedTasks).save(captured.capture());
        var progress = TaskProgressSnapshot.fromJson(captured.getValue().getTaskProgressJson());
        assertEquals("original-dag-execution", progress.executionId());
        assertEquals("OUTCOME_UNKNOWN", progress.reason());
        assertEquals("new-turn-trace", trace.currentExecutionId());
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
        verify(desktop).releaseFor(7L, "1", "s1");
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
    void originalRequestConstrainsSearchDatesEvenWhenTheCompiledGoalLosesToday() {
        put(app, "searchClock", java.time.Clock.fixed(
                java.time.Instant.parse("2026-11-11T16:05:00Z"), java.time.ZoneOffset.UTC));
        when(runs.tryStart(7L, "s1")).thenAnswer(invocation -> {
            // Admission and worker execution can straddle midnight; keep the original request's date.
            put(app, "searchClock", java.time.Clock.fixed(
                    java.time.Instant.parse("2026-11-12T16:05:00Z"), java.time.ZoneOffset.UTC));
            return new TaskRunService.StartResult(run, null);
        });
        when(loop.runOutcome(any(), anyString(), anyString(), anyList(), anyInt(), any(), nullable(AgentStreamSink.class)))
                .thenAnswer(invocation -> {
                    var window = SearchDateContext.current();
                    assertNotNull(window, "compiled goal 'goal' must not erase the original request's date");
                    assertEquals(java.time.LocalDate.of(2026, 11, 12), window.start());
                    assertEquals(window.start(), window.end());
                    assertSame(window, com.miniagent.agent.core.RunScope.capture().searchDateWindow());
                    return outcome("SUCCESS", "当日检索结果");
                });

        assertEquals("当日检索结果", app.chat(7L, "s1", "帮我搜索今日 AI 技术最新动态"));
        assertNull(SearchDateContext.current());
    }

    @Test
    void attachedDocumentCannotIntroduceATrustedDateConstraint() {
        when(loop.runOutcome(any(), anyString(), anyString(), anyList(), anyInt(), any(), nullable(AgentStreamSink.class)))
                .thenAnswer(invocation -> {
                    assertNull(SearchDateContext.current(), "attachment text is not the user's requested publication window");
                    return outcome("SUCCESS", "已分析资料");
                });
        app.chatStreamMultimodal(7L, "s1", "分析附件\n---文件---\n今日AI新闻", "分析附件",
                List.of(), List.of(), List.of(), List.of(), null);
        verify(runs, timeout(5_000)).markCompleted(run);
        verify(streams, timeout(5_000)).markIdle("s1");
        assertNull(SearchDateContext.current());
    }

    @Test
    void failedRunRestoresTheCallersSearchWindow() {
        var callerWindow = SearchDateContext.fromRequest("昨日AI新闻",
                java.time.Instant.parse("2026-11-12T02:00:00Z"), java.time.ZoneId.of("Asia/Shanghai"));
        doAnswer(invocation -> {
            assertNull(SearchDateContext.current(), "ordinary request must clear any inherited date constraint");
            throw new IllegalStateException("worker initialization failed");
        }).when(workers).open("s1");
        try (var ignored = SearchDateContext.bind(callerWindow)) {
            assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal"));
            assertSame(callerWindow, SearchDateContext.current());
        }
        assertNull(SearchDateContext.current());
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
    @ValueSource(strings = {"SUCCESS", "MAX_ITERATIONS", "CANCELLED", "USER_QUESTION", "PERM_ASK"})
    void everyLoopTerminalStateReleasesOnlyTheAuthenticatedSessionsDesktopInputs(String reason) {
        stubLoop(reason, ANSWER);

        app.chat(7L, "s1", "goal");

        var order = inOrder(desktop, workers, streams);
        order.verify(desktop).releaseFor(7L, "1", "s1");
        order.verify(workers).close("s1");
        order.verify(streams).markIdle("s1");
        verify(desktop, times(1)).releaseFor(7L, "1", "s1");
        verify(desktop, never()).revokeSession(anyString());
    }

    @Test
    void desktopReleaseFailureCannotMaskTheAnswerOrSkipRemainingRunCleanup() {
        stubLoop("SUCCESS", "已交付全部结果。");
        doThrow(new IllegalStateException("native input cleanup failed"))
                .when(desktop).releaseFor(7L, "1", "s1");
        ExecutionControl control = spy(new ExecutionControl(60_000, 20, 10_000));
        put(app, "executionControl", control);
        TaskTodoContext.set("stale-session");
        WorkspaceContext.setRootOverride(Path.of("D:/stale-workspace"));
        WorkspaceContext.setSessionOverride("stale-session");

        assertEquals("已交付全部结果。", app.chat(7L, "s1", "goal"));

        verify(runs).markCompleted(run);
        verify(workers).close("s1");
        verify(control).finish("s1", "fence");
        verify(streams).markIdle("s1");
        assertEquals("default", TaskTodoContext.currentSessionId());
        assertNull(WorkspaceContext.getRootOverride());
        assertNull(WorkspaceContext.getSessionOverride());
    }

    @Test
    void desktopReleaseFailureCannotReplaceAnEarlierWorkerStartupFailure() {
        IllegalStateException workerFailure = new IllegalStateException("worker initialization failed");
        doThrow(workerFailure).when(workers).open("s1");
        doThrow(new IllegalStateException("native input cleanup failed"))
                .when(desktop).releaseFor(7L, "1", "s1");

        assertSame(workerFailure, assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal")));

        verify(desktop).releaseFor(7L, "1", "s1");
        verify(workers).close("s1");
        verify(streams).markIdle("s1");
        verify(runs).markFailed(eq(run), contains("worker initialization failed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "USER_QUESTION", "MAX_ITERATIONS", "CANCELLED"})
    void synchronousCleanupKeepsAdmissionUntilOldSessionStateCannotAffectTheNextRun(String reason) throws Exception {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        AtomicInteger ids = new AtomicInteger();
        when(repository.save(any(AgentTaskRun.class))).thenAnswer(call -> {
            AgentTaskRun entity = call.getArgument(0);
            entity.setId((long) ids.incrementAndGet());
            return entity;
        });
        TaskRunService realRuns = new TaskRunService();
        ReflectionTestUtils.setField(realRuns, "transactionManager",
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        put(realRuns, "repository", repository);
        put(realRuns, "maxPerUser", 1);
        put(app, "taskRunService", realRuns);
        put(streams, "taskRunService", realRuns);
        TaskRunLeaseRenewer renewer = mock(TaskRunLeaseRenewer.class);
        TaskRunLeaseRenewer.Guard renewal = mock(TaskRunLeaseRenewer.Guard.class);
        when(renewer.start(any())).thenReturn(renewal);
        put(app, "taskRunLeaseRenewer", renewer);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch nextLoopEntered = new CountDownLatch(1);
        CountDownLatch releaseNextLoop = new CountDownLatch(1);
        AtomicInteger cleanupCalls = new AtomicInteger();
        AtomicInteger loopCalls = new AtomicInteger();
        AtomicBoolean workerOpen = new AtomicBoolean();
        doAnswer(call -> { workerOpen.set(true); return null; }).when(workers).open("s1");
        doAnswer(call -> { workerOpen.set(false); return null; }).when(workers).close("s1");
        doAnswer(call -> {
            if (cleanupCalls.incrementAndGet() == 1) {
                cleanupEntered.countDown();
                assertTrue(releaseCleanup.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(desktop).releaseFor(7L, "1", "s1");
        when(loop.runOutcome(any(), anyString(), anyString(), anyList(), anyInt(), any(), nullable(AgentStreamSink.class)))
                .thenAnswer(call -> {
                    if (loopCalls.incrementAndGet() == 1) return outcome(reason, ANSWER);
                    nextLoopEntered.countDown();
                    assertTrue(releaseNextLoop.await(5, TimeUnit.SECONDS));
                    return outcome("SUCCESS", "下一轮完成");
                });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var old = executor.submit(() -> app.chat(7L, "s1", "goal"));
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            var replacement = realRuns.tryStart(7L, "s1");
            if (replacement.accepted()) realRuns.markCancelled(replacement.handle(), "test cleanup");
            assertFalse(replacement.accepted(), "native cleanup must still own the session admission");
            verify(renewal, never()).close();
            verify(repository, never()).finishIfRunning(any(), any(), any(), any(), any());

            releaseCleanup.countDown();
            assertEquals(ANSWER, old.get(5, TimeUnit.SECONDS));
            assertFalse(realRuns.isRunning("s1"));
            assertFalse(workerOpen.get());
            verify(renewal).close();
            var next = executor.submit(() -> app.chat(7L, "s1", "goal"));
            assertTrue(nextLoopEntered.await(5, TimeUnit.SECONDS));
            assertTrue(workerOpen.get(), "old close must not remove the new worker");
            assertTrue(streams.isTaskRunning("s1"), "old markIdle must not erase the next run");
            assertTrue(realRuns.isRunning("s1"));
            releaseNextLoop.countDown();
            assertEquals("下一轮完成", next.get(5, TimeUnit.SECONDS));
        } finally {
            releaseCleanup.countDown();
            releaseNextLoop.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void terminalPersistenceFailureCannotSkipCleanupOrReplaceTheWorkerFailure() {
        IllegalStateException workerFailure = new IllegalStateException("worker initialization failed");
        IllegalStateException databaseFailure = new IllegalStateException("terminal database failed");
        doThrow(workerFailure).when(workers).open("s1");
        doThrow(databaseFailure).when(runs).markFailed(eq(run), anyString());

        assertSame(workerFailure, assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal")));

        assertTrue(List.of(workerFailure.getSuppressed()).contains(databaseFailure));
        var order = inOrder(desktop, workers, streams, runs);
        order.verify(desktop).releaseFor(7L, "1", "s1");
        order.verify(workers).close("s1");
        order.verify(streams).markIdle("s1");
        order.verify(runs).markFailed(eq(run), contains("worker initialization failed"));
    }

    @Test
    void synchronousIdleFailureCannotSkipControlOrReleaseOfTheRun() {
        stubLoop("SUCCESS", "已交付全部结果。");
        ExecutionControl control = spy(new ExecutionControl(60_000, 20, 10_000));
        put(app, "executionControl", control);
        doThrow(new IllegalStateException("stream state cleanup failed")).when(streams).markIdle("s1");

        assertEquals("已交付全部结果。", app.chat(7L, "s1", "goal"));

        var order = inOrder(workers, streams, control, runs);
        order.verify(workers).close("s1");
        order.verify(streams).markIdle("s1");
        order.verify(control).finish("s1", "fence");
        order.verify(runs).markCompleted(run);
    }

    @Test
    @SuppressWarnings("unchecked")
    void oldSseFinalizationReservationBlocksSynchronousAdmissionAfterItsTaskLeaseWasReleased() throws Exception {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        AtomicInteger ids = new AtomicInteger();
        when(repository.save(any(AgentTaskRun.class))).thenAnswer(call -> {
            AgentTaskRun entity = call.getArgument(0); entity.setId((long) ids.incrementAndGet()); return entity;
        });
        TaskRunService realRuns = new TaskRunService();
        ReflectionTestUtils.setField(realRuns, "transactionManager",
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        put(realRuns, "repository", repository);
        UserRepository users = (UserRepository) ReflectionTestUtils.getField(app, "userRepository");
        when(users.findLocalTenantIdById(7L)).thenReturn(Optional.of(1L));
        ObjectProvider<ToolExecutionGuards> toolProvider = mock(ObjectProvider.class);
        ObjectProvider<BrowserService> browserProvider = mock(ObjectProvider.class);
        ObjectProvider<ChatStreamingService> streamProvider = mock(ObjectProvider.class);
        when(toolProvider.getObject()).thenReturn(mock(ToolExecutionGuards.class));
        when(browserProvider.getObject()).thenReturn(mock(BrowserService.class));
        when(streamProvider.getObject()).thenReturn(streams);
        put(realRuns, "admissionGuard", new TaskAdmissionGuard(toolProvider, browserProvider, users, streamProvider));
        put(app, "taskRunService", realRuns);
        put(streams, "taskRunService", realRuns);
        CountDownLatch finalizationEntered = new CountDownLatch(1);
        CountDownLatch releaseFinalization = new CountDownLatch(1);
        CountDownLatch finalizationIdle = new CountDownLatch(1);
        doAnswer(call -> {
            finalizationEntered.countDown();
            assertTrue(releaseFinalization.await(5, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(streams).markCompleting("s1");
        doAnswer(call -> {
            Object value = call.callRealMethod();
            finalizationIdle.countDown();
            return value;
        }).when(streams).markIdle("s1");
        stubLoop("SUCCESS", "页面已完成");
        try {
            startStream(List.of());
            assertTrue(finalizationEntered.await(5, TimeUnit.SECONDS));
            assertFalse(realRuns.isRunning("s1"), "inner worker has released its durable lease");
            assertTrue(streams.hasLocalLifecycleReservation("s1"));

            assertFalse(realRuns.tryStart(7L, "s1").accepted());
            var blocked = assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal"));
            assertTrue(blocked.getMessage().contains("尚未结束"));
            verify(loop, times(1)).runOutcome(any(), anyString(), anyString(), anyList(), anyInt(), any(), nullable(AgentStreamSink.class));
            assertEquals(1, ids.get(), "rejected reservations must not create extra durable run rows");

            releaseFinalization.countDown();
            assertTrue(finalizationIdle.await(5, TimeUnit.SECONDS));
            assertFalse(streams.hasLocalLifecycleReservation("s1"));
            assertEquals("页面已完成", app.chat(7L, "s1", "goal"));
            assertEquals(2, ids.get());
            assertFalse(realRuns.isRunning("s1"));
        } finally {
            releaseFinalization.countDown();
            assertTrue(finalizationIdle.await(5, TimeUnit.SECONDS));
        }
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

        var order = inOrder(renewer, desktop, workers, streams, renewal, runs);
        order.verify(renewer).start(run);
        order.verify(desktop).releaseFor(7L, "1", "s1");
        order.verify(workers).close("s1");
        order.verify(streams).markIdle("s1");
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
        when(planner.selectRoute(any(), any(), eq("s1"), eq("goal"), any(), anyList()))
                .thenReturn(com.miniagent.agent.planner.PlanningRoute.dag("test", "dependencies"));
        when(planner.runWithOutcome(any(), anyString(), anyString(), any(), anyList(), any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new PlanningLoop.RunOutcome("请确认计划。", PlanningLoop.Outcome.BLOCKED_ON_HUMAN, "PLAN_APPROVAL_REQUIRED"));
        when(trace.currentExecutionId()).thenReturn("execution-1");

        app.chat(7L, "s1", "goal");

        verify(runs).markWaiting(run, "PLAN_APPROVAL_REQUIRED");
        verify(runs, never()).markCompleted(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROUTER_MODEL_UNAVAILABLE", "ROUTER_MODEL_ERROR", "ROUTER_INVALID_RESPONSE",
            "ROUTER_MODEL_AUTHENTICATION_FAILED", "ROUTER_MODEL_PERMISSION_DENIED", "ROUTER_MODEL_NOT_FOUND",
            "ROUTER_MODEL_RATE_LIMITED", "ROUTER_MODEL_TIMEOUT", "ROUTER_MODEL_CONNECTION_FAILED",
            "ROUTER_MODEL_SERVICE_UNAVAILABLE", "ROUTER_MODEL_INVALID_REQUEST"})
    void unresolvedStrategyCannotBypassPlanningAndExecuteDirectly(String reason) {
        when(planner.selectRoute(any(), any(), eq("s1"), eq("goal"), any(), anyList()))
                .thenReturn(com.miniagent.agent.planner.PlanningRoute.unresolved(reason));
        when(planner.runWithOutcome(any(), anyString(), anyString(), any(), anyList(), any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new PlanningLoop.RunOutcome("执行策略生成失败，请重试。", PlanningLoop.Outcome.FAILED, reason));

        assertEquals("执行策略生成失败，请重试。", app.chat(7L, "s1", "goal"));

        verifyNoInteractions(loop);
        verify(runs).markFailed(eq(run), contains(reason));
        verify(runs, never()).markCompleted(any());
        verify(runs, never()).markWaiting(any(), anyString());
        verify(trace).endExecution("FAILURE");
    }

    @Test
    void modelAuthenticationFailureReachesTheChatStreamWithItsActualReason() {
        String reason = "ROUTER_MODEL_AUTHENTICATION_FAILED";
        String answer = "模型服务认证失败（401）。请在设置中核对服务地址、模型名称和 API Key。";
        when(planner.selectRoute(any(), any(), anyString(), anyString(), any(), anyList()))
                .thenReturn(com.miniagent.agent.planner.PlanningRoute.unresolved(reason));
        when(planner.runWithOutcome(any(), anyString(), anyString(), any(), anyList(), any(), anyString(), any(), any(), any(), any()))
                .thenReturn(new PlanningLoop.RunOutcome(answer, PlanningLoop.Outcome.FAILED, reason));

        startStream(List.of());

        verify(streams, timeout(5_000)).markIdle("s1");
        verify(events).publish(eq("s1"), eq("task_outcome"),
                argThat(payload -> payload instanceof Map<?, ?> fields && "FAILURE".equals(fields.get("status"))
                        && reason.equals(fields.get("reason")) && fields.containsKey("executionId") && fields.containsKey("taskId")), anyString());
        verify(events).error(eq("s1"), eq(answer), anyString());
        verify(events, never()).complete(anyString(), anyString(), anyString());
        verify(runs).markFailed(eq(run), contains(reason));
        verifyNoInteractions(loop);
        verify(runs, never()).markCompleted(any());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionControl.StopReason.class, names = {"CANCELLED", "DEADLINE_EXCEEDED", "TOOL_BUDGET_EXCEEDED", "TENANT_QUOTA_EXCEEDED"})
    void stoppedRoutingPersistsTheActualTerminalStatus(ExecutionControl.StopReason reason) {
        when(planner.selectRoute(any(), any(), anyString(), anyString(), any(), anyList()))
                .thenThrow(new ModelCallControl.StoppedException(reason));

        String answer = app.chat(7L, "s1", "goal");

        assertFalse(answer.isBlank());
        verifyNoInteractions(loop);
        verifyStoppedTurnWasPersisted(reason);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionControl.StopReason.class, names = {"CANCELLED", "DEADLINE_EXCEEDED"})
    void stoppedCompilationPersistsTheActualTerminalStatus(ExecutionControl.StopReason reason) {
        when(planner.selectRoute(any(), any(), anyString(), anyString(), any(), anyList()))
                .thenReturn(com.miniagent.agent.planner.PlanningRoute.dag("model", "依赖与验收"));
        when(planner.runWithOutcome(any(), anyString(), anyString(), any(), anyList(), any(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new ModelCallControl.StoppedException(reason));

        assertFalse(app.chat(7L, "s1", "goal").isBlank());

        verifyNoInteractions(loop);
        verifyStoppedTurnWasPersisted(reason);
    }

    @Test
    void routingCancellationEmitsCancelledOutcomeAndCompletesWithoutAProcessingError() {
        when(planner.selectRoute(any(), any(), anyString(), anyString(), any(), anyList()))
                .thenThrow(new ModelCallControl.StoppedException(ExecutionControl.StopReason.CANCELLED));

        startStream(List.of());

        verify(streams, timeout(5_000)).markIdle("s1");
        verify(events).publish(eq("s1"), eq("task_outcome"),
                argThat(payload -> payload instanceof Map<?, ?> fields && "CANCELED".equals(fields.get("status"))
                        && "CANCELLED".equals(fields.get("reason")) && fields.containsKey("executionId")), anyString());
        verify(events).complete(eq("s1"), eq("任务已取消。"), anyString());
        verify(events, never()).error(anyString(), anyString(), anyString());
        verifyStoppedTurnWasPersisted(ExecutionControl.StopReason.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionControl.StopReason.class,
            names = {"CANCELLED", "DEADLINE_EXCEEDED", "TOOL_BUDGET_EXCEEDED", "TENANT_QUOTA_EXCEEDED"})
    void stoppedDagOutcomePersistsStatusWithoutRequiringTheCancellationFlag(ExecutionControl.StopReason reason) {
        when(planner.selectRoute(any(), any(), anyString(), anyString(), any(), anyList()))
                .thenReturn(com.miniagent.agent.planner.PlanningRoute.dag("model", "依赖与验收"));
        when(planner.runWithOutcome(any(), anyString(), anyString(), any(), anyList(), any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new PlanningLoop.RunOutcome("任务已停止，进度已保留。", PlanningLoop.Outcome.FAILED, reason.name()));

        assertEquals("任务已停止，进度已保留。", app.chat(7L, "s1", "goal"));

        verifyNoInteractions(loop);
        verifyStoppedTurnWasPersisted(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEADLINE_EXCEEDED", "RESOURCE_QUOTA_EXCEEDED", "TOOL_BUDGET_EXCEEDED", "TENANT_QUOTA_EXCEEDED"})
    void stoppedReasonCannotMapToSuccessEvenIfCompletionWasReported(String reason) {
        assertEquals(com.miniagent.common.RunStatus.FAILURE,
                AgentChatApplicationService.planningStatus(new PlanningLoop.RunOutcome(
                        "stopped", PlanningLoop.Outcome.COMPLETED, reason)));
    }

    private void verifyStoppedTurnWasPersisted(ExecutionControl.StopReason reason) {
        boolean cancelled = reason == ExecutionControl.StopReason.CANCELLED;
        String status = cancelled ? "CANCELED" : "FAILURE";
        if (cancelled) verify(runs).markCancelled(eq(run), anyString());
        else verify(runs).markFailed(eq(run), contains(reason.name()));
        verify(runs, never()).markCompleted(any());
        verify(trace).endExecution(status);
        ChatTaskRepository repository = (ChatTaskRepository) ReflectionTestUtils.getField(app, "chatTaskRepository");
        var task = org.mockito.ArgumentCaptor.forClass(com.miniagent.config.entity.ChatTask.class);
        verify(repository).save(task.capture());
        assertFalse(task.getValue().getAnswer().isBlank());
        assertTrue(task.getValue().getTaskProgressJson().contains(status));
        assertTrue(task.getValue().getTaskProgressJson().contains(reason.name()));
    }

    @Test
    void failedCancellationReadCannotSkipTerminalCleanupOrMaskTheWorkerFailure() {
        ExecutionSignalStore signals = mock(ExecutionSignalStore.class);
        IllegalStateException readFailure = new IllegalStateException("redis status read unavailable");
        when(signals.isCancelled(anyString())).thenReturn(false).thenThrow(readFailure);
        ExecutionControl control = new ExecutionControl(60_000, 20, 10_000,
                Optional.of(signals), Optional.empty());
        put(app, "executionControl", control);
        IllegalStateException workerFailure = new IllegalStateException("worker initialization failed");
        doThrow(workerFailure).when(workers).open("s1");

        var failure = assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal"));

        assertSame(workerFailure, failure, "a secondary status read must preserve the execution failure");
        assertTrue(List.of(failure.getSuppressed()).contains(readFailure));
        verify(runs).markFailed(eq(run), contains("worker initialization failed"));
        verify(runs, never()).markCompleted(any());
        verify(runs, never()).markCancelled(any(), anyString());
        verify(workers).close("s1");
        verify(streams).markIdle("s1");
    }

    @Test
    void locallyConfirmedCancellationStillClosesAsCancelledDuringRedisFailure() {
        ExecutionSignalStore signals = mock(ExecutionSignalStore.class);
        when(signals.isCancelled(anyString())).thenReturn(false)
                .thenThrow(new IllegalStateException("redis status read unavailable"));
        IllegalStateException writeFailure = new IllegalStateException("redis cancel write unavailable");
        doThrow(writeFailure).when(signals).cancel(anyString(), anyLong());
        ExecutionControl control = new ExecutionControl(60_000, 20, 10_000,
                Optional.of(signals), Optional.empty());
        put(app, "executionControl", control);
        doAnswer(invocation -> {
            control.cancel("s1", "fence");
            return null;
        }).when(workers).open("s1");

        var failure = assertThrows(IllegalStateException.class, () -> app.chat(7L, "s1", "goal"));

        assertSame(writeFailure, failure);
        verify(runs).markCancelled(eq(run), anyString());
        verify(runs, never()).markFailed(any(), anyString());
        verify(runs, never()).markCompleted(any());
        verify(signals, times(1)).isCancelled(anyString());
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
                argThat(payload -> payload instanceof Map<?, ?> fields && status.equals(fields.get("status"))
                        && reason.equals(fields.get("reason")) && fields.containsKey("executionId")), anyString());
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
        ReflectionTestUtils.setField(realRuns, "transactionManager",
                mock(org.springframework.transaction.PlatformTransactionManager.class));
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
