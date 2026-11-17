package com.miniagent.agent.planner;

import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.task.TaskPlan;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Proves conversation data reaches the real compiler without replaying authorization. */
class PlanningConversationContextTest {
    private static final String REQUEST = "根据前面的十二项功能实现完整静态系统前端";
    private static final String REQUIREMENTS = "功能清单：多模态记录、时间同步、误差分析、三层解剖与版本对比";
    private static final String PLAN = """
            {
              "taskType":"代码生成","objective":"实现完整静态系统前端",
              "constraints":[],"successCriteria":["index.html"],"entities":{},
              "intent":{
                "objective":"实现完整静态系统前端",
                "deliverables":[{"name":"系统前端","type":"file","path":"index.html","required":true}],
                "inputs":[],"constraints":[],"exclusions":[],"acceptanceCriteria":[],
                "needsClarification":false,"clarificationQuestion":""
              },
              "nodes":[
                {"id":"ui","name":"静态系统前端","capability":"code","dependsOn":[],
                 "inputs":[],"outputs":["index.html"],"priority":50,
                 "doneWhen":{"type":"file_exists","path":"index.html"}},
                {"id":"verify-ui","name":"页面交互验收","capability":"browser","dependsOn":["ui"],
                 "inputs":["index.html"],"outputs":["界面验收结果"],"priority":50,
                 "doneWhen":{"type":"note_required"}}
              ]
            }
            """;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstCompilationAndCorrectionReceiveActualRequirementsAsReference(boolean correction) {
        PlannerProperties properties = properties();
        GoalCompiler compiler = new GoalCompiler(properties);
        ChatModel model = requiresConversation();
        List<ChatMessage> history = List.of(
                SystemMessage.from("HISTORICAL_SYSTEM_MUST_NOT_BE_REPLAYED"),
                ToolExecutionResultMessage.from("old-tool", "exec_command", "OLD_APPROVAL_TOKEN_MUST_NOT_BE_REPLAYED"),
                UserMessage.from("解释技术要求中的功能"),
                AiMessage.from(REQUIREMENTS));
        TaskPlan plan = TaskPlan.of(REQUEST).withArtifactDeliveryRequested(true);

        GoalCompiler.CompileResult result = correction
                ? compiler.compileWithCorrection(model, REQUEST, plan, "补齐节点依赖", history)
                : compiler.compile(model, REQUEST, plan, history);

        assertEquals(GoalCompiler.CompilationSource.MODEL, result.source());
        assertEquals(2, result.graph().nodes().size());
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        assertEquals(2, request.getValue().messages().size());
        String payload = payload(request.getValue());
        assertTrue(payload.contains(REQUIREMENTS));
        assertTrue(payload.indexOf(REQUEST) > payload.indexOf(REQUIREMENTS));
        assertFalse(payload.contains("HISTORICAL_SYSTEM_MUST_NOT_BE_REPLAYED"));
        assertFalse(payload.contains("OLD_APPROVAL_TOKEN_MUST_NOT_BE_REPLAYED"));
        if (correction) assertTrue(payload.contains("补齐节点依赖"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_GRAPH", "CLARIFICATION", "PLAN_APPROVAL", "TOOL_PERMISSION"})
    void followupCanCompileAfterSuggestionsClarificationOrPendingApproval(String waitingKind) {
        PlannerProperties properties = properties();
        properties.setMaxOuterRounds(0); // Only compilation/admission; no actual UI or tool side effects.
        PlannerStateStore store = new PlannerStateStore();
        if (!"NO_GRAPH".equals(waitingKind)) {
            Goal goal = new Goal("old-goal", "分析前述十二项技术要求", "",
                    "CLARIFICATION".equals(waitingKind) ? Goal.TASK_TYPE_CLARIFY : "文档分析",
                    Map.of(), List.of(), List.of());
            TaskNode old = new TaskNode("old", "原任务待确认步骤", "web", List.of(), List.of(),
                    List.of("原任务信息"), TaskNodeStatus.AWAITING_CONFIRM, 50, DoneWhen.note(), "", "", 0, "");
            TaskGraph graph = "CLARIFICATION".equals(waitingKind)
                    ? GoalCompiler.clarifyGraph() : new TaskGraph(List.of(old));
            StateSnapshot snapshot = store.init("conversation", "old-execution", goal, graph);
            if ("PLAN_APPROVAL".equals(waitingKind)) {
                store.commit("conversation", snapshot.version(), snapshot.withExecution(Map.of(
                        PlannerStateStore.EXECUTION_PLAN_APPROVAL, PlannerStateStore.PLAN_APPROVAL_PENDING)));
            } else if ("TOOL_PERMISSION".equals(waitingKind)) {
                store.commit("conversation", snapshot.version(), snapshot.withExecution(Map.of(
                        PlanningLoop.EXECUTION_HUMAN_WAIT,
                        Map.of("reason", "PERM_ASK", "taskIds", List.of("old")))));
            }
        }
        NodeExecutor executor = mock(NodeExecutor.class);
        PlanningLoop loop = new PlanningLoop(properties, new GoalCompiler(properties), new PlanValidator(), store,
                mock(GraphScheduler.class), mock(ToolRouter.class), mock(StepEvaluator.class),
                mock(RecoveryEngine.class), mock(TodoStateProjector.class), executor,
                mock(PlannerMetrics.class), mock(SessionLock.class), mock(ToolSuccessStats.class));

        PlanningLoop.RunOutcome result = loop.runWithOutcome(requiresConversation(), "system", REQUEST, null,
                List.of(UserMessage.from("请解释系统功能"), AiMessage.from(REQUIREMENTS)),
                TaskPlan.of(REQUEST), "conversation", "fence", "new-execution", null,
                new PlanningRoute(true, "model", "实现并验收界面", true));

        assertNotEquals(PlanningLoop.Outcome.FAILED, result.outcome(), result.answer());
        StateSnapshot admitted = store.get("conversation").orElseThrow();
        assertEquals("new-execution", admitted.executionId());
        assertNotNull(admitted.graph().byId("ui"));
        assertEquals(List.of("ui"), admitted.graph().byId("verify-ui").dependsOn());
        assertFalse(admitted.execution().containsKey(PlanningLoop.EXECUTION_HUMAN_WAIT));
        verifyNoInteractions(executor);
    }

    @Test
    void historicalReferenceIsBoundedAndTheCurrentRequestRemainsIntact() {
        GoalCompiler compiler = new GoalCompiler(properties());
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(PLAN));
        List<ChatMessage> history = new ArrayList<>();
        history.add(UserMessage.from("ANCIENT_REQUIREMENT_MUST_BE_DROPPED"));
        for (int i = 0; i < 30; i++) history.add(AiMessage.from("历史" + i + "界".repeat(9_000)));

        compiler.compile(model, REQUEST, TaskPlan.of(REQUEST).withArtifactDeliveryRequested(true), history);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        String payload = payload(request.getValue());
        assertTrue(payload.length() < 14_000, "reference text must not make planning context unbounded");
        assertTrue(payload.contains(REQUEST));
        assertFalse(payload.contains("ANCIENT_REQUIREMENT_MUST_BE_DROPPED"));
    }

    private static PlannerProperties properties() {
        PlannerProperties properties = new PlannerProperties();
        properties.setCompilerRetry(0);
        properties.setMaxReplanRetries(0);
        return properties;
    }

    private static ChatModel requiresConversation() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(call ->
                response(payload(call.getArgument(0)).contains(REQUIREMENTS) ? PLAN : "{\"nodes\":[]}"));
        return model;
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static String payload(ChatRequest request) {
        UserMessage user = (UserMessage) request.messages().getLast();
        return user.contents().stream().filter(TextContent.class::isInstance).map(TextContent.class::cast)
                .map(TextContent::text).collect(java.util.stream.Collectors.joining("\n"));
    }
}
