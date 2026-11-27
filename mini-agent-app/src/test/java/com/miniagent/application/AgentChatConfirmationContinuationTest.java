package com.miniagent.application;

import com.miniagent.agent.context.ContextLoader;
import com.miniagent.agent.context.LoadedContext;
import com.miniagent.agent.core.ContextCompressor;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.GoalContinuationContext;
import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.agent.delegate.ClientMultiAgent;
import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.planner.DoneWhen;
import com.miniagent.agent.planner.Goal;
import com.miniagent.agent.planner.PlannerStateStore;
import com.miniagent.agent.planner.PlanningLoop;
import com.miniagent.agent.planner.PlanningRoute;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.agent.planner.TaskGraph;
import com.miniagent.agent.planner.TaskNode;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.agent.planner.TodoStateProjector;
import com.miniagent.agent.scope.TaskBoundary;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskPlanFactory;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.common.RunStatus;
import com.miniagent.config.entity.User;
import com.miniagent.config.model.EffectiveModelSettings;
import com.miniagent.config.model.ModelClientFactory;
import com.miniagent.config.repository.ChatTaskRepository;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.service.TaskRunService;
import com.miniagent.memory.MemoryService;
import com.miniagent.memory.MemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the production control entry and its shared worker, without a desktop client or live LLM. */
class AgentChatConfirmationContinuationTest {
    private static final Long USER = 7L;
    private static final String SID = "confirmation-session", EXECUTION = "original-execution";
    private static final String OBJECTIVE = "Build and verify the multiplayer UI";
    private AgentChatApplicationService chats;
    private PlannerStateStore states;
    private StateSnapshot initial;
    private TaskRunService runs;
    private ExecutionControl control;
    private ChatStreamingService streams;
    private SessionEventCenter events;
    private ConversationTaskContinuationService continuations;
    private ConversationTaskContinuationService.Binding binding;
    private ContextLoader loader;
    private PlanningLoop planner;
    private ChatTaskRepository tasks;
    private DatabaseConversationStore conversations;
    private List<Runnable> workers;
    private SseEmitter emitter;
    private ChatMemory memory;
    private TaskTodoStore todos;

    @BeforeEach void setUp() {
        var permissions = mock(SessionPermissionStore.class);
        when(permissions.getMode(SID)).thenReturn(PermissionMode.DEFAULT);
        when(permissions.getConfirmPolicy(SID)).thenReturn(ConfirmPolicy.DANGEROUS);
        PermissionContext.bindStore(permissions);
        chats = new AgentChatApplicationService();
        states = new PlannerStateStore();
        initial = states.init(SID, EXECUTION, new Goal("goal", OBJECTIVE, "", "code", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(node("accepted", TaskNodeStatus.SUCCESS), node("work", TaskNodeStatus.AWAITING_CONFIRM))));
        runs = mock(TaskRunService.class);
        control = mock(ExecutionControl.class);
        streams = mock(ChatStreamingService.class);
        events = mock(SessionEventCenter.class);
        continuations = mock(ConversationTaskContinuationService.class);
        binding = mock(ConversationTaskContinuationService.Binding.class);
        loader = mock(ContextLoader.class);
        planner = mock(PlanningLoop.class);
        tasks = mock(ChatTaskRepository.class);
        conversations = mock(DatabaseConversationStore.class);
        todos = mock(TaskTodoStore.class);
        workers = new ArrayList<>();
        emitter = new SseEmitter(0L);

        User user = new User(); user.setId(USER); user.setTenantId(3L); user.setEnabled(true);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        when(runs.tryStart(USER, SID)).thenReturn(new TaskRunService.StartResult(
                new TaskRunService.RunHandle(9L, USER, SID, "run-fence"), null));
        when(runs.renewSessionLock(SID, "run-fence")).thenReturn(true);
        when(streams.tryCreateStreamForAcceptedRun(SID, "", USER)).thenReturn(
                new ChatStreamingService.StreamStart(emitter, true, "stream-token"));
        when(streams.getEventCenter()).thenReturn(events);
        when(binding.userId()).thenReturn(USER); when(binding.sessionId()).thenReturn(SID);
        when(binding.executionId()).thenReturn(EXECUTION); when(binding.taskId()).thenReturn(0L);
        when(binding.question()).thenReturn(OBJECTIVE); when(binding.expectedAnswer()).thenReturn("waiting for confirmation");
        when(continuations.prepare(USER, SID, EXECUTION)).thenReturn(binding);

        put("plannerStates", states); put("taskRunService", runs); put("executionControl", control);
        put("streamingService", streams); put("eventCenter", events); put("taskContinuations", continuations);
        put("todoProjector", new TodoStateProjector(todos)); put("taskTodoStore", todos);
        put("contextLoader", loader); put("planningLoop", planner); put("userRepository", users);
        put("conversationProjectService", mock(ConversationProjectService.class));
        put("agentRunExecutor", (Executor) workers::add); put("chatTaskRepository", tasks);
        put("conversationStore", conversations);
    }

