package com.miniagent.agent.todo;

import com.miniagent.agent.tool.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TodoSemanticValidatorShortTextFileTest {

    @TempDir
    Path workspace;

    @AfterEach
    void clearWorkspaceOverride() {
        WorkspaceContext.clearAll();
    }

    @Test
    void acceptsNonEmptyShortTxtAndMarkdownArtifactsWhenContractOnlyRequiresExistence() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Files.writeString(workspace.resolve("fixture.txt"), "hello from dag", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("fixture.md"), "# Result\nOK", StandardCharsets.UTF_8);

        TodoSemanticValidator.Result txt = TodoSemanticValidator.validate(
                "Write short fixture data", "file_exists:fixture.txt", "fixture.txt");
        TodoSemanticValidator.Result markdown = TodoSemanticValidator.validate(
                "Write compact release notes", "file_exists:fixture.md", "fixture.md");

        assertThat(txt.ok()).isTrue();
        assertThat(txt.contentHash()).isNotBlank();
        assertThat(markdown.ok()).isTrue();
        assertThat(markdown.contentHash()).isNotBlank();
    }

    @Test
    void stillRejectsBlankTextFiles() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Files.writeString(workspace.resolve("blank.txt"), " \r\n\t", StandardCharsets.UTF_8);

        TodoSemanticValidator.Result result = TodoSemanticValidator.validate(
                "Write text", "file_exists:blank.txt", "blank.txt");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("无有效文本");
    }

    @Test
    void acceptanceSnapshotReportsByteFactsFromTheAuthorizedUtf8Bytes() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Files.write(workspace.resolve("snapshot.txt"), "hello from dag".getBytes(StandardCharsets.UTF_8));

        AcceptanceEvidenceFiles.TextSnapshot snapshot = AcceptanceEvidenceFiles.readSnapshot("snapshot.txt", "");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.byteLength()).isEqualTo(14);
        assertThat(snapshot.utf8Valid()).isTrue();
        assertThat(snapshot.utf8Bom()).isFalse();
        assertThat(snapshot.hasLineBreak()).isFalse();
        assertThat(snapshot.content()).isEqualTo("hello from dag");
        assertThat(snapshot.sha256()).matches("[0-9a-f]{64}");

        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshot.toEvidenceJson());
        assertThat(evidence.path("path").asText()).endsWith("snapshot.txt");
        assertThat(evidence.path("byteLength").asInt()).isEqualTo(14);
        assertThat(evidence.path("utf8Valid").asBoolean()).isTrue();
        assertThat(LlmJudgeTodoValidator.loadEvidencePayload(snapshot.toEvidenceJson()))
                .isEqualTo(snapshot.toEvidenceJson());
    }

    @Test
    void snapshotFailsClosedOnInvalidUtf8AndMismatchedContentHash() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Files.write(workspace.resolve("invalid.txt"), new byte[]{(byte) 0xc3, 0x28});
        Files.writeString(workspace.resolve("valid.txt"), "hello", StandardCharsets.UTF_8);

        assertThat(AcceptanceEvidenceFiles.readSnapshot("invalid.txt", "")).isNull();
        assertThat(AcceptanceEvidenceFiles.readSnapshot("valid.txt", "0000000000000000")).isNull();
    }
}
