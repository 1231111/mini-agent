package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryPlanContractTest {

    @Test
    void keepsOriginalCausalChainThroughNewIntermediateNodeAndMergedSuccess() {
        TaskNode fetched = node("fetch", "获取原始数据", List.of(), List.of("raw"),
                TaskNodeStatus.SUCCESS, DoneWhen.note());
        TaskNode repaired = node("repair", "修复数据", List.of("fetch"), List.of("fixed"),
                TaskNodeStatus.FAILED, DoneWhen.validation("fixed data"));
        TaskNode verified = node("verify", "验收数据", List.of("repair"), List.of("report"),
                TaskNodeStatus.FAILED, DoneWhen.validation("final report"));
        StateSnapshot before = snapshot(new TaskGraph(List.of(fetched, repaired, verified)));

        TaskNode replacementRepair = node("new_repair", "修复数据", List.of("fetch"),
                List.of("fixed"), TaskNodeStatus.PENDING, DoneWhen.validation("fixed data"));
        TaskNode evidence = node("new_evidence", "补取验收证据", List.of("new_repair"),
                List.of("evidence"), TaskNodeStatus.PENDING, DoneWhen.note());
        TaskNode replacementVerify = node("new_verify", "验收数据", List.of("new_evidence"),
                List.of("report"), TaskNodeStatus.PENDING, DoneWhen.validation("final report"));
        TaskGraph candidate = new TaskGraph(List.of(replacementRepair, evidence, replacementVerify));
        TaskGraph merged = PlanningLoop.mergeKeepSuccess(before.graph(), candidate, 7);

        assertThat(merged).isNotNull();
        assertThat(merged.byId("fetch")).isEqualTo(fetched);
        PlanValidationReport report = RecoveryPlanContract.validate(before, merged);

        assertThat(report.valid()).isTrue();
    }

    @Test
    void rejectsConsumerWhoseAncestorChainDropsOriginalUnfinishedPredecessor() {
        TaskNode repair = node("repair", "修复数据", List.of(), List.of("fixed"),
                TaskNodeStatus.FAILED, DoneWhen.validation("fixed data"));
        TaskNode verify = node("verify", "验收数据", List.of("repair"), List.of("report"),
                TaskNodeStatus.FAILED, DoneWhen.validation("final report"));
        StateSnapshot before = snapshot(new TaskGraph(List.of(repair, verify)));

        TaskNode replacementRepair = node("rp8_repair", "修复数据", List.of(),
                List.of("fixed"), TaskNodeStatus.PENDING, DoneWhen.validation("fixed data"));
        // It retains the output and frozen acceptance condition but is no longer
        // causally downstream of the repair obligation.
        TaskNode replacementVerify = node("rp8_verify", "验收数据", List.of(),
                List.of("report"), TaskNodeStatus.PENDING, DoneWhen.validation("final report"));

        PlanValidationReport report = RecoveryPlanContract.validate(before,
                new TaskGraph(List.of(replacementRepair, replacementVerify)));

        assertThat(report.valid()).isFalse();
        assertThat(report.summary()).contains("祖先链");
    }

    @Test
    void rejectsAmbiguousOutputReplacementInsteadOfGuessing() {
        TaskNode original = node("inspect", "检查源文件", List.of(), List.of("facts"),
                TaskNodeStatus.FAILED, DoneWhen.note());
        StateSnapshot before = snapshot(new TaskGraph(List.of(original)));

        TaskNode first = node("rp9_first", "搜索源文件", List.of(), List.of("facts"),
                TaskNodeStatus.PENDING, DoneWhen.note());
        TaskNode second = node("rp9_second", "读取源文件", List.of(), List.of("facts"),
                TaskNodeStatus.PENDING, DoneWhen.note());

        PlanValidationReport report = RecoveryPlanContract.validate(before,
                new TaskGraph(List.of(first, second)));

        assertThat(report.valid()).isFalse();
        assertThat(report.summary()).contains("多个可能替代节点");
    }

    @Test
    void acceptsUniqueRenamedOutputProducerWithAdditionalAcceptanceCheck() {
        DoneWhen frozen = new DoneWhen(DoneWhen.VALIDATION, "original criterion", "",
                List.of("original check"));
        TaskNode original = node("old", "原义务", List.of(), List.of("facts"),
                TaskNodeStatus.FAILED, frozen);
        TaskNode replacement = node("new", "补证后重新验收", List.of(), List.of("facts", "extra"),
                TaskNodeStatus.PENDING, frozen.withChecks(List.of("original check", "new check")));

        assertThat(RecoveryPlanContract.validate(snapshot(new TaskGraph(List.of(original))),
                new TaskGraph(List.of(replacement))).valid()).isTrue();
    }

    @Test
    void rejectsChangedFrozenCriterionEvenWhenOutputMappingIsUnique() {
        TaskNode original = node("old", "原义务", List.of(), List.of("facts"),
                TaskNodeStatus.FAILED, DoneWhen.validation("must inspect source"));
        TaskNode replacement = node("new", "改名义务", List.of(), List.of("facts"),
                TaskNodeStatus.PENDING, DoneWhen.validation("just claim complete"));

        PlanValidationReport report = RecoveryPlanContract.validate(snapshot(new TaskGraph(List.of(original))),
                new TaskGraph(List.of(replacement)));

        assertThat(report.valid()).isFalse();
        assertThat(report.summary()).contains("完整冻结验收条件");
    }

    @Test
    void rejectsDroppedOutputEvenWhenStableIdAndNameMatch() {
        TaskNode original = node("inspect", "检查源文件", List.of(), List.of("facts", "proof"),
                TaskNodeStatus.FAILED, DoneWhen.note());
        TaskNode replacement = node("inspect", "检查源文件", List.of(), List.of("facts"),
                TaskNodeStatus.PENDING, DoneWhen.note());

        PlanValidationReport report = RecoveryPlanContract.validate(snapshot(new TaskGraph(List.of(original))),
                new TaskGraph(List.of(replacement)));

        assertThat(report.valid()).isFalse();
        assertThat(report.summary()).contains("全部命名产出");
    }

    @Test
    void mapsOutputlessObligationsByStableIdOrNameAndPreservesTheirEdge() {
        TaskNode originalParent = node("prepare", "准备动作", List.of(), List.of(),
                TaskNodeStatus.FAILED, DoneWhen.note());
        TaskNode originalConsumer = node("submit", "提交动作", List.of("prepare"), List.of(),
                TaskNodeStatus.PENDING, DoneWhen.note());
        TaskNode parent = node("new_prepare", "准备动作", List.of(), List.of(),
                TaskNodeStatus.PENDING, DoneWhen.note());
        TaskNode consumer = node("submit", "更新后的提交名称", List.of("new_prepare"), List.of(),
                TaskNodeStatus.PENDING, DoneWhen.note());

        assertThat(RecoveryPlanContract.validate(snapshot(new TaskGraph(List.of(originalParent, originalConsumer))),
                new TaskGraph(List.of(parent, consumer))).valid()).isTrue();
    }

    @Test
    void rejectsConflictingIdAndNameMatchesForOutputlessObligation() {
        TaskNode original = node("inspect", "检查源文件", List.of(), List.of(),
                TaskNodeStatus.FAILED, DoneWhen.note());
        TaskNode idMatch = node("inspect", "另一个动作", List.of(), List.of(),
                TaskNodeStatus.PENDING, DoneWhen.note());
        TaskNode nameMatch = node("new_inspect", "检查源文件", List.of(), List.of(),
                TaskNodeStatus.PENDING, DoneWhen.note());

        assertThat(RecoveryPlanContract.validate(snapshot(new TaskGraph(List.of(original))),
                new TaskGraph(List.of(idMatch, nameMatch))).summary()).contains("多个可能替代节点");
    }

    @Test
    void rejectsOneCandidateThatCollapsesTwoOriginalObligations() {
        TaskNode first = node("first", "第一义务", List.of(), List.of("first-facts"),
                TaskNodeStatus.FAILED, DoneWhen.note());
        TaskNode second = node("second", "第二义务", List.of(), List.of("second-facts"),
                TaskNodeStatus.FAILED, DoneWhen.note());
        TaskNode combined = node("combined", "合并义务", List.of(), List.of("first-facts", "second-facts"),
                TaskNodeStatus.PENDING, DoneWhen.note());

        PlanValidationReport report = RecoveryPlanContract.validate(snapshot(new TaskGraph(List.of(first, second))),
                new TaskGraph(List.of(combined)));

        assertThat(report.valid()).isFalse();
        assertThat(report.summary()).contains("同时替代多个原未完成义务");
    }

    @Test
    void rejectsReversedDependencyEvenWhenBothObligationsArePreserved() {
        TaskNode first = node("first", "修复动作", List.of(), List.of("fixed"),
                TaskNodeStatus.FAILED, DoneWhen.note());
        TaskNode second = node("second", "验收动作", List.of("first"), List.of("verified"),
                TaskNodeStatus.PENDING, DoneWhen.note());
        TaskNode newFirst = node("new_first", "修复动作", List.of("new_second"), List.of("fixed"),
                TaskNodeStatus.PENDING, DoneWhen.note());
        TaskNode newSecond = node("new_second", "验收动作", List.of(), List.of("verified"),
                TaskNodeStatus.PENDING, DoneWhen.note());

        assertThat(RecoveryPlanContract.validate(snapshot(new TaskGraph(List.of(first, second))),
                new TaskGraph(List.of(newFirst, newSecond))).summary()).contains("祖先链");
    }

    @Test
    void acceptsReferenceToSuccessReceiptSuppliedByMergeAndRejectsDroppingItsEdge() {
        TaskNode completed = node("done", "已完成核验", List.of(), List.of("inventory"),
                TaskNodeStatus.SUCCESS, DoneWhen.note());
        TaskNode original = node("inspect", "后续检查", List.of("done"), List.of("facts"),
                TaskNodeStatus.FAILED, DoneWhen.note());
        StateSnapshot before = snapshot(new TaskGraph(List.of(completed, original)));
        TaskNode replacement = node("new_inspect", "后续检查", List.of("done"), List.of("facts"),
                TaskNodeStatus.PENDING, DoneWhen.note());

        assertThat(RecoveryPlanContract.validate(before, new TaskGraph(List.of(replacement))).valid()).isTrue();
        TaskNode disconnected = replacement.withIdentityAndDependencies(replacement.id(), List.of());
        assertThat(RecoveryPlanContract.validate(before,
                new TaskGraph(List.of(completed, disconnected))).summary()).contains("祖先链");
    }

    @Test
    void requiresCancelledPrerequisiteWhenAnUnfinishedConsumerStillDependsOnIt() {
        TaskNode cancelled = node("cancelled", "生成原始数据", List.of(), List.of("raw"),
                TaskNodeStatus.CANCELLED, DoneWhen.note());
        TaskNode consumer = node("consume", "消费原始数据", List.of("cancelled"), List.of("report"),
                TaskNodeStatus.PENDING, DoneWhen.note());
        StateSnapshot before = snapshot(new TaskGraph(List.of(cancelled, consumer)));
        TaskNode replacementParent = node("new_parent", "恢复原始数据", List.of(), List.of("raw"),
                TaskNodeStatus.PENDING, DoneWhen.note());
        TaskNode replacementConsumer = node("new_consumer", "消费原始数据", List.of("new_parent"),
                List.of("report"), TaskNodeStatus.PENDING, DoneWhen.note());

        assertThat(RecoveryPlanContract.validate(before,
                new TaskGraph(List.of(replacementParent, replacementConsumer))).valid()).isTrue();
        TaskNode disconnected = replacementConsumer.withIdentityAndDependencies(replacementConsumer.id(), List.of());
        assertThat(RecoveryPlanContract.validate(before,
                new TaskGraph(List.of(disconnected))).valid()).isFalse();
    }

    @Test
    void permitsOmittingUnrelatedCancelledBranch() {
        TaskNode cancelled = node("abandoned", "废弃支路", List.of(), List.of("old"),
                TaskNodeStatus.CANCELLED, DoneWhen.note());
        TaskNode original = node("inspect", "剩余义务", List.of(), List.of("facts"),
                TaskNodeStatus.FAILED, DoneWhen.note());
        TaskNode replacement = node("new_inspect", "剩余义务", List.of(), List.of("facts"),
                TaskNodeStatus.PENDING, DoneWhen.note());

        assertThat(RecoveryPlanContract.validate(snapshot(new TaskGraph(List.of(cancelled, original))),
                new TaskGraph(List.of(replacement))).valid()).isTrue();
    }

    @Test
    void rejectsUnknownDependencyAndDuplicateCandidateIds() {
        TaskNode original = node("inspect", "检查源文件", List.of(), List.of("facts"),
                TaskNodeStatus.FAILED, DoneWhen.note());
        StateSnapshot before = snapshot(new TaskGraph(List.of(original)));
        TaskNode unknownDependency = node("new_inspect", "检查源文件", List.of("invented"), List.of("facts"),
                TaskNodeStatus.PENDING, DoneWhen.note());
        TaskNode duplicate = node("new_inspect", "另一个动作", List.of(), List.of("other"),
                TaskNodeStatus.PENDING, DoneWhen.note());

        PlanValidationReport report = RecoveryPlanContract.validate(before,
                new TaskGraph(List.of(unknownDependency, duplicate)));

        assertThat(report.valid()).isFalse();
        assertThat(report.summary()).contains("无法解析", "重复节点 id");
    }

    private static StateSnapshot snapshot(TaskGraph graph) {
        Goal goal = new Goal("recovery-contract", "完成恢复契约测试", "", "test",
                Map.of(), List.of(), List.of());
        return new PlannerStateStore().init("recovery-contract-session",
                "recovery-contract-execution", goal, graph, TaskIntent.fromGoal(goal));
    }

    private static TaskNode node(String id, String name, List<String> dependsOn,
                                 List<String> outputs, TaskNodeStatus status,
                                 DoneWhen doneWhen) {
        return new TaskNode(id, name, "test", dependsOn, List.of(), outputs,
                status, 50, doneWhen, "", "", 0, "");
    }
}
