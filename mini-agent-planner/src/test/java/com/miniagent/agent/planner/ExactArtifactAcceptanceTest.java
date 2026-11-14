package com.miniagent.agent.planner;

import com.miniagent.agent.todo.AcceptanceEvidenceFiles;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.todo.TodoSemanticValidator;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExactArtifactAcceptanceTest {
    @TempDir Path workspace;
    @BeforeEach void bindWorkspace() { WorkspaceContext.setRootOverride(workspace); }
    @AfterEach void clearWorkspace() { WorkspaceContext.clearRootOverride(); WorkspaceContext.clearTaskOverride(); }

    @Test void evidenceCannotSubstituteAnUnrelatedExistingFileForAMissingFrozenArtifact() throws Exception {
        Path unrelated = Files.writeString(workspace.resolve("existing.md"), "Existing reference document. ".repeat(5));
        String expected = "missing-generated.html";
        assertFalse(TodoSemanticValidator.validate("生成网页", "file_exists:" + expected, unrelated.toString()).ok());
        assertNotNull(new TaskTodoStore(workspace.resolve(".todos").toString()).verifyCompletion(
                "file_exists:" + expected, unrelated.toString()));
        TaskNode node = node(expected);
        assertFalse(evaluator().evaluate(node, ToolResult.success(unrelated.toString()), unrelated.toString()).ok());
        assertFalse(NodeOutputBinder.bind(node, unrelated.toString(), List.of(), "done").complete(node));
    }

    @Test void recursiveSameNamedFilesCannotSatisfyRelativeOrAbsoluteDeclaredPaths() throws Exception {
        Path archive = Files.createDirectory(workspace.resolve("archive"));
        Path unrelated = Files.writeString(archive.resolve("report.html"), "<html>old unrelated report</html>");
        for (String expected : List.of("report.html", "reports/report.html", workspace.resolve("missing/report.html").toString())) {
            assertFalse(evaluator().evaluate(node(expected), ToolResult.success(unrelated.toString()), unrelated.toString()).ok(), expected);
            assertNull(AcceptanceEvidenceFiles.resolveDeclaredFile(expected), expected);
        }
    }

    @Test void declaredDirectoriesSpacesAndWorkspaceAliasesResolveWithoutChangingTheContract() throws Exception {
        Path directory = Files.createDirectories(workspace.resolve("reports"));
        Path actual = Files.writeString(directory.resolve("sales report.html"), "<html>actual sales report</html>");
        for (String declared : List.of("reports/sales report.html", "workspace/reports/sales report.html", actual.toString())) {
            assertTrue(evaluator().evaluate(node(declared), ToolResult.success("write receipt"), "write receipt").ok(), declared);
            assertEquals(actual.toRealPath(), AcceptanceEvidenceFiles.resolveDeclaredFile(declared).toRealPath());
        }
    }

    @Test void newArtifactValidationUsesTheCompleteSha256Digest() throws Exception {
        Path file = Files.writeString(workspace.resolve("page.html"), "<html>actual generated artifact</html>");
        TodoSemanticValidator.Result validated = TodoSemanticValidator.validate("生成网页", "file_exists:" + file, file.toString());
        assertTrue(validated.ok());
        assertEquals(64, validated.contentHash().length());
        assertNotNull(AcceptanceEvidenceFiles.readText(file.toString(), validated.contentHash()));
    }

    @Test void legacyPersistedHashPrefixesStillValidateDependenciesAndDetectArtifactChanges() throws Exception {
        Path file = Files.writeString(workspace.resolve("page.html"), "<html>actual generated artifact</html>");
        String oldHash = TodoSemanticValidator.validate("生成网页", "file_exists:" + file, file.toString()).contentHash().substring(0, 16);
        TaskTodoStore store = new TaskTodoStore(workspace.resolve(".todos").toString());
        store.set("s", List.of(
                java.util.Map.of("id", 1, "content", "生成网页", "status", "completed", "done_when", "file_exists:" + file,
                        "evidence", file.toString(), "validation_hash", oldHash, "depends_on", List.of()),
                java.util.Map.of("id", 2, "content", "交付网页", "done_when", "note_required", "depends_on", List.of(1))));
        String[] error = new String[1];
        assertNotNull(store.update("s", 2, "in_progress", "", "", error));
        Files.writeString(file, "<html>artifact changed since validation</html>");
        assertNull(store.update("s", 2, "completed", "交付", "实际交付", error));
        assertTrue(error[0].contains("指纹不匹配"));
    }

    @Test void taskScopedBareNamesFollowTheWriterAndCannotFallBackToTheRootOrAnotherTask() throws Exception {
        Files.writeString(workspace.resolve("page.html"), "<html>unrelated root page</html>");
        Files.createDirectories(workspace.resolve("out"));
        WorkspaceContext.setTaskOverride("out");
        assertNull(AcceptanceEvidenceFiles.resolveDeclaredFile("page.html"));
        Path actual = Files.writeString(BuiltinTools.resolveOutputPath("page.html"), "<html>actual task output</html>");
        assertEquals(actual, AcceptanceEvidenceFiles.resolveDeclaredFile("page.html"));
        assertTrue(evaluator().evaluate(node("page.html"), ToolResult.success("write receipt"), "write receipt").ok());
        assertEquals(workspace.resolve("page.html"), AcceptanceEvidenceFiles.resolveDeclaredFile("workspace/page.html"));
    }

    private static StepEvaluator evaluator() { return new StepEvaluator(new PlannerProperties(), new PlannerMetrics(), null); }
    private static TaskNode node(String path) {
        return new TaskNode("n1", "生成网页", "file_write", List.of(), List.of(), List.of("generated artifact"),
                TaskNodeStatus.PENDING, 1, DoneWhen.file(path), "", "", 0, "");
    }
}
