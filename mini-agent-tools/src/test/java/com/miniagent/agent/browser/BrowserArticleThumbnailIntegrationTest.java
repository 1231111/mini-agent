package com.miniagent.agent.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.NetworkGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Loaded loopback documents only; thumbnail metadata must never initiate a cover request. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserArticleThumbnailIntegrationTest {
    private static final String SESSION = "article-thumbnails";
    private final AtomicInteger pageRequests = new AtomicInteger();
    private final AtomicInteger externalRequests = new AtomicInteger();
    private BrowserService browser;
    private HttpServer server;
    private String base;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        browser = new BrowserService();
        ReflectionTestUtils.setField(browser, "headless", true);
        ReflectionTestUtils.setField(browser, "networkGuard", new NetworkGuard() {
            @Override public String validateUrl(String value) {
                URI target = URI.create(value);
                if ("http".equals(target.getScheme()) && "127.0.0.1".equals(target.getHost())
                        && target.getPort() == server.getAddress().getPort()) return null;
                externalRequests.incrementAndGet();
                return "fixture origin denied";
            }
            @Override public String validateWebSocketUrl(String value) { return "fixture websocket denied"; }
        });
    }

    @AfterEach void stop() {
        try { if (browser != null) browser.shutdown(); }
        finally { if (server != null) server.stop(0); }
    }

    @Test void currentArticleReturnsItsOwnCoverWithLegacyTextAndNoExtraRequests() {
        navigate("/article");
        int requestsBeforeExtract = pageRequests.get();
        var extracted = browser.extractArticleWithMetadata(SESSION);
        assertTrue(extracted.success(), extracted.text());
        assertTrue(extracted.text().contains("Ordinary article body contains enough text for reliable extraction."));
        assertEquals(1, extracted.sources().size());
        var source = extracted.sources().get(0);
        assertEquals(base + "/article", source.sourceUrl());
        assertEquals("Fixture AI article", source.title());
        assertEquals("2026-11-16", source.publicationDate());
        assertEquals("https://covers.example.test/ai.jpg?size=600&lang=zh", source.thumbnailUrl());
        assertEquals(extracted.text(), browser.extractArticleText(SESSION), "Legacy callers receive the same Markdown");
        assertEquals(requestsBeforeExtract, pageRequests.get(), "Extraction reuses the loaded document");
        assertEquals(0, externalRequests.get(), "Reading cover metadata must not fetch the image or run DNS validation");
    }

    @Test void missingCoverAndFailedExtractionCannotReusePreviousPageMetadata() throws Exception {
        navigate("/article");
        assertNotNull(browser.extractArticleWithMetadata(SESSION).sources().get(0).thumbnailUrl());
        navigate("/plain");
        var noCover = browser.extractArticleWithMetadata(SESSION);
        assertTrue(noCover.success(), noCover.text());
        assertEquals(1, noCover.sources().size(), "Successful pages remain represented even when they have no cover");
        assertEquals(base + "/plain", noCover.sources().get(0).sourceUrl());
        assertNull(noCover.sources().get(0).thumbnailUrl());

        navigate("/login");
        var denied = browser.extractArticleWithMetadata(SESSION);
        assertFalse(denied.success());
        assertTrue(denied.sources().isEmpty());
        assertEquals("PERMISSION_DENIED", new ObjectMapper().readTree(denied.text()).path("errorCode").asText());

        navigate("/empty");
        var empty = browser.extractArticleWithMetadata(SESSION);
        assertFalse(empty.success());
        assertTrue(empty.sources().isEmpty());
        assertEquals("EMPTY_RESULT", new ObjectMapper().readTree(empty.text()).path("errorCode").asText());
        assertEquals(0, externalRequests.get());
    }

    @Test void wikiChaptersKeepSeparateSourceCoverPairsAndSkipFailedPages() {
        navigate("/wiki/home");
        var extracted = browser.extractArticleWithMetadata(SESSION);
        assertTrue(extracted.success(), extracted.text());
        assertEquals(List.of(base + "/wiki/one", base + "/wiki/two"),
                extracted.sources().stream().map(source -> source.sourceUrl()).toList());
        assertEquals(List.of("https://covers.example.test/chapter-one.jpg", "https://covers.example.test/chapter-two.jpg"),
                extracted.sources().stream().map(source -> source.thumbnailUrl()).toList());
        assertTrue(extracted.text().contains("Chapter one body contains enough text for reliable extraction."));
        assertTrue(extracted.text().contains("Chapter two body contains enough text for reliable extraction."));
        assertFalse(extracted.text().contains("PERMISSION_DENIED"), "A failed chapter must not be written as document content");
        assertFalse(extracted.text().contains("chapter-bad.jpg"));
        assertEquals(0, externalRequests.get());
    }

    private void navigate(String path) {
        String result = browser.navigate(SESSION, base + path);
        assertFalse(result.contains("\"success\":false"), result);
    }

    private void serve(HttpExchange exchange) throws java.io.IOException {
        pageRequests.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        String title = "Fixture " + path;
        String metadata = "";
        String body;
        switch (path) {
            case "/article" -> {
                title = "Fixture AI article";
                metadata = "<meta property='og:image' content='https://covers.example.test/ai.jpg?size=600&amp;lang=zh'>"
                        + "<meta property='article:published_time' content='2026-11-16T08:00:00+08:00'>";
                body = "<article>Ordinary article body contains enough text for reliable extraction.</article>";
            }
            case "/plain" -> body = "<article>Plain article body contains enough text for reliable extraction without a cover.</article>";
            case "/login", "/wiki/bad" -> {
                metadata = "<meta property='og:image' content='https://covers.example.test/chapter-bad.jpg'>";
                body = "<h1>Restricted page</h1><input type=password aria-label='Password'>";
            }
            case "/empty" -> body = "<p>x</p>";
            case "/wiki/home" -> body = """
                    <aside class=wiki-tree-inner-container style='width:220px;height:180px'>
                      <div data-node-uid='wikiToken=one'>Chapter one</div>
                      <div data-node-uid='wikiToken=two'>Chapter two</div>
                      <div data-node-uid='wikiToken=bad'>Restricted</div>
                    </aside>
                    <div class=wiki-content>Home document contains enough text to avoid delayed document loading waits.</div>
                    """;
            case "/wiki/one" -> {
                metadata = "<meta name='twitter:image' content='https://covers.example.test/chapter-one.jpg'>";
                body = "<div class=wiki-content>Chapter one body contains enough text for reliable extraction.</div>";
            }
            case "/wiki/two" -> {
                metadata = "<script type='application/ld+json'>{\"@type\":\"NewsArticle\",\"headline\":\"Chapter two\","
                        + "\"image\":{\"contentUrl\":\"https://covers.example.test/chapter-two.jpg\"}}</script>";
                body = "<div class=wiki-content>Chapter two body contains enough text for reliable extraction.</div>";
            }
            default -> body = "<p>Unexpected fixture page.</p>";
        }
        String html = "<!doctype html><html><head><meta charset=utf-8><title>" + title + "</title>"
                + "<link rel=icon href='data:,'>" + metadata + "</head><body>" + body + "</body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
