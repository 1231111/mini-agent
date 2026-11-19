package com.miniagent.agent.planner;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CodeDiffReviewServiceTest {
    private final CodeDiffReviewService service = new CodeDiffReviewService(null, null);

    @Test
    void reviewUsesOnlyChangesMadeAfterCapturedBaseline() throws Exception {
        Path repo = gitRepo("baseline");
        Path source = repo.resolve("src/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Main { int value = 1; }\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "initial");

        Files.writeString(source, "class Main { int value = 2; }\n"); // user change before the task
        CodeDiffReviewService.Baseline baseline = service.capture(repo);
        assertTrue(baseline.available());
        Files.writeString(source, "class Main { int value = 3; }\n"); // current task change

        AtomicReference<String> prompt = new AtomicReference<>();
        ChatModel reviewer = mock(ChatModel.class);
        when(reviewer.chat(any(ChatRequest.class))).thenAnswer(call -> {
            prompt.set(call.getArgument(0, ChatRequest.class).messages().toString());
            return response("{\"decision\":\"PASS\",\"findings\":[]}");
        });
        CodeDiffReviewService.Review review = service.review(baseline, reviewer);

        assertTrue(review.passed(), review.feedback());
        assertEquals(64, review.diffHash().length());
        assertTrue(prompt.get().contains("value = 2"));
        assertTrue(prompt.get().contains("value = 3"));
        assertFalse(prompt.get().contains("value = 1"));
    }

    @Test
    void reviewerBlockingFindingFailsClosedWithActionableDetails() throws Exception {
        Path repo = gitRepo("finding");
        Path source = repo.resolve("Main.java");
        Files.writeString(source, "class Main {}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "initial");
        CodeDiffReviewService.Baseline baseline = service.capture(repo);
        Files.writeString(source, "class Main { void run() { throw new RuntimeException(); } }\n");
        ChatModel reviewer = mock(ChatModel.class);
        when(reviewer.chat(any(ChatRequest.class))).thenReturn(response(
                "{\"decision\":\"BLOCK\",\"findings\":[{\"severity\":\"high\",\"file\":\"Main.java\",\"line\":1,\"issue\":\"uncaught failure\",\"fix\":\"handle it\"}]}"));

        CodeDiffReviewService.Review review = service.review(baseline, reviewer);
        assertFalse(review.passed());
        assertTrue(review.feedback().contains("uncaught failure"), review.feedback());
        assertTrue(review.feedback().contains("handle it"));
    }

    @Test
    void missingChangesMalformedReviewAndMissingReviewerNeverPass() throws Exception {
        Path repo = gitRepo("fail-closed");
        Path source = repo.resolve("Main.java");
        Files.writeString(source, "class Main {}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "initial");
        CodeDiffReviewService.Baseline baseline = service.capture(repo);

        assertFalse(service.review(baseline, mock(ChatModel.class)).passed());
        Files.writeString(source, "class Main { int changed; }\n");
        assertFalse(service.review(baseline, null).passed());
        ChatModel malformed = mock(ChatModel.class);
        when(malformed.chat(any(ChatRequest.class))).thenReturn(response("PASS"));
        assertFalse(service.review(baseline, malformed).passed());
    }

    @Test
    void changeDuringReviewInvalidatesTheVerdict() throws Exception {
        Path repo = gitRepo("race");
        Path source = repo.resolve("Main.java");
        Files.writeString(source, "class Main {}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "initial");
        CodeDiffReviewService.Baseline baseline = service.capture(repo);
        Files.writeString(source, "class Main { int value = 1; }\n");
        ChatModel reviewer = mock(ChatModel.class);
        when(reviewer.chat(any(ChatRequest.class))).thenAnswer(call -> {
            Files.writeString(source, "class Main { int value = 2; }\n");
            return response("{\"decision\":\"PASS\",\"findings\":[]}");
        });

        CodeDiffReviewService.Review review = service.review(baseline, reviewer);
        assertFalse(review.passed());
        assertTrue(review.feedback().contains("发生变化"));
    }

    @Test
    void blockedReviewBaselineIsReusedOnTheNextTurnUntilAccepted() throws Exception {
        Path repo = gitRepo("resume");
        Path source = repo.resolve("Main.java");
        Files.writeString(source, "class Main {}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "initial");
        String session = "review-resume-" + System.nanoTime();
        CodeDiffReviewService.Baseline original = service.captureForSession(session, repo);
        Files.writeString(source, "class Main { int value = 1; }\n");
        ChatModel blocker = mock(ChatModel.class);
        when(blocker.chat(any(ChatRequest.class))).thenReturn(response(
                "{\"decision\":\"BLOCK\",\"findings\":[{\"severity\":\"high\",\"file\":\"Main.java\",\"line\":1,\"issue\":\"unfinished\",\"fix\":\"finish it\"}]}"));
        assertFalse(service.review(original, blocker).passed());

        CodeDiffReviewService.Baseline resumed = service.captureForSession(session, repo);
        assertSame(original, resumed);
        Files.writeString(source, "class Main { int value = 2; }\n");
        AtomicReference<String> prompt = new AtomicReference<>();
        ChatModel reviewer = mock(ChatModel.class);
        when(reviewer.chat(any(ChatRequest.class))).thenAnswer(call -> {
            prompt.set(call.getArgument(0, ChatRequest.class).messages().toString());
            return response("{\"decision\":\"PASS\",\"findings\":[]}");
        });
        assertTrue(service.review(resumed, reviewer).passed());
        assertTrue(prompt.get().contains("class Main {}"));
        assertTrue(prompt.get().contains("value = 2"));
        service.completeSessionReview(session);
        assertNotSame(original, service.captureForSession(session, repo));
    }

    @Test
    void trackedBaselineFileThatCannotBeReadCompletelyFailsClosed() throws Exception {
        Path repo = gitRepo("large-baseline");
        Path source = repo.resolve("Main.java");
        Files.writeString(source, "class Main {\n" + " ".repeat(120_000) + "\n}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "initial");
        CodeDiffReviewService.Baseline baseline = service.capture(repo);
        Files.writeString(source, "class Main {}\n");
        ChatModel reviewer = mock(ChatModel.class);
        when(reviewer.chat(any(ChatRequest.class))).thenReturn(response(
                "{\"decision\":\"PASS\",\"findings\":[]}"));

        CodeDiffReviewService.Review review = service.review(baseline, reviewer);

        assertFalse(review.passed());
        assertTrue(review.feedback().contains("Git 基线中的文件无法完整读取"), review.feedback());
        verifyNoInteractions(reviewer);
    }

    @Test
    void ignoredUntrackedCodeFilesAreIncludedInReview() throws Exception {
        Path repo = gitRepo("ignored-source");
        Files.writeString(repo.resolve(".gitignore"), "generated/\n");
        Files.writeString(repo.resolve("README.md"), "baseline\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "initial");
        CodeDiffReviewService.Baseline baseline = service.capture(repo);
        Path ignoredSource = repo.resolve("generated/NewFeature.java");
        Files.createDirectories(ignoredSource.getParent());
        Files.writeString(ignoredSource, "class NewFeature {}\n");
        AtomicReference<String> prompt = new AtomicReference<>();
        ChatModel reviewer = mock(ChatModel.class);
        when(reviewer.chat(any(ChatRequest.class))).thenAnswer(call -> {
            prompt.set(call.getArgument(0, ChatRequest.class).messages().toString());
            return response("{\"decision\":\"PASS\",\"findings\":[]}");
        });

        CodeDiffReviewService.Review review = service.review(baseline, reviewer);

        assertTrue(review.passed(), review.feedback());
        assertTrue(prompt.get().contains("generated/NewFeature.java"));
        assertTrue(prompt.get().contains("class NewFeature"));
    }

    @Test
    void unavailableBaselineIsNeverTreatedAsNoCodeChange() {
        CodeDiffReviewService.Baseline unavailable = new CodeDiffReviewService.Baseline(
                null, "", java.util.Map.of(), java.util.Set.of(), "not a Git repository");
        assertTrue(service.hasChangedCodeArtifact(unavailable));
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static Path gitRepo(String prefix) throws Exception {
        Path parent = Path.of(System.getProperty("user.dir"), "target", "code-review-tests");
        Files.createDirectories(parent);
        Path repo = Files.createTempDirectory(parent, prefix + "-");
        git(repo, "init");
        git(repo, "config", "user.email", "review-tests@example.invalid");
        git(repo, "config", "user.name", "Review Tests");
        return repo;
    }

    private static void git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repo.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
