package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

class GoalCompilerGranularityTest {

    private final GoalCompiler compiler = new GoalCompiler(new PlannerProperties());

    @Test
    void researchThenFileIsTwoNodes() {
        String msg = "帮我查一下 OpenAI 最新 API 文档，整理成 api.md";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.parse("web,file"));
        assertTrue(DecompositionPolicy.researchThenFile(msg, plan));
        TaskGraph g = compiler.templateGraph(msg, plan);
        assertEquals(2, g.nodes().size());
        assertEquals("web", g.nodes().get(0).capability());
        assertEquals("file_write", g.nodes().get(1).capability());
        assertEquals("api.md", g.nodes().get(1).doneWhen().path());
    }

    @Test
    void readThenAnalyzeIsTwoNodes() {
        String msg = "分析 test.xlsx 各月数据";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.parse("file,read"));
        assertTrue(DecompositionPolicy.readThenAnalyze(msg, plan));
        TaskGraph g = compiler.templateGraph(msg, plan);
        assertEquals(2, g.nodes().size());
        assertEquals("file_read", g.nodes().get(0).capability());
        assertEquals("code", g.nodes().get(1).capability());
        assertEquals("test.xlsx",
                g.nodes().get(0).toolArguments().get(ActionBinder.ARG_PATH));
    }

    /**
     * 「生成 sales.xlsx」也要落盘、也有 xlsx 路径，但它是写不是读。
     * 光看路径会把它拆成「读一个还不存在的文件 + 统计」，第一步必然失败。
     */
    @Test
    void fileDeliveryXlsxIsWriteNotAnalyze() {
        String msg = "生成 sales.xlsx";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.parse("file"));
        assertFalse(DecompositionPolicy.readThenAnalyze(msg, plan));
        TaskGraph g = compiler.templateGraph(msg, plan);
        assertEquals(1, g.nodes().size());
        assertEquals("file_write", g.nodes().get(0).capability());
    }

    /** 同一句话，只是词表命中了读类动词，就该拆成两段。 */
    @Test
    void sameXlsxWithReadVerbSplits() {
        String msg = "统计 sales.xlsx 各月销售额";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.parse("file,read"));
        assertTrue(DecompositionPolicy.readThenAnalyze(msg, plan));
        assertEquals(2, compiler.templateGraph(msg, plan).nodes().size());
    }

    @Test
    void pdfReadIsNotSplit() {
        String msg = "读取 test.pdf";
        TaskPlan plan = TaskPlan.of(msg, false, TaskSignals.parse("file,read"));
        assertFalse(DecompositionPolicy.researchThenFile(msg, plan));
        assertFalse(DecompositionPolicy.readThenAnalyze(msg, plan));
        TaskGraph g = compiler.templateGraph(msg, plan);
        assertEquals(1, g.nodes().size());
    }

    @Test
    void inferFromPlanReadsSignalsNotName() {
        TaskPlan research = TaskPlan.of("随便写点什么", true, TaskSignals.parse("web"));
        assertEquals("research", GoalCompiler.inferFromPlan(research));
        TaskPlan neu = TaskPlan.of("搜索并保存到本地", true, TaskSignals.NONE);
        assertEquals("general", GoalCompiler.inferFromPlan(neu));
    }

    @Test
    void fallbackHelloTxtIsSchedulable() {
        String msg = "写一个 hello.txt";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.parse("file,simpleFile"));
        GoalCompiler.CompileResult r = compiler.fallback(null, msg, plan);
        assertEquals("file_write", r.graph().byId("n1").capability());
        assertTrue(r.graph().byId("n1").doneWhen().isFile());
        assertEquals(List.of("hello.txt"), r.goal().successCriteria());
        assertTrue(new PlanValidator().validate(r.graph(), plan, r.goal()).valid());
    }

    @Test
    void fallbackVagueGoalClarifies() {
        String msg = "做点什么";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.NONE);
        GoalCompiler.CompileResult r = compiler.fallback(null, msg, plan);
        assertTrue(r.goal().isClarify());
        assertEquals(TaskNodeStatus.AWAITING_CONFIRM, r.graph().byId("n1").status());
        assertEquals("plan", r.graph().byId("n1").capability());
        assertTrue(r.goal().successCriteria().isEmpty());
        assertTrue(new PlanValidator().validate(r.graph(), plan, r.goal()).valid());
    }

    @Test
    void fileDeliveryWithoutPathClarifies() {
        String msg = "写一份报告";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.parse("file"));
        GoalCompiler.CompileResult r = compiler.fallback(null, msg, plan);
        assertTrue(r.goal().isClarify());
        assertTrue(new PlanValidator().validate(r.graph(), plan, r.goal()).valid());
    }

    @Test
    void newTaskUrlIsWebNode() {
        String msg = "看看 https://example.com 说了什么";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.NONE);
        TaskGraph g = compiler.templateGraph(msg, plan);
        assertEquals(1, g.nodes().size());
        assertEquals("web", g.nodes().get(0).capability());
        assertEquals("https://example.com",
                g.nodes().get(0).toolArguments().get(ActionBinder.ARG_URL));
        assertTrue(new PlanValidator().validate(g, plan).valid());
    }

    @Test
    void fetchWriteBindsNamedPath() {
        String msg = "打开 https://example.com 写入 guide.md";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.parse("web,file"));
        assertTrue(DecompositionPolicy.fetchWrite(msg, plan));
        TaskGraph g = compiler.templateGraph(msg, plan);
        assertEquals("guide.md", g.byId("n2").doneWhen().path());
        assertEquals("https://example.com",
                g.byId("n1").toolArguments().get(ActionBinder.ARG_URL));
    }

    @Test
    void diagramBindsPngName() {
        String msg = "画出 flow.png";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.parse("file"));
        TaskGraph g = compiler.templateGraph(msg, plan);
        assertEquals(2, g.nodes().size());
        assertEquals("flow.mmd", g.byId("n1").doneWhen().path());
        assertEquals("flow.png", g.byId("n2").doneWhen().path());
    }

    @Test
    void compileSimpleFileDoesNotCallLlm() {
        ChatModel chat = mock(ChatModel.class);
        String msg = "写一个 hello.txt";
        TaskPlan plan = TaskPlan.of(msg, false, TaskSignals.parse("file,simpleFile"));
        GoalCompiler.CompileResult r = compiler.compile(chat, msg, plan);
        assertTrue(r.fromTemplate());
        assertEquals("file_write", r.graph().byId("n1").capability());
        verifyNoInteractions(chat);
    }

    @Test
    void compileResearchWithoutFileCallsLlm() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("no"));
        String msg = "调研一下竞品方案";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.parse("web"));
        assertFalse(GoalCompiler.structureDeterminate(
                compiler.templateGraph(msg, plan), msg, plan));
        GoalCompiler.CompileResult r = compiler.compile(chat, msg, plan);
        verify(chat, atLeastOnce()).chat(any(ChatRequest.class));
        assertEquals("research", r.graph().byId("n1").capability());
    }

    @Test
    void parseDropsPlaceholderCriterion() throws Exception {
        Goal seed = new Goal("g1", "写一个 hello.txt", TaskSignals.NONE.describe(),
                Map.of(), List.of(), List.of("完成 objective 且可验收"));
        GoalCompiler.ParsedCompilation p = compiler.parseCompilation("""
                {"successCriteria":["完成 objective 且可验收"],
                 "nodes":[{"id":"n1","name":"写入 hello.txt",
                   "capability":"file_write",
                   "doneWhen":{"type":"file_exists","path":"hello.txt"}}]}
                """, seed);
        assertEquals(List.of("hello.txt"), p.goal().successCriteria());
    }

    @Test
    void checkableCriteriaKeepsMatchingJudge() {
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("n2", "统计", "code", List.of(), List.of(), List.of(),
                        TaskNodeStatus.PENDING, 9,
                        DoneWhen.judge("统计覆盖各月销售额"), "", "", 0, "")));
        assertEquals(List.of("统计覆盖各月销售额"),
                GoalCompiler.checkableCriteria(
                        List.of("统计覆盖各月销售额", "文档要写全"), "统计", g));
    }

    @Test
    void fetchWriteSplitsBrowserThenFile() {
        TaskGraph g = GoalCompiler.fetchWriteTemplate();
        assertEquals(3, g.nodes().size());
        assertEquals("browser", g.nodes().get(0).capability());
        assertFalse(g.nodes().get(0).doneWhen().isFile());
        assertEquals("file_write", g.nodes().get(1).capability());
        assertTrue(g.nodes().get(1).doneWhen().isFile());
    }
}
