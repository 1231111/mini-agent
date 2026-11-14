package com.miniagent.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.execution.*;
import com.miniagent.agent.hook.StopHookChain;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.*;
import com.miniagent.agent.surface.ToolSurface;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.tool.*;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.common.model.EffectiveModelContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real tool pipeline and loop; only the native capture and model endpoints are substituted. */
class AgentLoopToolVisionTest {
    private static final String SESSION = "tool-vision";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @AfterEach
    void clearContexts() {
        AgentLoop.clearCurrentSession();
        AgentLoop.clearCurrentModels();
        LoopTurnContext.clear();
        PermissionContext.clear();
        PermissionContext.bindStore(null);
        ExecutionTurnContext.clear();
        WorkspaceContext.clearAll();
        OwnerContext.clearCurrentUser();
    }

    @Test
    void serialResultsAreFollowedByRealImagesOnTheConfiguredVisionModel() throws Exception {
        assertBatchProjection(false);
    }

    @Test
    void parallelResultsAreAllPresentBeforeImageObservation() throws Exception {
        assertBatchProjection(true);
    }

    private void assertBatchProjection(boolean parallel) throws Exception {
        Fixture f = fixture(parallel);
        Path screen = ToolObservationProjectorTest.png(workspace.resolve("screen.png"), 3, 2);
        register(f.registry, "desktop_observe", screenshot(screen, "desktop-1"));
        register(f.registry, "browser_screenshot", screenshot(screen, "browser-1"));
        when(f.text.chat(any(ChatRequest.class))).thenReturn(tools(
                call("desktop", "desktop_observe"), call("browser", "browser_screenshot")));
        List<List<ChatMessage>> visionRequests = new ArrayList<>();
        when(f.vision.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            visionRequests.add(new ArrayList<>(request.messages()));
            assertSame(f.vision, EffectiveModelContext.currentChat());
            assertEquals(32000, EffectiveModelContext.currentContextWindow());
            return answer("The screen is visible and the observation is complete.");
        });
        EffectiveModelContext.set(f.text, null, 128000);

        var outcome = run(f, null);

