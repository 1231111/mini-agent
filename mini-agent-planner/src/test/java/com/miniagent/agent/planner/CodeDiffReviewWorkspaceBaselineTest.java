package com.miniagent.agent.planner;

import com.miniagent.agent.tool.PathGuard;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.agent.core.ModelCallControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeDiffReviewWorkspaceBaselineTest {

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearWorkspaceOverride() {
        WorkspaceContext.clearAll();
    }

    @Test
    void nestedWorkspaceInsideGitRepositoryReviewsOnlyActiveWorkspace() throws Exception {
        Path repository = Files.createDirectory(tempDirectory.resolve("parent-repository")).toRealPath();
        Path workspace = Files.createDirectory(repository.resolve("nested-workspace")).toRealPath();
        Files.writeString(repository.resolve("outside.js"), "const outside = 'before';\n");
        Files.writeString(workspace.resolve("index.html"), "<main>inside-before</main>\n");
        Assumptions.assumeTrue(initializeGitRepository(repository),
                "Git is required to exercise a nested workspace under a committed repository");

        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", repository.toString());
        WorkspaceContext.setRootOverride(workspace);
        try {
            CodeDiffReviewService service = new CodeDiffReviewService(null, null);
            CodeDiffReviewService.Baseline baseline = service.capture(workspace);
            assertThat(baseline.kind()).isEqualTo(CodeDiffReviewService.BaselineKind.WORKSPACE_SNAPSHOT);

            Files.writeString(repository.resolve("outside.js"), "const outside = 'after';\n");
            Files.writeString(workspace.resolve("index.html"), "<main>inside-after</main>\n");

            CodeDiffReviewService.DiffSnapshot diff = service.collectDiff(baseline);
            assertThat(diff.error()).isEmpty();
            assertThat(diff.changedPaths()).containsExactly("index.html");
            assertThat(diff.content()).contains("inside-after").doesNotContain("outside-after");

            ChatModel reviewer = mock(ChatModel.class);
            ChatResponse response = mock(ChatResponse.class);
            when(response.aiMessage()).thenReturn(AiMessage.from("{\"decision\":\"PASS\",\"findings\":[]}"));
            when(reviewer.chat(any(ChatRequest.class))).thenReturn(response);

            CodeDiffReviewService.Review review = service.review(baseline, reviewer);
            assertThat(review.passed()).isTrue();
            ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
            verify(reviewer).chat(request.capture());
            String payload = ((dev.langchain4j.data.message.UserMessage)
                    request.getValue().messages().get(1)).singleText();
            assertThat(payload).contains("index.html", "inside-before", "inside-after")
                    .doesNotContain("outside.js", "outside-after");

            Files.writeString(repository.resolve("outside.js"), "const outside = 'changed again';\n");
            assertThat(service.isCurrent(baseline, review.diffHash())).isTrue();

            Files.writeString(workspace.resolve("index.html"), "<main>changed after review</main>\n");
            assertThat(service.isCurrent(baseline, review.diffHash())).isFalse();
        } finally {
            WorkspaceContext.clearAll();
            if (originalUserDir == null) System.clearProperty("user.dir");
            else System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void nonGitWorkspaceDiffIncludesAddedModifiedAndDeletedFiles() throws Exception {
        Path workspace = createWorkspace("workspace-diff");
        Files.writeString(workspace.resolve("unchanged.js"), "const same = true;\n");
        Files.writeString(workspace.resolve("modified.html"), "<main>before</main>\n");
        Files.writeString(workspace.resolve("deleted.py"), "print('before')\n");
        CodeDiffReviewService service = new CodeDiffReviewService(null, null);

        CodeDiffReviewService.Baseline baseline = service.capture(workspace);
        assertThat(baseline.kind()).isEqualTo(CodeDiffReviewService.BaselineKind.WORKSPACE_SNAPSHOT);
        assertThat(baseline.available()).isTrue();

        Files.writeString(workspace.resolve("modified.html"), "<main>after</main>\n");
        Files.writeString(workspace.resolve("added.ts"), "export const added = true;\n");
        Files.delete(workspace.resolve("deleted.py"));

        CodeDiffReviewService.DiffSnapshot diff = service.collectDiff(baseline);
        assertThat(diff.error()).isEmpty();
        assertThat(diff.hasChanges()).isTrue();
        assertThat(diff.changedPaths()).containsExactlyInAnyOrder("modified.html", "added.ts", "deleted.py");
        assertThat(diff.content())
                .contains("--- FILE CHANGE: modified.html ---")
                .contains("<main>before</main>")
                .contains("<main>after</main>")
                .contains("--- FILE CHANGE: added.ts ---")
                .contains("<FILE ABSENT>")
                .contains("--- FILE CHANGE: deleted.py ---")
                .contains("<FILE DELETED>")
                .doesNotContain("FILE CHANGE: unchanged.js");
        assertThat(service.isCurrent(baseline, diff.hash())).isTrue();

        Files.writeString(workspace.resolve("modified.html"), "<main>changed after review</main>\n");
        assertThat(service.isCurrent(baseline, diff.hash())).isFalse();
    }

    @Test
    void unchangedNonGitWorkspaceProducesNoDiff() throws Exception {
        Path workspace = createWorkspace("workspace-unchanged");
        Files.writeString(workspace.resolve("index.html"), "<!doctype html><main>ready</main>\n");
        CodeDiffReviewService service = new CodeDiffReviewService(null, null);

        CodeDiffReviewService.Baseline baseline = service.capture(workspace);
        CodeDiffReviewService.DiffSnapshot diff = service.collectDiff(baseline);

        assertThat(baseline.kind()).isEqualTo(CodeDiffReviewService.BaselineKind.WORKSPACE_SNAPSHOT);
        assertThat(diff.error()).isEmpty();
        assertThat(diff.hasChanges()).isFalse();
        assertThat(diff.changedPaths()).isEmpty();
        assertThat(diff.content()).isEmpty();
        assertThat(service.isCurrent(baseline, diff.hash())).isFalse();
    }

    @Test
    void independentReviewReceivesWorkspaceSnapshotDiffAndRechecksItsHash() throws Exception {
        Path workspace = createWorkspace("workspace-review");
        Files.writeString(workspace.resolve("index.html"), "<main>before</main>\n");
        CodeDiffReviewService service = new CodeDiffReviewService(null, null);
        CodeDiffReviewService.Baseline baseline = service.capture(workspace);
        Files.writeString(workspace.resolve("index.html"), "<main>after</main>\n");

        ChatModel reviewer = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from("{\"decision\":\"PASS\",\"findings\":[]}"));
        when(reviewer.chat(any(ChatRequest.class))).thenReturn(response);

        CodeDiffReviewService.Review review = service.review(baseline, reviewer);

        assertThat(review.passed()).isTrue();
        assertThat(review.hasChanges()).isTrue();
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(reviewer).chat(request.capture());
        String payload = ((dev.langchain4j.data.message.UserMessage)
                request.getValue().messages().get(1)).singleText();
        assertThat(payload).contains("UNTRUSTED_CODE_DIFF", "index.html", "<main>before</main>",
                "<main>after</main>", review.diffHash());
        assertThat(service.isCurrent(baseline, review.diffHash())).isTrue();
    }

    @Test
    void independentReviewerTimeoutFailsClosedInsteadOfHoldingTaskUntilRunDeadline() throws Exception {
        Path workspace = createWorkspace("workspace-review-timeout");
        Files.writeString(workspace.resolve("index.html"), "<main>before</main>\n");
        CodeDiffReviewService service = new CodeDiffReviewService(new ModelCallControl(null), null);
        var timeoutField = CodeDiffReviewService.class.getDeclaredField("codeReviewTimeoutSeconds");
        timeoutField.setAccessible(true);
        timeoutField.setInt(service, 1);
        CodeDiffReviewService.Baseline baseline = service.capture(workspace);
        Files.writeString(workspace.resolve("index.html"), "<main>after</main>\n");

        CountDownLatch reviewerStarted = new CountDownLatch(1);
        ChatModel reviewer = mock(ChatModel.class);
        when(reviewer.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            reviewerStarted.countDown();
            new CountDownLatch(1).await();
            return null;
        });
        long start = System.nanoTime();

        CodeDiffReviewService.Review review = service.review(baseline, reviewer);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(reviewerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsedMillis).isLessThan(3_000);
        assertThat(review.passed()).isFalse();
        assertThat(review.feedback()).contains("local timeout", "阻止完成");
    }

    @Test
    void unchangedWorkspaceBlocksReviewWithoutCallingIndependentReviewer() throws Exception {
        Path workspace = createWorkspace("workspace-review-no-change");
        Files.writeString(workspace.resolve("index.html"), "<main>unchanged</main>\n");
        CodeDiffReviewService service = new CodeDiffReviewService(null, null);
        CodeDiffReviewService.Baseline baseline = service.capture(workspace);
        ChatModel reviewer = mock(ChatModel.class);

        CodeDiffReviewService.Review review = service.review(baseline, reviewer);

        assertThat(review.passed()).isFalse();
        assertThat(review.hasChanges()).isFalse();
        assertThat(review.feedback()).contains("没有检测到新的代码差异");
        verify(reviewer, never()).chat(any(ChatRequest.class));
    }

    @Test
    void workspaceSymlinkFailsClosedWithoutReadingTarget() throws Exception {
        Path workspace = createWorkspace("workspace-symlink");
        Path outside = Files.createDirectory(tempDirectory.resolve("outside-dir"));
        Files.writeString(outside.resolve("secret.js"), "const secret = 'outside';\n");
        Path link = workspace.resolve("linked-dir");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.abort("Symbolic links are unavailable in this test environment");
        }

        CodeDiffReviewService.Baseline baseline = new CodeDiffReviewService(null, null).capture(workspace);

        assertThat(baseline.available()).isFalse();
        assertThat(baseline.kind()).isEqualTo(CodeDiffReviewService.BaselineKind.UNAVAILABLE);
        assertThat(baseline.unavailableReason()).contains("符号链接");
    }

    @Test
    void symlinkIntroducedAfterBaselineFailsClosed() throws Exception {
        Path workspace = createWorkspace("workspace-symlink-after-baseline");
        CodeDiffReviewService service = new CodeDiffReviewService(null, null);
        CodeDiffReviewService.Baseline baseline = service.capture(workspace);
        Path outside = Files.createDirectory(tempDirectory.resolve("outside-after-baseline"));
        Files.writeString(outside.resolve("hidden.js"), "const hidden = true;\n");
        try {
            Files.createSymbolicLink(workspace.resolve("linked-dir"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.abort("Symbolic links are unavailable in this test environment");
        }

        CodeDiffReviewService.DiffSnapshot diff = service.collectDiff(baseline);

        assertThat(diff.error()).contains("符号链接");
        assertThat(diff.hasChanges()).isTrue();
        assertThat(diff.content()).isEmpty();
    }

    @Test
    void switchingActiveWorkspaceInvalidatesThePreviousBaseline() throws Exception {
        Path firstWorkspace = createWorkspace("workspace-first");
        CodeDiffReviewService service = new CodeDiffReviewService(null, null);
        CodeDiffReviewService.Baseline baseline = service.capture(firstWorkspace);
        Path secondWorkspace = Files.createDirectory(tempDirectory.resolve("workspace-second")).toRealPath();
        PathGuard.allowRoots(secondWorkspace.toString());
        WorkspaceContext.setRootOverride(secondWorkspace);

        CodeDiffReviewService.DiffSnapshot diff = service.collectDiff(baseline);

        assertThat(diff.error()).contains("workspace 已切换");
        assertThat(diff.hasChanges()).isTrue();
        assertThat(service.isCurrent(baseline, "some-hash")).isFalse();
    }

    @Test
    void workspaceSnapshotExceedingPerFileLimitFailsClosed() throws Exception {
        Path workspace = createWorkspace("workspace-oversize");
        byte[] oversizedSource = new byte[CodeDiffReviewService.MAX_WORKSPACE_FILE_BYTES + 1];
        java.util.Arrays.fill(oversizedSource, (byte) ' ');
        Files.write(workspace.resolve("oversized.js"), oversizedSource);

        CodeDiffReviewService.Baseline baseline = new CodeDiffReviewService(null, null).capture(workspace);

        assertThat(baseline.available()).isFalse();
        assertThat(baseline.kind()).isEqualTo(CodeDiffReviewService.BaselineKind.UNAVAILABLE);
        assertThat(baseline.unavailableReason()).contains("大小上限");
    }

    @Test
    void workspaceSnapshotExceedingAggregateLimitFailsClosed() throws Exception {
        Path workspace = createWorkspace("workspace-aggregate-limit");
        byte[] content = new byte[256 * 1024];
        java.util.Arrays.fill(content, (byte) 'x');
        Files.write(workspace.resolve("one.js"), content);
        Files.write(workspace.resolve("two.ts"), content);
        Files.write(workspace.resolve("three.html"), content);

        CodeDiffReviewService.Baseline baseline = new CodeDiffReviewService(null, null).capture(workspace);

        assertThat(baseline.available()).isFalse();
        assertThat(baseline.kind()).isEqualTo(CodeDiffReviewService.BaselineKind.UNAVAILABLE);
        assertThat(baseline.unavailableReason()).contains("超过大小上限");
    }

    @Test
    void workspaceSnapshotWithInvalidUtf8FailsClosed() throws Exception {
        Path workspace = createWorkspace("workspace-invalid-utf8");
        Files.write(workspace.resolve("invalid.js"), new byte[]{(byte) 0xc3, 0x28});

        CodeDiffReviewService.Baseline baseline = new CodeDiffReviewService(null, null).capture(workspace);

        assertThat(baseline.available()).isFalse();
        assertThat(baseline.kind()).isEqualTo(CodeDiffReviewService.BaselineKind.UNAVAILABLE);
        assertThat(baseline.unavailableReason()).contains("UTF-8");
    }

    private Path createWorkspace(String name) throws IOException {
        Path workspace = Files.createDirectory(tempDirectory.resolve(name)).toRealPath();
        PathGuard.allowRoots(workspace.toString());
        WorkspaceContext.setRootOverride(workspace);
        return workspace;
    }

    private static boolean initializeGitRepository(Path repository) throws Exception {
        return git(repository, "init", "-q")
                && git(repository, "config", "user.name", "MiniAgent Test")
                && git(repository, "config", "user.email", "miniagent-test@example.invalid")
                && git(repository, "add", "outside.js", "nested-workspace/index.html")
                && git(repository, "commit", "-q", "-m", "baseline");
    }

    private static boolean git(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException gitUnavailable) {
            return false;
        }
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
    }
}
