package com.miniagent.agent.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.NetworkGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real Chromium against an ephemeral local fixture; the public navigation policy remains unchanged. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserControlIntegrationTest {
    @TempDir Path workspace;
    private BrowserService service;
    private HttpServer fixture;
    private String url;

    @BeforeEach
    void start() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            byte[] html = """
                    <!doctype html><html><head><title>MiniAgent fixture</title></head><body>
                    <label>Name <input aria-label="Name" id="name"></label>
                    <button id="save" onclick="document.getElementById('result').textContent='Saved '+document.getElementById('name').value">Save</button>
                    <div id="result">Ready</div>
                    <div id="telemetry">Clock</div><script>
                    if(location.search.includes('telemetry'))setInterval(()=>{
                      document.getElementById('telemetry').textContent='Clock '+Date.now();
                    },25);
                    </script></body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        fixture.start();
        url = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/";
        service = new BrowserService();
        ReflectionTestUtils.setField(service, "networkGuard", mock(NetworkGuard.class));
        ReflectionTestUtils.setField(service, "headless", true);
    }

    @AfterEach
    void stop() {
        if (service != null) service.shutdown();
        if (fixture != null) fixture.stop(0);
    }

    @Test
    void observesTypesClicksVerifiesAndReturnsARealImage() throws Exception {
        String first = service.navigate("chat-a", url);
        assertTrue(first.contains("MiniAgent fixture"), first);
        String filled = service.type("chat-a", reference(first, "textbox"), "MiniAgent");
        assertTrue(filled.contains("输入成功"), filled);
        String clicked = service.click("chat-a", reference(filled, "button"), "ref");
        assertTrue(clicked.contains("Saved MiniAgent"), clicked);
        assertTrue(service.click("chat-a", reference(filled, "button"), "ref").contains("STALE_OBSERVATION"));
        var screenshot = new ObjectMapper().readTree(service.screenshot("chat-a", workspace));
        assertTrue(screenshot.path("success").asBoolean(), screenshot.toString());
        Path image = Path.of(screenshot.path("tool_images").get(0).path("path").asText());
        assertTrue(image.isAbsolute());
        assertTrue(image.startsWith(workspace.toAbsolutePath()));
        assertNotNull(ImageIO.read(image.toFile()));
        assertTrue(Files.size(image) > 100);
        var next = new ObjectMapper().readTree(service.screenshot("chat-a", workspace));
        assertNotEquals(image.toString(), next.path("tool_images").get(0).path("path").asText());
    }

    @Test
    void independentSessionsCannotReadOrMutateEachOthersPage() {
        String first = service.navigate("chat-a", url);
        service.type("chat-a", reference(first, "textbox"), "Alice");
        String second = service.navigate("chat-b", url);
        assertFalse(second.contains("Alice"), second);
        assertTrue(service.evaluate("chat-a", "document.getElementById('name').value").contains("Alice"));
        assertFalse(service.evaluate("chat-b", "document.getElementById('name').value").contains("Alice"));
    }

    @Test
    void asynchronousDomChangeMakesPreviousObservationStale() throws Exception {
        service.navigate("chat-a", url);
        service.evaluate("chat-a", "setTimeout(() => document.getElementById('save').textContent='Delete', 350); true");
        String observed = service.snapshot("chat-a", false);
        String ref = reference(observed, "button");
        Thread.sleep(500);
        assertTrue(service.click("chat-a", ref, "ref").contains("STALE_OBSERVATION"));
        assertTrue(service.evaluate("chat-a", "document.getElementById('result').textContent").contains("Ready"));
    }

    @Test
    void liveTelemetryDoesNotPreventClickingTheSameObservedButton() throws Exception {
        String observed = service.navigate("chat-a", url + "?telemetry=1");
        String ref = reference(observed, "button");
        Thread.sleep(100); // The page itself updates live text, without an explicit invalidating tool action.
        String result = service.click("chat-a", ref, "ref");
        assertTrue(result.contains("点击成功"), result);
        assertTrue(service.evaluate("chat-a", "document.getElementById('result').textContent").contains("Saved"));
    }

    @Test
    void observationsIncludeActualDiagnosticsAndTheyRemainSessionScoped() {
        String clean = service.navigate("chat-a", url);
        assertTrue(clean.contains("browserDiagnostics="), clean);
        assertTrue(clean.contains("\"consoleErrors\":[]"), clean);
        service.evaluate("chat-a", "console.error('fixture-console-error'); true");
        service.evaluate("chat-a", "setTimeout(() => { throw new Error('fixture-script-error') }, 0); true");
        String observed = service.snapshot("chat-a", false);
        assertTrue(observed.contains("fixture-console-error"), observed);
        assertTrue(observed.contains("fixture-script-error"), observed);
        assertTrue(observed.contains("requestOrigins"), observed);
        String other = service.navigate("chat-b", url);
        assertFalse(other.contains("fixture-console-error"));
        assertFalse(other.contains("fixture-script-error"));
    }

    private static String reference(String snapshot, String role) {
        var matcher = Pattern.compile("\\[(b\\d+\\.\\d+)] - " + role + "\\b").matcher(snapshot);
        assertTrue(matcher.find(), snapshot);
        return matcher.group(1);
    }
}
