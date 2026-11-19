package com.miniagent.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.GuardedHttpClient;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.tool.ToolExecutionDeadline;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSearchServiceExtractionFallbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAGE = "https://movies.example.com/film/42";
    private static final String FAILED = "{\"success\":false,\"error\":\"Tavily Extract 未返回结果\"}";
    private record Fixture(WebSearchService service, NetworkGuard guard, GuardedHttpClient client) { }

    private Fixture prepare() {
        var service = spy(new WebSearchService());
        var guard = mock(NetworkGuard.class);
        var client = mock(GuardedHttpClient.class);
        ReflectionTestUtils.setField(service, "networkGuard", guard);
        ReflectionTestUtils.setField(service, "guardedHttpClient", client);
        ReflectionTestUtils.setField(service, "configuredBackend", "tavily");
        ReflectionTestUtils.setField(service, "configuredTimeZone", "Asia/Shanghai");
        return new Fixture(service, guard, client);
    }

    @Test void providerFailureFallsBackOnceToOriginalMoviePageAndPreservesRealPoster() throws Exception {
        var fixture = prepare();
        doReturn(FAILED).when(fixture.service()).extractViaTavily(PAGE);
        String html = """
                <title>对应电影详情</title><h1>对应电影详情</h1><p>已核实的电影资料</p>
                <script type="application/ld+json">{"@type":"Movie","name":"对应电影",
                  "image":"/posters/42.jpg"}</script>
                """;
        when(fixture.client().get(eq(PAGE), any(), any(), anyInt())).thenReturn(new GuardedHttpClient.Response(
                200, URI.create(PAGE), HttpHeaders.of(Map.of(), (name, value) -> true), html.getBytes(StandardCharsets.UTF_8)));
        JsonNode result = JSON.readTree(fixture.service().extract(PAGE));
        assertTrue(result.path("success").asBoolean());
        assertEquals("https://movies.example.com/posters/42.jpg", result.path("thumbnail_url").asText());
        assertEquals(PAGE, result.path("thumbnail_source_url").asText());
        assertEquals("source_page_metadata", result.path("thumbnail_source").asText());
        assertEquals("tavily", result.path("fallback_from").asText());
        assertTrue(result.path("content").asText().contains("已核实的电影资料"));
        verify(fixture.client()).get(eq(PAGE), any(), eq(Duration.ofSeconds(15)), eq(4 * 1024 * 1024));
        verifyNoMoreInteractions(fixture.client());
    }

    @Test void providerSuccessDoesNotFetchPageAgain() throws Exception {
        var fixture = prepare();
        String success = "{\"success\":true,\"content\":\"原文\",\"thumbnail_url\":\"https://cdn.example.com/42.jpg\"}";
        doReturn(success).when(fixture.service()).extractViaTavily(PAGE);
        assertEquals(success, fixture.service().extract(PAGE));
        verify(fixture.guard()).validateUrl(PAGE);
        verifyNoInteractions(fixture.client());
    }

    @Test void rejectedTargetDoesNotContactProviderOrFallbackTransport() throws Exception {
        var fixture = prepare();
        when(fixture.guard().validateUrl(PAGE)).thenReturn("blocked target");
        assertFalse(JSON.readTree(fixture.service().extract(PAGE)).path("success").asBoolean());
        verify(fixture.service(), never()).extractViaTavily(anyString());
        verifyNoInteractions(fixture.client());
    }

    @Test void providerAndDirectPageFailureRemainAFailureWithoutAnotherRetry() throws Exception {
        var fixture = prepare();
        doReturn(FAILED).when(fixture.service()).extractViaTavily(PAGE);
        when(fixture.client().get(eq(PAGE), any(), any(), anyInt())).thenThrow(new IOException("source unavailable"));
        JsonNode result = JSON.readTree(fixture.service().extract(PAGE));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("DEEPSEEK_API_KEY"));
        verify(fixture.client()).get(eq(PAGE), any(), any(), anyInt());
        verifyNoMoreInteractions(fixture.client());
    }

    @Test void providerCancellationDoesNotStartFallbackRequest() throws Exception {
        var fixture = prepare();
        doThrow(new CancellationException()).when(fixture.service()).extractViaTavily(PAGE);
        JsonNode result = JSON.readTree(fixture.service().extract(PAGE));
        assertEquals("EXTRACTION_CANCELLED", result.path("error_code").asText());
        verifyNoInteractions(fixture.client());
    }

    @Test void providerInterruptionIsPreservedAndDoesNotStartFallbackRequest() throws Exception {
        var fixture = prepare();
        doThrow(new InterruptedException()).when(fixture.service()).extractViaTavily(PAGE);
        try {
            JsonNode result = JSON.readTree(fixture.service().extract(PAGE));
            assertEquals("EXTRACTION_INTERRUPTED", result.path("error_code").asText());
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(fixture.client());
        } finally { Thread.interrupted(); }
    }

    @Test void exhaustedEnclosingBudgetDoesNotStartAnyRequest() throws Exception {
        var fixture = prepare();
        try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(1, TimeUnit.NANOSECONDS))) {
            JsonNode result = JSON.readTree(fixture.service().extract(PAGE));
            assertEquals("EXTRACTION_DEADLINE", result.path("error_code").asText());
            verify(fixture.service(), never()).extractViaTavily(anyString());
            verifyNoInteractions(fixture.guard(), fixture.client());
        }
    }

    @Test void providerCannotRestartAnExpiredEnclosingBudgetForFallback() throws Exception {
        var fixture = prepare();
        AtomicReference<ToolExecutionDeadline.Binding> nested = new AtomicReference<>();
        doAnswer(invocation -> {
            nested.set(ToolExecutionDeadline.bind(ToolExecutionDeadline.after(1, TimeUnit.NANOSECONDS)));
            return FAILED;
        }).when(fixture.service()).extractViaTavily(PAGE);
        try {
            JsonNode result = JSON.readTree(fixture.service().extract(PAGE));
            assertEquals("EXTRACTION_DEADLINE", result.path("error_code").asText());
            verifyNoInteractions(fixture.client());
        } finally { if (nested.get() != null) nested.get().close(); }
    }

    @Test void fallbackTimeoutIsBoundedByRemainingToolBudget() throws Exception {
        var fixture = prepare();
        doReturn(FAILED).when(fixture.service()).extractViaTavily(PAGE);
        when(fixture.client().get(eq(PAGE), any(), any(), anyInt())).thenReturn(new GuardedHttpClient.Response(
                503, URI.create(PAGE), HttpHeaders.of(Map.of(), (name, value) -> true), new byte[0]));
        try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(2, TimeUnit.SECONDS))) {
            fixture.service().extract(PAGE);
        }
        verify(fixture.client()).get(eq(PAGE), any(), argThat(timeout -> !timeout.isZero()
                && !timeout.isNegative() && timeout.toMillis() <= 1900), anyInt());
    }
}
