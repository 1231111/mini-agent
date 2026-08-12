package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryEngineDiagnoseTest {

    @Test
    void classifiesByFailureKind() {
        ToolCapabilityIndex index = new ToolCapabilityIndex(new com.miniagent.agent.tool.ToolRegistry());
        RecoveryEngine engine = new RecoveryEngine(
                new PlannerStateStore(), index, new PlannerProperties());
        TaskNode node = new TaskNode("n1", "write", "file_write", List.of(),
                TaskNodeStatus.FAILED, 1, "note_required", "write_file", "", 0);
        FailureDiagnosis d1 = engine.diagnose(node, "write_file", "missing required argument path");
        assertEquals(FailureKind.PARAM_ERROR, d1.kind());
        assertEquals(FailureClass.LOCAL_REPAIR, d1.failureClass());

        FailureDiagnosis d2 = engine.diagnose(node, "write_file", "未知工具: foo");
        assertEquals(FailureKind.UNKNOWN_TOOL, d2.kind());
        assertEquals(FailureClass.REPLACE_TOOL, d2.failureClass());

        FailureDiagnosis d3 = engine.diagnose(node, "write_file", "drift:偏离子目标");
        assertEquals(FailureKind.DRIFT, d3.kind());
        assertEquals(FailureClass.LOCAL_REPAIR, d3.failureClass());

        TaskNode retried = node.withRetryInc().withRetryInc();
        FailureDiagnosis d4 = engine.diagnose(retried, "write_file", "still failing tool error");
        assertEquals(FailureClass.REWRITE_GRAPH, d4.failureClass());
    }

    @Test
    void recoverIncrementsVersion() {
        PlannerStateStore store = new PlannerStateStore();
        ToolCapabilityIndex index = new ToolCapabilityIndex(new com.miniagent.agent.tool.ToolRegistry());
        index.rebuild();
        RecoveryEngine engine = new RecoveryEngine(store, index, new PlannerProperties());
        Goal goal = new Goal("g", "o", "NEW_TASK", Map.of(), List.of(), List.of());
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("n1", "x", "file_write", List.of(), TaskNodeStatus.FAILED,
                        1, "note_required", "write_file", "err", 0)));
        store.init("s", "e", goal, graph);
        FailureDiagnosis dx = engine.diagnose(graph.byId("n1"), "write_file", "未知工具: x");
        StateSnapshot next = engine.recover("s", dx).orElseThrow();
        assertEquals(2L, next.version());
        assertEquals(1, next.recoveryCount());
        assertEquals(TaskNodeStatus.PENDING, next.graph().byId("n1").status());
        assertEquals(1, engine.classCount(next, FailureClass.REPLACE_TOOL));
    }

    @Test
    void classFuseBlocksFurtherRecovery() {
        PlannerStateStore store = new PlannerStateStore();
        ToolCapabilityIndex index = new ToolCapabilityIndex(new com.miniagent.agent.tool.ToolRegistry());
        index.rebuild();
        PlannerProperties props = new PlannerProperties();
        props.setMaxReplaceTool(1);
        props.setMaxRecoveries(10);
        RecoveryEngine engine = new RecoveryEngine(store, index, props);
        Goal goal = new Goal("g", "o", "NEW_TASK", Map.of(), List.of(), List.of());
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("n1", "x", "file_write", List.of(), TaskNodeStatus.FAILED,
                        1, "note_required", "write_file", "err", 0)));
        store.init("s2", "e", goal, graph);
        FailureDiagnosis dx = engine.diagnose(graph.byId("n1"), "write_file", "未知工具: x");
        assertTrue(engine.recover("s2", dx).isPresent());
        StateSnapshot after = store.get("s2").orElseThrow();
        TaskGraph g2 = after.graph().replace(
                after.graph().byId("n1").withStatus(TaskNodeStatus.FAILED));
        store.commit("s2", after.version(), after.withGraph(g2));
        assertTrue(engine.recover("s2", dx).isEmpty());
    }
}
