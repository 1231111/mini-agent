package com.miniagent.agent.planner;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ModelCallControl;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.agent.todo.AcceptanceFeedback;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Production acceptance for the failure-control loop. This test exercises the
 * planner APIs directly; no desktop client or computer-use action is involved.
 */
class AutonomousFailureRecoveryIntegrationTest {

    @Test
    void analyzesFailureReplansOnlyTheFailedNodeAndRetainsFrozenContract() {
        PlannerStateStore store = new PlannerStateStore();
        String session = "s_recovery_closed_loop";
        String execution = "exec_recovery_closed_loop";
        Goal goal = new Goal("recovery-goal", "inspect the source and produce the facts", "",
                "research", Map.of(), List.of(), List.of());

        TaskNode accepted = new TaskNode("inventory", "已核验工作区文件清单", "file_read", List.of(),
                List.of(), List.of("workspace-inventory"), TaskNodeStatus.SUCCESS, 100,
                DoneWhen.note(), "", "", 0, "workspace inventory receipt")
                .withToolStatus(ToolStatus.SUCCESS);
        DoneWhen frozen = new DoneWhen(DoneWhen.NOTE, "", "", List.of());
        TaskNode failed = new TaskNode("inspect", "梳理源文件入口和状态逻辑", "file_read",
                List.of("inventory"), List.of("workspace-inventory"), List.of("source-facts"),
                TaskNodeStatus.FAILED, 90, frozen, "", "过程证据缺少 search_code 回执", 0,
                "{\"kind\":\"EXECUTION_EVIDENCE\",\"observations\":[],\"complete\":false}");
        StateSnapshot snapshot = store.init(session, execution, goal,
                new TaskGraph(List.of(accepted, failed)), TaskIntent.fromGoal(goal));

        FailureDiagnosis diagnosis = new FailureDiagnosis(FailureClass.REWRITE_GRAPH,
                FailureKind.EVAL_FAILED, failed.id(), "read_file",
                "验收失败：缺少先搜索再读取的真实工具回执",
                "补齐真实只读观察后重新验收");
        AcceptanceFeedback feedback = new AcceptanceFeedback(1,
                AcceptanceFeedback.criterionId("必须先搜索再读取"),
                AcceptanceFeedback.Verdict.FAIL,
                AcceptanceFeedback.FailureKind.PROCESS_EVIDENCE_GAP,
                "缺少先搜索再读取的真实工具回执",
                List.of("search_code 调用和命中位置"),
                AcceptanceFeedback.NextAction.COLLECT_EVIDENCE,
                AcceptanceFeedback.Protocol.STRUCTURED);

        ChatModel recoveryModel = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from("""
                {"schemaVersion":1,"sessionId":"s_recovery_closed_loop","executionId":"exec_recovery_closed_loop",
                 "planVersion":1,"nodeId":"inspect","action":"REPLAN",
                 "rootCause":"The prior turn read the source without a paired search receipt.",
                 "nextSteps":["Run one bounded search_code query.","Read only matched regions.","Submit paired receipts and re-evaluate the frozen node."],
                 "evidenceNeeded":["search_code receipt","matched ranges"]}
                """));
        AtomicReference<ChatRequest> request = new AtomicReference<>();
        when(recoveryModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            request.set(invocation.getArgument(0));
            return response;
        });

        FailureRecoveryPlanner.Decision decision = new FailureRecoveryPlanner((ModelCallControl) null,
                (ExecutionControl) null).analyze(recoveryModel, snapshot, failed, diagnosis, feedback,
                ExecutionEvidence.fromEnvelope(failed.output()), List.of("search_code", "read_file"));

        assertThat(decision.action()).isEqualTo(FailureRecoveryPlanner.Action.REPLAN);
        assertThat(decision.nextSteps()).hasSize(3);
        assertThat(request.get().messages().get(1).toString())
                .contains("UNTRUSTED_RECOVERY_DATA", "trustedContract", "executionEvidence");

        TaskNode replacement = new TaskNode("focused-inspect", "按命中区域梳理源文件入口和状态逻辑",
                "file_read", List.of("inventory"), List.of("workspace-inventory"),
                List.of("source-facts"), TaskNodeStatus.PENDING, 90, frozen,
                "", "", 0, "");
        TaskGraph candidate = new TaskGraph(List.of(replacement));
        assertThat(RecoveryPlanContract.validate(snapshot, candidate).valid()).isTrue();

        TaskGraph merged = PlanningLoop.mergeKeepSuccess(snapshot.graph(), candidate, 7);
        assertThat(merged).isNotNull();
        assertThat(merged.byId("inventory")).isEqualTo(accepted);
        TaskNode replanned = merged.nodes().stream()
                .filter(node -> node.name().equals(replacement.name())).findFirst().orElseThrow();
        assertThat(replanned.id()).isEqualTo("rp7_focused-inspect");
        assertThat(replanned.outputs()).containsExactly("source-facts");
        assertThat(replanned.doneWhen()).isEqualTo(frozen);
    }

    @Test
    void replanThenExecuteAndReevaluateReachesCompletedWithoutRepeatingSuccess() {
        PlannerProperties properties = new PlannerProperties();
        StepEvaluator evaluator = new StepEvaluator(properties, new PlannerMetrics(), null);
        Goal goal = new Goal("completion-goal", "inspect source", "", "research",
                Map.of(), List.of(), List.of());
        TaskNode accepted = new TaskNode("accepted", "已完成的前置核验", "research", List.of(),
                List.of(), List.of("accepted-facts"), TaskNodeStatus.SUCCESS, 100,
                DoneWhen.note(), "", "", 0, "accepted receipt")
                .withOutput("accepted receipt", Map.of("accepted-facts", "accepted receipt"))
                .withToolStatus(ToolStatus.SUCCESS);
        TaskNode replacement = new TaskNode("rp7_inspect", "按搜索命中区域完成源文件梳理", "file_read",
                List.of("accepted"), List.of("accepted-facts"), List.of("source-facts"),
                TaskNodeStatus.SUCCESS, 90, DoneWhen.note(), "", "", 0,
                "search_code: setAnimationLoop\nread_file: matched region\n验收回执已提交")
                .withOutput("search_code: setAnimationLoop\nread_file: matched region\n验收回执已提交",
                        Map.of("source-facts", "search_code: setAnimationLoop\nread_file: matched region"))
                .withToolStatus(ToolStatus.SUCCESS);
        StateSnapshot completed = new PlannerStateStore().init("s_completion", "exec_completion", goal,
                new TaskGraph(List.of(accepted, replacement)), TaskIntent.fromGoal(goal));

        assertThat(completed.graph().allTerminalSuccess()).isTrue();
        StepEvaluator.GraphEval result = evaluator.evaluateGraph(goal, completed.graph(), completed);
        assertThat(result.ok()).isTrue();
        assertThat(completed.graph().byId("accepted").output()).isEqualTo("accepted receipt");
        assertThat(completed.graph().byId("accepted").retryCount()).isZero();
        assertThat(completed.graph().byId("rp7_inspect").output()).contains("search_code", "read_file");
    }
}
