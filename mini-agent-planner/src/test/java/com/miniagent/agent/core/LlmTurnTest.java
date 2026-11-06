package com.miniagent.agent.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmTurnTest {

    @Test
    void vendorEnvelopeIsRefused() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(
                        "The request was rejected because it was considered high risk"))
                .build();
        LlmTurn turn = LlmTurn.classify(response);
        assertEquals(LlmTurn.Status.REFUSED, turn.status());
        assertEquals(LlmTurn.REFUSE_VENDOR_ENVELOPE, turn.refuseKind());
    }

    @Test
    void normalTextIsContent() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("今日 AI 动态如下"))
                .build();
        assertEquals(LlmTurn.Status.CONTENT, LlmTurn.classify(response).status());
        assertFalse(LlmTurn.isVendorEnvelope("今日 AI 动态如下"));
    }

    @Test
    void blankTextIsEmpty() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("  "))
                .build();
        assertEquals(LlmTurn.Status.EMPTY, LlmTurn.classify(response).status());
    }

    @Test
    void contentFilterFinishIsRefused() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("nope"))
                .finishReason(FinishReason.CONTENT_FILTER)
                .build();
        LlmTurn turn = LlmTurn.classify(response);
        assertEquals(LlmTurn.Status.REFUSED, turn.status());
        assertTrue(LlmTurn.isSafetyFinish(FinishReason.CONTENT_FILTER));
    }

    @Test
    void nullResponseIsError() {
        LlmTurn turn = LlmTurn.classify(null);
        assertEquals(LlmTurn.Status.ERROR, turn.status());
    }
}
