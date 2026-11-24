package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.todo.LlmJudgeTodoValidator;
import com.miniagent.agent.tool.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class StepEvaluatorFileSnapshotTest {

    @TempDir
    Path workspace;

    @AfterEach
    void clearWorkspaceOverride() {
        WorkspaceContext.clearAll();
    }

    @Test
    void semanticJudgeReceivesVerifiedByteFactsFromTheSameStableFileSnapshot() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Files.writeString(workspace.resolve("fixture.txt"), "hello from dag", StandardCharsets.UTF_8);
        LlmJudgeTodoValidator judge = mock(LlmJudgeTodoValidator.class);
        when(judge.validate(any(), anyString())).thenReturn(null);
        StepEvaluator evaluator = new StepEvaluator(new PlannerProperties(), new PlannerMetrics(), judge);
        TaskNode node = new TaskNode("n1", "Exact UTF-8 fixture", "file_write", List.of(), List.of(),
                List.of("fixture.txt"), TaskNodeStatus.PENDING, 50,
                DoneWhen.file("fixture.txt", "内容精确为 hello from dag，UTF-8 正好 14 字节，无 BOM 且无换行"),
                "", "", 0, "");

        StepEvaluator.EvalResult result = evaluator.evaluate(node, "write completed", "fixture.txt");

        assertThat(result.ok()).isTrue();
        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(judge).validate(any(), evidence.capture());
        var snapshot = new ObjectMapper().readTree(evidence.getValue());
        assertThat(snapshot.path("kind").asText()).isEqualTo("ACCEPTANCE_FILE_SNAPSHOT");
        assertThat(snapshot.path("byteLength").asInt()).isEqualTo(14);
        assertThat(snapshot.path("encoding").asText()).isEqualTo("UTF-8");
        assertThat(snapshot.path("utf8Valid").asBoolean()).isTrue();
        assertThat(snapshot.path("utf8Bom").asBoolean()).isFalse();
        assertThat(snapshot.path("hasLineBreak").asBoolean()).isFalse();
        assertThat(snapshot.path("content").asText()).isEqualTo("hello from dag");
        assertThat(snapshot.path("sha256").asText()).matches("[0-9a-f]{64}");
    }
}
