package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    @Test
    void fetchAndSaveMustSplit() {
        TaskPlan plan = structured("查 OpenAI API 写入 api.md");
        TaskGraph g = new GoalCompiler(new PlannerProperties())
                .templateGraph("查 OpenAI API 写入 api.md",
                        TaskPlan.of("查 OpenAI API 写入 api.md", true,
                                TaskSignals.parse("web,file")));
        assertEquals(2, g.nodes().size());
        assertTrue(validator.validate(g, plan).valid());
    }

    @Test
    void readAndAnalyzeMustSplit() {
        String msg = "分析 test.xlsx 各月数据";
        TaskPlan plan = TaskPlan.of(msg, true, TaskSignals.parse("file,read"));
        TaskGraph g = new GoalCompiler(new PlannerProperties()).templateGraph(msg, plan);
        assertEquals(2, g.nodes().size());
        assertTrue(validator.validate(g, plan).valid());
    }

    @Test
    void parsePdfStaysAtomic() {
        String msg = "读取 test.pdf";
        TaskGraph g = new GoalCompiler(new PlannerProperties()).templateGraph(msg,
                TaskPlan.of(msg, false, TaskSignals.parse("file,read")));
        assertEquals(1, g.nodes().size());
    }

    @Test
    void rejectsSingleCompoundNode() {
        TaskPlan plan = structured("帮我查一下 OpenAI 最新 API 文档，然后保存成 Markdown");
        TaskGraph g = new TaskGraph(List.of(
                node("n1", "完成 OpenAI API 文档整理并保存", "general")));
        PlanValidationReport report = validator.validate(g, plan);
        assertFalse(report.valid());
        assertEquals(1, report.countByType(
                PlanValidationReport.ValidationError.Type.CAPABILITY_MISMATCH));
        assertEquals(0, report.countByType(
                PlanValidationReport.ValidationError.Type.UNDER_DECOMPOSED));
    }

    @Test
    void acceptsSplitFetchThenWrite() {
        TaskPlan plan = structured("查 OpenAI API 文档保存成 Markdown");
        TaskGraph g = DataflowNormalizer.normalize(new TaskGraph(List.of(
                node("n1", "获取官方文档", "web"),
                node("n2", "写入 notes.md", "file_write", List.of("n1")))));
        assertTrue(validator.validate(g, plan).valid());
    }

    @Test
    void acceptsSimpleWrite() {
        TaskPlan plan = structured("写一个 hello.txt");
        TaskGraph g = DataflowNormalizer.normalize(new TaskGraph(List.of(
                node("n1", "写入 hello.txt", "file_write"))));
        assertTrue(validator.validate(g, plan).valid());
        assertTrue(g.byId("n1").doneWhen().isFile());
    }

    @Test
    void rejectsFileWriteWithNote() {
        TaskPlan plan = structured("整理一份文档");
        TaskGraph g = new TaskGraph(List.of(
                node("n1", "整理文档", "file_write")));
        PlanValidationReport report = validator.validate(g, plan);
        assertFalse(report.valid());
        assertEquals(1, report.countByType(
                PlanValidationReport.ValidationError.Type.INVALID_DONE_WHEN));
    }

    @Test
    void rejectsUnknownCapability() {
        TaskPlan plan = structured("做点什么");
        TaskGraph g = new TaskGraph(List.of(
                node("n1", "做点什么", "teleport")));
        PlanValidationReport report = validator.validate(g, plan);
        assertFalse(report.valid());
        assertEquals(1, report.countByType(
                PlanValidationReport.ValidationError.Type.CAPABILITY_MISMATCH));
    }

    @Test
    void rejectsGeneralOnStructured() {
        TaskPlan plan = structured("做点什么");
        TaskGraph g = new TaskGraph(List.of(
                node("n1", "做点什么", "general")));
        PlanValidationReport report = validator.validate(g, plan);
        assertFalse(report.valid());
        assertEquals(1, report.countByType(
                PlanValidationReport.ValidationError.Type.CAPABILITY_MISMATCH));
    }

    @Test
    void rejectsBrowserWithFileExists() {
        TaskPlan plan = structured("打开页面抽取全部章节");
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("n1", "打开页面抽取全部章节", "browser",
                        List.of(), List.of(), List.of(),
                        TaskNodeStatus.PENDING, 10,
                        DoneWhen.file("_source.md"), "", "", 0, "")));
        PlanValidationReport report = validator.validate(g, plan);
        assertFalse(report.valid());
        assertEquals(1, report.countByType(
                PlanValidationReport.ValidationError.Type.UNDER_DECOMPOSED));
    }

    @Test
    void fetchWriteTemplateValidates() {
        TaskPlan plan = structured("https://example.com 写入学习资料");
        TaskGraph g = DataflowNormalizer.normalize(GoalCompiler.fetchWriteTemplate());
        assertTrue(validator.validate(g, plan).valid());
    }

    @Test
    void doesNotSplitOnNodeNameKeywords() {
        TaskPlan plan = structured("获取资料原文");
        TaskGraph g = new TaskGraph(List.of(
                node("n1", "搜索并保存到本地", "web")));
        assertTrue(validator.validate(g, plan).valid());
    }

    @Test
    void rejectsDisconnectedAcquireAndPersist() {
        TaskPlan plan = structured("查文档并保存");
        TaskGraph g = DataflowNormalizer.normalize(new TaskGraph(List.of(
                node("n1", "获取官方文档", "web"),
                node("n2", "写入 notes.md", "file_write"))));
        PlanValidationReport report = validator.validate(g, plan);
        assertFalse(report.valid());
        assertEquals(1, report.countByType(
                PlanValidationReport.ValidationError.Type.UNDER_DECOMPOSED));
    }

    @Test
    void rejectsGoalFileMissingFromGraph() {
        TaskPlan plan = structured("写报告");
        Goal goal = new Goal("g1", "写报告", TaskSignals.parse("file").describe(),
                Map.of(), List.of(), List.of("report.md"));
        TaskGraph g = new TaskGraph(List.of(
                node("n1", "获取资料原文", "web")));
        PlanValidationReport report = validator.validate(g, plan, goal);
        assertFalse(report.valid());
        assertEquals(1, report.countByType(
                PlanValidationReport.ValidationError.Type.UNDER_DECOMPOSED));
    }

    @Test
    void rejectsAcquireWithMedia() {
        TaskPlan plan = structured("打开页面出图");
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("n1", "打开页面出图", "browser",
                        List.of(), List.of(), List.of(),
                        TaskNodeStatus.PENDING, 10,
                        DoneWhen.media(), "", "", 0, "")));
        PlanValidationReport report = validator.validate(g, plan);
        assertFalse(report.valid());
        assertEquals(1, report.countByType(
                PlanValidationReport.ValidationError.Type.UNDER_DECOMPOSED));
    }

    private static TaskPlan structured(String goal) {
        return TaskPlan.of(goal, true, TaskSignals.NONE);
    }

    private static TaskNode node(String id, String name, String cap) {
        return node(id, name, cap, List.of());
    }

    private static TaskNode node(String id, String name, String cap, List<String> deps) {
        return new TaskNode(id, name, cap, deps, List.of(), List.of(),
                TaskNodeStatus.PENDING, 10, DoneWhen.note(), "", "", 0, "");
    }
}
