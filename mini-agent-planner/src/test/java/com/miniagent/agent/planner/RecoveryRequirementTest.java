package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Durable recovery requirements are independent of optional acceptance feedback. */
class RecoveryRequirementTest {

    @Test
    void remembersSemanticDiagnosisWithoutAcceptanceFeedbackAndReadsOnlySameIdentity() {
        TaskNode node = node("inspect", TaskNodeStatus.FAILED, "缺少可信的语义观察");
        StateSnapshot source = snapshot("session-rq", "execution-rq", new TaskGraph(List.of(node)));
        FailureDiagnosis diagnosis = new FailureDiagnosis(
                FailureClass.REWRITE_GRAPH, FailureKind.EVAL_FAILED, node.id(), "read_file",
                "图终验失败：原节点缺少可核验的搜索回执", "重新规划未完成节点并补齐真实观察");

        // This is deliberately a semantic diagnosis with no AcceptanceFeedback object.
        StateSnapshot remembered = RecoveryRequirement.remember(source, node, diagnosis);
        assertThat(remembered.execution()).containsKey(RecoveryRequirement.KEY);
        assertThat(RecoveryRequirement.diagnosis(remembered, node)).isEqualTo(diagnosis);

        StateSnapshot differentExecution = new StateSnapshot(
                remembered.version(), remembered.sessionId(), "execution-other", remembered.goal(),
                remembered.graph(), remembered.execution(), remembered.environment(),
                remembered.knowledgeRefs(), remembered.recoveryCount(), remembered.planRevision(),
                remembered.intent());
        assertThat(RecoveryRequirement.diagnosis(differentExecution, node)).isNull();

        TaskGraph revisedGraph = new TaskGraph(List.of(node.withStatus(TaskNodeStatus.PENDING)));
        StateSnapshot differentPlan = remembered.revisePlan(revisedGraph, remembered.goal(),
                "unrelated state transition");
        assertThat(differentPlan.planVersion()).isGreaterThan(remembered.planVersion());
        assertThat(RecoveryRequirement.diagnosis(differentPlan, differentPlan.graph().byId(node.id())))
                .isNull();
    }

    @Test
    void migratesOnlyLegacyRecoveryAnalysisBlockedWaitIntoAReanalysisRequirement() {
        TaskNode node = node("inspect", TaskNodeStatus.AWAITING_CONFIRM, "验收分析暂时不可用");
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put(PlanningLoop.EXECUTION_HUMAN_WAIT,
                Map.of("reason", "RECOVERY_ANALYSIS_BLOCKED", "taskIds", List.of(node.id())));
        StateSnapshot legacy = snapshot("legacy-session", "legacy-execution",
                new TaskGraph(List.of(node)), execution);

        StateSnapshot migrated = RecoveryRequirement.migrateLegacyWait(legacy);
        FailureDiagnosis diagnosis = RecoveryRequirement.diagnosis(migrated, node);
        assertThat(diagnosis).isNotNull();
        assertThat(diagnosis.failureClass()).isEqualTo(FailureClass.REWRITE_GRAPH);
        assertThat(diagnosis.kind()).isEqualTo(FailureKind.EVAL_FAILED);
        assertThat(diagnosis.taskId()).isEqualTo(node.id());
        assertThat(migrated.execution()).containsKey(PlanningLoop.EXECUTION_HUMAN_WAIT)
                .containsKey(RecoveryRequirement.KEY);
    }

    @Test
    void doesNotMigrateOrdinaryAuthorizationWait() {
        TaskNode node = node("confirm", TaskNodeStatus.AWAITING_CONFIRM, "等待用户确认");
        Map<String, Object> execution = Map.of(
                PlanningLoop.EXECUTION_HUMAN_WAIT,
                Map.of("reason", "PERM_ASK", "taskIds", List.of(node.id())));
        StateSnapshot ordinaryWait = snapshot("ordinary-session", "ordinary-execution",
                new TaskGraph(List.of(node)), execution);

        assertThat(RecoveryRequirement.migrateLegacyWait(ordinaryWait)).isEqualTo(ordinaryWait);
        assertThat(ordinaryWait.execution()).doesNotContainKey(RecoveryRequirement.KEY);
    }

    @Test
    void successfulReplanClearsRequirementAndHumanWaitWhilePreservingOtherExecutionMetadata() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put(RecoveryRequirement.KEY, Map.of("1:inspect", Map.of("nodeId", "inspect")));
        execution.put(PlanningLoop.EXECUTION_HUMAN_WAIT,
                Map.of("reason", "RECOVERY_ANALYSIS_BLOCKED", "taskIds", List.of("inspect")));
        execution.put("artifactDeliveryRequested", true);
        execution.put("lastFailureKind", FailureKind.EVAL_FAILED.name());

        Map<String, Object> after = RecoveryRequirement.afterReplan(execution);
        assertThat(after).doesNotContainKeys(RecoveryRequirement.KEY, PlanningLoop.EXECUTION_HUMAN_WAIT)
                .containsEntry("artifactDeliveryRequested", true)
                .containsEntry("lastFailureKind", FailureKind.EVAL_FAILED.name());
        assertThat(execution).containsKeys(RecoveryRequirement.KEY, PlanningLoop.EXECUTION_HUMAN_WAIT);
    }

    @Test
    void ordinaryLocalRepairDiagnosisIsNotPersistedAsARecoveryRequirement() {
        TaskNode node = node("read", TaskNodeStatus.FAILED, "参数无效");
        StateSnapshot source = snapshot("local-session", "local-execution", new TaskGraph(List.of(node)));
        FailureDiagnosis local = new FailureDiagnosis(
                FailureClass.LOCAL_REPAIR, FailureKind.PARAM_ERROR, node.id(), "read_file",
                "参数无效", "修正参数后重试");

        assertThat(RecoveryRequirement.remember(source, node, local)).isEqualTo(source);
        assertThat(source.execution()).doesNotContainKey(RecoveryRequirement.KEY);
    }

    private static StateSnapshot snapshot(String session, String execution, TaskGraph graph) {
        return snapshot(session, execution, graph, Map.of());
    }

    private static StateSnapshot snapshot(String session, String execution, TaskGraph graph,
                                         Map<String, Object> executionMetadata) {
        Goal goal = new Goal("rq-goal", "恢复失败节点", "", "research",
                Map.of(), List.of(), List.of());
        return new PlannerStateStore().init(session, execution, goal, graph,
                TaskIntent.fromGoal(goal)).withExecution(executionMetadata);
    }

    private static TaskNode node(String id, TaskNodeStatus status, String error) {
        return new TaskNode(id, "梳理源文件事实", "file_read", List.of(), List.of(),
                List.of("facts"), status, 90, DoneWhen.note(), "", "", 0, "")
                .withError(error);
    }
}
