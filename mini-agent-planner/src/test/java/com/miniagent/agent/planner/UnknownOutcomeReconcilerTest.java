package com.miniagent.agent.planner;

import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnknownOutcomeReconcilerTest {
    private final PlannerStateStore states = new PlannerStateStore();
    private final StepEvaluator evaluator = mock(StepEvaluator.class);
    private final TodoStateProjector projector = mock(TodoStateProjector.class);
    private final UnknownOutcomeReconciler reviews = new UnknownOutcomeReconciler(states, evaluator, projector);

    private StateSnapshot init() {
        TaskNode first = new TaskNode("search", "检索航班", "web", List.of(), List.of(), List.of(),
                TaskNodeStatus.SUCCESS, 1, DoneWhen.note(), "", "", 0, "已有航班列表");
        TaskNode second = new TaskNode("verify", "核验候选航班", "browser", List.of("search"), List.of(), List.of(),
                TaskNodeStatus.OUTCOME_UNKNOWN, 1, DoneWhen.note(), "", "点击结果未知", 0, "")
                .withToolStatus(ToolStatus.UNKNOWN);
        return states.init("s", "execution-1", new Goal("g", "查询航班", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(first, second)));
    }

    private UnknownOutcomeReconciler.Request request(StateSnapshot s, UnknownOutcomeReconciler.Decision decision) {
        return new UnknownOutcomeReconciler.Request(s.executionId(), s.version(), s.planVersion(), "verify",
                decision, "已打开实际页面核验，回执编号 RECEIPT-123，候选航班存在。", true);
    }

    @Test void retryRequiresExplicitHumanReviewAndNewPlanVersion() {
        StateSnapshot s = init();
        AtomicInteger fenceChecks = new AtomicInteger();
        StateSnapshot result = reviews.reconcile("s", 7L, request(s,
                UnknownOutcomeReconciler.Decision.CONFIRMED_NOT_APPLIED), fenceChecks::incrementAndGet);
        assertEquals(s.planVersion() + 1, result.planVersion());
        assertEquals(TaskNodeStatus.PENDING, result.graph().byId("verify").status());
        assertNull(result.graph().byId("verify").toolStatus());
        assertEquals(TaskNodeStatus.SUCCESS, result.graph().byId("search").status());
        assertEquals(1, fenceChecks.get());
        assertTrue(result.execution().containsKey("outcomeReconciliations"));
        verifyNoInteractions(evaluator);
    }

    @Test void confirmedSuccessStillRequiresFrozenAcceptance() {
        StateSnapshot s = init();
        when(evaluator.evaluateAfterLoop(any(), anyString())).thenReturn(StepEvaluator.EvalResult.fail("缺少回执"));
        assertThrows(IllegalArgumentException.class, () -> reviews.reconcile("s", 7L,
                request(s, UnknownOutcomeReconciler.Decision.CONFIRMED_APPLIED)));
        assertEquals(s, states.get("s").orElseThrow());
        verifyNoInteractions(projector);
    }

    @Test void acceptedEvidenceResolvesOnlyOriginalNodeAndPersistsReviewer() {
        StateSnapshot s = init();
        when(evaluator.evaluateAfterLoop(any(), anyString())).thenReturn(StepEvaluator.EvalResult.pass());
        StateSnapshot result = reviews.reconcile("s", 7L, request(s, UnknownOutcomeReconciler.Decision.CONFIRMED_APPLIED));
        assertEquals(TaskNodeStatus.SUCCESS, result.graph().byId("verify").status());
        assertEquals(ToolStatus.SUCCESS, result.graph().byId("verify").toolStatus());
        assertEquals(s.planVersion(), result.planVersion());
        assertTrue(result.execution().get("outcomeReconciliations").toString().contains("reviewerId=7"));
    }

    @Test void staleOrDuplicateReviewCannotResetAnAlreadyResolvedNode() {
        StateSnapshot s = init();
        var request = request(s, UnknownOutcomeReconciler.Decision.CONFIRMED_NOT_APPLIED);
        StateSnapshot committed = reviews.reconcile("s", 7L, request);
        assertEquals(committed, reviews.reconcile("s", 7L, request));
        assertEquals(1, states.get("s").orElseThrow().graph().byId("verify").retryCount());
        assertThrows(PlannerStateStore.VersionConflictException.class, () -> reviews.reconcile("s", 8L, request));
    }

    @Test void wrongExecutionEvenWithSameVersionIsRejected() {
        StateSnapshot s = init();
        var good = request(s, UnknownOutcomeReconciler.Decision.CONFIRMED_NOT_APPLIED);
        var bad = new UnknownOutcomeReconciler.Request("old-execution", good.version(), good.planVersion(),
                good.nodeId(), good.decision(), good.evidence(), true);
        assertThrows(PlannerStateStore.VersionConflictException.class, () -> reviews.reconcile("s", 7L, bad));
        assertEquals(s, states.get("s").orElseThrow());
    }

    @Test void missingEvidenceOrUnconfirmedOrUnownedReviewCannotChangeState() {
        StateSnapshot s = init();
        var good = request(s, UnknownOutcomeReconciler.Decision.CONFIRMED_NOT_APPLIED);
        for (var bad : List.of(new UnknownOutcomeReconciler.Request(s.executionId(), s.version(), s.planVersion(),
                        "verify", good.decision(), "", true),
                new UnknownOutcomeReconciler.Request(s.executionId(), s.version(), s.planVersion(),
                        "verify", good.decision(), good.evidence(), false))) {
            assertThrows(IllegalArgumentException.class, () -> reviews.reconcile("s", 7L, bad));
        }
        assertThrows(IllegalArgumentException.class, () -> reviews.reconcile("s", null, good));
        assertEquals(s, states.get("s").orElseThrow());
    }

    @Test void expiredLeaseCannotCommitAfterValidation() {
        StateSnapshot s = init();
        assertThrows(IllegalStateException.class, () -> reviews.reconcile("s", 7L,
                request(s, UnknownOutcomeReconciler.Decision.CONFIRMED_NOT_APPLIED),
                () -> { throw new IllegalStateException("lost lease"); }));
        assertEquals(s, states.get("s").orElseThrow());
    }

    @Test void projectionFailureCannotUndoOrMisreportCommittedDecision() {
        StateSnapshot s = init();
        doThrow(new IllegalStateException("projection storage unavailable")).when(projector).project(anyString(), any());
        StateSnapshot result = reviews.reconcile("s", 7L,
                request(s, UnknownOutcomeReconciler.Decision.CONFIRMED_NOT_APPLIED));
        assertEquals(result, states.get("s").orElseThrow());
        assertEquals(s.planVersion() + 1, result.planVersion());
    }
}