        assertTrue(outcome.successful(), outcome.text());
        List<ChatMessage> sent = visionRequests.getFirst();
        assertEquals(2, imageCount(sent));
        int observationIndex = -1, resultCount = 0;
        for (int i = 0; i < sent.size(); i++) {
            if (sent.get(i) instanceof ToolExecutionResultMessage) resultCount++;
            if (ToolObservationProjector.isObservation(sent.get(i))) {
                observationIndex = i;
                assertEquals(2, resultCount, "both results must precede the synthetic observation message");
            }
        }
        assertTrue(observationIndex > 0);
        assertEquals(0, imageCount(outcome.messages()), "tool base64 must not be persisted in chat history");
        assertSame(f.text, EffectiveModelContext.currentChat(), "vision binding must restore the original model");
        assertEquals(128000, EffectiveModelContext.currentContextWindow());
        verify(f.text, times(1)).chat(any(ChatRequest.class));
        verify(f.resolver, times(1)).resolve();
        verify(f.compressor).maybeCompress(anyList(), eq(32000 - 2 * 170), eq(SESSION), anyInt());
    }

    @Test
    void eachFreshObservationReplacesPriorScreenshotButKeepsToolEvidence() throws Exception {
        Fixture f = fixture(false);
        Path first = ToolObservationProjectorTest.png(workspace.resolve("before.png"), 3, 2);
        Path second = ToolObservationProjectorTest.png(workspace.resolve("after.png"), 5, 2);
        register(f.registry, "desktop_observe", screenshot(first, "before"));
        register(f.registry, "desktop_act", screenshot(second, "after"));
        when(f.text.chat(any(ChatRequest.class))).thenReturn(tools(call("observe", "desktop_observe")));
        List<List<ChatMessage>> captured = new ArrayList<>();
        when(f.vision.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            captured.add(new ArrayList<>(request.messages()));
            return captured.size() == 1 ? tools(call("act", "desktop_act")) : answer("Verified the updated screen.");
        });

        var outcome = run(f, null);

        assertTrue(outcome.successful(), outcome.text());
        assertEquals(2, captured.size());
        assertEquals(1, imageCount(captured.get(0)));
        assertEquals(1, imageCount(captured.get(1)));
        assertEquals(java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(second)),
                images(captured.get(1)).getFirst().image().base64Data());
        assertEquals(2, captured.get(1).stream().filter(ToolExecutionResultMessage.class::isInstance).count());
        assertEquals(0, imageCount(outcome.messages()));
        verify(f.resolver, times(1)).resolve();
    }

    @Test
    void compressionCannotRemoveTheOnlyGroundingImage() throws Exception {
        Fixture f = fixture(false);
        Path image = ToolObservationProjectorTest.png(workspace.resolve("screen.png"), 2, 2);
        register(f.registry, "desktop_observe", screenshot(image, "current"));
        when(f.text.chat(any(ChatRequest.class))).thenReturn(tools(call("observe", "desktop_observe")));
        when(f.compressor.maybeCompress(anyList(), anyInt(), anyString(), anyInt())).thenAnswer(invocation -> {
            List<ChatMessage> supplied = invocation.getArgument(0);
            return ContextCompressor.CompressResult.untouched(supplied.stream()
                    .filter(message -> !ToolObservationProjector.isObservation(message)).toList(), 1);
        });
        when(f.vision.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            assertEquals(1, imageCount(request.messages()), "compression must preserve the current visual evidence");
            return answer("Observed the current screen.");
        });
        assertTrue(run(f, null).successful());
    }

    @Test
    void unavailableVisionIsAnExplicitFailureAndNeverSendsImagesToTextModel() throws Exception {
        Fixture f = fixture(false);
        register(f.registry, "desktop_observe", screenshot(
                ToolObservationProjectorTest.png(workspace.resolve("screen.png"), 2, 2), "current"));
        when(f.text.chat(any(ChatRequest.class))).thenReturn(tools(call("observe", "desktop_observe")));
        ReflectionTestUtils.setField(f.loop, "toolVisionModelResolver", null);

        var outcome = run(f, null);

        assertFalse(outcome.successful());
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, outcome.lastErrorCode());
        assertTrue(outcome.text().contains("视觉模型不可用"));
        assertEquals(0, imageCount(outcome.messages()));
        verify(f.text, times(1)).chat(any(ChatRequest.class));
        verifyNoInteractions(f.vision);
    }

    @Test
    void rejectedToolImagesFailBeforeAnotherModelTurnOrDesktopAction() throws Exception {
        Fixture f = fixture(false);
        Path invalidImage = Files.writeString(workspace.resolve("screen.png"), "not an image");
        register(f.registry, "desktop_observe", screenshot(invalidImage, "invalid"));
        AtomicInteger actions = new AtomicInteger();
        f.registry.register(Tool.builder().name("desktop_act").description("must not act")
                .sideEffect(ToolSideEffect.READ_ONLY).resultHandler(args -> {
                    actions.incrementAndGet();
                    return ToolResult.success("{\"success\":true}");
                }).build());
        when(f.text.chat(any(ChatRequest.class))).thenReturn(tools(call("observe", "desktop_observe")),
                tools(call("guessed", "desktop_act")));

        var outcome = run(f, null);

        assertFalse(outcome.successful());
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, outcome.lastErrorCode());
        assertTrue(outcome.text().contains("视觉模型没有看到界面"));
        assertEquals(0, actions.get());
        verify(f.text, times(1)).chat(any(ChatRequest.class));
        verifyNoInteractions(f.vision, f.resolver);
    }

    @Test
    void resumedLoopMustObserveAgainBeforeDesktopInputWhileDomToolsRemainAvailable() throws Exception {
        Fixture f = fixture(false);
        Path image = ToolObservationProjectorTest.png(workspace.resolve("screen.png"), 2, 2);
        ToolResult capture = screenshot(image, "old-frame");
        register(f.registry, "desktop_observe", capture);
        register(f.registry, "browser_click", ToolResult.success("{\"success\":true}"));
        AtomicInteger actions = new AtomicInteger();
        f.registry.register(Tool.builder().name("desktop_act").description("input")
                .sideEffect(ToolSideEffect.READ_ONLY).resultHandler(args -> {
                    actions.incrementAndGet();
                    return capture;
                }).build());
        installContexts();
        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"),
                UserMessage.from("Original user goal: inspect the selected window"),
                AiMessage.from(List.of(call("previous", "desktop_observe"))),
                ToolExecutionResultMessage.from("previous", "desktop_observe", capture.legacyText()),
                ToolObservationProjector.project(List.of(
                        new ToolObservationProjector.Observation("desktop_observe", "previous", capture)))));
        ToolObservationProjector.stripImages(history);
        AtomicInteger textTurns = new AtomicInteger();
        when(f.text.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            List<String> available = request.toolSpecifications().stream().map(spec -> spec.name()).toList();
            assertFalse(available.contains("desktop_act"));
            assertTrue(available.contains("browser_click"), "DOM browser actions do not require desktop screenshots");
            assertEquals(0, imageCount(request.messages()));
            assertTrue(request.messages().stream().anyMatch(message -> message instanceof UserMessage user
                    && user.hasSingleText() && user.singleText().equals("Original user goal: inspect the selected window")));
            return textTurns.incrementAndGet() == 1
                    ? tools(call("guess", "desktop_act")) : tools(call("refresh", "desktop_observe"));
        });
        when(f.vision.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            assertEquals(1, imageCount(request.messages()));
            assertTrue(request.toolSpecifications().stream().anyMatch(spec -> spec.name().equals("desktop_act")));
            return answer("The fresh screenshot has been inspected.");
        });

        var outcome = f.loop.continueLoop(f.text, history,
                "Original user goal: inspect the selected window", 5, TaskPlan.of("inspect screen"), null);

        assertTrue(outcome.successful(), outcome.text());
        assertEquals(0, actions.get(), "ToolPipeline must reject invented input before fresh visual grounding");
        assertEquals(2, textTurns.get());
        verify(f.vision, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void failedScreenshotDoesNotResolveOrSwitchToVision() throws Exception {
        Fixture f = fixture(false);
        ToolResult raw = screenshot(ToolObservationProjectorTest.png(workspace.resolve("screen.png"), 2, 2), "invalid");
        register(f.registry, "desktop_observe", ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "capture failed", false, raw.legacyText()));
        when(f.text.chat(any(ChatRequest.class))).thenReturn(tools(call("observe", "desktop_observe")),
                answer("Screen capture failed, so I cannot inspect the screen."));

        var outcome = run(f, null);

        assertFalse(outcome.successful());
        verifyNoInteractions(f.resolver, f.vision);
        verify(f.text, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void syncVisionEndpointWithStreamSinkNeverFallsBackToGlobalTextStreamingClient() throws Exception {
        Fixture f = fixture(false);
        register(f.registry, "desktop_observe", screenshot(
                ToolObservationProjectorTest.png(workspace.resolve("screen.png"), 2, 2), "current"));
        StreamingChatModel textStreaming = mock(StreamingChatModel.class);
        ReflectionTestUtils.setField(f.loop, "streamingChatModel", textStreaming);
        AtomicInteger streamingCalls = new AtomicInteger();
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            assertEquals(0, imageCount(request.messages()));
            streamingCalls.incrementAndGet();
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(tools(call("observe", "desktop_observe")));
            return null;
        }).when(textStreaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(f.vision.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            assertEquals(1, imageCount(request.messages()));
            return answer("The screen is visible.");
        });
        AgentStreamSink sink = mock(AgentStreamSink.class);

        var outcome = run(f, sink);

        assertTrue(outcome.successful(), outcome.text());
        assertEquals(1, streamingCalls.get());
        verify(sink).onAnswerToken("The screen is visible.");
        verifyNoInteractions(f.text);
    }

    @Test
    void ordinaryTextTasksNeverResolveVision() {
        Fixture f = fixture(false);
        when(f.text.chat(any(ChatRequest.class))).thenReturn(answer("Hello."));
        assertTrue(run(f, null).successful());
        verifyNoInteractions(f.resolver, f.vision);
    }

    @Test
    void insufficientModelWindowDoesNotInflateBudgetOrCallTheProvider() {
        Fixture f = fixture(false);
        register(f.registry, "read_file", ToolResult.success("fixture"));
        EffectiveModelContext.set(f.text, null, 1_024);

        var outcome = run(f, null);

        assertFalse(outcome.successful());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.lastToolResult().errorCode());
        assertTrue(outcome.text().contains("上下文窗口不足"));
        verifyNoInteractions(f.text, f.vision, f.compressor);
    }

    @Test
    void smallUsableModelWindowIsPassedToCompressionWithoutIncreasingIt() {
        Fixture f = fixture(false);
        register(f.registry, "read_file", ToolResult.success("fixture"));
        EffectiveModelContext.set(f.text, null, 2_048);
        when(f.text.chat(any(ChatRequest.class))).thenReturn(answer("Hello."));

        assertTrue(run(f, null).successful());

        verify(f.compressor).maybeCompress(anyList(), eq(2_048 - 170), eq(SESSION), anyInt());
    }

    @Test
    void observationMessagesDoNotResetTheBrowserProbeLoopGuard() throws Exception {
        Path image = ToolObservationProjectorTest.png(workspace.resolve("screen.png"), 2, 2);
        WorkspaceContext.setRootOverride(workspace);
        UserMessage observation = ToolObservationProjector.project(List.of(
                new ToolObservationProjector.Observation("browser_screenshot", "capture", screenshot(image, "current"))));
        List<ChatMessage> history = new ArrayList<>(List.of(UserMessage.from("Inspect the screen")));
        for (int i = 0; i < AgentLoop.BROWSER_PROBE_CAP; i++) {
            history.add(AiMessage.from(List.of(call("capture-" + i, "browser_screenshot"))));
            history.add(ToolExecutionResultMessage.from("capture-" + i, "browser_screenshot", "{\"success\":true}"));
            history.add(observation);
        }
        assertTrue(AgentLoop.shouldDropBrowserProbes(AgentLoop.consecutiveProbeTurns(history)));
    }

    @Test
    void modelFailureRecoveryPreservesUserInstructionsAndTheWholeLargeToolBatch() {
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from("original goal")));
        for (int i = 0; i < 20; i++) messages.add(AiMessage.from("old assistant response " + i));
        messages.add(UserMessage.from("additional user constraint"));
        List<ToolExecutionRequest> calls = new ArrayList<>();
        for (int i = 0; i < 15; i++) calls.add(call("call-" + i, "read_file"));
        AiMessage batch = AiMessage.from(calls);
        messages.add(batch);
        for (var call : calls) messages.add(ToolExecutionResultMessage.from(call.id(), call.name(), "file content"));
        messages.add(SystemMessage.from("retry after model failure"));

        List<ChatMessage> retained = AgentLoop.recoveryHistory(messages);

        assertTrue(retained.contains(UserMessage.from("original goal")));
        assertTrue(retained.contains(UserMessage.from("additional user constraint")));
        assertTrue(retained.contains(batch), "last ten messages alone would orphan these tool results");
        assertEquals(15, retained.stream().filter(ToolExecutionResultMessage.class::isInstance).count());
        assertFalse(retained.contains(AiMessage.from("old assistant response 0")));
    }

    private Fixture fixture(boolean parallel) {
        ToolRegistry registry = new ToolRegistry();
        ExecutionControl control = new ExecutionControl(60_000, 20, 100_000);
        ToolExecutionGuards guards = spy(new ToolExecutionGuards(registry, new AgentToolsProperties(4)));
        doReturn(parallel).when(guards).canRunBatchInParallel(anyList(), any(), any());
        SessionPermissionStore permissions = new SessionPermissionStore();
        ToolPipeline pipeline = new ToolPipeline(control, new ToolHookChain(new ArrayList<>()), permissions,
                new InMemoryActionJournal(), guards, registry, new ExecPolicyService("allow", "", permissions));
        ToolSurface surface = new ToolSurface();
        ReflectionTestUtils.setField(surface, "toolRegistry", registry);
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.maybeCompress(anyList(), anyInt(), anyString(), anyInt()))
                .thenAnswer(invocation -> ContextCompressor.CompressResult.untouched(invocation.getArgument(0), 1));
        AgentLoop loop = new AgentLoop();
        ReflectionTestUtils.setField(loop, "toolRegistry", registry);
        ReflectionTestUtils.setField(loop, "toolSurface", surface);
        ReflectionTestUtils.setField(loop, "toolExecutionGuards", guards);
        ReflectionTestUtils.setField(loop, "toolPipeline", pipeline);
        ReflectionTestUtils.setField(loop, "executionControl", control);
        ReflectionTestUtils.setField(loop, "executionProperties", new ExecutionProperties());
        ReflectionTestUtils.setField(loop, "contextCompressor", compressor);
        ReflectionTestUtils.setField(loop, "taskTodoStore", mock(TaskTodoStore.class));
        ReflectionTestUtils.setField(loop, "stopHookChain", new StopHookChain(new ArrayList<>()));
        ChatModel text = mock(ChatModel.class), vision = mock(ChatModel.class);
        ToolVisionModelResolver resolver = mock(ToolVisionModelResolver.class);
        when(resolver.resolve()).thenReturn(new ToolVisionModelResolver.Models(vision, null, 32000));
        ReflectionTestUtils.setField(loop, "toolVisionModelResolver", resolver);
        return new Fixture(loop, registry, compressor, text, vision, resolver);
    }

    private AgentLoop.LoopOutcome run(Fixture f, AgentStreamSink sink) {
        installContexts();
        return f.loop.runOutcome(f.text, "Use actual observations to inspect the computer.",
                "Inspect the requested screen.", List.of(), 5, TaskPlan.of("inspect screen"), sink);
    }

    private void installContexts() {
        AgentLoop.setCurrentSession(SESSION);
        OwnerContext.setCurrentUser(17L);
        WorkspaceContext.setRootOverride(workspace);
        PermissionContext.force(SESSION, PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO, true);
    }

    private static void register(ToolRegistry registry, String name, ToolResult result) {
        registry.register(Tool.builder().name(name).description("controlled test observation")
                .sideEffect(ToolSideEffect.READ_ONLY).resultHandler(args -> result).build());
    }

    private static ToolResult screenshot(Path path, String id) throws Exception {
        // Extra result data exceeds the textual browser_screenshot clamp. Projection
        // must use the structured result rather than parse its truncated model text.
        return ToolResult.success(JSON.writeValueAsString(Map.of("success", true, "observationId", id,
                "tool_images", List.of(Map.of("path", path.toString(), "mimeType", "image/png")),
                "details", "x".repeat(5000))));
    }

    private static ToolExecutionRequest call(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static ChatResponse tools(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests))).build();
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static List<ImageContent> images(List<ChatMessage> messages) {
        return messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .flatMap(user -> user.contents().stream()).filter(ImageContent.class::isInstance)
                .map(ImageContent.class::cast).toList();
    }

    private static int imageCount(List<ChatMessage> messages) { return images(messages).size(); }

    private record Fixture(AgentLoop loop, ToolRegistry registry, ContextCompressor compressor,
                           ChatModel text, ChatModel vision, ToolVisionModelResolver resolver) {}
}
