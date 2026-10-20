package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentGraphValidatorTest {

    private final IntentGraphValidator validator = new IntentGraphValidator();

    @Test
    void rejectsRequiredDeliverableMissingFromGraph() {
        TaskIntent intent = new TaskIntent("整理资料",
                List.of(new TaskIntent.Deliverable("结果文件", "markdown", "result.md", true)),
                List.of(), List.of(), List.of(), List.of(), false, "");

        PlanValidationReport report = validator.validate(intent,
                new TaskGraph(List.of(node("读取资料", "file_read"))));

        assertFalse(report.valid());
        assertEquals(1, report.countByType(
                PlanValidationReport.ValidationError.Type.GOAL_GRAPH_MISMATCH));
    }

    @Test
    void acceptsRequiredDeliverableMappedToNodeOutput() {
        TaskIntent intent = new TaskIntent("生成结果",
                List.of(new TaskIntent.Deliverable("结果文件", "markdown", "result.md", true)),
                List.of(), List.of(), List.of(), List.of(), false, "");
        TaskNode writer = new TaskNode("write", "写入 result.md", "file_write",
                List.of(), List.of(), List.of("result.md"), TaskNodeStatus.PENDING,
                1, DoneWhen.file("result.md"), "", "", 0, "");

        assertTrue(validator.validate(intent, new TaskGraph(List.of(writer))).valid());
    }

    @Test
    void doesNotRejectOptionalDeliverableOrEmptyIntent() {
        TaskIntent intent = new TaskIntent("检查",
                List.of(new TaskIntent.Deliverable("可选摘要", "text", "summary.txt", false)),
                List.of(), List.of(), List.of(), List.of(), false, "");

        assertTrue(validator.validate(intent, new TaskGraph(List.of(node("检查输入", "file_read")))).valid());
        assertTrue(validator.validate(null, new TaskGraph(List.of())).valid());
    }

    @Test
    void exclusionInDescriptiveTextIsNotViolationButActionTargetIs() {
        TaskIntent intent = new TaskIntent("整理文件",
                List.of(), List.of(), List.of(), List.of("删除原文件"), List.of(), false, "");

        TaskNode safe = new TaskNode("n1", "复制文件并保留副本", "file_write",
                List.of(), List.of(), List.of("copy.txt"), TaskNodeStatus.PENDING,
                1, DoneWhen.file("copy.txt", "不得删除原文件"), "", "", 0, "");
        assertTrue(validator.validate(intent, new TaskGraph(List.of(safe))).valid(),
                "说明性文本提到排除项不应判违规");

        TaskNode violating = new TaskNode("n2", "删除原文件", "file_write",
                List.of(), List.of(), List.of(), TaskNodeStatus.PENDING,
                1, DoneWhen.note(), "", "", 0, "");
        assertFalse(validator.validate(intent, new TaskGraph(List.of(violating))).valid(),
                "节点动作目标命中排除项必须拒绝");
    }

    @Test
    void deliverableMentionedOnlyInArgumentsIsNotAProducer() {
        TaskIntent intent = new TaskIntent("生成报告",
                List.of(new TaskIntent.Deliverable("报告", "markdown", "report.md", true)),
                List.of(), List.of(), List.of(), List.of(), false, "");
        TaskNode talkOnly = new TaskNode("n1", "讨论 report.md 的结构", "general",
                List.of(), List.of(), List.of(), TaskNodeStatus.PENDING,
                1, DoneWhen.note(), "", java.util.Map.of("note", "report.md"),
                "", List.of(), "", 0, "");

        assertFalse(validator.validate(intent, new TaskGraph(List.of(talkOnly))).valid(),
                "只提到路径、没有写盘动作的节点不算生产者");
    }

    @Test
    void requiredInputNeedsConsumerNode() {
        TaskIntent intent = new TaskIntent("分析表格",
                List.of(), List.of(new TaskIntent.Input("销售明细", "upload", true)),
                List.of(), List.of(), List.of(), false, "");

        assertFalse(validator.validate(intent, new TaskGraph(List.of(node("闲聊", "general")))).valid());
        assertTrue(validator.validate(intent, new TaskGraph(List.of(node("读取 销售明细", "file_read")))).valid());
    }

    private static TaskNode node(String name, String capability) {
        return new TaskNode("n1", name, capability, List.of(), List.of(), List.of(),
                TaskNodeStatus.PENDING, 1, DoneWhen.note(), "", "", 0, "");
    }
}
