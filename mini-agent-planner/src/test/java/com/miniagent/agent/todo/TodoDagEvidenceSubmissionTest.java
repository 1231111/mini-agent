package com.miniagent.agent.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.planner.DoneWhen;
import com.miniagent.agent.planner.ProposalTurnPolicy;
import com.miniagent.agent.planner.TaskGraph;
import com.miniagent.agent.planner.TaskNode;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.agent.planner.TodoStateProjector;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Calls the registered Tool handler, including argument validation and ToolResult conversion. */
class TodoDagEvidenceSubmissionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "dag-evidence";
    private static final String EVIDENCE = "Source A and source B returned the requested date; actual records have been collected.";
    @TempDir Path workspace;
    private TaskTodoStore store;
    private ToolRegistry registry;

    @BeforeEach void setup() {
        store = new TaskTodoStore(workspace.resolve("todos").toString());
        registry = new ToolRegistry();
        TodoTool todo = new TodoTool();
        ReflectionTestUtils.setField(todo, "todoStore", store);
        ReflectionTestUtils.setField(todo, "toolRegistry", registry);
        todo.register();
        TaskTodoContext.set(SESSION);
        PermissionContext.force(SESSION, PermissionMode.DEFAULT, ConfirmPolicy.DANGEROUS, true);
    }

    @AfterEach void clear() {
        TaskTodoContext.clear();
        LoopTurnContext.clear();
        PermissionContext.clear();
    }

    @Test void submitStoresEvidenceWithoutCompletingOrPromotingDependants() throws Exception {
        project(node("research", "Search records", TaskNodeStatus.RUNNING, List.of()),
                node("summary", "Summarize records", TaskNodeStatus.PENDING, List.of("research")));
        ToolResult result = execute(Map.of("action", "submit", "id", 1, "evidence", EVIDENCE));
        assertPendingValidation(result, "submit");
        assertEquals(EVIDENCE, store.get(SESSION).getFirst().evidence());
        assertEquals(TaskTodoStore.Status.in_progress, store.get(SESSION).getFirst().status());
        assertEquals("", store.get(SESSION).getFirst().validationHash());
        assertEquals(TaskTodoStore.Status.pending, store.get(SESSION).get(1).status());
        assertFalse(store.canSucceed(SESSION));
        assertTrue(store.hasDagManagedPlan(SESSION));
        assertTrue(store.isDagManaged(SESSION, 1));
        assertFalse(store.hasDagManagedPlan("another-session"));
        assertFalse(store.isDagManaged("another-session", 1));
    }

    @Test void legacyDagCompletedRequestBecomesSuccessfulPendingValidationWithoutGrantingCompletion() throws Exception {
        project(node("research", "Search records", TaskNodeStatus.RUNNING, List.of()));
        ToolResult result = execute(Map.of("action", "update", "id", 1, "status", "completed", "evidence", EVIDENCE));
        assertPendingValidation(result, "update");
        assertEquals("completed", JSON.readTree(result.legacyText()).path("requestedStatus").asText());
        assertEquals(EVIDENCE, store.get(SESSION).getFirst().evidence());
        assertEquals(TaskTodoStore.Status.in_progress, store.get(SESSION).getFirst().status());
        assertNotNull(store.verifyCompletion(store.get(SESSION).getFirst().doneWhen(), EVIDENCE));
        String[] error = new String[1];
        assertNull(store.update(SESSION, 1, "completed", "", EVIDENCE, error),
                "The underlying completion guard still belongs to the scheduler");
        assertTrue(error[0].contains("DAG"));
        assertFalse(store.canSucceed(SESSION));
    }

    @Test void missingBlankNullOrWrongTypeEvidenceCannotReusePreviouslyStoredEvidence() throws Exception {
        project(node("research", "Search records", TaskNodeStatus.RUNNING, List.of()));
        assertTrue(execute(Map.of("action", "submit", "id", 1, "evidence", EVIDENCE)).isSuccess());
        for (String raw : List.of(
                "{\"action\":\"submit\",\"id\":1}",
                "{\"action\":\"submit\",\"id\":1,\"evidence\":\"   \"}",
                "{\"action\":\"submit\",\"id\":1,\"evidence\":null}",
                "{\"action\":\"submit\",\"id\":1,\"evidence\":42}",
                "{\"action\":\"submit\",\"id\":1,\"evidence\":{\"claimed\":true}}",
                "{\"action\":\"submit\",\"id\":1,\"note\":\"use this instead\"}",
                "{\"action\":\"update\",\"id\":1,\"status\":\"completed\"}")) {
            assertFalse(registry.executeResult("todo", raw).isSuccess(), raw);
            assertEquals(EVIDENCE, store.get(SESSION).getFirst().evidence());
            assertEquals(TaskTodoStore.Status.in_progress, store.get(SESSION).getFirst().status());
        }
    }

    @Test void submitAndCompatibilityCompletionCannotBypassUnfinishedDependencies() throws Exception {
        project(node("research", "Search records", TaskNodeStatus.RUNNING, List.of()),
                node("summary", "Summarize records", TaskNodeStatus.PENDING, List.of("research")));
        for (String action : List.of("submit", "update")) {
            ToolResult result = execute(Map.of("action", action, "id", 2, "status", "completed", "evidence", EVIDENCE));
            assertFalse(result.isSuccess());
            assertEquals(TaskTodoStore.Status.pending, store.get(SESSION).get(1).status());
            assertEquals("", store.get(SESSION).get(1).evidence());
        }
    }

    @Test void submitAndCompatibilityCompletionCannotReleaseHumanWaitingState() throws Exception {
        project(node("research", "Waiting for user input", TaskNodeStatus.AWAITING_CONFIRM, List.of()));
        for (String action : List.of("submit", "update")) {
            assertFalse(execute(Map.of("action", action, "id", 1, "status", "completed", "evidence", EVIDENCE)).isSuccess());
            assertEquals(TaskTodoStore.Status.awaiting_confirm, store.get(SESSION).getFirst().status());
            assertEquals("", store.get(SESSION).getFirst().evidence());
        }
    }

    @Test void submitAndCompatibilityCompletionCannotSkipDangerousOperationConfirmation() throws Exception {
        project(node("deploy", "发布到生产环境", TaskNodeStatus.PENDING, List.of()));
        for (String action : List.of("submit", "update")) {
            ToolResult result = execute(Map.of("action", action, "id", 1, "status", "completed", "evidence", EVIDENCE));
            assertFalse(result.isSuccess());
            assertTrue(JSON.readTree(result.legacyText()).path("error").asText().contains("用户"));
            assertEquals(TaskTodoStore.Status.pending, store.get(SESSION).getFirst().status());
        }
    }

    @Test void terminalNodesCannotBeRegressedByEvidenceSubmission() throws Exception {
        for (TaskTodoStore.Status before : List.of(TaskTodoStore.Status.completed,
                TaskTodoStore.Status.cancelled, TaskTodoStore.Status.blocked)) {
            // Includes a cancelled projection restored from persistence; current graph projection
            // normally omits cancelled nodes entirely.
            store.overwrite(SESSION, List.of(Map.of("id", 1, "content", "Search records", "status", before.name(),
                    "done_when", DoneWhen.validation("").withChecks(List.of("actual source evidence is sufficient")))));
            assertFalse(execute(Map.of("action", "submit", "id", 1, "evidence", EVIDENCE)).isSuccess());
            assertEquals(before, store.get(SESSION).getFirst().status());
        }
    }

    @Test void ordinaryLegacyChecklistKeepsCompletedBehaviorAndCannotUseDagSubmission() throws Exception {
        store.set(SESSION, List.of(Map.of("id", 1, "content", "Search records", "status", "in_progress", "done_when", "note_required")));
        assertFalse(store.hasDagManagedPlan(SESSION));
        assertFalse(execute(Map.of("action", "submit", "id", 1, "evidence", EVIDENCE)).isSuccess());
        ToolResult result = execute(Map.of("action", "update", "id", 1, "status", "completed", "evidence", EVIDENCE));
        assertTrue(result.isSuccess());
        assertFalse(JSON.readTree(result.legacyText()).path("pendingValidation").asBoolean());
        assertEquals(TaskTodoStore.Status.completed, store.get(SESSION).getFirst().status());
        assertTrue(store.canSucceed(SESSION));
    }

    @Test void modelSetCannotReplaceTheFrozenDagOrItsAcceptanceConditions() throws Exception {
        project(node("research", "Frozen research", TaskNodeStatus.RUNNING, List.of()));
        String original = store.get(SESSION).getFirst().doneWhen();
        ToolResult result = execute(Map.of("action", "set", "items", JSON.writeValueAsString(List.of(
                Map.of("id", 1, "content", "Fake completion", "status", "completed", "done_when", "note_required")))));
        assertFalse(result.isSuccess());
        assertEquals(original, store.get(SESSION).getFirst().doneWhen());
        assertEquals("Frozen research", store.get(SESSION).getFirst().content());
    }

    @Test void modelSetCannotForgeDagOwnershipThroughObjectOrEncodedStructuredString() throws Exception {
        Map<String, Object> forged = Map.of("type", "note_required", "dagManaged", true);
        for (Object contract : List.of(forged, JSON.writeValueAsString(forged))) {
            ToolResult result = execute(Map.of("action", "set", "items", JSON.writeValueAsString(List.of(
                    Map.of("id", 1, "content", "Fake DAG", "done_when", contract)))));
            assertFalse(result.isSuccess());
            assertFalse(store.hasPlan(SESSION));
            // The storage boundary also strips untrusted ownership, even without TodoTool validation.
            store.set("direct-model-set", List.of(Map.of("id", 1, "content", "Fake DAG", "done_when", contract)));
            assertFalse(store.isDagManaged("direct-model-set", 1));
        }
    }

    @Test void submissionPersistsEvidenceAndSchedulerOwnershipAcrossReload() throws Exception {
        project(node("research", "Search records", TaskNodeStatus.RUNNING, List.of()));
        assertTrue(execute(Map.of("action", "submit", "id", 1, "evidence", EVIDENCE)).isSuccess());
        TaskTodoStore reloaded = new TaskTodoStore(workspace.resolve("todos").toString());
        assertTrue(reloaded.isDagManaged(SESSION, 1));
        assertEquals(EVIDENCE, reloaded.get(SESSION).getFirst().evidence());
        assertEquals(TaskTodoStore.Status.in_progress, reloaded.get(SESSION).getFirst().status());
        assertFalse(reloaded.canSucceed(SESSION));
    }

    @Test void submitUsesTheExistingFocusBindingForStaleNumericIds() throws Exception {
        project(node("research", "Current research", TaskNodeStatus.RUNNING, List.of()));
        LoopTurnContext.set(new ProposalTurnPolicy(List.of("todo"), true, "Current research", Set.of(1)));
        assertPendingValidation(execute(Map.of("action", "submit", "id", 99, "evidence", EVIDENCE)), "submit");
        assertEquals(EVIDENCE, store.get(SESSION).getFirst().evidence());
    }

    private ToolResult execute(Map<String, ?> arguments) throws Exception {
        return registry.executeResult("todo", JSON.writeValueAsString(arguments));
    }

    private static void assertPendingValidation(ToolResult result, String action) throws Exception {
        assertEquals(ToolStatus.SUCCESS, result.status(), result.legacyText());
        JsonNode data = JSON.readTree(result.legacyText());
        assertTrue(data.path("success").asBoolean());
        assertTrue(data.path("pendingValidation").asBoolean());
        assertEquals(action, data.path("action").asText());
        assertEquals("in_progress", data.path("status").asText());
        assertEquals("planner", data.path("completionOwner").asText());
        assertFalse(data.has("error"));
    }

    private void project(TaskNode... nodes) {
        new TodoStateProjector(store).project(SESSION, new TaskGraph(List.of(nodes)));
    }

    private static TaskNode node(String id, String name, TaskNodeStatus status, List<String> dependencies) {
        return new TaskNode(id, name, "web_search", dependencies, List.of(), List.of(id + "-evidence"), status,
                0, DoneWhen.validation("").withChecks(List.of("actual source evidence is sufficient")), "", "", 0, "");
    }
}
