package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataflowNormalizerTest {

    @Test
    void wiresDependsOnWhenPortsMissing() {
        TaskNode n1 = new TaskNode("n1", "获取资料", "web", List.of(),
                List.of(), List.of(), TaskNodeStatus.PENDING, 10,
                DoneWhen.note(), "", "", 0, "");
        TaskNode n2 = new TaskNode("n2", "写入 notes.md", "file_write", List.of("n1"),
                List.of(), List.of(), TaskNodeStatus.PENDING, 9,
                DoneWhen.file("notes.md"), "", "", 0, "");
        TaskGraph g = DataflowNormalizer.wire(new TaskGraph(List.of(n1, n2)));
        assertEquals(List.of("n1_out"), g.byId("n1").outputs());
        assertEquals(List.of("n1_out"), g.byId("n2").inputs());
        assertEquals(List.of("notes.md"), g.byId("n2").outputs());
    }

    @Test
    void keepsExplicitPorts() {
        TaskNode n1 = new TaskNode("n1", "获取", "web", List.of(),
                List.of(), List.of("source_text"), TaskNodeStatus.PENDING, 10,
                DoneWhen.note(), "", "", 0, "");
        TaskNode n2 = new TaskNode("n2", "写入", "file_write", List.of("n1"),
                List.of("source_text"), List.of("notes_md"), TaskNodeStatus.PENDING, 9,
                DoneWhen.file("notes.md"), "", "", 0, "");
        TaskGraph g = DataflowNormalizer.wire(new TaskGraph(List.of(n1, n2)));
        assertEquals(List.of("source_text"), g.byId("n1").outputs());
        assertEquals(List.of("source_text"), g.byId("n2").inputs());
        assertEquals(List.of("notes_md"), g.byId("n2").outputs());
    }

    @Test
    void successorSeesPredecessorEvidence() {
        TaskNode n1 = new TaskNode("n1", "获取资料", "web", List.of(),
                List.of(), List.of(), TaskNodeStatus.SUCCESS, 10,
                DoneWhen.note(), "", "", 0, "");
        TaskNode n2 = new TaskNode("n2", "写入", "file_write", List.of("n1"),
                List.of(), List.of(), TaskNodeStatus.READY, 9,
                DoneWhen.file("notes.md"), "", "", 0, "");
        TaskGraph g = DataflowNormalizer.wire(new TaskGraph(List.of(n1, n2)));
        g = g.replace(g.byId("n1").withOutput("OpenAI API 文档正文若干字"));
        ActionProposal p = new GraphScheduler().propose(
                new StateSnapshot(1, "s", "e", null, g, Map.of(), Map.of(), List.of(), 0),
                List.of(g.byId("n2")), 1);
        String prior = PlanningLoop.predecessorOutputs(g, p);
        assertTrue(prior.contains("OpenAI API 文档正文若干字"));
        assertTrue(prior.contains("n1_out"));
        assertEquals("s:n2", p.actions().get(0).concurrencyKey());
        assertEquals(1, p.actions().get(0).retryPolicy().maxAttempts());
        assertEquals("write_file", p.actions().get(0).tool());
        assertTrue(ActionBinder.canDirect(p));
    }

    @Test
    void parseGraphWiresMissingPorts() throws Exception {
        GoalCompiler compiler = new GoalCompiler(new PlannerProperties());
        TaskGraph g = compiler.parseGraph("""
                {"nodes":[
                  {"id":"n1","name":"获取","capability":"web","dependsOn":[],
                   "doneWhen":{"type":"note_required"}},
                  {"id":"n2","name":"保存","capability":"file_write","dependsOn":["n1"],
                   "doneWhen":{"type":"file_exists","path":"notes.md"}}
                ]}
                """);
        assertEquals(List.of("n1_out"), g.byId("n1").outputs());
        assertEquals(List.of("n1_out"), g.byId("n2").inputs());
        TaskPlan plan = TaskPlan.of("查文档保存", true, TaskSignals.parse("web,file"));
        assertTrue(new PlanValidator().validate(g, plan).valid());
    }

    @Test
    void webOutputsAreNotFileDelivery() {
        TaskNode n = new TaskNode("n1", "获取资料", "web", List.of(),
                List.of(), List.of("n1_out"), TaskNodeStatus.READY, 10,
                DoneWhen.note(), "", "", 0, "");
        assertFalse(StepEvaluator.looksLikeFileDelivery(n));
        assertTrue(StepEvaluator.looksLikeAcquire(n));
    }

    @Test
    void coercesFileWriteNoteWhenNameHasPath() {
        TaskNode n = new TaskNode("n1", "写入 hello.txt", "file_write", List.of(),
                List.of(), List.of(), TaskNodeStatus.PENDING, 10,
                DoneWhen.note(), "", "", 0, "");
        TaskGraph g = DataflowNormalizer.normalize(new TaskGraph(List.of(n)));
        assertTrue(g.byId("n1").doneWhen().isFile());
        assertEquals("hello.txt", g.byId("n1").doneWhen().path());
    }

    @Test
    void infersFileWriteFromFileDoneWhen() {
        TaskNode n = new TaskNode("n1", "获取资料原文", "general", List.of(),
                List.of(), List.of(), TaskNodeStatus.PENDING, 10,
                DoneWhen.file("notes.md"), "", "", 0, "");
        TaskGraph g = DataflowNormalizer.normalize(new TaskGraph(List.of(n)));
        assertEquals("file_write", g.byId("n1").capability());
    }

    @Test
    void infersFileWriteFromFilenameInName() {
        TaskNode n = new TaskNode("n1", "写入 hello.txt", "general", List.of(),
                List.of(), List.of(), TaskNodeStatus.PENDING, 10,
                DoneWhen.note(), "", "", 0, "");
        TaskGraph g = DataflowNormalizer.normalize(new TaskGraph(List.of(n)));
        assertEquals("file_write", g.byId("n1").capability());
        assertTrue(g.byId("n1").doneWhen().isFile());
        assertEquals("hello.txt", g.byId("n1").doneWhen().path());
    }

    @Test
    void leavesGeneralWhenNoStructuredHint() {
        TaskNode n = new TaskNode("n1", "获取资料原文", "general", List.of(),
                List.of(), List.of(), TaskNodeStatus.PENDING, 10,
                DoneWhen.note(), "", "", 0, "");
        TaskGraph g = DataflowNormalizer.normalize(new TaskGraph(List.of(n)));
        assertEquals("general", g.byId("n1").capability());
    }
}
