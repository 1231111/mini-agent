package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
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
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

class GoalCompilerGranularityTest {

    private final GoalCompiler compiler = new GoalCompiler(new PlannerProperties());

    @Test
    void compilerDoesNotUseTemplateFallbackWhenLlmFails() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("no"));
        String msg = "写一个 hello.txt";
        TaskPlan plan = TaskPlan.of(msg);
        GoalCompiler.CompileResult r = compiler.compile(chat, msg, plan);
        assertEquals(GoalCompiler.CompilationSource.MODEL_ERROR, r.source());
        assertTrue(r.degraded());
        assertTrue(r.graph().isEmpty(), "模型失败不得构造规则图");
    }

    @Test
    void parseDropsPlaceholderCriterion() throws Exception {
        Goal seed = new Goal("g1", "写一个 hello.txt", "",
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
    void parseGraphPreservesToolArgumentsForScheduledCalls() throws Exception {
        TaskGraph graph = compiler.parseGraph("""
                {"nodes":[
                  {"id":"camel","name":"查网页","capability":"web_search",
                   "toolArguments":{"query":"mini-agent","limit":4,"includeImages":false}},
                  {"id":"snake","name":"打开网页","capability":"browser_navigate",
                   "tool_arguments":{"url":"https://example.test/","waitUntil":"domcontentloaded"}}
                ]}
                """);

        TaskNode search = graph.byId("camel");
        assertEquals(Map.of("query", "mini-agent", "limit", 4, "includeImages", false),
                search.toolArguments());
        assertEquals(search.toolArguments(), ActionBinder.bind(search, graph).arguments());

        TaskNode browser = graph.byId("snake");
        assertEquals(Map.of("url", "https://example.test/", "waitUntil", "domcontentloaded"),
                browser.toolArguments());
        assertEquals(browser.toolArguments(), ActionBinder.bind(browser, graph).arguments());
    }

    @Test
    void parseGraphRejectsMalformedToolArguments() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                compiler.parseGraph("""
                        {"nodes":[{"id":"n1","name":"查网页","capability":"web_search",
                         "toolArguments":["not","an","object"]}]}
                        """));
    }

    @Test
    void correctionPromptKeepsRejectedStructureButDropsExecutionEvidence() {
        TaskNode node = new TaskNode("n1", "查航班", "web_search", List.of(),
                List.of(), List.of("flight_results"), TaskNodeStatus.FAILED, 10,
                DoneWhen.judge("比较航班"), "use web_search", "ignored", 1, "private page body");
        node = node.withToolArguments(Map.of("query", "上海 北京 2026-11-17"));

        String correction = GoalCompiler.rejectedGraphPrompt(new TaskGraph(List.of(node)));

        assertTrue(correction.contains("REJECTED_GRAPH_DRAFT_JSON"));
        assertTrue(correction.contains("\"capability\":\"web_search\""));
        assertTrue(correction.contains("\"query\":\"上海 北京 2026-11-17\""));
        assertTrue(correction.contains("untrusted planning data"));
        assertFalse(correction.contains("private page body"));
    }

    @Test
    void modelFailureIsExplicitlyMarkedWithoutTemplateSemantics() {
        PlannerProperties props = new PlannerProperties();
        props.setCompilerRetry(0);
        GoalCompiler c = new GoalCompiler(props);
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("planner unavailable"));

        String msg = "调研一下竞品方案";
        TaskPlan plan = TaskPlan.of(msg);
        GoalCompiler.CompileResult result = c.compile(chat, msg, plan);

        assertEquals(GoalCompiler.CompilationSource.MODEL_ERROR, result.source());
        assertTrue(result.degraded());
        assertTrue(result.fallbackReason().startsWith("MODEL_ERROR:"));
        assertTrue(result.graph().isEmpty(), "模型失败不得回退模板图，必须返回空图");
    }

    /** 修正编译失败同样不回退模板图，直接返回空图让调用方拒绝调度。 */
    @Test
    void correctionCompileFailureReturnsEmptyGraphNotTemplate() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("planner unavailable"));
        String msg = "调研一下竞品方案";
        TaskPlan plan = TaskPlan.of(msg);
        GoalCompiler.CompileResult result = compiler.compileWithCorrection(
                chat, msg, plan, "上一步验证失败，请修正");
        assertTrue(result.graph().isEmpty(), "修正编译失败不得回退模板图");
    }

    @Test
    void validatorRejectsUnknownCapability() {
        TaskPlan plan = TaskPlan.of("执行一个动作");
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("n1", "执行一个动作", "teleport", List.of(), List.of(), List.of(),
                        TaskNodeStatus.PENDING, 10, DoneWhen.note(), "", "", 0, "")));

        PlanValidationReport report = new PlanValidator().validate(graph, plan);

        assertFalse(report.valid());
        assertEquals(1, report.countByType(
                PlanValidationReport.ValidationError.Type.CAPABILITY_MISMATCH));
    }

    @Test
    void checkableCriteriaKeepsMatchingJudge() {
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("n2", "统计", "code", List.of(), List.of(), List.of(),
                        TaskNodeStatus.PENDING, 9,
                        DoneWhen.judge("统计覆盖各月销售额"), "", "", 0, "")));
        assertEquals(List.of("统计覆盖各月销售额", "文档要写全"),
                GoalCompiler.checkableCriteria(
                        List.of("统计覆盖各月销售额", "文档要写全"), "统计", g));
    }

}