    @AfterEach void clearThreadScopes() {
        GoalContinuationContext.restore(null); PermissionContext.clear(); PermissionContext.bindStore(null);
    }

    @Test void confirmationKeepsTheExactGraphAndQueuesOnlyOneWorker() {
        SseEmitter result = chats.confirmAndContinue(USER, SID, 2, EXECUTION, initial.version());
        assertThat(result).isSameAs(emitter);
        StateSnapshot confirmed = states.get(SID).orElseThrow();
        assertThat(confirmed.executionId()).isEqualTo(EXECUTION);
        assertThat(confirmed.planVersion()).isEqualTo(initial.planVersion());
        assertThat(confirmed.goal()).isEqualTo(initial.goal());
        assertThat(confirmed.graph().byId("accepted").status()).isEqualTo(TaskNodeStatus.SUCCESS);
        assertThat(confirmed.graph().byId("work").status()).isEqualTo(TaskNodeStatus.PENDING);
        assertThat(workers).hasSize(1);
        verifyNoInteractions(tasks, conversations, planner);
        assertThatThrownBy(() -> chats.confirmAndContinue(USER, SID, 2, EXECUTION, confirmed.version()))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(workers).hasSize(1);
    }

    @Test void staleVersionOrForeignExecutionCannotConfirmOrScheduleAnything() {
        assertConflict(() -> chats.confirmAndContinue(USER, SID, 2, EXECUTION, initial.version() + 1));
        assertConflict(() -> chats.confirmAndContinue(USER, SID, 2, "other-execution", initial.version()));
        assertThat(states.get(SID).orElseThrow()).isEqualTo(initial);
        assertThat(workers).isEmpty();
        verify(streams, never()).tryCreateStreamForAcceptedRun(anyString(), anyString(), anyLong());
    }

    @Test void missingOriginalCardOrChangedTaskScopeCannotConfirm() {
        when(binding.taskId()).thenReturn(23L);
        assertConflict(() -> chats.confirmAndContinue(USER, SID, 2, EXECUTION, initial.version()));
        when(continuations.prepare(USER, SID, EXECUTION)).thenReturn(null);
        assertConflict(() -> chats.confirmAndContinue(USER, SID, 2, EXECUTION, initial.version()));
        assertThat(states.get(SID).orElseThrow()).isEqualTo(initial);
        assertThat(workers).isEmpty();
    }

    @Test void lostAdmissionLeaseDoesNotChangeTheGraphAndReleasesTheStream() {
        when(runs.renewSessionLock(SID, "run-fence")).thenReturn(false);
        assertConflict(() -> chats.confirmAndContinue(USER, SID, 2, EXECUTION, initial.version()));
        assertThat(states.get(SID).orElseThrow()).isEqualTo(initial);
        assertThat(workers).isEmpty();
        verify(streams).markIdle(SID);
        verify(control).finish(SID, "run-fence");
        verify(events).error(eq(SID), anyString(), eq("stream-token"));
    }

