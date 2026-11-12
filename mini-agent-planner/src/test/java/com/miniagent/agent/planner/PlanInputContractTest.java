package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanInputContractTest {
    private final PlanValidator validator = new PlanValidator();
    private final TaskPlan plan = TaskPlan.of("实现并验证页面").withArtifactDeliveryRequested(true);

    @Test void acceptsDeclaredUserAndAttachmentInputsWithoutInventingProducerNodes() {
        TaskNode build = node("build", List.of(), List.of("需求正文", "用户要求"), List.of("index.html"));
        TaskIntent intent = intent(List.of(new TaskIntent.Input("需求正文", "附件", true),
                new TaskIntent.Input("用户要求", "用户消息", true)));
        var report = validator.validate(new TaskGraph(List.of(build)), plan, null, intent);
        assertTrue(report.valid(), report.summary());
        assertTrue(new IntentGraphValidator().validate(intent, new TaskGraph(List.of(build))).valid());
    }

    @Test void undeclaredExternalInputIsStillRejected() {
        var graph = new TaskGraph(List.of(node("build", List.of(), List.of("不明数据"), List.of("index.html"))));
        assertEquals(1, validator.validate(graph, plan, null, intent(List.of()))
                .countByType(PlanValidationReport.ValidationError.Type.UNPRODUCED_INPUTS));
    }

    @Test void downstreamCanConsumeTransitiveAncestorOutput() {
        TaskNode build = node("build", List.of(), List.of(), List.of("index.html"))
                .withOutput("HTML正文", Map.of("index.html", "页面正文"));
        TaskNode serve = node("serve", List.of("build"), List.of("index.html"), List.of("url"));
        TaskNode verify = node("verify", List.of("serve"), List.of("index.html", "url"), List.of("checked"));
        TaskGraph graph = new TaskGraph(List.of(build, serve, verify));
        assertTrue(PlanValidator.inputsProducedByDeps(graph, verify));
        assertEquals(build, PlanningLoop.producerOf(graph, verify, "index.html"));
        TaskNode consumer = node("consumer", List.of("serve"), List.of("index.html"), List.of("result"));
        assertEquals("页面正文", ActionBinder.predecessorContent(consumer, graph));
    }

    @Test void unrelatedAndFutureNodesCannotProduceInputsWithoutDependencies() {
        TaskNode build = node("build", List.of(), List.of(), List.of("index.html"));
        TaskNode verify = node("verify", List.of(), List.of("index.html"), List.of("checked"));
        TaskGraph graph = new TaskGraph(List.of(build, verify));
        TaskIntent misleading = intent(List.of(new TaskIntent.Input("index.html", "附件", true)));
        assertFalse(validator.validate(graph, plan, null, misleading).valid());
        assertNull(PlanningLoop.producerOf(graph, verify, "index.html"));
    }

    @Test void dependencyCyclesDoNotLoopForeverDuringInputResolution() {
        TaskNode a = node("a", List.of("b"), List.of("missing"), List.of("a-out"));
        TaskNode b = node("b", List.of("a"), List.of(), List.of("b-out"));
        TaskGraph graph = new TaskGraph(List.of(a, b));
        assertNull(PlanningLoop.producerOf(graph, a, "missing"));
        assertFalse(validator.validate(graph, plan, null, intent(List.of())).valid());
    }

    @Test void answerDeliveryCanReturnALinkWithoutWritingAnotherFile() {
        TaskNode delivery = new TaskNode("delivery", "交付页面链接", "deliver", List.of(), List.of(),
                List.of("页面链接"), TaskNodeStatus.PENDING, 50, DoneWhen.note(), "", "", 0, "");
        var report = validator.validate(new TaskGraph(List.of(delivery)), plan, null, intent(List.of()));
        assertTrue(report.valid(), report.summary());
        assertFalse(StepEvaluator.looksLikeFileDelivery(delivery));
    }

    @Test void actualFileDeliveryStillRequiresFileEvidence() {
        TaskNode delivery = new TaskNode("delivery", "交付页面文件", "deliver", List.of(), List.of(),
                List.of("index.html"), TaskNodeStatus.PENDING, 50, DoneWhen.file("index.html"), "", "", 0, "");
        assertTrue(StepEvaluator.looksLikeFileDelivery(delivery));
        assertFalse(validator.validate(new TaskGraph(List.of(delivery)),
                TaskPlan.of("搜索今日AI新闻").withArtifactDeliveryRequested(false), null, intent(List.of())).valid());
    }

    @Test void browserVerificationCanFollowGeneratedArtifact() {
        TaskNode build = new TaskNode("build", "实现页面", "code", List.of(), List.of(),
                List.of("index.html"), TaskNodeStatus.PENDING, 50, DoneWhen.file("index.html"), "", "", 0, "");
        TaskNode verify = new TaskNode("verify", "浏览器验收", "browser", List.of("build"),
                List.of("index.html"), List.of("检查结论"), TaskNodeStatus.PENDING, 40,
                DoneWhen.validation("实际检查页面与交互"), "", "", 0, "");
        var report = validator.validate(new TaskGraph(List.of(build, verify)), plan, null, intent(List.of()));
        assertTrue(report.valid(), report.summary());
    }

    @Test void browserVerificationMayFollowATransitiveArtifactDependency() {
        TaskNode build = new TaskNode("build", "实现页面", "code", List.of(), List.of(),
                List.of("index.html"), TaskNodeStatus.PENDING, 50, DoneWhen.file("index.html"), "", "", 0, "");
        TaskNode prepare = node("prepare", List.of("build"), List.of("index.html"), List.of("访问地址"));
        TaskNode verify = new TaskNode("verify", "浏览器验收", "browser", List.of("prepare"),
                List.of("index.html", "访问地址"), List.of("检查结论"), TaskNodeStatus.PENDING, 40,
                DoneWhen.validation("实际检查页面与交互"), "", "", 0, "");
        var report = validator.validate(new TaskGraph(List.of(build, prepare, verify)), plan, null, intent(List.of()));
        assertTrue(report.valid(), report.summary());
    }

    private static TaskNode node(String id, List<String> dependencies, List<String> inputs, List<String> outputs) {
        return new TaskNode(id, "节点" + id, "code", dependencies, inputs, outputs,
                TaskNodeStatus.PENDING, 50, DoneWhen.note(), "", "", 0, "");
    }

    private static TaskIntent intent(List<TaskIntent.Input> inputs) {
        return new TaskIntent("实现页面", List.of(), inputs, List.of(), List.of(), List.of(), false, "");
    }
}
