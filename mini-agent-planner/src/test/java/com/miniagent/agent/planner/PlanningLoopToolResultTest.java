package com.miniagent.agent.planner;

import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.execution.ToolInvocation;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PlanningLoopToolResultTest {
    @TempDir
    Path dir;

    @AfterEach
    void clearPermission() {
        PermissionContext.clear();
    }

    @Test
    void successfulReadContainingTimeoutAndNestedErrorCompletesTheNode() {
        TaskNode read = new TaskNode("read", "读取 config.json", "file_read", List.of(),
                List.of(), List.of("config"), TaskNodeStatus.PENDING, 1,
                DoneWhen.note(), "", "", 0, "")
                .withToolArguments(Map.of("path", "config.json"));
        String body = "{\"success\":true,\"data\":{\"timeout\":30,\"error\":\"documented value\"}}";
        Run run = run(read, ToolResult.success(body));

        assertEquals(PlanningLoop.Outcome.COMPLETED, run.outcome().outcome());
        assertEquals(TaskNodeStatus.SUCCESS, run.store().get("s").orElseThrow().graph().byId("read").status());
    }

    @Test
    void structuredSuccessOutranksTopLevelBusinessErrorEvenAtGraphAcceptance() throws Exception {
        TaskNode read = readNode();
        String body = "{\"error\":\"this is a documented business field\",\"timeout\":30}";
        Run run = run(read, ToolResult.success(body));

        assertEquals(PlanningLoop.Outcome.COMPLETED, run.outcome().outcome());
        TaskNode saved = run.store().get("s").orElseThrow().graph().byId("read");
        TaskNode restored = PlannerStateJson.MAPPER.readValue(
                PlannerStateJson.MAPPER.writeValueAsString(saved), TaskNode.class);
        assertEquals(ToolStatus.SUCCESS, restored.toolStatus());
        assertEquals(ToolStatus.SUCCESS,
                restored.withIdentityAndDependencies("renamed", List.of()).toolStatus());
    }

    @Test
    void unknownOutcomeDoesNotRecoverOrReplayWhenUserContinues() {
        Run run = run(readNode(), ToolResult.unknown("The request may have completed remotely", null));
        assertUnknownAndNoReplay(run);
    }

    @Test
    void thrownExecutionErrorIsUnknownRatherThanAnAutomaticallyRetryableFailure() {
        Run run = run(readNode(), null, new IllegalStateException("connection reset after dispatch"));
        assertUnknownAndNoReplay(run);
    }

    @Test
    void interruptedDispatchReceiptIsQuarantinedBeforeAnyReplay() {
        TaskNode dispatched = readNode().withStatus(TaskNodeStatus.RUNNING)
                .withToolStatus(ToolStatus.UNKNOWN);
        PlannerStateStore store = new PlannerStateStore();
        store.init("s", "execution", new Goal("goal", "保存报告", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(dispatched)));
        NodeExecutor executor = mock(NodeExecutor.class);
        PlanningLoop loop = loop(store, executor, List.of("read_file", "write_file"));
        PermissionContext.force("s", PermissionMode.PLAN, true);

        PlanningLoop.RunOutcome outcome = invoke(loop);

        assertEquals("OUTCOME_UNKNOWN", outcome.reason());
        TaskNode saved = store.get("s").orElseThrow().graph().byId("read");
        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN, saved.status());
        verifyNoInteractions(executor);
    }

    @Test
    void thrownReActExecutionIsUnknownRatherThanAnAutomaticallyRetryableFailure() {
        TaskNode web = new TaskNode("web", "搜索今日 AI 新闻", "web_search", List.of(),
                List.of(), List.of("news"), TaskNodeStatus.PENDING, 1,
                DoneWhen.note(), "", "", 0, "")
                .withToolArguments(Map.of("query", "today AI news"));
        PlannerStateStore store = new PlannerStateStore();
        store.init("s", "execution", new Goal("goal", "搜索今日 AI 新闻", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(web)));
        NodeExecutor executor = mock(NodeExecutor.class);
        when(executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("connection reset after dispatch"));
        PlanningLoop loop = loop(store, executor, List.of("web_search"));
        PermissionContext.force("s", PermissionMode.PLAN, true);

        PlanningLoop.RunOutcome outcome = invoke(loop);

        assertEquals("OUTCOME_UNKNOWN", outcome.reason());
        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN,
                store.get("s").orElseThrow().graph().byId("web").status());
        verify(executor, times(1)).execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any());
        verify(executor, never()).executeBoundInvocation(anyString(), anyString());
    }

    @Test
    void reActStructuredFailureCannotBecomeSuccessFromPlainPayload() {
        ToolResult failure = ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "upstream rejected", false, "upstream rejected");

        Run run = runReAct(failure);

        assertNotEquals(PlanningLoop.Outcome.COMPLETED, run.outcome().outcome());
        TaskNode saved = run.store().get("s").orElseThrow().graph().byId("web");
        assertNotEquals(TaskNodeStatus.SUCCESS, saved.status());
        assertEquals(ToolStatus.FAILED, saved.toolStatus());
    }

    @Test
    void reActStructuredUnknownCannotBeHiddenBySuccessEndReason() {
        Run run = runReAct(ToolResult.unknown("remote side effect unknown", "operation pending"));

        assertEquals("OUTCOME_UNKNOWN", run.outcome().reason());
        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN,
                run.store().get("s").orElseThrow().graph().byId("web").status());
        assertEquals("OUTCOME_UNKNOWN", invoke(run.loop()).reason());
        verify(run.executor(), times(1)).execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any());
    }

    @Test
    void reActStructuredSuccessRetainsAuthorityOverBusinessErrorFields() {
        Run run = runReAct(ToolResult.success("{\"error\":\"documented field\",\"timeout\":30}"));

        assertEquals(PlanningLoop.Outcome.COMPLETED, run.outcome().outcome());
        assertEquals(ToolStatus.SUCCESS,
                run.store().get("s").orElseThrow().graph().byId("web").toolStatus());
    }

    private Run runReAct(ToolResult result) {
        TaskNode web = new TaskNode("web", "搜索资料", "web_search", List.of(), List.of(),
                List.of("findings"), TaskNodeStatus.PENDING, 1, DoneWhen.note(), "", "", 0, "")
                .withToolArguments(Map.of("query", "AI news"));
        PlannerStateStore store = new PlannerStateStore();
        store.init("s", "execution", new Goal("goal", "搜索资料", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(web)));
        NodeExecutor executor = mock(NodeExecutor.class);
        AgentLoop.LoopOutcome out = new AgentLoop.LoopOutcome("task complete", "SUCCESS",
                List.of(ToolExecutionResultMessage.from("search", "web_search", result.legacyText())),
                List.of("web_search"), result.errorCode(), result);
        when(executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(out);
        PlanningLoop loop = loop(store, executor, List.of("web_search"));
        PermissionContext.force("s", PermissionMode.PLAN, true);

        return new Run(invoke(loop), store, loop, executor);
    }

    private void assertUnknownAndNoReplay(Run run) {
        assertEquals(PlanningLoop.Outcome.FAILED, run.outcome().outcome());
        assertEquals("OUTCOME_UNKNOWN", run.outcome().reason());
        TaskNode saved = run.store().get("s").orElseThrow().graph().byId("read");
        assertEquals(ToolStatus.UNKNOWN, saved.toolStatus());
        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN, saved.status());

        PlanningLoop.RunOutcome resumed = invoke(run.loop());
        assertEquals("OUTCOME_UNKNOWN", resumed.reason());
        verify(run.executor(), times(1)).executeBoundInvocation(anyString(), anyString());
        assertEquals(0, run.store().get("s").orElseThrow().recoveryCount());
    }

    @Test
    void structuredWriteFailureCannotUseAnExistingFileAsSuccessfulEvidence() throws Exception {
        Path target = dir.resolve("notes.md");
        Files.writeString(target, "# Old report\n\nThis report already existed before the failed replacement attempt.");
        TaskNode write = new TaskNode("write", "保存报告", "file_write", List.of(),
                List.of(), List.of("report"), TaskNodeStatus.PENDING, 1,
                DoneWhen.file(target.toString()), "", "", 0, "")
                .withToolArguments(Map.of("content", "New report content"));

        Run run = run(write, ToolResult.failure(
                ToolErrorCode.EXECUTION_FAILED, "write rejected", false, "write rejected"));

        assertNotEquals(PlanningLoop.Outcome.COMPLETED, run.outcome().outcome());
        assertNotEquals(TaskNodeStatus.SUCCESS,
                run.store().get("s").orElseThrow().graph().byId("write").status());
        assertTrue(Files.readString(target).startsWith("# Old report"));
    }

    private Run run(TaskNode node, ToolResult result) {
        return run(node, result, null);
    }

    private Run run(TaskNode node, ToolResult result, RuntimeException exception) {
        PlannerStateStore store = new PlannerStateStore();
        store.init("s", "execution", new Goal("goal", "保存报告", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(node)));
        NodeExecutor executor = mock(NodeExecutor.class);
        if (exception == null) {
            when(executor.executeBoundInvocation(anyString(), anyString()))
                    .thenAnswer(call -> ToolInvocation.executed(call.getArgument(0), result));
        } else {
            when(executor.executeBoundInvocation(anyString(), anyString())).thenThrow(exception);
        }
        PlanningLoop loop = loop(store, executor, List.of("read_file", "write_file"));
        PermissionContext.force("s", PermissionMode.PLAN, true);
        PlanningLoop.RunOutcome outcome = invoke(loop);
        verify(executor).executeBoundInvocation(anyString(), anyString());
        return new Run(outcome, store, loop, executor);
    }

    private PlanningLoop loop(PlannerStateStore store, NodeExecutor executor, List<String> tools) {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxRecoveries(0);
        properties.setMaxOuterRounds(3);
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        ToolRouter router = new ToolRouter(capabilities);
        when(capabilities.toolsFor(anyString())).thenReturn(tools);
        SessionLock lock = mock(SessionLock.class);
        when(lock.renewSessionLock(anyString(), anyString())).thenReturn(true);
        PlannerMetrics metrics = new PlannerMetrics();
        return new PlanningLoop(properties, mock(GoalCompiler.class), new PlanValidator(),
                store, new GraphScheduler(properties), router,
                new StepEvaluator(properties, metrics, null), new RecoveryEngine(store, capabilities, properties),
                mock(TodoStateProjector.class), executor, metrics, lock, new ToolSuccessStats());
    }

    private PlanningLoop.RunOutcome invoke(PlanningLoop loop) {
        return loop.runWithOutcome(null, "system", "继续", null, List.of(),
                TaskPlan.of("保存报告"), "s", "fence", "execution", null);
    }

    private static TaskNode readNode() {
        return new TaskNode("read", "读取 config.json", "file_read", List.of(), List.of(),
                List.of("config"), TaskNodeStatus.PENDING, 1, DoneWhen.note(), "", "", 0, "")
                .withToolArguments(Map.of("path", "config.json"));
    }

    private record Run(PlanningLoop.RunOutcome outcome, PlannerStateStore store,
                       PlanningLoop loop, NodeExecutor executor) {}
}
