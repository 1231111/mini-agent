package com.miniagent.agent.core;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextCompressorModelControlTest {
    @Test void cancelledSummaryCannotBecomeAnEngineeringFallback() throws Exception {
        String session = "cancel-summary";
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.start(session);
        ContextCompressor compressor = new ContextCompressor();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            control.cancel(session);
            throw new IllegalStateException("provider disconnected");
        });
        ReflectionTestUtils.setField(compressor, "chatModel", model);
        ReflectionTestUtils.setField(compressor, "modelCallControl", new ModelCallControl(control));
        Class<?> stateType = Class.forName(ContextCompressor.class.getName() + "$SessionState");
        var ctor = stateType.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object state = ctor.newInstance();
        AgentLoop.setCurrentSession(session);
        try {
            var stopped = assertThrows(ModelCallControl.StoppedException.class, () ->
                    ReflectionTestUtils.invokeMethod(compressor, "generateSummaryAndExtractMemory",
                            List.of(UserMessage.from("completed work")), 300, state));
            assertEquals(ExecutionControl.StopReason.CANCELLED, stopped.reason());
            verify(model, times(1)).chat(any(ChatRequest.class));
            assertNull(ReflectionTestUtils.getField(state, "previousSummary"));
        } finally {
            AgentLoop.clearCurrentSession();
        }
    }
}
