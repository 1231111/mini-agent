package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.LoopTurnPolicy;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.todo.AcceptanceFeedback;
import com.miniagent.agent.todo.LlmJudgeTodoValidator;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.agent.tool.WorkspaceContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Runs the actual orchestration loop; only the model and node execution boundaries are scripted. */
class PlanningLoopAutonomousRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FACTS_CHECK = "事实说明包含游戏入口和状态更新逻辑及其真实来源";
    private static final String ORDER_CHECK = "先搜索符号，再读取命中区域，并提交成对的真实工具回执";
    private static final String READ_ONLY_CHECK = "只读核验，不修改源文件";
    private static final String SOURCE = "// game source\n// inspect matched regions only\n"
            + "function setAnimationLoop(state) {\n  return state + 1;\n}\n";

    @TempDir
    Path workspace;

    @AfterEach
    void clearContexts() {
        WorkspaceContext.clearAll();
        LoopTurnContext.clear();
        ExecutionTurnContext.clear();
        PermissionContext.clear();
    }

    @Test
    void semanticFailureIsAnalyzedReplannedExecutedAndAcceptedWithoutRepeatingSuccess() throws Exception {
        Scenario scenario = new Scenario(false);
        assertThat(scenario.initial.graph().byId("inspect").status()).isEqualTo(TaskNodeStatus.PENDING);
        assertThat(scenario.initial.execution()).containsEntry("recovery.LOCAL_REPAIR", 3)
                .containsEntry(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT, 3);

        PlanningLoop.RunOutcome outcome;
        try (RecoveryBudget.Scope ignored = RecoveryBudget.bindRound("fresh-user", true)) {
            outcome = scenario.run();
        }

        StateSnapshot completed = scenario.current();
        assertThat(outcome.outcome()).isEqualTo(PlanningLoop.Outcome.COMPLETED);
        assertThat(completed.graph().allTerminalSuccess()).isTrue();
        assertThat(completed.planVersion()).isGreaterThan(scenario.initial.planVersion());
        assertThat(completed.recoveryCount()).isEqualTo(scenario.initial.recoveryCount() + 1);
        assertThat(completed.goal()).isEqualTo(scenario.goal);
        assertThat(completed.intent()).isEqualTo(scenario.intent);
        assertThat(completed.graph().byId("inventory")).isEqualTo(scenario.inventory);
        assertThat(completed.graph().byId("inspect")).isNull();
        TaskNode repaired = completed.graph().nodes().stream()
                .filter(node -> node.outputs().contains("source-facts")).findFirst().orElseThrow();
        TaskNode notes = completed.graph().nodes().stream()
                .filter(node -> node.outputs().contains("final-notes")).findFirst().orElseThrow();
        assertThat(repaired.id()).startsWith("rp");
        assertThat(repaired.doneWhen()).isEqualTo(scenario.frozen);
        assertThat(repaired.outputs()).containsExactly("source-facts");
        assertThat(repaired.outputBindings()).containsEntry("source-facts", scenario.factsClaim);
        assertThat(notes.dependsOn()).containsExactly(repaired.id());
        assertThat(notes.outputBindings()).containsKey("final-notes");
        assertThat(notes.output()).contains("setAnimationLoop");
        ExecutionEvidence proof = ExecutionEvidence.fromEnvelope(repaired.output());
        assertThat(proof.completeness().complete()).isTrue();
        assertThat(proof.observations()).extracting(ExecutionEvidence.Observation::toolName)
                .containsExactly("search_code", "read_file");
        assertThat(proof.observations()).allSatisfy(observation -> {
            assertThat(observation.success()).isTrue();
            assertThat(observation.resultMessageIndex()).isGreaterThan(observation.callMessageIndex());
            assertThat(observation.receipt()).contains("setAnimationLoop");
        });
        assertThat(scenario.dispatches).containsExactly("inspect", repaired.id(), notes.id());
        assertThat(scenario.reads).hasValue(2);
        assertThat(scenario.searches).hasValue(1);
        assertThat(scenario.submissions).hasValue(3);
        assertThat(scenario.failVerdicts).hasValue(1);
        // All three frozen criteria run on the replacement and again at graph acceptance.
        assertThat(scenario.passVerdicts).hasValue(6);
        assertThat(completed.execution()).containsEntry(RecoveryBudget.ROUND_ID, "fresh-user")
                .containsEntry(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT, 1)
                .containsEntry("recovery.REWRITE_GRAPH", 1)
                .doesNotContainKey("recovery.LOCAL_REPAIR");
        assertThat(RecoveryEngine.counter(completed.execution().get(
                PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL))).isEqualTo(4);
        assertThat(scenario.store.events(scenario.session)).filteredOn(event ->
                event.type() == DomainEventType.RECOVERY_APPLIED).singleElement().satisfies(event -> {
                    assertThat(event.taskId()).isEqualTo("inspect");
                    assertThat(event.payload()).containsEntry("class", "REWRITE_GRAPH")
                            .containsEntry("kind", "EVAL_FAILED").containsEntry("llmReplan", true);
                });
        assertRecoveryRequestAndCompilation(scenario);
        verify(scenario.executor, times(3)).execute(any(ChatModel.class), anyString(), anyString(), any(),
                anyList(), anyInt(), any(TaskPlan.class), any());
        verify(scenario.executor, never()).executeBoundInvocation(anyString(), anyString());
        verify(scenario.executor, never()).continueNode(any(ChatModel.class), anyList(), anyString(),
                anyInt(), any(TaskPlan.class), any());
        assertThat(Files.readString(workspace.resolve("game.js"))).isEqualTo(SOURCE);
    }

    @Test
    void weakenedDoneWhenIsRejectedBeforeAnyReplacementNodeCanExecute() throws Exception {
        Scenario scenario = new Scenario(true);
        assertThat(scenario.properties.getMaxReplanRetries()).isZero();
        PlanningLoop.RunOutcome outcome;
        try (RecoveryBudget.Scope ignored = RecoveryBudget.bindRound("fresh-user", true)) {
            outcome = scenario.run();
        }

        StateSnapshot retained = scenario.current();
        assertThat(outcome.outcome()).isEqualTo(PlanningLoop.Outcome.FAILED);
        assertThat(outcome.reason()).isEqualTo("REPLAN_VALIDATION_FAILED");
        assertThat(retained.planVersion()).isEqualTo(scenario.initial.planVersion());
        assertThat(retained.graph().byId("inventory")).isEqualTo(scenario.inventory);
        assertThat(retained.graph().byId("inspect").doneWhen()).isEqualTo(scenario.frozen);
        assertThat(retained.graph().byId("inspect").status()).isEqualTo(TaskNodeStatus.FAILED);
        assertThat(retained.graph().byId("notes").status()).isEqualTo(TaskNodeStatus.PENDING);
        assertThat(retained.graph().nodes()).noneMatch(node -> node.id().startsWith("rp"));
        assertThat(retained.recoveryCount()).isEqualTo(scenario.initial.recoveryCount());
        assertThat(scenario.dispatches).containsExactly("inspect");
        assertThat(scenario.reads).hasValue(1);
        assertThat(scenario.searches).hasValue(0);
        assertThat(scenario.submissions).hasValue(1);
        assertThat(scenario.failVerdicts).hasValue(1);
        assertThat(scenario.passVerdicts).hasValue(0);
        assertThat(scenario.store.events(scenario.session)).noneMatch(event ->
                event.type() == DomainEventType.RECOVERY_APPLIED);
        assertRecoveryRequestAndCompilation(scenario);
        verify(scenario.executor).execute(any(ChatModel.class), anyString(), anyString(), any(),
                anyList(), anyInt(), any(TaskPlan.class), any());
        assertThat(Files.readString(workspace.resolve("game.js"))).isEqualTo(SOURCE);
    }

    private static void assertRecoveryRequestAndCompilation(Scenario scenario) throws Exception {
        JsonNode request = scenario.recoveryPayload.get();
        assertThat(request).isNotNull();
        assertThat(request.path("kind").asText()).isEqualTo("UNTRUSTED_RECOVERY_DATA");
        assertThat(request.path("identity").path("sessionId").asText()).isEqualTo(scenario.session);
        assertThat(request.path("identity").path("executionId").asText()).isEqualTo(scenario.execution);
        assertThat(request.path("identity").path("planVersion").asLong())
                .isEqualTo(scenario.initial.planVersion());
        assertThat(request.path("identity").path("nodeId").asText()).isEqualTo("inspect");
        assertThat(request.path("acceptanceFeedback").path("kind").asText()).isEqualTo("ARTIFACT_DEFECT");
        assertThat(request.path("executionEvidence").path("observations")).hasSize(1);
        assertThat(request.path("executionEvidence").path("observations").get(0)
                .path("toolName").asText()).isEqualTo("read_file");
        assertThat(request.path("failedNode").path("doneWhen"))
                .isEqualTo(JSON.valueToTree(scenario.frozen));
        assertThat(scenario.correction.get()).contains("The original attempt read without a search receipt",
                ORDER_CHECK, READ_ONLY_CHECK, "inventory", "source-facts", "final-notes");
        verify(scenario.chat).chat(any(ChatRequest.class));
        verify(scenario.compiler).compileWithCorrection(eq(scenario.chat), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class));
    }

    private final class Scenario {
        final String session = "s_autonomous_" + System.nanoTime();
        final String execution = "exec_autonomous_" + System.nanoTime();
        final PlannerProperties properties = new PlannerProperties();
        final PlannerStateStore store = new PlannerStateStore();
        final GoalCompiler compiler = mock(GoalCompiler.class);
        final ChatModel chat = mock(ChatModel.class);
        final NodeExecutor executor = mock(NodeExecutor.class);
        final AtomicReference<JsonNode> recoveryPayload = new AtomicReference<>();
        final AtomicReference<String> correction = new AtomicReference<>();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger searches = new AtomicInteger();
        final AtomicInteger submissions = new AtomicInteger();
        final AtomicInteger failVerdicts = new AtomicInteger();
        final AtomicInteger passVerdicts = new AtomicInteger();
        final List<String> dispatches = new ArrayList<>();
        final Map<Integer, String> submitted = new LinkedHashMap<>();
        final ToolRegistry tools = new ToolRegistry();
        final TodoStateProjector projector = mock(TodoStateProjector.class);
        final TodoStateProjector todoIds = new TodoStateProjector(null);
        final DoneWhen frozen = new DoneWhen(DoneWhen.NOTE, FACTS_CHECK, "",
                List.of(ORDER_CHECK, READ_ONLY_CHECK));
        final Goal goal = new Goal("source-goal", "梳理游戏入口和状态逻辑，并给出事实说明", "",
                "research", Map.of(), List.of(READ_ONLY_CHECK), List.of(FACTS_CHECK));
        final TaskIntent intent = TaskIntent.fromGoal(goal);
        final TaskNode inventory = node("inventory", "已核验工作区清单", "file_read", List.of(),
                List.of(), "workspace-inventory", DoneWhen.note(), 100)
                .withStatus(TaskNodeStatus.SUCCESS)
                .withOutput("game.js 位于工作区", Map.of("workspace-inventory", "game.js"))
                .withToolStatus(ToolStatus.SUCCESS);
        final String factsClaim = "游戏入口 setAnimationLoop(state)，读取命中区域后确认状态更新为 state + 1。";
        final StateSnapshot initial;
        final PlanningLoop loop;

        Scenario(boolean weakenContract) throws Exception {
            Files.writeString(workspace.resolve("game.js"), SOURCE);
            WorkspaceContext.setRootOverride(workspace);
            properties.setMaxReplanRetries(0);
            properties.setMaxOuterRounds(8);
            properties.setProposalMaxToolCalls(6);
            BuiltinTools builtins = new BuiltinTools();
            ReflectionTestUtils.setField(builtins, "registry", tools);
            ReflectionTestUtils.invokeMethod(builtins, "registerFileTools");
            ReflectionTestUtils.invokeMethod(builtins, "registerSearchAndEditTools");

            TaskNode inspect = node("inspect", "梳理游戏入口和状态逻辑", "file_read", List.of("inventory"),
                    List.of("workspace-inventory"), "source-facts", frozen, 90);
            TaskNode notes = node("notes", "提供入口和状态逻辑的事实说明", "deliver", List.of("inspect"),
                    List.of("source-facts"), "final-notes", DoneWhen.note(), 80);
            StateSnapshot created = store.init(session, execution, goal,
                    new TaskGraph(List.of(inventory, inspect, notes)), intent,
                    GoalCompiler.CompilationSource.MODEL, false);
            // Reproduce the exhausted legacy user turn; a trusted new request must rotate it.
            initial = store.commit(session, created.version(), created.withExecution(Map.of(
                    "recovery.LOCAL_REPAIR", 3,
                    PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT, 3,
                    PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL, 3)));

            when(projector.todoIdFor(any(TaskGraph.class), anyString())).thenAnswer(invocation ->
                    todoIds.todoIdFor(invocation.getArgument(0), invocation.getArgument(1)));
            when(projector.submittedEvidence(eq(session), anyInt())).thenAnswer(invocation ->
                    submitted.getOrDefault(invocation.getArgument(1), ""));

            LlmJudgeTodoValidator judge = mock(LlmJudgeTodoValidator.class);
            when(judge.validateFeedback(any(TaskTodoStore.TodoItem.class), anyString())).thenAnswer(invocation -> {
                TaskTodoStore.TodoItem item = invocation.getArgument(0);
                String criterion = item.doneWhen().substring((DoneWhen.JUDGE + ":").length());
                ExecutionEvidence evidence = ExecutionEvidence.fromEnvelope(invocation.getArgument(1));
                List<String> observedTools = evidence.observations().stream()
                        .map(ExecutionEvidence.Observation::toolName).toList();
                if (!observedTools.contains("search_code")) {
                    failVerdicts.incrementAndGet();
                    assertThat(observedTools).containsExactly("read_file");
                    return new AcceptanceFeedback(1, AcceptanceFeedback.criterionId(criterion),
                            AcceptanceFeedback.Verdict.FAIL, AcceptanceFeedback.FailureKind.ARTIFACT_DEFECT,
                            "原尝试缺少 search_code 实际回执，入口结论没有命中来源支持", List.of(),
                            AcceptanceFeedback.NextAction.FIX_ARTIFACT, AcceptanceFeedback.Protocol.STRUCTURED);
                }
                assertThat(observedTools).containsExactly("search_code", "read_file");
                assertThat(evidence.completeness().complete()).isTrue();
                assertThat(evidence.observations()).allMatch(observation -> observation.success()
                        && observation.receipt().contains("setAnimationLoop"));
                assertThat(evidence.observations().get(0).resultMessageIndex())
                        .isLessThan(evidence.observations().get(1).callMessageIndex());
                passVerdicts.incrementAndGet();
                return AcceptanceFeedback.pass(criterion, "真实搜索命中与随后读取结果满足冻结条件",
                        AcceptanceFeedback.Protocol.STRUCTURED);
            });

            when(compiler.resolvePlannerChat(chat)).thenReturn(chat);
            when(chat.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
                ChatRequest request = invocation.getArgument(0);
                UserMessage user = (UserMessage) request.messages().get(1);
                JsonNode payload = JSON.readTree(user.singleText());
                assertThat(payload.path("kind").asText()).isEqualTo("UNTRUSTED_RECOVERY_DATA");
                recoveryPayload.set(payload);
                JsonNode identity = payload.path("identity");
                ObjectNode decision = JSON.createObjectNode().put("schemaVersion", 1);
                for (String field : List.of("sessionId", "executionId", "planVersion", "nodeId")) {
                    decision.set(field, identity.get(field));
                }
                decision.put("action", "REPLAN")
                        .put("rootCause", "The original attempt read without a search receipt; collect targeted observations.");
                decision.putArray("nextSteps").add("Search setAnimationLoop in game.js")
                        .add("Read the matched range and submit the paired receipts")
                        .add("Re-evaluate the unchanged acceptance checks and provide the notes");
                decision.putArray("evidenceNeeded").add("search_code hit receipt").add("matched read_file receipt");
                ChatResponse response = mock(ChatResponse.class);
                when(response.aiMessage()).thenReturn(AiMessage.from(JSON.writeValueAsString(decision)));
                return response;
            });
            when(compiler.compileWithCorrection(eq(chat), anyString(), any(TaskPlan.class),
                    anyString(), anyList(), any(TaskGraph.class))).thenAnswer(invocation -> {
                correction.set(invocation.getArgument(3));
                TaskGraph failed = invocation.getArgument(5);
                assertThat(failed.byId("inspect").status()).isEqualTo(TaskNodeStatus.FAILED);
                assertThat(failed.byId("inventory")).isEqualTo(inventory);
                DoneWhen replacementContract = weakenContract
                        ? new DoneWhen(frozen.type(), frozen.criteria(), frozen.path(), List.of(READ_ONLY_CHECK))
                        : frozen;
                TaskNode replacement = node("focused_inspect", "按命中区域核验游戏入口和状态逻辑", "file_read",
                        List.of("inventory"), List.of("workspace-inventory"), "source-facts", replacementContract, 90);
                TaskNode remainingNotes = node("notes", notes.name(), "deliver", List.of("focused_inspect"),
                        notes.inputs(), "final-notes", notes.doneWhen(), 80);
                return new GoalCompiler.CompileResult(goal, new TaskGraph(List.of(replacement, remainingNotes)),
                        false, GoalCompiler.CompilationSource.MODEL, "", intent);
            });
            doAnswer(invocation -> executeNode(invocation.getArgument(2))).when(executor)
                    .execute(any(ChatModel.class), anyString(), anyString(), any(), anyList(), anyInt(),
                            any(TaskPlan.class), any());
            CapabilityRegistry capabilities = new CapabilityRegistry(tools);
            PlannerMetrics metrics = new PlannerMetrics();
            SessionLock lock = mock(SessionLock.class);
            when(lock.renewSessionLock(anyString(), anyString())).thenReturn(true);
            loop = new PlanningLoop(properties, compiler, new PlanValidator(properties), store,
                    new GraphScheduler(properties), new ToolRouter(capabilities),
                    new StepEvaluator(properties, metrics, judge), new RecoveryEngine(store, capabilities, properties),
                    projector, executor, metrics, lock, mock(ToolSuccessStats.class));
        }

        PlanningLoop.RunOutcome run() {
            return loop.runWithOutcome(chat, "", "继续梳理游戏逻辑", null, List.of(),
                    TaskPlan.of(goal.objective()), session, "fence", execution, null,
                    PlanningRoute.resume("test", "RESUME_EXISTING"));
        }

        StateSnapshot current() {
            return store.get(session).orElseThrow();
        }

        private AgentLoop.LoopOutcome executeNode(String user) throws Exception {
            TaskNode running = current().graph().nodes().stream()
                    .filter(node -> node.status() == TaskNodeStatus.RUNNING).findFirst().orElseThrow();
            dispatches.add(running.id());
            assertThat(running.id()).isNotEqualTo("inventory");
            assertThat(ExecutionTurnContext.current().isValid()).isTrue();
            List<ChatMessage> messages = new ArrayList<>();
            List<String> invoked = new ArrayList<>();
            String claim;
            if (running.outputs().contains("source-facts")) {
                if (!running.id().equals("inspect")) {
                    call("search_code", Map.of("pattern", "setAnimationLoop", "path", workspace.toString(),
                            "glob", "*.js", "maxResults", 5), messages, invoked);
                }
                call("read_file", Map.of("path", "game.js", "offset", running.id().equals("inspect") ? 1 : 3,
                        "limit", running.id().equals("inspect") ? 2 : 3), messages, invoked);
                // A model mentioning the tool in prose must never manufacture its observation.
                claim = running.id().equals("inspect") ? "声称 search_code 已找到入口，但当前只有开头两行读取。" : factsClaim;
            } else {
                assertThat(user).contains("setAnimationLoop", "source-facts");
                claim = "事实说明：setAnimationLoop(state) 是入口，状态由 state + 1 更新；来源为真实搜索命中及读取回执。";
            }
            int todoId = todoIds.todoIdFor(current().graph(), running.id());
            assertThat(todoId).isPositive();
            submitted.put(todoId, claim); // authoritative submit boundary is mocked, graph status is never changed here
            submissions.incrementAndGet();
            String submit = JSON.writeValueAsString(Map.of("success", true, "action", "submit", "id", todoId,
                    "evidence", claim));
            addReceipt(messages, CapabilityRegistry.FALLBACK_TOOL,
                    JSON.writeValueAsString(Map.of("action", "submit", "id", todoId, "evidence", claim)), submit);
            invoked.add(CapabilityRegistry.FALLBACK_TOOL);
            return new AgentLoop.LoopOutcome(claim, "SUCCESS", messages, invoked, ToolErrorCode.NONE,
                    ToolResult.success(submit));
        }

        private void call(String tool, Map<String, Object> arguments, List<ChatMessage> messages,
                          List<String> invoked) throws Exception {
            String encoded = JSON.writeValueAsString(arguments);
            LoopTurnPolicy policy = LoopTurnContext.current();
            assertThat(policy.denyToolInvocation(tool, encoded)).isNull();
            ToolResult result = tools.executeResult(tool, encoded);
            assertThat(result.isSuccess()).as("real %s receipt: %s", tool, result.legacyText()).isTrue();
            policy.observeToolOutcome(tool, encoded, result);
            if (tool.equals("read_file")) reads.incrementAndGet();
            if (tool.equals("search_code")) searches.incrementAndGet();
            addReceipt(messages, tool, encoded, result.legacyText());
            invoked.add(tool);
        }
    }

    private static void addReceipt(List<ChatMessage> messages, String tool, String arguments, String result) {
        String id = "call-" + messages.size();
        messages.add(AiMessage.builder().text("").toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                .id(id).name(tool).arguments(arguments).build())).build());
        messages.add(ToolExecutionResultMessage.from(id, tool, result));
    }

    private static TaskNode node(String id, String name, String capability, List<String> dependencies,
                                 List<String> inputs, String output, DoneWhen doneWhen, int priority) {
        return new TaskNode(id, name, capability, dependencies, inputs, List.of(output), TaskNodeStatus.PENDING,
                priority, doneWhen, "", "", 0, "");
    }
}
