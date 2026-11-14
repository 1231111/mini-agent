package com.miniagent.application;

import com.miniagent.agent.planner.*;
import com.miniagent.config.entity.AgentSessionPlanner;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewedTaskProgressTest {
    final AgentSessionPlannerRepository planners = mock(AgentSessionPlannerRepository.class);
    final AgentTraceStepRepository traces = mock(AgentTraceStepRepository.class);
    final ChatTaskRepository tasks = mock(ChatTaskRepository.class);
    final ConversationTaskProgressService service = new ConversationTaskProgressService(planners, traces, tasks);
    final LocalDateTime created = LocalDateTime.of(2026, 11, 13, 10, 0);
    final Instant reviewedAt = created.atZone(ZoneId.systemDefault()).toInstant().plusSeconds(60);

    @Test void onlyLatestExactExecutionCardAndReviewedNodeAreChanged() {
        ChatTask older = card(1, "exec", "OUTCOME_UNKNOWN"), current = card(2, "exec", "OUTCOME_UNKNOWN");
        ChatTask unrelated = card(3, "different-exec", "OUTCOME_UNKNOWN");
        String oldJson = older.getTaskProgressJson(), unrelatedJson = unrelated.getTaskProgressJson();
        wire(unrelated, current, older);
        StateSnapshot graph = graph(false, "CONFIRMED_APPLIED", "n1", reviewedAt);

        var update = service.persistReviewedOutcome(7L, "session", graph);

        assertEquals(2L, update.messageId());
        assertEquals("completed", update.progress().items().getFirst().status());
        assertEquals("pending", update.progress().items().get(1).status(), "later graph SUCCESS cannot rewrite saved successor");
        assertEquals("WAITING", update.progress().status(), "review does not claim whole-task completion");
        assertEquals("OUTCOME_REVIEWED", update.progress().reason());
        assertEquals(oldJson, older.getTaskProgressJson());
        assertEquals(unrelatedJson, unrelated.getTaskProgressJson());
        assertEquals("old answer", current.getAnswer());
        assertEquals("upload.png", current.getImages());
        assertNull(service.persistReviewedOutcome(7L, "session", graph), "receipt replay is idempotent, never falls back to older card");
        verify(tasks, times(1)).compareAndSetTaskProgress(eq(2L), eq(7L), eq("session"), anyString(), anyString());
    }

    @Test void notAppliedReviewKeepsPendingNodeAfterItsLaterGraphExecutionSucceeds() {
        ChatTask current = card(2, "exec", "OUTCOME_UNKNOWN"); wire(current);
        var graph = graph(false, "CONFIRMED_NOT_APPLIED", "n1", reviewedAt);
        var updated = service.persistReviewedOutcome(7L, "session", graph);
        assertEquals("pending", updated.progress().items().getFirst().status());
        assertTrue(updated.progress().items().getFirst().note().contains("未生效"));
        assertEquals("pending", updated.progress().items().get(1).status());
    }

    @Test void durablePlannerReceiptRepairsFailedProjectionAfterServiceReconstruction() {
        ChatTask current = card(2, "exec", "OUTCOME_UNKNOWN"); wire(current);
        var graph = graph(false, "CONFIRMED_APPLIED", "n1", reviewedAt);
        AtomicInteger calls = new AtomicInteger();
        when(tasks.compareAndSetTaskProgress(anyLong(), anyLong(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("transient projection failure");
            current.setTaskProgressJson(call.getArgument(4)); return 1;
        });
        assertThrows(IllegalStateException.class, () -> service.persistReviewedOutcome(7L, "session", graph));
        AgentSessionPlanner persisted = new AgentSessionPlanner(); persisted.setSessionId("session");
        persisted.setStateJson(PlannerStateJson.snapshotToJson(graph));
        when(planners.findBySessionIdOrSessionIdStartingWith("session", "session#")).thenReturn(List.of(persisted));

        var restarted = new ConversationTaskProgressService(planners, traces, tasks);
        var restored = restarted.forMessages(7L, "session", List.of(current));
        assertEquals("completed", restored.get(2L).items().getFirst().status());
        assertEquals("OUTCOME_REVIEWED", restored.get(2L).reason());
        // The next process needs only the saved reply JSON; no current graph lookup.
        clearInvocations(planners, traces);
        assertEquals("completed", new ConversationTaskProgressService(planners, traces, tasks)
                .forMessages(7L, "session", List.of(current)).get(2L).items().getFirst().status());
        verifyNoInteractions(planners, traces);
    }

    @Test void conflictingCasRetriesWithoutMergingTheOldAnswerOrUsingAnotherTurn() {
        ChatTask current = card(2, "exec", "OUTCOME_UNKNOWN"); wire(current);
        when(tasks.compareAndSetTaskProgress(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(0, 1);
        assertNotNull(service.persistReviewedOutcome(7L, "session", graph(false, "CONFIRMED_APPLIED", "n1", reviewedAt)));
        verify(tasks, times(2)).compareAndSetTaskProgress(eq(2L), eq(7L), eq("session"), anyString(), anyString());
        verify(tasks, never()).save(any());
    }

    @Test void intermediateCancelledNodesUseTheTodoProjectionNumbering() {
        ChatTask current = card(2, "exec", "OUTCOME_UNKNOWN"); wire(current);
        var updated = service.persistReviewedOutcome(7L, "session", graph(true, "CONFIRMED_APPLIED", "n1", reviewedAt));
        assertNotNull(updated);
        assertEquals("提交表单", updated.progress().items().getFirst().content());
        assertEquals("completed", updated.progress().items().getFirst().status());
        assertEquals("pending", updated.progress().items().get(1).status());
    }

    @Test void mismatchedNamesOrDependenciesAndCompletedCardsAreNeverRewritten() {
        ChatTask current = card(2, "exec", "OUTCOME_UNKNOWN");
        current.setTaskProgressJson(new TaskProgressSnapshot("exec", 0, "FAILURE", "OUTCOME_UNKNOWN", List.of(
                new TaskProgressSnapshot.Item(1, "不同任务", "blocked", "", List.of()),
                new TaskProgressSnapshot.Item(2, "返回回执", "pending", "", List.of(1)))).toJson());
        wire(current);
        assertNull(service.persistReviewedOutcome(7L, "session", graph(false, "CONFIRMED_APPLIED", "n1", reviewedAt)));
        current.setTaskProgressJson(new TaskProgressSnapshot("exec", 0, "SUCCESS", "SUCCESS", List.of()).toJson());
        assertNull(service.persistReviewedOutcome(7L, "session", graph(false, "CONFIRMED_APPLIED", "n1", reviewedAt)));
        verify(tasks, never()).compareAndSetTaskProgress(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test void duplicateSavedItemIdsCannotHideAMissingNode() {
        ChatTask current = card(2, "exec", "OUTCOME_UNKNOWN");
        var duplicate = new TaskProgressSnapshot.Item(1, "提交表单", "blocked", "OUTCOME_UNKNOWN", List.of());
        current.setTaskProgressJson(new TaskProgressSnapshot("exec", 0, "FAILURE", "OUTCOME_UNKNOWN",
                List.of(duplicate, duplicate)).toJson());
        wire(current);
        assertNull(service.persistReviewedOutcome(7L, "session", graph(false, "CONFIRMED_APPLIED", "n1", reviewedAt)));
        verify(tasks, never()).compareAndSetTaskProgress(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test void anotherHistoricalUnknownCannotBeClearedByLaterGraphSuccess() {
        ChatTask current = card(2, "exec", "OUTCOME_UNKNOWN");
        current.setTaskProgressJson(new TaskProgressSnapshot("exec", 0, "FAILURE", "OUTCOME_UNKNOWN", List.of(
                new TaskProgressSnapshot.Item(1, "提交表单", "blocked", "OUTCOME_UNKNOWN", List.of()),
                new TaskProgressSnapshot.Item(2, "返回回执", "blocked", "OUTCOME_UNKNOWN", List.of(1)))).toJson());
        wire(current);
        var updated = service.persistReviewedOutcome(7L, "session", graph(false, "CONFIRMED_APPLIED", "n1", reviewedAt));
        assertEquals("completed", updated.progress().items().getFirst().status());
        assertEquals("blocked", updated.progress().items().get(1).status());
        assertEquals("FAILURE", updated.progress().status());
        assertEquals("OUTCOME_UNKNOWN", updated.progress().reason());
    }

    @Test void anOldReviewCannotUnlockANewerUnknownTurnOrAnotherOwner() {
        ChatTask current = card(2, "exec", "OUTCOME_UNKNOWN");
        current.setCreatedAt(created.plusMinutes(3)); wire(current);
        assertNull(service.persistReviewedOutcome(7L, "session", graph(false, "CONFIRMED_APPLIED", "n1", reviewedAt)));
        current.setCreatedAt(created); current.setUserId(8L);
        assertNull(service.persistReviewedOutcome(7L, "session", graph(false, "CONFIRMED_APPLIED", "n1", reviewedAt)));
        current.setUserId(7L); current.setDeleted(true);
        assertNull(service.persistReviewedOutcome(7L, "session", graph(false, "CONFIRMED_APPLIED", "n1", reviewedAt)));
        verify(tasks, never()).compareAndSetTaskProgress(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test void oldJsonWithoutReceiptsRemainsReadable() {
        var snapshot = TaskProgressSnapshot.fromJson("""
                {"executionId":"exec","taskId":0,"status":"FAILURE","reason":"OUTCOME_UNKNOWN","items":[]}
                """);
        assertNotNull(snapshot); assertTrue(snapshot.outcomeReviewReceipts().isEmpty());
        assertEquals(snapshot, TaskProgressSnapshot.fromJson(snapshot.toJson()));
    }

    void wire(ChatTask... rows) {
        when(tasks.findByUserIdAndSessionIdAndDeletedFalseOrderByIdDesc(eq(7L), eq("session"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(rows)));
        when(tasks.compareAndSetTaskProgress(anyLong(), anyLong(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            for (ChatTask task : rows) if (Objects.equals(task.getId(), call.getArgument(0))
                    && Objects.equals(task.getTaskProgressJson(), call.getArgument(3))) {
                task.setTaskProgressJson(call.getArgument(4)); return 1;
            }
            return 0;
        });
    }
    ChatTask card(long id, String execution, String reason) {
        ChatTask task = new ChatTask(); task.setId(id); task.setUserId(7L); task.setSessionId("session");
        task.setQuestion("测试"); task.setAnswer("old answer"); task.setImages("upload.png"); task.setCreatedAt(created);
        task.setTaskProgressJson(new TaskProgressSnapshot(execution, 0, "FAILURE", reason, List.of(
                new TaskProgressSnapshot.Item(1, "提交表单", "blocked", "OUTCOME_UNKNOWN", List.of()),
                new TaskProgressSnapshot.Item(2, "返回回执", "pending", "", List.of(1)))).toJson());
        return task;
    }
    StateSnapshot graph(boolean cancelled, String decision, String node, Instant at) {
        List<TaskNode> nodes = new ArrayList<>();
        if (cancelled) nodes.add(node("cancelled", "取消步骤", TaskNodeStatus.CANCELLED, List.of()));
        nodes.add(node("n1", "提交表单", TaskNodeStatus.SUCCESS, List.of()));
        nodes.add(node("n2", "返回回执", TaskNodeStatus.SUCCESS, List.of("n1")));
        return new StateSnapshot(3, "session", "exec", new Goal("g", "提交", "", Map.of(), List.of(), List.of()),
                new TaskGraph(nodes), Map.of("outcomeReconciliations", List.of(Map.of(
                    "reviewerId", 7L, "nodeId", node, "previousVersion", 1, "previousPlanVersion", 1,
                    "decision", decision, "at", at.toString()))), Map.of(), List.of(), 0);
    }
    TaskNode node(String id, String name, TaskNodeStatus status, List<String> dependencies) {
        return new TaskNode(id, name, "general", dependencies, List.of(), List.of(), status, 1,
                DoneWhen.note(), "", "", 0, "");
    }
}
