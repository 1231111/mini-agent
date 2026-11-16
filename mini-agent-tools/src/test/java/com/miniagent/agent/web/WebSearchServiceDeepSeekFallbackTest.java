package com.miniagent.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.GuardedHttpClient;
import com.miniagent.agent.security.NetworkGuard;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSearchServiceDeepSeekFallbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAGE = "https://example.com/article";

    private WebSearchService service(String baseUrl, NetworkGuard guard, GuardedHttpClient http) {
        WebSearchService service = spy(new WebSearchService());
        ReflectionTestUtils.setField(service, "configuredBackend", "bing");
        ReflectionTestUtils.setField(service, "configuredTimeZone", "Asia/Shanghai");
        ReflectionTestUtils.setField(service, "deepSeekApiKey", "test-key");
        ReflectionTestUtils.setField(service, "deepSeekBaseUrl", baseUrl);
        ReflectionTestUtils.setField(service, "networkGuard", guard);
        ReflectionTestUtils.setField(service, "guardedHttpClient", http);
        return service;
    }

    @Test
    void primaryUsableResultsDoNotCallPaidFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(calls, new AtomicReference<>(), "{}");
        try {
            WebSearchService service = service(base(server), mock(NetworkGuard.class), mock(GuardedHttpClient.class));
            doReturn(new WebSearchService.SearchProviderResult(List.of(
                    new WebSearchService.SearchResult("主结果", PAGE, "摘要", 1, null)), List.of()))
                    .when(service).searchConfiguredBackend(anyString(), anyString(), anyInt(), any(), anyBoolean(), any());
            String result = service.search("今日 AI 新闻", 5);
            assertTrue(JSON.readTree(result).path("success").asBoolean());
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void emptyPrimaryResultsUseDeepSeekSearchAndNormalizeResponse() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(calls, requestBody,
                "{\"results\":[{\"title\":\"DeepSeek 新闻\",\"url\":\"https://news.example/item\",\"snippet\":\"实时摘要\",\"date\":\"2026-11-16T02:00:00Z\"}]}");
        try {
            WebSearchService service = service(base(server), mock(NetworkGuard.class), mock(GuardedHttpClient.class));
            doReturn(new WebSearchService.SearchProviderResult(List.of(), List.of()))
                    .when(service).searchConfiguredBackend(anyString(), anyString(), anyInt(), any(), anyBoolean(), any());
            String result = service.search("今日 AI 新闻", 5);
            var root = JSON.readTree(result);
            assertTrue(root.path("success").asBoolean());
            assertEquals("deepseek_search", root.path("search_backend").asText());
            assertEquals("bing", root.path("fallback_from").asText());
            assertEquals("2026-11-16", root.at("/data/web/0/publication_date").asText());
            assertEquals(1, calls.get());
            assertEquals("今日 AI 新闻", JSON.readTree(requestBody.get()).path("ori_question").asText());
            assertEquals(15, JSON.readTree(requestBody.get()).path("count").asInt());
        } finally { server.stop(0); }
    }

    @Test
    void failedPrimaryProviderUsesPaidSearchFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(calls, new AtomicReference<>(), "{\"results\":[]}");
        try {
            WebSearchService service = service(base(server), mock(NetworkGuard.class), mock(GuardedHttpClient.class));
            doThrow(new IOException("primary unavailable"))
                    .when(service).searchConfiguredBackend(anyString(), anyString(), anyInt(), any(), anyBoolean(), any());
            String result = service.search("AI news", 5);
            assertTrue(JSON.readTree(result).path("success").asBoolean());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void failedDirectExtractUsesDeepSeekFetch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(calls, requestBody,
                "{\"title\":\"页面标题\",\"content\":\"# Markdown 正文\"}");
        NetworkGuard guard = mock(NetworkGuard.class);
        when(guard.validateUrl(PAGE)).thenReturn(null);
        GuardedHttpClient direct = mock(GuardedHttpClient.class);
        when(direct.get(eq(PAGE), any(), any(), anyInt())).thenThrow(new IOException("origin down"));
        try {
            WebSearchService service = service(base(server), guard, direct);
            var root = JSON.readTree(service.extract(PAGE));
            assertTrue(root.path("success").asBoolean());
            assertEquals("deepseek_fetch", root.path("extraction_backend").asText());
            assertEquals("guarded_http", root.path("fallback_from").asText());
            assertEquals("# Markdown 正文", root.path("content").asText());
            assertEquals(1, calls.get());
            assertEquals(PAGE, JSON.readTree(requestBody.get()).path("url").asText());
        } finally { server.stop(0); }
    }

    @Test
    void cancelledOrExpiredSearchNeverStartsPaidFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(calls, new AtomicReference<>(), "{\"results\":[]}");
        try {
            WebSearchService service = service(base(server), mock(NetworkGuard.class), mock(GuardedHttpClient.class));
            doThrow(new CancellationException("cancelled"))
                    .when(service).searchConfiguredBackend(anyString(), anyString(), anyInt(), any(), anyBoolean(), any());
            String cancelled = service.search("AI 新闻", 5);
            assertEquals("SEARCH_CANCELLED", JSON.readTree(cancelled).path("error_code").asText());
            assertEquals(0, calls.get());

            doThrow(new IOException("primary unavailable"))
                    .when(service).searchConfiguredBackend(anyString(), anyString(), anyInt(), any(), anyBoolean(), any());
            try (var ignored = com.miniagent.agent.tool.ToolExecutionDeadline.bind(
                    com.miniagent.agent.tool.ToolExecutionDeadline.after(1, TimeUnit.NANOSECONDS))) {
                String expired = service.search("AI 新闻", 5);
                assertEquals("SEARCH_DEADLINE", JSON.readTree(expired).path("error_code").asText());
                assertEquals(0, calls.get());
            }
        } finally { server.stop(0); }
    }

    @Test
    void blockedFetchNeverSendsUrlToPaidProvider() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(calls, new AtomicReference<>(), "{\"title\":\"x\",\"content\":\"x\"}");
        NetworkGuard guard = mock(NetworkGuard.class);
        when(guard.validateUrl(PAGE)).thenReturn("blocked");
        try {
            WebSearchService service = service(base(server), guard, mock(GuardedHttpClient.class));
            assertFalse(JSON.readTree(service.extract(PAGE)).path("success").asBoolean());
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void blockedRedirectNeverSendsUrlToPaidProvider() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(calls, new AtomicReference<>(), "{\"title\":\"x\",\"content\":\"x\"}");
        NetworkGuard guard = mock(NetworkGuard.class);
        when(guard.validateUrl(PAGE)).thenReturn(null);
        GuardedHttpClient direct = mock(GuardedHttpClient.class);
        when(direct.get(eq(PAGE), any(), any(), anyInt()))
                .thenThrow(new GuardedHttpClient.NetworkRejectedException("blocked redirect"));
        try {
            WebSearchService service = service(base(server), guard, direct);
            assertFalse(JSON.readTree(service.extract(PAGE)).path("success").asBoolean());
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }

    private static HttpServer server(AtomicInteger calls, AtomicReference<String> requestBody,
                                     String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tools/search", exchange -> respond(exchange, calls, requestBody, body));
        server.createContext("/v1/tools/fetch", exchange -> respond(exchange, calls, requestBody, body));
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
                                AtomicInteger calls, AtomicReference<String> requestBody,
                                String body) throws IOException {
        calls.incrementAndGet();
        assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
