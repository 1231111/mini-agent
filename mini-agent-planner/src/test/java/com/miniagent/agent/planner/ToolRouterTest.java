package com.miniagent.agent.planner;

import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRouterTest {

    private ToolRouter router;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of(
                "web_search", "write_file", "read_file", "browser_navigate",
                "todo", "memory", "exec_command")) {
            registry.register(name, name, Map.of("type", "object"), x -> "ok");
        }
        router = new ToolRouter(new CapabilityRegistry(registry));
    }

    @Test
    void webNodeWithOutputsDoesNotGetWrite() {
        TaskNode n = node("n1", "获取资料原文", "web",
                List.of("source_text"), DoneWhen.note());
        List<String> allowed = allowed(n, true);
        assertTrue(allowed.contains("web_search"));
        assertFalse(allowed.contains("write_file"));
    }

    @Test
    void fileWriteNodeGetsWrite() {
        TaskNode n = node("n2", "写入 notes.md", "file_write",
                List.of("notes_md"), DoneWhen.file("notes.md"));
        List<String> allowed = allowed(n, true);
        assertTrue(allowed.contains("write_file"));
    }

    @Test
    void blockedToolIsExcludedOnRetry() {
        TaskNode n = node("n1", "获取资料原文", "web",
                List.of("source_text"), DoneWhen.note())
                .withBlockedTool("web_search");
        List<String> allowed = allowed(n, true);
        assertFalse(allowed.contains("web_search"));
        assertTrue(allowed.contains("browser_navigate")
                || allowed.contains("todo"));
    }

    @Test
    void browserFileExistsDoesNotGetWrite() {
        TaskNode n = node("n1", "打开页面抽取全部章节", "browser",
                List.of("source_text"), DoneWhen.file("_source.md"));
        List<String> allowed = allowed(n, true);
        assertTrue(allowed.contains("browser_navigate"));
        assertFalse(allowed.contains("write_file"));
    }

    private List<String> allowed(TaskNode n, boolean hard) {
        TaskGraph g = new TaskGraph(List.of(n.withStatus(TaskNodeStatus.READY)));
        TaskNode ready = g.nodes().get(0);
        ActionProposal p = new GraphScheduler().propose(
                new StateSnapshot(1, "s", "e", null, g, Map.of(), Map.of(), List.of(), 0),
                List.of(ready), 1);
        return router.allowedFor(p, g, hard);
    }

    private static TaskNode node(String id, String name, String cap,
                                 List<String> outputs, DoneWhen done) {
        return new TaskNode(id, name, cap, List.of(), List.of(), outputs,
                TaskNodeStatus.PENDING, 10, done, "", "", 0, "");
    }
}
