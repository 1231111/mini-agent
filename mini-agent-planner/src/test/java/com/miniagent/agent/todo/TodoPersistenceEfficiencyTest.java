package com.miniagent.agent.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.planner.DoneWhen;
import com.miniagent.agent.planner.ProposalTurnPolicy;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.trace.TraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Persistence-port counters exercise actual state transitions and the registered tool handler. */
class TodoPersistenceEfficiencyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "todo-efficiency";
    @TempDir Path workspace;
    private TaskTodoStore store;
    private CountingPersistence persistence;
    private SessionEventCenter events;
    private TraceRecorder trace;
    private ToolRegistry registry;

    @BeforeEach void setup() {
        persistence = new CountingPersistence();
        events = mock(SessionEventCenter.class);
        trace = mock(TraceRecorder.class);
        store = new TaskTodoStore(workspace.toString());
        store.setPersistence(persistence);
        store.setEventCenter(events);
        registry = new ToolRegistry();
        TodoTool tool = new TodoTool();
        ReflectionTestUtils.setField(tool, "todoStore", store);
        ReflectionTestUtils.setField(tool, "toolRegistry", registry);
        ReflectionTestUtils.setField(tool, "traceRecorder", trace);
        tool.register();
        TaskTodoContext.set(SESSION);
        PermissionContext.force(SESSION, PermissionMode.DEFAULT, ConfirmPolicy.DANGEROUS, true);
    }

    @AfterEach void clearContext() {
        TaskTodoContext.clear();
        LoopTurnContext.clear();
        PermissionContext.clear();
    }

    @Test void repeatedUnchangedFocusReadsFreshStateWithoutWritesOrUiEvents() {
        seed(item(1, "Collect evidence", "in_progress", List.of()),
                item(2, "Summarize", "pending", List.of(1)));
        for (int i = 0; i < 8; i++) assertEquals(1, store.currentSubGoalDetail(SESSION).id());
        assertEquals(8, persistence.loads, "Each request still checks authoritative persistence");
        assertEquals(0, persistence.saves);
        verifyNoInteractions(events);
    }

    @Test void realPromotionAndDependencyRepairPersistAndPublishExactlyOnce() {
        seed(item(1, "Collect evidence", "pending", List.of()),
                item(2, "Summarize", "in_progress", List.of(1)));
        assertEquals(1, store.currentSubGoalDetail(SESSION).id());
        assertEquals(1, persistence.saves);
        List<TaskTodoStore.TodoItem> current = store.get(SESSION);
        assertEquals(TaskTodoStore.Status.in_progress, current.get(0).status());
        assertEquals(TaskTodoStore.Status.pending, current.get(1).status());
        for (int i = 0; i < 5; i++) assertEquals(1, store.currentSubGoalDetail(SESSION).id());
        assertEquals(1, persistence.saves);
        verify(events, times(1)).publishTodo(eq(SESSION), anyList());
    }

    @Test void dangerousPendingPromotionStillWaitsForUserAndWritesOnlyTheTransition() {
        seed(item(1, "部署到生产环境", "pending", List.of()));
        assertTrue(store.currentSubGoalDetail(SESSION).awaitingConfirm());
        assertTrue(store.currentSubGoalDetail(SESSION).awaitingConfirm());
        assertEquals(TaskTodoStore.Status.awaiting_confirm, store.get(SESSION).getFirst().status());
        assertEquals(1, persistence.saves);
        verify(events, times(1)).publishTodo(eq(SESSION), anyList());
    }

    @Test void terminalAndBlockedFocusReadsDoNotWriteOrAdvance() {
        seed(item(1, "Completed task", "completed", List.of()),
                item(2, "Blocked task", "blocked", List.of()));
        assertNull(store.currentSubGoalDetail(SESSION));
        assertNull(store.currentSubGoalDetail(SESSION));
        assertEquals(0, persistence.saves);
        verifyNoInteractions(events);
    }

    @Test void anotherWriterChangesTheNextReadWithoutAnyCrossRequestCache() throws Exception {
        seed(item(1, "First version", "in_progress", List.of()));
        assertEquals("First version", store.currentSubGoalDetail(SESSION).text());
        persistence.rows.put(SESSION, new SessionTodoPersistence.State(JSON.writeValueAsString(List.of(
                new TaskTodoStore.TodoItem(1, "First version", TaskTodoStore.Status.blocked, ""),
                new TaskTodoStore.TodoItem(2, "Remote replacement", TaskTodoStore.Status.in_progress, ""))), null));
        TaskTodoStore.SubGoal fresh = store.currentSubGoalDetail(SESSION);
        assertEquals(2, fresh.id());
        assertEquals("Remote replacement", fresh.text());
        assertEquals(2, persistence.loads);
        assertEquals(0, persistence.saves);
        verifyNoInteractions(events);
    }

    @Test void explicitUiReplayStillPublishesAnUnchangedPlanWithoutWritingIt() {
        seed(item(1, "Collect evidence", "in_progress", List.of()));
        store.currentSubGoalDetail(SESSION);
        store.publishCurrentState(SESSION);
        assertEquals(0, persistence.saves);
        verify(events, times(1)).publishTodo(eq(SESSION), anyList());
    }

    @Test void submitReceiptAndActiveTraceUseTheSameSavedSnapshotWithoutReloading() throws Exception {
        assertReceiptSnapshot("submit", 3);
    }

    @Test void legacyCompletionReceiptRetainsCompatibilityAndAvoidsReloadsAfterSubmission() throws Exception {
        JsonNode response = assertReceiptSnapshot("update", 4);
        assertEquals("completed", response.path("requestedStatus").asText());
    }

    @Test void aNewSubmitRevalidatesRemoteConfirmationInsteadOfReusingThePreviousReceipt() throws Exception {
        seed(item(1, "Collect evidence", "in_progress", List.of()));
        assertTrue(execute("submit").isSuccess());
        List<TaskTodoStore.TodoItem> actual = store.get(SESSION);
        TaskTodoStore.TodoItem first = actual.getFirst();
        persistence.rows.put(SESSION, new SessionTodoPersistence.State(JSON.writeValueAsString(List.of(Map.of(
                "id", first.id(), "content", first.content(), "status", "awaiting_confirm",
                "doneWhen", first.doneWhen(), "dependsOn", first.dependsOn()))), null));
        persistence.reset();
        reset(events);
        ToolResult blocked = execute("submit");
        assertFalse(blocked.isSuccess());
        assertTrue(blocked.legacyText().contains("确认"));
        assertTrue(persistence.loads > 0);
        assertEquals(0, persistence.saves);
        verifyNoInteractions(events);
    }

    private JsonNode assertReceiptSnapshot(String action, int expectedLoads) throws Exception {
        seed(item(1, "Collect evidence", "in_progress", List.of()),
                item(2, "Summarize", "pending", List.of(1)));
        LoopTurnContext.set(new ProposalTurnPolicy(List.of("todo"), true, "Collect evidence", Set.of(1), 30));
        when(trace.isActive()).thenReturn(true);
        // A post-save read might see another writer's state; receipts and trace must not perform it.
        persistence.rejectReadsAfterSave = true;
        ToolResult result = execute(action);
        assertTrue(result.isSuccess(), result.legacyText());
        assertEquals(expectedLoads, persistence.loads);
        assertEquals(1, persistence.saves);
        verify(events, times(1)).publishTodo(eq(SESSION), anyList());
        JsonNode response = JSON.readTree(result.legacyText());
        assertEquals(action, response.path("action").asText());
        assertEquals("in_progress", response.path("status").asText());
        assertTrue(response.path("pendingValidation").asBoolean());
        assertEquals("planner", response.path("completionOwner").asText());
        assertEquals(2, response.path("stats").path("total").asInt());
        assertEquals(0, response.path("stats").path("completed").asInt());
        assertEquals("Actual collected evidence", response.path("items").get(0).path("evidence").asText());
        assertTrue(response.path("todo").asText().contains("Actual collected evidence"));
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(trace).recordNode(eq("TASK_UPDATE"), content.capture(), eq("SUCCESS"), eq(0L));
        JsonNode traced = JSON.readTree(content.getValue());
        assertEquals(response.path("items"), traced.path("items"));
        assertEquals(response.path("stats"), traced.path("stats"));
        assertEquals(response.path("todo"), traced.path("render"));
        return response;
    }

    private ToolResult execute(String action) throws Exception {
        return registry.executeResult("todo", JSON.writeValueAsString(Map.of("action", action,
                "id", 1, "status", "completed", "evidence", "Actual collected evidence")));
    }

    @SafeVarargs private void seed(Map<String, Object>... items) {
        store.overwrite(SESSION, List.of(items));
        persistence.reset();
        reset(events);
    }

    private static Map<String, Object> item(int id, String content, String status, List<Integer> dependencies) {
        return Map.of("id", id, "content", content, "status", status,
                "done_when", DoneWhen.note(), "depends_on", dependencies);
    }

    private static final class CountingPersistence implements SessionTodoPersistence {
        final Map<String, State> rows = new HashMap<>();
        int loads;
        int saves;
        boolean rejectReadsAfterSave;
        public State load(String sessionId) {
            if (rejectReadsAfterSave && saves > 0) throw new AssertionError("Receipt reloaded after mutation");
            loads++;
            return rows.getOrDefault(sessionId, new State("[]", null));
        }
        public void save(String sessionId, String activeJson, String suspendedJson) {
            saves++;
            rows.put(sessionId, new State(activeJson, suspendedJson));
        }
        public void delete(String sessionId) { rows.remove(sessionId); }
        void reset() { loads = saves = 0; rejectReadsAfterSave = false; }
    }
}
