package com.miniagent.agent.planner;

import com.miniagent.agent.tool.CapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionBinderTest {

    @Test
    void writeWithPredecessorLocksWriteFile() {
        TaskNode n1 = new TaskNode("n1", "获取资料", "web", List.of(),
                List.of(), List.of(), TaskNodeStatus.SUCCESS, 10,
                DoneWhen.note(), "", "", 0, "");
        TaskNode n2 = new TaskNode("n2", "写入", "file_write", List.of("n1"),
                List.of(), List.of(), TaskNodeStatus.READY, 9,
                DoneWhen.file("notes.md"), "", "", 0, "");
        TaskGraph g = DataflowNormalizer.wire(new TaskGraph(List.of(n1, n2)));
        g = g.replace(g.byId("n1").withOutput("OpenAI API 文档正文若干字"));
        ActionProposal p = propose(g, "n2");
        ActionSpec a = p.actions().get(0);
        assertEquals(CapabilityRegistry.WRITE_FILE, a.tool());
        assertEquals("notes.md", a.arguments().get(ActionBinder.ARG_PATH));
        assertTrue(String.valueOf(a.arguments().get(ActionBinder.ARG_CONTENT))
                .contains("OpenAI API"));
        assertTrue(ActionBinder.canDirect(p));
    }

    @Test
    void writeWithoutContentKeepsCapability() {
        TaskNode n = new TaskNode("n1", "写入 notes.md", "file_write", List.of(),
                List.of(), List.of(), TaskNodeStatus.READY, 10,
                DoneWhen.file("notes.md"), "", "", 0, "");
        ActionProposal p = propose(new TaskGraph(List.of(n)), "n1");
        ActionSpec a = p.actions().get(0);
        assertEquals("file_write", a.tool());
        assertEquals("notes.md", a.arguments().get(ActionBinder.ARG_PATH));
        assertFalse(ActionBinder.canDirect(p));
    }

    @Test
    void readFileLocksReadFile() {
        TaskNode n = new TaskNode("n1", "读取 sales.xlsx", "file_read", List.of(),
                List.of(), List.of(), TaskNodeStatus.READY, 10,
                DoneWhen.note(), "", "", 0, "");
        ActionProposal p = propose(new TaskGraph(List.of(n)), "n1");
        ActionSpec a = p.actions().get(0);
        assertEquals(CapabilityRegistry.READ_FILE, a.tool());
        assertEquals("sales.xlsx", a.arguments().get(ActionBinder.ARG_PATH));
        assertTrue(ActionBinder.canDirect(p));
    }

    @Test
    void browserStaysOnCapability() {
        TaskNode n = new TaskNode("n1", "打开页面", "browser", List.of(),
                List.of(), List.of(), TaskNodeStatus.READY, 10,
                DoneWhen.note(), "", "", 0, "")
                .withToolArguments(Map.of(ActionBinder.ARG_URL, "https://ex.com"));
        ActionProposal p = propose(new TaskGraph(List.of(n)), "n1");
        ActionSpec a = p.actions().get(0);
        assertEquals("browser", a.tool());
        assertEquals("https://ex.com", a.arguments().get(ActionBinder.ARG_URL));
        assertFalse(ActionBinder.canDirect(p));
    }

    private static ActionProposal propose(TaskGraph g, String id) {
        TaskNode ready = g.byId(id).withStatus(TaskNodeStatus.READY);
        TaskGraph graph = g.replace(ready);
        return new GraphScheduler().propose(
                new StateSnapshot(1, "s", "e", null, graph,
                        Map.of(), Map.of(), List.of(), 0),
                List.of(graph.byId(id)), 1);
    }
}
