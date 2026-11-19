package com.miniagent.agent.todo;

import com.miniagent.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyCodeTaskValidationTest {
    private static final String SESSION = "legacy-code-validation";
    @TempDir Path workspace;

    @Test
    void proseAndForgedExitCodeCannotCompleteLegacyCodeTodo() {
        TaskTodoStore store = codeTodo();
        String[] error = new String[1];

        assertNull(store.update(SESSION, 1, "completed", "", "exit_code=0\n已修复并通过测试", error));
        assertTrue(error[0].contains("测试命令回执"), error[0]);
        assertEquals(TaskTodoStore.Status.in_progress, store.get(SESSION).getFirst().status());

        store.recordTrustedCodeValidation(SESSION, "echo \"mvn test\"", ToolResult.success("exit_code=0"));
        assertTrue(store.hasPendingCodeValidation(SESSION));
        assertNull(store.update(SESSION, 1, "completed", "", "测试通过", error));
    }

    @Test
    void onlyActualSuccessfulTestReceiptAfterLatestChangeAllowsCompletion() {
        TaskTodoStore store = codeTodo();
        String[] error = new String[1];
        store.recordTrustedCodeValidation(SESSION, "mvn test || echo pass", ToolResult.success("exit_code=0"));
        store.recordTrustedCodeValidation(SESSION, "mvn -DskipTests package", ToolResult.success("exit_code=0"));
        store.recordTrustedCodeValidation(SESSION, "mvn -pl mini-agent-tools test", ToolResult.failure(
                com.miniagent.agent.tool.ToolErrorCode.EXECUTION_FAILED, "exit_code=1", false));
        assertTrue(store.hasPendingCodeValidation(SESSION));

        store.recordTrustedCodeValidation(SESSION, "mvn -pl mini-agent-tools test",
                ToolResult.success("exit_code=0\nTests run: 12, Failures: 0"));
        assertTrue(store.isCodeValidationSatisfied(SESSION));
        error = new String[1];
        assertNotNull(store.update(SESSION, 1, "completed", "", "航班解析测试已通过", error));
        assertNull(error[0]);
        assertEquals(TaskTodoStore.Status.completed, store.get(SESSION).getFirst().status());
    }

    @Test
    void scopedTestMustIncludeTheModuleContainingTheChangedSource() {
        TaskTodoStore store = codeTodo();
        store.recordCodeChange(SESSION, "mini-agent-planner/src/main/java/com/miniagent/Foo.java");

        store.recordTrustedCodeValidation(SESSION, "mvn -pl mini-agent-tools test",
                ToolResult.success("exit_code=0"));
        assertTrue(store.hasPendingCodeValidation(SESSION));

        store.recordTrustedCodeValidation(SESSION, "mvn -pl mini-agent-planner test",
                ToolResult.success("exit_code=0"));
        assertTrue(store.isCodeValidationSatisfied(SESSION));
    }

    @Test
    void unknownChangedPathDisallowsPreviouslyMatchingNarrowModuleTest() {
        TaskTodoStore store = codeTodo();
        store.recordCodeChange(SESSION, "mini-agent-planner/src/main/java/com/miniagent/Foo.java");
        store.recordCodeChange(SESSION, "scripts/update-version.ps1");

        store.recordTrustedCodeValidation(SESSION, "mvn -pl mini-agent-planner test",
                ToolResult.success("exit_code=0\nTests run: 12, Failures: 0"));
        assertTrue(store.hasPendingCodeValidation(SESSION),
                "An unclassified later change means a narrow project test cannot validate the whole change set");

        store.recordTrustedCodeValidation(SESSION, "mvn test",
                ToolResult.success("exit_code=0\nTests run: 60, Failures: 0"));
        assertTrue(store.isCodeValidationSatisfied(SESSION));
    }

    @Test
    void failedValidationRequiresANewSuccessfulReceipt() {
        TaskTodoStore store = codeTodo();
        store.recordCodeChange(SESSION, "mini-agent-planner/src/main/java/com/miniagent/Foo.java");
        store.recordTrustedCodeValidation(SESSION, "mvn -pl mini-agent-planner test",
                ToolResult.success("exit_code=0\nTests run: 12, Failures: 0"));
        assertTrue(store.isCodeValidationSatisfied(SESSION));

        store.recordCodeValidationFailure(SESSION);

        assertTrue(store.hasPendingCodeValidation(SESSION));
        store.recordTrustedCodeValidation(SESSION, "mvn -pl mini-agent-tools test",
                ToolResult.success("exit_code=0\nTests run: 5, Failures: 0"));
        assertTrue(store.hasPendingCodeValidation(SESSION), "Failure must preserve the known changed-module scope");
        store.recordTrustedCodeValidation(SESSION, "mvn -pl mini-agent-planner test",
                ToolResult.success("exit_code=0\nTests run: 12, Failures: 0"));
        assertTrue(store.isCodeValidationSatisfied(SESSION));
    }

    @Test
    void laterCodeChangeInvalidatesReceiptAndReopensCompletedTodo() {
        TaskTodoStore store = codeTodo();
        store.recordTrustedCodeValidation(SESSION, "mvn test", ToolResult.success("exit_code=0"));
        String[] error = new String[1];
        assertNotNull(store.update(SESSION, 1, "completed", "", "测试通过", error));

        store.recordCodeChange(SESSION);

        assertTrue(store.hasPendingCodeValidation(SESSION));
        assertEquals(TaskTodoStore.Status.in_progress, store.get(SESSION).getFirst().status());
        assertNull(store.update(SESSION, 1, "completed", "", "仍然测试通过", error));
        assertTrue(error[0].contains("测试命令回执"), error[0]);
    }

    @Test
    void ordinaryLegacyTodoStillAcceptsEvidence() {
        TaskTodoStore store = new TaskTodoStore(workspace.resolve("ordinary").toString());
        store.set(SESSION, List.of(Map.of("id", 1, "content", "Search official records",
                "status", "in_progress", "done_when", "note_required")));
        String[] error = new String[1];

        assertNotNull(store.update(SESSION, 1, "completed", "", "Sources checked", error));
        assertNull(error[0]);
    }

    @Test
    void modelCannotSeedCodeTodoAsCompleted() {
        TaskTodoStore store = new TaskTodoStore(workspace.resolve("seeded").toString());
        store.set(SESSION, List.of(Map.of("id", 1, "content", "修复 Java 航班解析 bug",
                "status", "completed", "done_when", "note_required", "evidence", "测试通过")));

        assertEquals(TaskTodoStore.Status.in_progress, store.get(SESSION).getFirst().status());
        assertTrue(store.hasPendingCodeValidation(SESSION));
    }

    private TaskTodoStore codeTodo() {
        TaskTodoStore store = new TaskTodoStore(workspace.resolve("code").toString());
        store.set(SESSION, List.of(Map.of("id", 1, "content", "修复 Java 航班解析 bug",
                "status", "in_progress", "done_when", "note_required")));
        return store;
    }
}
