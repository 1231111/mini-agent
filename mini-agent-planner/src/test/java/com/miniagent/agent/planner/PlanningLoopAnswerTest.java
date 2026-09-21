package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import com.miniagent.agent.tool.ToolErrorCode;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanningLoopAnswerTest {

    @Test
    void stubDoesNotReplaceRealAnswer() {
        String kept = PlanningLoop.keepBetterAnswer(
                "文件路径：workspace/qa.md", AgentLoop.STEP_SEGMENT_DONE);
        assertEquals("文件路径：workspace/qa.md", kept);
    }

    @Test
    void realAnswerReplacesPrevious() {
        String kept = PlanningLoop.keepBetterAnswer("旧", "OpenJDK");
        assertEquals("OpenJDK", kept);
    }

    @Test
    void lastInvokedToolReadsToolResult() {
        assertEquals("web_search", PlanningLoop.lastInvokedTool(List.of(
                ToolExecutionResultMessage.from("1", "web_search", "ok"))));
        assertEquals("web_search",
                PlanningLoop.diagnoseTool(null, "web_search"));
    }

    @Test
    void lastInvokedToolPrefersOutcomeList() {
        AgentLoop.LoopOutcome out = new AgentLoop.LoopOutcome(
                "ok", "",
                List.of(ToolExecutionResultMessage.from("1", "web_extract", "ok")),
                List.of("web_search"),
                ToolErrorCode.NONE);
        assertEquals("web_search", PlanningLoop.lastInvokedTool(out));
    }

    @Test
    void acceptFailPrefersFileNode() {
        TaskNode web = new TaskNode("n1", "获取", "web", List.of(),
                List.of(), List.of(), TaskNodeStatus.SUCCESS, 10,
                DoneWhen.note(), "", "", 0, "evidence-text-enough");
        TaskNode file = new TaskNode("n2", "写入", "file_write", List.of("n1"),
                List.of(), List.of(), TaskNodeStatus.SUCCESS, 9,
                DoneWhen.file("notes.md"), "", "", 0, "ok");
        TaskGraph g = new TaskGraph(List.of(web, file));
        assertEquals("n2", PlanningLoop.resolveAcceptFailNode(g, "").id());
        assertEquals("n1", PlanningLoop.resolveAcceptFailNode(g, "n1").id());
    }

    @Test
    void clarifyReplyRecompiles() {
        Goal goal = new Goal("g1", "做点什么", TaskSignals.NONE.describe(),
                Goal.TASK_TYPE_CLARIFY, Map.of(), List.of(), List.of());
        TaskGraph graph = GoalCompiler.clarifyGraph();
        assertTrue(PlanningLoop.shouldRecompileClarify(goal, graph, "写一个 hello.txt"));
        assertFalse(PlanningLoop.shouldRecompileClarify(goal, graph, "继续"));
        assertEquals("做点什么\n写一个 hello.txt",
                PlanningLoop.mergeClarifyObjective("做点什么", "写一个 hello.txt"));
    }

    /**
     * 要不要编任务图，看的是「消息里有没有文件名或 URL」这个事实，
     * 不是「这轮属于哪一类」。只有联网需求、没有落盘路径时不该编图 ——
     * 那种一步就能做完。
     */
    @Test
    void pathOrUrlDrivesGraphNotWebAlone() {
        String msg = "写一个 hello.txt";
        assertTrue(DecompositionPolicy.hasGraphSignal(msg,
                TaskPlan.of(msg, false, TaskSignals.parse("file,simpleFile"))));

        String news = "搜索今日新闻";
        TaskPlan webOnly = TaskPlan.of(news, false, TaskSignals.parse("web"));
        assertFalse(DecompositionPolicy.hasGraphSignal(news, webOnly));
        assertFalse(webOnly.requiresStructuredPlan());
    }
}
