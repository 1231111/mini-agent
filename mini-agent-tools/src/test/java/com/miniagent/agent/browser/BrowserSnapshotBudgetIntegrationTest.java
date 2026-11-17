package com.miniagent.agent.browser;

import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.tool.ToolExecutionDeadline;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real Chromium, disposable loopback fixture: bounded reads must release the shared actor. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserSnapshotBudgetIntegrationTest {
    private BrowserService browser;
    private HttpServer server;
    private String url;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] html = "<!doctype html><html><body><button>Save fixture</button><input type=password value=fixture-secret></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var output = exchange.getResponseBody()) { output.write(html); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        browser = new BrowserService();
        ReflectionTestUtils.setField(browser, "headless", true);
        ReflectionTestUtils.setField(browser, "networkGuard", new NetworkGuard() {
            @Override public String validateUrl(String value) {
                URI target = URI.create(value);
                return "http".equals(target.getScheme()) && "127.0.0.1".equals(target.getHost())
                        && target.getPort() == server.getAddress().getPort() ? null : "fixture origin denied";
            }
            @Override public String validateWebSocketUrl(String value) { return "fixture websocket denied"; }
        });
        assertTrue(browser.navigate("slow", url).contains("Save fixture"));
    }

    @AfterEach void stop() {
        try { if (browser != null) browser.shutdown(); }
        finally { if (server != null) server.stop(0); }
    }

    @ParameterizedTest @ValueSource(booleans = { false, true })
    void missingBodyTimesOutWithinInvocationAndTheNextSessionStillWorks(boolean full) {
        browser.evaluate("slow", "document.body.remove(); 'removed'");
        long started = System.nanoTime();
        String timedOut;
        try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(700, TimeUnit.MILLISECONDS))) {
            timedOut = browser.snapshot("slow", full);
        }
        assertEquals(ToolStatus.TIMEOUT, ToolResult.fromLegacy(timedOut).status(), timedOut);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_500,
                "Missing body must not use a separate 30/120 second SDK wait");
        assertFalse(browser.hasPendingOperations("slow"));
        assertTrue(browser.navigate("next", url).contains("Save fixture"));
    }

    @Test void budgetedSnapshotPreservesBoundRefsAndPasswordRedaction() {
        String snapshot;
        try (var ignored = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(3, TimeUnit.SECONDS))) {
            snapshot = browser.snapshot("slow", true);
        }
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(snapshot).status(), snapshot);
        assertTrue(snapshot.matches("(?s).*\\[b\\d+\\.\\d+] - button.*"), snapshot);
        assertFalse(snapshot.contains("fixture-secret"), snapshot);
    }
}
