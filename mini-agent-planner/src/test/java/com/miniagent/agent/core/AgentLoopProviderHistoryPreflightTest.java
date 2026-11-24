package com.miniagent.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.agent.permission.PermissionPolicy;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.common.ChatMessageTexts;
import com.miniagent.common.model.EffectiveModelContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopProviderHistoryPreflightTest {

    @TempDir
    Path workspace;

    @AfterEach
    void clearModelCapabilities() {
        EffectiveModelContext.clear();
    }

    @Test
    void officialReasoningModeKeepsToolExchangeButMovesLegacyAssistantAnswersToUntrustedSystemContext() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1").name("read_file").arguments("{\"path\":\"notes.txt\"}").build();
        List<ChatMessage> input = List.of(
                SystemMessage.from("System prompt"),
                UserMessage.from("Continue the task"),
                AiMessage.from("Previous assistant answer"),
                AiMessage.builder().text("").thinking("opaque tool reasoning")
                        .toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.from("call-1", "read_file", "current file content"),
                AiMessage.from("Legacy final answer"));

        AgentLoop.ProviderHistoryPreflight result = AgentLoop.preflightProviderHistory(input, true);

        assertThat(result.droppedAssistantMessages()).isEqualTo(2);
        assertThat(result.droppedCallMessages()).isZero();
        assertThat(result.droppedResultMessages()).isZero();
        assertThat(result.preservedTextChars()).isGreaterThan(0);
        assertThat(result.messages()).anySatisfy(message -> {
            assertThat(message).isInstanceOf(SystemMessage.class);
            assertThat(((SystemMessage) message).text())
                    .contains("reasoning_content", "trust=\"untrusted\"", "Previous assistant answer", "Legacy final answer");
        });
        assertThat(result.messages()).anySatisfy(message -> {
            assertThat(message).isInstanceOf(AiMessage.class);
            AiMessage ai = (AiMessage) message;
            assertThat(ai.hasToolExecutionRequests()).isTrue();
            assertThat(ai.thinking()).isEqualTo("opaque tool reasoning");
        });
        assertThat(result.messages()).anySatisfy(message -> {
            assertThat(message).isInstanceOf(ToolExecutionResultMessage.class);
            assertThat(((ToolExecutionResultMessage) message).text()).isEqualTo("current file content");
        });
    }

    @Test
    void reasoningModeDropsToolCallAndItsResultWhenProviderThinkingIsMissing() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-2").name("write_file").arguments("{}").build();
        List<ChatMessage> input = List.of(
                UserMessage.from("Write a file"),
                AiMessage.builder().text("").toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.from("call-2", "write_file", "written"));

        AgentLoop.ProviderHistoryPreflight result = AgentLoop.preflightProviderHistory(input, true);

        assertThat(result.droppedCallMessages()).isEqualTo(1);
        assertThat(result.droppedResultMessages()).isEqualTo(1);
        assertThat(result.toolHistoryDiagnosticDetails())
                .contains("write_file", "possible_side_effect", "missing_required_reasoning", "call-2")
                .doesNotContain("written");
        assertThat(result.messages()).noneMatch(message -> message instanceof ToolExecutionResultMessage);
        assertThat(result.messages()).filteredOn(SystemMessage.class::isInstance)
                .singleElement().satisfies(message -> assertThat(((SystemMessage) message).text())
                        .contains("write_file", "possible_side_effect",
                                "必须先用只读观察核验当前状态", "禁止盲目重试", "trust=\"untrusted\""));
    }

    @Test
    void droppedReadOnlyCallIncludesOnlyBoundedRedactedIdentityAndPath() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("read-call-1").name("read_file")
                .arguments("{\"path\":\"notes.txt\",\"api_key\":\"sk-123456789012345678901234567890\"}").build();
        List<ChatMessage> input = List.of(
                UserMessage.from("Read notes.txt"),
                AiMessage.builder().text("").toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.from("read-call-1", "read_file", "private document body"));

        AgentLoop.ToolHistoryPreflight result = AgentLoop.preflightToolMessages(input, true);

        assertThat(result.droppedCallMessages()).isEqualTo(1);
        assertThat(result.droppedResultMessages()).isEqualTo(1);
        assertThat(result.diagnosticDetails())
                .contains("read_file", "read-call-1", "notes.txt", "read_only", "missing_required_reasoning")
                .doesNotContain("private document body", "sk-123456789012345678901234567890");
        assertThat(result.diagnosticDetails().length()).isLessThanOrEqualTo(1_600);
        assertThat(result.messages()).filteredOn(SystemMessage.class::isInstance)
                .singleElement().satisfies(message -> {
            String warning = ((SystemMessage) message).text();
            assertThat(warning)
                    .contains("相关调用及结果已从模型上下文移除", "read_only", "read_file",
                            "read-call-1", "notes.txt", "正常工具与权限流程重新读取",
                            "possible_side_effect", "只读观察核验当前状态")
                    .doesNotContain("private document body", "sk-123456789012345678901234567890");
        });
    }

    @Test
    void droppedHistoryDiagnosticIsPersistedOnceAndDoesNotRepeatOnNextPreflight() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("read-repeat-1").name("read_file").arguments("{\"path\":\"notes.txt\"}").build();
        List<ChatMessage> input = List.of(
                UserMessage.from("Read notes.txt"),
                AiMessage.builder().text("").toolExecutionRequests(List.of(call)).build());

        AgentLoop.ToolHistoryPreflight first = AgentLoop.preflightToolMessages(input, true);
        AgentLoop.ToolHistoryPreflight nextTurn = AgentLoop.preflightToolMessages(first.messages(), true);

        assertThat(first.droppedCallMessages()).isEqualTo(1);
        assertThat(first.messages()).anySatisfy(message -> {
            assertThat(message).isInstanceOf(SystemMessage.class);
            assertThat(((SystemMessage) message).text()).contains(
                    "read_file", "read-repeat-1", "notes.txt", "read_only", "missing_required_reasoning");
        });
        assertThat(nextTurn.changed()).isFalse();
        assertThat(nextTurn.messages()).hasSize(first.messages().size());
        assertThat(nextTurn.messages().stream().filter(SystemMessage.class::isInstance).count()).isEqualTo(1);
    }

    @Test
    void toolHistoryDiagnosticCapsNumberAndSizeOfEntries() throws Exception {
        List<ChatMessage> input = new java.util.ArrayList<>();
        input.add(UserMessage.from("Continue"));
        for (int i = 0; i < 60; i++) {
            ToolExecutionRequest call = ToolExecutionRequest.builder()
                    .id("call-" + i).name("read_file")
                    .arguments("{\"path\":\"" + "nested/".repeat(50) + i + ".txt\"}").build();
            input.add(AiMessage.builder().text("").toolExecutionRequests(List.of(call)).build());
        }

        AgentLoop.ToolHistoryPreflight result = AgentLoop.preflightToolMessages(input, false);

        assertThat(result.diagnosticDetails()).hasSizeLessThanOrEqualTo(1_600);
        JsonNode details = new ObjectMapper().readTree(result.diagnosticDetails());
        assertThat(details.path("droppedCallMessages").asInt()).isEqualTo(60);
        assertThat(details.path("omittedEntries").asInt()).isGreaterThan(0);
        assertThat(details.path("entries").size()).isLessThanOrEqualTo(8);
    }

    @Test
    void diagnosticDoesNotExposeAbsoluteHostPathsOrUnsafeTraversalPaths() {
        ToolExecutionRequest absolute = ToolExecutionRequest.builder()
                .id("absolute-call").name("read_file")
                .arguments("{\"path\":\"C:\\\\Users\\\\alice\\\\secret.txt\"}").build();
        ToolExecutionRequest traversal = ToolExecutionRequest.builder()
                .id("traversal-call").name("read_file")
                .arguments("{\"path\":\"../../private.txt\"}").build();

        AgentLoop.ToolHistoryPreflight result = AgentLoop.preflightToolMessages(List.of(
                UserMessage.from("Read safely"),
                AiMessage.builder().text("").toolExecutionRequests(List.of(absolute, traversal)).build()), false);

        assertThat(result.diagnosticDetails())
                .contains("<absolute-path>/secret.txt", "<unsafe-relative-path>")
                .doesNotContain("C:/Users/alice", "../../private.txt");
    }

    @Test
    void nonReasoningProviderHistoryIsPreservedWithoutCompatibilityRewriting() {
        List<ChatMessage> input = List.of(UserMessage.from("Hi"), AiMessage.from("Hello"));

        AgentLoop.ProviderHistoryPreflight result = AgentLoop.preflightProviderHistory(input, false);

        assertThat(result.changed()).isFalse();
        assertThat(result.messages()).containsExactlyElementsOf(input);
    }

    @Test
    void visionRequestUsesVisionProviderRulesInsteadOfPrimaryReasoningRequirement() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-vision-1").name("read_file").arguments("{\"path\":\"notes.txt\"}").build();
        List<ChatMessage> input = List.of(
                SystemMessage.from("System prompt"),
                UserMessage.from("Inspect this screenshot"),
                AiMessage.from("Earlier assistant answer"),
                AiMessage.builder().text("").toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.from("call-vision-1", "read_file", "file content"));

        AgentLoop.ProviderHistoryPreflight result =
                AgentLoop.preflightSelectedRequestHistory(input, true, true);

        assertThat(result.changed()).isFalse();
        assertThat(result.messages()).containsExactlyElementsOf(input);
    }

    @Test
    void visionRecoveryDoesNotApplyPrimaryReasoningFilter() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-recovery-1").name("read_file").arguments("{}").build();
        List<ChatMessage> input = List.of(
                SystemMessage.from("System prompt"),
                UserMessage.from("Inspect the image"),
                AiMessage.from("Earlier answer"),
                AiMessage.builder().text("").toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.from("call-recovery-1", "read_file", "observed"));

        List<ChatMessage> recovered = AgentLoop.recoveryHistory(input, false);

        assertThat(recovered).containsExactlyElementsOf(input);
    }

    @Test
    void providerDiagnosticIsBoundedAndRedactedBeforeItCanReachTrace() {
        String secret = "sk-123456789012345678901234567890";
        RuntimeException providerFailure = new dev.langchain4j.exception.InvalidRequestException(
                "The reasoning_content in the thinking mode must be passed back to the API. api_key=" + secret);
        AgentLoop.LlmFailure classified = AgentLoop.classifyLlmFailure(providerFailure);
        String diagnostic = AgentLoop.llmFailureDiagnostic(providerFailure);

        assertThat(classified.diagnosticCode()).isEqualTo("LLM_REASONING_HISTORY_INVALID");
        assertThat(diagnostic).contains("reasoning_content");
        assertThat(diagnostic).doesNotContain(secret);
        assertThat(diagnostic).hasSizeLessThanOrEqualTo(1_201);
    }

    @Test
    void validatedWorkspaceImageIsProjectedAsVisualObservationRatherThanTextPath() throws Exception {
        Path image = workspace.resolve("screenshot.png");
        assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile())).isTrue();
        WorkspaceContext.setRootOverride(workspace);
        try {
            String payload = new ObjectMapper().writeValueAsString(Map.of(
                    "success", true,
                    "tool_images", List.of(Map.of("path", image.toAbsolutePath().toString(), "mimeType", "image/png"))));
            ToolObservationProjector.Projection projection = ToolObservationProjector.projectBatch(List.of(
                    new ToolObservationProjector.Observation("image_inspect", "inspect-call",
                            ToolResult.success(payload))));

            assertThat(projection.allImagesRejected()).isFalse();
            assertThat(projection.observedTools()).containsExactly("image_inspect");
            assertThat(ToolObservationProjector.hasImages(List.of(projection.message()))).isTrue();
            assertThat(ChatMessageTexts.userPlain(projection.message())).contains("tool=image_inspect");
        } finally {
            WorkspaceContext.clearAll();
        }
    }

    @Test
    void diagnosticEventsAreRegisteredAndWorkspaceImageInspectionIsPlanSafe() {
        assertThat(AgentStepNode.ofCode("REASONING_HISTORY_PREFLIGHT"))
                .hasValueSatisfying(node -> assertThat(node.isPersisted()).isTrue());
        assertThat(AgentStepNode.ofCode("LLM_REQUEST_REJECTED")).isPresent();
        assertThat(AgentStepNode.ofCode("CODE_REVIEW")).isPresent();
        assertThat(PermissionPolicy.isPlanSafe("image_inspect")).isTrue();
    }

    @Test
    void actualModelCallReceivesSanitizedHistoryForReasoningProvider() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(null);
        EffectiveModelContext.set(chat, null, 0, true);
        List<ChatMessage> history = List.of(
                SystemMessage.from("system prompt"),
                UserMessage.from("previous question"),
                AiMessage.from("legacy answer without stored reasoning"),
                UserMessage.from("continue"));
        AgentLoop loop = new AgentLoop();

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(loop, "callLlm",
                chat, null, history, List.of(), false, null, null);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).chat(request.capture());
        List<ChatMessage> sent = request.getValue().messages();
        assertThat(sent).noneMatch(message -> message instanceof AiMessage
                && "legacy answer without stored reasoning".equals(((AiMessage) message).text()));
        assertThat(sent).anySatisfy(message -> {
            assertThat(message).isInstanceOf(SystemMessage.class);
            assertThat(((SystemMessage) message).text())
                    .contains("reasoning_content", "legacy answer without stored reasoning", "trust=\"untrusted\"");
        });
        assertThat(sent).anyMatch(UserMessage.class::isInstance);
    }
}
