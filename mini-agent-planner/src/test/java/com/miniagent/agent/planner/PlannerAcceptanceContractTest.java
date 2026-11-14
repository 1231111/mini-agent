package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.todo.LlmJudgeTodoValidator;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlannerAcceptanceContractTest {
    @TempDir Path temporary;

    @BeforeEach void bindAuthorizedWorkspace() { WorkspaceContext.setRootOverride(temporary); }
    @AfterEach void clearAuthorizedWorkspace() { WorkspaceContext.clearRootOverride(); }

    @Test
    void oldDoneWhenJsonAndConstructorKeepTheirStructuralMeaning() throws Exception {
        DoneWhen restored = PlannerStateJson.MAPPER.readValue(
                "{\"type\":\"file_exists\",\"path\":\"report.md\",\"criteria\":\"\"}", DoneWhen.class);
        assertEquals(DoneWhen.file("report.md"), restored);
        assertTrue(restored.checks().isEmpty());
        assertTrue(restored.valid());
    }

    @Test
    void checksSurviveSnapshotRoundTripAndPathNormalization() throws Exception {
        DoneWhen original = DoneWhen.file("/tmp/page.html").withChecks(List.of("包含销售趋势", "图表可交互"));
        DoneWhen restored = PlannerStateJson.MAPPER.readValue(
                PlannerStateJson.MAPPER.writeValueAsString(original), DoneWhen.class);
        assertEquals("page.html", restored.path());
        assertEquals(original.checks(), restored.checks());
        assertTrue(restored.verifies("图表可交互"));
        assertFalse(restored.verifies("图表"));
        assertFalse(PlannerStateJson.MAPPER.readValue(
                "{\"type\":\"note_required\",\"checks\":\"ignored requirement\"}", DoneWhen.class).valid());
    }

    @Test
    void automaticStructuralAcceptanceRepairCannotDropDeclaredSemanticConditions() {
        DoneWhen note = new DoneWhen(DoneWhen.NOTE, "必须覆盖三个月", "", List.of("排除退款"));
        TaskNode normalized = DataflowNormalizer.normalize(graph(
                node("生成 report.md", "file_write", note))).byId("n1");
        assertTrue(normalized.doneWhen().isFile());
        assertEquals(List.of("必须覆盖三个月", "排除退款"), normalized.doneWhen().semanticChecks());
        ActionProposal proposal = new GraphScheduler().propose(new StateSnapshot(
                1L, "session", "execution", new Goal("g", "统计", "", Map.of(), List.of(), List.of()),
                graph(normalized), Map.of(), Map.of(), List.of(), 0), List.of(normalized), 1);
        assertTrue(proposal.actions().getFirst().expectedResult().contains("排除退款"));
    }

    @Test
    void constraintMentionInNameOrArgumentsDoesNotReplaceExecutableAcceptance() {
        String requirement = "排除退款订单";
        TaskNode merelyMentioned = node("统计并" + requirement, "code", DoneWhen.note())
                .withToolArguments(Map.of("constraint", requirement));
        TaskIntent intent = intent(List.of(requirement), List.of());

        PlanValidationReport report = new IntentGraphValidator().validate(intent, graph(merelyMentioned));

        assertFalse(report.valid());
        assertTrue(report.errors().stream().anyMatch(error -> error.message().contains("尚未验证")));
        assertTrue(new IntentGraphValidator().validate(intent,
                graph(node("统计", "code", DoneWhen.validation("").withChecks(List.of(requirement))))).valid());
    }

    @Test
    void shortConstraintsAndEachIndependentConditionNeedACheck() {
        TaskIntent intent = intent(List.of("中文", "不得删除源文件"), List.of());
        TaskNode partial = node("验证", "code", DoneWhen.note().withChecks(List.of("中文")));
        assertFalse(new IntentGraphValidator().validate(intent, graph(partial)).valid());
        TaskNode complete = node("验证", "code", DoneWhen.note().withChecks(List.of("中文", "不得删除源文件")));
        assertTrue(new IntentGraphValidator().validate(intent, graph(complete)).valid());
    }

    @Test
    void missingAcceptanceConditionFailsInsteadOfBeingDroppedByCompiler() throws Exception {
        GoalCompiler compiler = new GoalCompiler(new PlannerProperties());
        GoalCompiler.ParsedCompilation parsed = compiler.parseCompilation("""
                {"objective":"分析销售数据","successCriteria":["排除退款订单"],
                 "nodes":[{"id":"n1","name":"统计","capability":"code","outputs":["统计结论"],
                 "doneWhen":{"type":"note_required"}}]}
                """, new Goal("g", "分析销售数据", "", Map.of(), List.of(), List.of()));
        assertEquals(List.of("排除退款订单"), parsed.goal().successCriteria());
        assertFalse(new IntentGraphValidator().validate(parsed.intent(), parsed.graph()).valid());
        assertFalse(new PlanValidator().validate(parsed.graph(), TaskPlan.of("分析销售数据"), parsed.goal()).valid());
    }

    @Test
    void aFileNameInsideABusinessConditionDoesNotEraseThatCondition() {
        String requirement = "report.md 必须包含三个月销售额";
        TaskNode writer = node("写报告", "file_write", DoneWhen.file("report.md"));
        assertEquals(List.of(requirement), GoalCompiler.checkableCriteria(List.of(requirement), "分析", graph(writer)));
        Goal goal = new Goal("g", "分析", "", Map.of(), List.of(), List.of(requirement));
        assertFalse(new PlanValidator().validate(graph(writer), TaskPlan.of("生成报告 report.md"), goal).valid());
    }

    @Test
    void fileCriteriaJudgeTheActualFileAndRejectGoodLookingWriteReceipts() throws Exception {
        Path file = temporary.resolve("page.html");
        Files.writeString(file, "<html><body>Actual generated page: required chart is missing.</body></html>");
        LlmJudgeTodoValidator judge = mock(LlmJudgeTodoValidator.class);
        when(judge.validate(any(), anyString())).thenReturn("缺少要求的图表");
        StepEvaluator evaluator = evaluator(judge);
        TaskNode writer = node("生成网页", "code", DoneWhen.file(file.toString(), "包含销售趋势图"));

        StepEvaluator.EvalResult result = evaluator.evaluate(writer,
                ToolResult.success("{\"success\":true,\"message\":\"all requirements passed\"}"), file.toString());

        assertFalse(result.ok());
        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(judge).validate(any(), evidence.capture());
        assertTrue(evidence.getValue().contains("Actual generated page"));
        assertFalse(evidence.getValue().contains("all requirements passed"));
    }

    @Test
    void structuralFileSuccessStaysCompatibleButDeclaredQualityCannotPassWithoutAJudge() throws Exception {
        Path file = temporary.resolve("page.html");
        Files.writeString(file, "<html><body>A basic page</body></html>");
        StepEvaluator evaluator = evaluator(null);
        assertTrue(evaluator.evaluate(node("生成网页", "code", DoneWhen.file(file.toString())),
                ToolResult.success(file.toString()), file.toString()).ok());
        StepEvaluator.EvalResult quality = evaluator.evaluate(
                node("生成网页", "code", DoneWhen.file(file.toString(), "展示有效图表")),
                ToolResult.success(file.toString()), file.toString());
        assertFalse(quality.ok());
        assertTrue(quality.reason().contains("llm_judge"));
    }

    @Test
    void eachNoteCheckRunsAndOneFailureStopsAcceptance() {
        LlmJudgeTodoValidator judge = mock(LlmJudgeTodoValidator.class);
        when(judge.validate(any(), anyString())).thenReturn(null, "未排除退款订单");
        StepEvaluator.EvalResult result = evaluator(judge).evaluate(
                node("统计", "code", DoneWhen.note().withChecks(List.of("逐月统计", "排除退款订单"))),
                ToolResult.success("销售明细统计结果"), "sales results");
        assertFalse(result.ok());
        verify(judge, times(2)).validate(any(), eq("sales results"));
    }

    @Test
    void allChecksPassingCanCertifyTheDeclaredSemanticConditions() {
        LlmJudgeTodoValidator judge = mock(LlmJudgeTodoValidator.class);
        TaskNode note = node("统计", "code", DoneWhen.note().withChecks(List.of("逐月统计", "排除退款")));
        StepEvaluator evaluator = evaluator(judge);
        assertTrue(evaluator.evaluate(note, ToolResult.success("统计数据"), "actual results and validation logs").ok());
        verify(judge, times(2)).validate(any(), eq("actual results and validation logs"));
        assertTrue(StepEvaluator.hasMatchingJudge(graph(note), "排除退款"));
    }

    @Test
    void failedToolAndMissingFileNeverReachSemanticJudge() {
        LlmJudgeTodoValidator judge = mock(LlmJudgeTodoValidator.class);
        StepEvaluator evaluator = evaluator(judge);
        TaskNode node = node("生成报告", "file_write", DoneWhen.file(
                temporary.resolve("missing.txt").toString(), "统计正确"));
        assertFalse(evaluator.evaluate(node, ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "write failed", false), "failure").ok());
        assertFalse(evaluator.evaluate(node, ToolResult.success("write receipt"), "write receipt").ok());
        verifyNoInteractions(judge);
    }

    @Test
    void binaryOrLongFileIsExplicitlyUnverifiedInsteadOfJudgingItsPathOrTruncatedContent() throws Exception {
        LlmJudgeTodoValidator judge = mock(LlmJudgeTodoValidator.class);
        Path binary = temporary.resolve("image.bin");
        Files.write(binary, new byte[200]);
        Path longFile = temporary.resolve("large.html");
        Files.writeString(longFile, "x".repeat(13000));
        for (Path path : List.of(binary, longFile)) {
            StepEvaluator.EvalResult result = evaluator(judge).evaluate(
                    node("生成文件", "file_write", DoneWhen.file(path.toString(), "内容符合要求")),
                    ToolResult.success(path.toString()), path.toString());
            assertFalse(result.ok());
            assertTrue(result.reason().contains("尚未验证"));
        }
        verifyNoInteractions(judge);
    }

    @Test
    void emptyConstraintsAndSimpleInformationalOutputsDoNotRequireInventedQualityChecks() {
        TaskNode information = node("查询天气", "web", DoneWhen.note());
        assertTrue(new IntentGraphValidator().validate(intent(List.of(), List.of()), graph(information)).valid());
        assertTrue(evaluator(null).evaluate(information, ToolResult.success("上海晴，20度"), "上海晴，20度").ok());
    }

    private static StepEvaluator evaluator(LlmJudgeTodoValidator judge) {
        return new StepEvaluator(new PlannerProperties(), new PlannerMetrics(), judge);
    }

    private static TaskNode node(String name, String capability, DoneWhen doneWhen) {
        return new TaskNode("n1", name, capability, List.of(), List.of(), List.of(), TaskNodeStatus.PENDING,
                1, doneWhen, "", "", 0, "");
    }

    private static TaskGraph graph(TaskNode node) { return new TaskGraph(List.of(node)); }

    private static TaskIntent intent(List<String> constraints, List<String> criteria) {
        return new TaskIntent("处理任务", List.of(), List.of(), constraints, List.of(), criteria, false, "");
    }
}