    @Test void completedOrNonWaitingNodeAndInvalidArgumentsCannotStartAWorker() {
        assertConflict(() -> chats.confirmAndContinue(USER, SID, 1, EXECUTION, initial.version()));
        assertThatThrownBy(() -> chats.confirmAndContinue(USER, SID, 0, EXECUTION, initial.version()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(workers).isEmpty();
        assertThat(states.get(SID).orElseThrow()).isEqualTo(initial);
    }

    @Test void workerResumesOriginalDagWithoutSemanticRoutingOrAnotherUserTurn() {
        configureWorker();
        when(planner.runWithOutcome(any(), anyString(), eq(OBJECTIVE), isNull(), anyList(), any(),
                eq(SID), eq("run-fence"), nullable(String.class), any(), any(PlanningRoute.class)))
                .thenAnswer(call -> {
                    var continuation = GoalContinuationContext.current();
                    assertThat(continuation).isNotNull();
                    assertThat(continuation.executionId()).isEqualTo(EXECUTION);
                    assertThat(continuation.taskId()).isZero();
                    assertThat(continuation.unattended()).isFalse();
                    assertThat(PermissionContext.isForced()).isFalse();
                    assertThat(PermissionContext.mode()).isEqualTo(PermissionMode.DEFAULT);
                    assertThat(PermissionContext.confirmPolicy()).isEqualTo(ConfirmPolicy.DANGEROUS);
                    PlanningRoute route = call.getArgument(10);
                    assertThat(route.source()).isEqualTo("control");
                    assertThat(route.dagAction()).isEqualTo(PlanningRoute.DagAction.RESUME_EXISTING);
                    StateSnapshot current = states.get(SID).orElseThrow();
                    states.commit(SID, current.version(), current.withGraph(current.graph().replace(
                            current.graph().byId("work").withStatus(TaskNodeStatus.SUCCESS))));
                    return new PlanningLoop.RunOutcome("verified all files", PlanningLoop.Outcome.COMPLETED, "GRAPH_COMPLETED");
                });

        chats.confirmAndContinue(USER, SID, 2, EXECUTION, initial.version());
        workers.removeFirst().run();

        verify(planner, never()).selectRoute(any(), any(), anyString(), anyString(), any(), anyList());
        ArgumentCaptor<TaskProgressSnapshot> progress = ArgumentCaptor.forClass(TaskProgressSnapshot.class);
        verify(continuations).complete(eq(binding), eq("verified all files"), progress.capture(), anyList());
        assertThat(progress.getValue().executionId()).isEqualTo(EXECUTION);
        assertThat(progress.getValue().taskId()).isZero();
        assertThat(progress.getValue().status()).isEqualTo(RunStatus.SUCCESS.name());
        assertThat(memory.messages()).hasSize(2);
        assertThat(memory.messages().getFirst()).isEqualTo(UserMessage.from(OBJECTIVE));
        assertThat(((AiMessage) memory.messages().getLast()).text()).isEqualTo("verified all files");
        verifyNoInteractions(tasks, conversations);
        verify(runs).markCompleted(any());
        assertThat(GoalContinuationContext.current()).isNull();
    }

    private void configureWorker() {
        put("clientMultiAgent", mock(ClientMultiAgent.class)); put("memoryStore", mock(MemoryStore.class));
        put("memoryService", mock(MemoryService.class)); put("contextCompressor", mock(ContextCompressor.class));
        put("builtinTools", mock(BuiltinTools.class));
        var workspaces = mock(ConversationWorkspaceService.class);
        when(workspaces.sessionRoot(SID, USER)).thenReturn(Optional.empty());
        put("conversationWorkspaceService", workspaces);
        memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(UserMessage.from(OBJECTIVE)); memory.add(AiMessage.from("waiting for confirmation"));
        put("chatMemoryProvider", (ChatMemoryConfig.ChatMemoryProvider) sid -> memory);
        var multimodal = mock(MultimodalMessageBuilder.class);
        when(multimodal.referenceFilesForConversation(eq(USER), anyList())).thenReturn(List.of());
        put("multimodalBuilder", multimodal);
        var factory = mock(ModelClientFactory.class);
        when(factory.resolve(USER)).thenReturn(new ModelClientFactory.ResolvedModels(mock(ChatModel.class), null,
                new EffectiveModelSettings("test", "test", "http://localhost", "test", "test", 32_000)));
        put("modelClientFactory", factory);
        var taskPlans = mock(TaskPlanFactory.class);
        when(taskPlans.build(OBJECTIVE)).thenReturn(TaskPlan.of(OBJECTIVE)); put("taskPlanFactory", taskPlans);
        when(loader.load(eq(SID), eq(OBJECTIVE), eq(false), anyList(), eq(TaskBoundary.SAME)))
                .thenReturn(new LoadedContext("system", memory.messages(), null, SID, 0, Map.of()));
        when(todos.get(SID)).thenReturn(List.of());
        when(todos.progressContext(SID)).thenAnswer(ignored -> {
            var snapshot = states.get(SID).orElseThrow();
            return new TaskTodoStore.ProgressContext(EXECUTION, snapshot.version(), OBJECTIVE, "", Map.of());
        });
    }

    private void put(String field, Object value) { ReflectionTestUtils.setField(chats, field, value); }
    private static void assertConflict(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
    private static TaskNode node(String id, TaskNodeStatus status) {
        return new TaskNode(id, "Verify " + id, "research", List.of(), List.of(), List.of(), status,
                1, DoneWhen.note(), "read_file", "", 0, "");
    }
}
