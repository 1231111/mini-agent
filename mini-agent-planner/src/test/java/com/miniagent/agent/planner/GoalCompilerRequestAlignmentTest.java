package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型放大用户请求时的对齐回归。
 *
 * <p>线上实测：用户只说「搜索今日 AI 技术最新动态」，模型把目标改写成
 * 「…并整理成一份可读的 Markdown 简报文件」，并生成 web → file_write 两节点。
 * {@link PlanValidator} 按用户原文判定「未要求落盘」拒绝了图（硬约束正确），
 * 但 {@link GoalCompiler#fallback} 沿用了模型改写的目标，于是出现
 * 「目标要文件、计划只搜索」的自相矛盾，还触发了降级审批。</p>
 */
class GoalCompilerRequestAlignmentTest {

    private final GoalCompiler compiler = new GoalCompiler(new PlannerProperties());

    private static final String SEARCH = "帮我搜索今日 AI 技术最新动态";

    @Test
    void inventedFileNodeIsDroppedAndObjectiveFallsBackToUserText() {
        Goal drifted = new Goal("g1", SEARCH + "，并整理成一份可读的 Markdown 简报文件",
                "web,action", "文档生成", Map.of(), List.of(), List.of("brief.md"));
        TaskIntent intent = new TaskIntent(drifted.objective(),
                List.of(new TaskIntent.Deliverable("简报", "file", "brief.md", true)),
                List.of(new TaskIntent.Input("今日 AI 动态", "网页", true)),
                List.of(), List.of(), List.of("brief.md"), false, "");
        GoalCompiler.ParsedCompilation parsed = new GoalCompiler.ParsedCompilation(
                drifted, new TaskGraph(List.of(webNode(), fileNode())), intent);

        TaskPlan plan = TaskPlan.of(SEARCH);
        GoalCompiler.ParsedCompilation aligned = compiler.alignWithRequest(parsed, plan);

        assertEquals(1, aligned.graph().nodes().size(), "未授权的落盘节点必须被移除");
        assertEquals("web", aligned.graph().nodes().get(0).capability());
        assertTrue(aligned.intent().deliverables().isEmpty(), "文件交付物必须一并移除");
        assertEquals(SEARCH, aligned.goal().objective(), "目标必须回到用户原文");
        assertFalse(PlanValidator.claimsFileDelivery(aligned.goal().objective()));
        assertTrue(aligned.goal().successCriteria().stream().noneMatch(c -> c.endsWith(".md")));

        PlanValidationReport report = new PlanValidator().validate(
                aligned.graph(), plan, aligned.goal());
        assertTrue(report.valid(), () -> "对齐后仍不合法: " + report.summary());
    }

    @Test
    void requestedFileDeliveryIsLeftUntouched() {
        String msg = "查一下 OpenAI 最新 API 文档，整理成 api.md";
        Goal goal = new Goal("g2", msg, "web,file", "文档生成", Map.of(), List.of(),
                List.of("api.md"));
        TaskIntent intent = new TaskIntent(msg,
                List.of(new TaskIntent.Deliverable("api.md", "file", "api.md", true)),
                List.of(), List.of(), List.of(), List.of("api.md"), false, "");
        GoalCompiler.ParsedCompilation parsed = new GoalCompiler.ParsedCompilation(
                goal, new TaskGraph(List.of(webNode(), fileNode())), intent);

        TaskPlan plan = TaskPlan.of(msg);
        GoalCompiler.ParsedCompilation aligned = compiler.alignWithRequest(parsed, plan);

        assertEquals(2, aligned.graph().nodes().size());
        assertEquals(1, aligned.intent().deliverables().size());
    }

    private static TaskNode webNode() {
        return new TaskNode("n1", "搜索今日 AI 技术动态", "web",
                List.of(), List.of(), List.of("今日 AI 动态"), TaskNodeStatus.PENDING,
                50, DoneWhen.note(), "", "", 0, "");
    }

    private static TaskNode fileNode() {
        return new TaskNode("n2", "写入 Markdown 简报", "file_write",
                List.of("n1"), List.of("今日 AI 动态"), List.of("brief.md"),
                TaskNodeStatus.PENDING, 40, DoneWhen.file("brief.md"), "", "", 0, "");
    }
}
