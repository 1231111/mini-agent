package com.miniagent.agent.core;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Timeout(8)
class AgentLoopModelControlTest {
    private static final String SESSION = "agent-model-control";

    @AfterEach void clear() {
        AgentLoop.clearCurrentSession();
        ExecutionTurnContext.clear();
    }

    @Test void revisedPlanRejectsCallbacksBeforeAnOldProviderCompletes() throws Exception {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.start(SESSION);
        AgentLoop loop = loop(control);
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        AgentStreamSink sink = mock(AgentStreamSink.class);
        AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean authority = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            handler.set(invocation.getArgument(1)); started.countDown(); return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        AgentLoop.setCurrentSession(SESSION);
        ExecutionTurnContext.open(SESSION, "old-execution", 1, List.of(), authority::get);
        RunScope scope = RunScope.capture();
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ChatResponse> result = callers.submit(() -> {
                try (var ignored = scope.bind()) { return invoke(loop, mock(ChatModel.class), streaming, sink); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            authority.set(false);
            handler.get().onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("old success")).build());
            var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(ModelCallControl.StoppedException.class, failure.getCause());
            handler.get().onPartialResponse("answer belonging to the superseded plan");
            verifyNoInteractions(sink);
            verify(streaming, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        }
    }

    @Test void cancellationDuringRetriableFailureDoesNotRetry() {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.start(SESSION);
        AgentLoop loop = loop(control);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            control.cancel(SESSION);
            throw new dev.langchain4j.exception.RetriableException("connection reset");
        });
        AgentLoop.setCurrentSession(SESSION);
        var stopped = assertThrows(ModelCallControl.StoppedException.class,
                () -> invoke(loop, model, null, null));
        assertEquals(ExecutionControl.StopReason.CANCELLED, stopped.reason());
        verify(model, times(1)).chat(any(ChatRequest.class));
    }

    @Test void cancelledStreamingRequestRejectsLateTokensAndCompletion() throws Exception {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.start(SESSION);
        AgentLoop loop = loop(control);
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        AgentStreamSink sink = mock(AgentStreamSink.class);
        AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            handler.set(invocation.getArgument(1)); started.countDown(); return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        AgentLoop.setCurrentSession(SESSION);
        RunScope scope = RunScope.capture();
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ChatResponse> result = callers.submit(() -> {
                try (var ignored = scope.bind()) { return invoke(loop, mock(ChatModel.class), streaming, sink); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            control.cancel(SESSION);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(ModelCallControl.StoppedException.class, failure.getCause());
            handler.get().onPartialResponse("late output from cancelled request");
            handler.get().onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("late completion")).build());
            verifyNoInteractions(sink);
            verify(streaming, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        }
    }

    @Test void pausedStreamingRequestHoldsTokensAndResumesWithoutANewRequest() throws Exception {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.start(SESSION);
        AgentLoop loop = loop(control);
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        AgentStreamSink sink = mock(AgentStreamSink.class);
        AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            handler.set(invocation.getArgument(1)); started.countDown(); return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        AgentLoop.setCurrentSession(SESSION);
        RunScope scope = RunScope.capture();
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ChatResponse> result = callers.submit(() -> {
                try (var ignored = scope.bind()) { return invoke(loop, mock(ChatModel.class), streaming, sink); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            control.pause(SESSION);
            Future<?> token = callers.submit(() -> handler.get().onPartialResponse("retained token"));
            try {
                assertThrows(TimeoutException.class, () -> token.get(350, TimeUnit.MILLISECONDS));
                verifyNoInteractions(sink);
                assertTrue(control.isPausedAtCheckpoint(SESSION));
                control.resume(SESSION);
                token.get(2, TimeUnit.SECONDS);
                verify(sink).onAnswerToken("retained token");
                handler.get().onCompleteResponse(ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("done")).build());
                assertEquals("done", result.get(2, TimeUnit.SECONDS).aiMessage().text());
                verify(streaming, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            } finally {
                control.cancel(SESSION); control.resume(SESSION);
            }
        }
    }

    @Test void blockedSinkCannotPreventCancellationFromClosingTheAttempt() throws Exception {
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.start(SESSION);
        AgentLoop loop = loop(control);
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        AgentStreamSink sink = mock(AgentStreamSink.class);
        CountDownLatch enteredSink = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredSink.countDown();
            while (releaseSink.getCount() > 0) {
                try { releaseSink.await(); }
                catch (InterruptedException ignored) { /* Broken I/O substitute. */ }
            }
            return null;
        }).when(sink).onAnswerToken(any());
        doAnswer(invocation -> {
            ((StreamingChatResponseHandler) invocation.getArgument(1)).onPartialResponse("in-flight token");
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        AgentLoop.setCurrentSession(SESSION);
        RunScope scope = RunScope.capture();
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ChatResponse> result = callers.submit(() -> {
                try (var ignored = scope.bind()) { return invoke(loop, mock(ChatModel.class), streaming, sink); }
            });
            try {
                assertTrue(enteredSink.await(2, TimeUnit.SECONDS));
                control.cancel(SESSION);
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> result.get(2, TimeUnit.SECONDS));
                assertInstanceOf(ModelCallControl.StoppedException.class, failure.getCause());
                assertEquals(1, releaseSink.getCount(), "cancel must not wait for the sink lock or I/O");
            } finally {
                releaseSink.countDown();
            }
        }
    }

    private static AgentLoop loop(ExecutionControl control) {
        AgentLoop loop = new AgentLoop();
        ReflectionTestUtils.setField(loop, "executionControl", control);
        ReflectionTestUtils.setField(loop, "llmMaxRetries", 2);
        return loop;
    }

    private static ChatResponse invoke(AgentLoop loop, ChatModel model, StreamingChatModel streaming, AgentStreamSink sink) {
        return ReflectionTestUtils.invokeMethod(loop, "callLlm", model, streaming,
                List.of(UserMessage.from("test")), List.of(), false, sink);
    }
}
