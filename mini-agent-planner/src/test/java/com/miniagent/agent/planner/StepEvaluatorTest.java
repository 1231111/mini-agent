package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StepEvaluatorTest {

    @Test
    void hollowEvidenceDetectedForFileDeliveryNode() {
        TaskNode node = new TaskNode(
                "n1", "写 eval_cpl06_report.md", "file_write",
                null, null, null, TaskNodeStatus.READY, 0,
                DoneWhen.file("eval_cpl06_report.md"), "", "", 0, "");
        assertTrue(StepEvaluator.isHollowEvidence(AgentLoop.STEP_SEGMENT_DONE));
        assertTrue(StepEvaluator.looksLikeFileDelivery(node));
    }

    @Test
    void hollowEvidenceRejectedForAcquireNode() {
        TaskNode node = new TaskNode(
                "n1", "获取资料原文", "web",
                null, null, null, TaskNodeStatus.READY, 0,
                DoneWhen.note(), "", "", 0, "");
        StepEvaluator evaluator = new StepEvaluator(
                new PlannerProperties(), new PlannerMetrics(), null);
        StepEvaluator.EvalResult r = evaluator.evaluate(
                node, AgentLoop.STEP_SEGMENT_DONE, AgentLoop.STEP_SEGMENT_DONE);
        assertFalse(r.ok());
    }

    @Test
    void shortAcquireEvidencePassesWhenNonHollow() {
        TaskNode node = new TaskNode(
                "n1", "获取资料原文", "web",
                null, null, null, TaskNodeStatus.READY, 0,
                DoneWhen.note(), "", "", 0, "");
        StepEvaluator evaluator = new StepEvaluator(
                new PlannerProperties(), new PlannerMetrics(), null);
        StepEvaluator.EvalResult r = evaluator.evaluate(node, "ok", "got it");
        assertTrue(r.ok());
    }

    @Test
    void codeStatsIsNotFileDelivery() {
        TaskNode node = new TaskNode(
                "n2", "统计每个月销售额", "code",
                null, null, null, TaskNodeStatus.READY, 0,
                DoneWhen.judge("统计覆盖各月销售额"), "", "", 0, "");
        assertFalse(StepEvaluator.looksLikeFileDelivery(node));
    }

    @Test
    void nameMdIsNotFileDeliveryOnWeb() {
        TaskNode node = new TaskNode(
                "n1", "整理 notes.md 要点", "web",
                null, null, null, TaskNodeStatus.READY, 0,
                DoneWhen.note(), "", "", 0, "");
        assertFalse(StepEvaluator.looksLikeFileDelivery(node));
    }

    @Test
    void graphRejectsHollowSuccessStamp() {
        TaskNode node = new TaskNode(
                "n1", "写入 notes.md", "file_write",
                null, null, null, TaskNodeStatus.SUCCESS, 0,
                DoneWhen.file("notes.md"), "", "", 0,
                AgentLoop.STEP_SEGMENT_DONE);
        StepEvaluator evaluator = new StepEvaluator(
                new PlannerProperties(), new PlannerMetrics(), null);
        StepEvaluator.GraphEval r = evaluator.evaluateGraph(
                null, new TaskGraph(List.of(node)));
        assertFalse(r.ok());
        assertEquals("n1", r.nodeId());
    }

    @Test
    void graphPassesAcquireEvidence() {
        TaskNode node = new TaskNode(
                "n1", "获取资料原文", "web",
                null, null, null, TaskNodeStatus.SUCCESS, 0,
                DoneWhen.note(), "", "", 0,
                "OpenAI official API documentation covering auth, rate limits, and error codes.");
        StepEvaluator evaluator = new StepEvaluator(
                new PlannerProperties(), new PlannerMetrics(), null);
        assertTrue(evaluator.evaluateGraph(null, new TaskGraph(List.of(node))).ok());
    }

    @Test
    void graphRejectsMissingCriteriaFile() {
        TaskNode node = new TaskNode(
                "n1", "获取资料原文", "web",
                null, null, null, TaskNodeStatus.SUCCESS, 0,
                DoneWhen.note(), "", "", 0,
                "OpenAI official API documentation covering auth, rate limits, and error codes.");
        Goal goal = new Goal("g1", "写报告", "NEW_TASK", null,
                Map.of(), List.of(), List.of("交付 report.md"));
        StepEvaluator evaluator = new StepEvaluator(
                new PlannerProperties(), new PlannerMetrics(), null);
        StepEvaluator.GraphEval r = evaluator.evaluateGraph(
                goal, new TaskGraph(List.of(node)));
        assertFalse(r.ok());
        assertTrue(r.reason().contains("report.md"));
    }

    @Test
    void graphIgnoresPlaceholderCriterion() {
        TaskNode node = new TaskNode(
                "n1", "获取资料原文", "web",
                null, null, null, TaskNodeStatus.SUCCESS, 0,
                DoneWhen.note(), "", "", 0,
                "OpenAI official API documentation covering auth, rate limits, and error codes.");
        Goal goal = new Goal("g1", "调研", "RESEARCH", null,
                Map.of(), List.of(), List.of("完成 objective 且可验收"));
        StepEvaluator evaluator = new StepEvaluator(
                new PlannerProperties(), new PlannerMetrics(), null);
        assertTrue(evaluator.evaluateGraph(goal, new TaskGraph(List.of(node))).ok());
    }

    @Test
    void graphRejectsOrphanProseCriterion() {
        TaskNode node = new TaskNode(
                "n1", "获取资料原文", "web",
                null, null, null, TaskNodeStatus.SUCCESS, 0,
                DoneWhen.note(), "", "", 0,
                "OpenAI official API documentation covering auth, rate limits, and error codes.");
        Goal goal = new Goal("g1", "调研", "RESEARCH", null,
                Map.of(), List.of(), List.of("文档要写全"));
        StepEvaluator evaluator = new StepEvaluator(
                new PlannerProperties(), new PlannerMetrics(), null);
        StepEvaluator.GraphEval r = evaluator.evaluateGraph(
                goal, new TaskGraph(List.of(node)));
        assertFalse(r.ok());
        assertTrue(r.reason().contains("无法验收"));
    }

    @Test
    void graphRejectsUnboundDeclaredOutput() {
        TaskNode node = new TaskNode(
                "n1", "获取资料原文", "web",
                List.of(), List.of(), List.of("source_text"),
                TaskNodeStatus.SUCCESS, 0,
                DoneWhen.note(), "", Map.of(), "", List.of(), "", 0,
                "OpenAI official API documentation covering auth, rate limits, and error codes.",
                Map.of());
        StepEvaluator evaluator = new StepEvaluator(
                new PlannerProperties(), new PlannerMetrics(), null);
        StepEvaluator.GraphEval r = evaluator.evaluateGraph(
                null, new TaskGraph(List.of(node)));
        assertFalse(r.ok());
        assertEquals("n1", r.nodeId());
        assertTrue(r.reason().contains("未绑定"));
    }

    @Test
    void graphPassesBoundAcquireOutput() {
        TaskNode node = new TaskNode(
                "n1", "获取资料原文", "web",
                List.of(), List.of(), List.of("source_text"),
                TaskNodeStatus.SUCCESS, 0,
                DoneWhen.note(), "", Map.of(), "", List.of(), "", 0,
                "extracted source",
                Map.of("source_text", "extracted source"));
        StepEvaluator evaluator = new StepEvaluator(
                new PlannerProperties(), new PlannerMetrics(), null);
        assertTrue(evaluator.evaluateGraph(null, new TaskGraph(List.of(node))).ok());
    }

    @Test
    void afterLoopDoesNotPassOnHollowEvidence() {
        TaskNode node = new TaskNode(
                "n1", "整理要点", "web",
                null, null, null, TaskNodeStatus.READY, 0,
                DoneWhen.note(), "", "", 0, "");
        PlannerProperties props = new PlannerProperties();
        props.setStrictEval(false);
        StepEvaluator evaluator = new StepEvaluator(props, new PlannerMetrics(), null);
        assertFalse(evaluator.evaluateAfterLoop(node, AgentLoop.STEP_SEGMENT_DONE).ok());
        assertTrue(evaluator.evaluateAfterLoop(
                node, "extracted source covering auth and rate limits").ok());
    }

    @Test
    void checkValidationNeedsExitCodeOrJson() {
        assertTrue(StepEvaluator.checkValidation("cmd done exit_code=0").ok());
        assertFalse(StepEvaluator.checkValidation("构建通过了").ok());
        assertTrue(StepEvaluator.checkValidation("{\"success\":true}").ok());
        assertFalse(StepEvaluator.checkValidation("exit_code=1").ok());
    }
}
