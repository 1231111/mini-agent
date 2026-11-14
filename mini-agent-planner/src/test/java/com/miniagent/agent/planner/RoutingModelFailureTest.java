package com.miniagent.agent.planner;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.internal.ExceptionMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutingModelFailureTest {
    @ParameterizedTest
    @CsvSource({
            "401,ROUTER_MODEL_AUTHENTICATION_FAILED,401",
            "403,ROUTER_MODEL_PERMISSION_DENIED,403",
            "404,ROUTER_MODEL_NOT_FOUND,404",
            "429,ROUTER_MODEL_RATE_LIMITED,429",
            "400,ROUTER_MODEL_INVALID_REQUEST,兼容",
            "408,ROUTER_MODEL_TIMEOUT,超时",
            "500,ROUTER_MODEL_SERVICE_UNAVAILABLE,5xx",
            "504,ROUTER_MODEL_TIMEOUT,超时"
    })
    void providerStatusSurvivesSdkWrappersWithoutExposingProviderBody(int status, String reason, String hint) {
        var providerFailure = new HttpException(status, "invalid_key: sk-do-not-display; private provider response");
        var wrapped = new CompletionException(new ExceptionMapper.DefaultExceptionMapper().mapException(providerFailure));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(wrapped);

        var decision = new SemanticPlanningRouter().decide(model, "查询今日AI新闻", null, List.of());

        assertFalse(decision.resolved());
        assertEquals(reason, decision.reason());
        String answer = RoutingModelFailure.userMessage(decision.reason());
        assertTrue(answer.contains(hint), answer);
        assertTrue(answer.contains("尚未开始操作"));
        assertFalse(answer.contains("sk-do-not-display"));
        assertFalse(answer.contains("private provider response"));
        verify(model, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void connectionAndTimeoutCausesHaveActionableDiagnostics() {
        assertEquals("ROUTER_MODEL_TIMEOUT", RoutingModelFailure.reason(
                new RuntimeException(new java.net.http.HttpTimeoutException("internal endpoint details"))));
        assertEquals("ROUTER_MODEL_CONNECTION_FAILED", RoutingModelFailure.reason(
                new RuntimeException(new java.net.ConnectException("internal endpoint details"))));
        assertEquals("ROUTER_MODEL_AUTHENTICATION_FAILED", RoutingModelFailure.reason(
                new AuthenticationException("key must never be displayed")));
    }

    @Test
    void cyclicOrUnknownExceptionsRemainBoundedAndDoNotLeakDetails() {
        var outer = new IllegalStateException("sk-private");
        var inner = new IllegalStateException("provider internals");
        outer.initCause(inner);
        inner.initCause(outer);
        assertEquals("ROUTER_MODEL_ERROR", RoutingModelFailure.reason(outer));
        assertFalse(RoutingModelFailure.userMessage("sk-private").contains("sk-private"));
        assertTrue(RoutingModelFailure.userMessage("ROUTER_INVALID_RESPONSE").contains("格式校验"));
    }
}
