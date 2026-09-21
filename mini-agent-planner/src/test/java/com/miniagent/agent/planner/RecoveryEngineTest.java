package com.miniagent.agent.planner;

import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryEngineTest {

    @Test
    void replaceToolBlocksFailedToolInsteadOfLockingAlternate() {
        PlannerStateStore store = new PlannerStateStore();
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of("web_search", "web_extract", "todo", "memory")) {
            registry.register(name, name, Map.of("type", "object"), x -> "ok");
        }
        RecoveryEngine engine = new RecoveryEngine(
                store, new CapabilityRegistry(registry), new PlannerProperties());
        TaskNode n = new TaskNode("n1", "获取资料原文", "web", List.of(),
                List.of(), List.of(), TaskNodeStatus.FAILED, 10,
                DoneWhen.note(), "", "timeout", 1, "");
        store.init("s", "e", null, new TaskGraph(List.of(n)));
        FailureDiagnosis dx = engine.diagnose(n, "web_search", "工具执行 timeout");
        assertEquals(FailureClass.REPLACE_TOOL, dx.failureClass());
        StateSnapshot snap = engine.recover("s", dx).orElseThrow();
        TaskNode recovered = snap.graph().byId("n1");
        assertEquals(List.of("web_search"), recovered.blockedTools());
        assertEquals(TaskNodeStatus.PENDING, recovered.status());
        assertTrue(recovered.retryCount() >= 2);
    }

    @Test
    void timeoutCodeMapsToTimeoutKind() {
        RecoveryEngine engine = new RecoveryEngine(
                new PlannerStateStore(), new CapabilityRegistry(new ToolRegistry()),
                new PlannerProperties());
        FailureDiagnosis dx = engine.diagnose(null, "web_search", "unrelated",
                ToolErrorCode.TIMEOUT);
        assertEquals(FailureKind.TIMEOUT, dx.kind());
        assertEquals(FailureClass.LOCAL_REPAIR, dx.failureClass());
    }

    @Test
    void rewriteGraphRetriesSameNode() {
        PlannerStateStore store = new PlannerStateStore();
        RecoveryEngine engine = new RecoveryEngine(
                store, new CapabilityRegistry(new ToolRegistry()),
                new PlannerProperties());
        TaskNode n = new TaskNode("n1", "获取资料原文", "web", List.of(),
                List.of(), List.of(), TaskNodeStatus.FAILED, 10,
                DoneWhen.note(), "", "eval: hollow", 2, "");
        store.init("s2", "e", null, new TaskGraph(List.of(n)));
        FailureDiagnosis dx = engine.diagnose(n, "web_search", "eval: hollow");
        assertEquals(FailureClass.REWRITE_GRAPH, dx.failureClass());
        StateSnapshot snap = engine.recover("s2", dx).orElseThrow();
        assertEquals(1, snap.graph().nodes().size());
        assertEquals("n1", snap.graph().nodes().get(0).id());
        assertEquals(TaskNodeStatus.PENDING, snap.graph().byId("n1").status());
    }

    @Test
    void acquireTimeoutHonorsRetryPolicy() {
        RecoveryEngine engine = new RecoveryEngine(
                new PlannerStateStore(), new CapabilityRegistry(new ToolRegistry()),
                new PlannerProperties());
        TaskNode n = new TaskNode("n1", "获取", "web", List.of(),
                List.of(), List.of(), TaskNodeStatus.FAILED, 10,
                DoneWhen.note(), "", "timeout", 1, "");
        FailureDiagnosis dx = engine.diagnose(n, "web_search", "unrelated",
                ToolErrorCode.TIMEOUT);
        assertEquals(FailureClass.LOCAL_REPAIR, dx.failureClass());
        TaskNode spent = n.withRetryInc();
        FailureDiagnosis late = engine.diagnose(spent, "web_search", "unrelated",
                ToolErrorCode.TIMEOUT);
        assertEquals(FailureClass.REWRITE_GRAPH, late.failureClass());
    }
}
