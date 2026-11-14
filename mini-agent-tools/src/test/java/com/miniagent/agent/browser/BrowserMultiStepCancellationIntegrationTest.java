package com.miniagent.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real Chromium must stop between opening an ARIA dropdown and choosing its option. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserMultiStepCancellationIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "multi-step-cancellation";
    private final CountDownLatch opened = new CountDownLatch(1), allowOptions = new CountDownLatch(1), ready = new CountDownLatch(1);
    private final AtomicInteger selections = new AtomicInteger();
    private BrowserService browser;
    private HttpServer server;
    private ExecutorService workers;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/opened", exchange -> {
            opened.countDown();
            try {
                if (!allowOptions.await(10, TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(408, -1); exchange.close(); return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); exchange.close(); return;
            }
            noContent(exchange);
        });
        server.createContext("/ready", exchange -> { ready.countDown(); noContent(exchange); });
        server.createContext("/selected", exchange -> { selections.incrementAndGet(); noContent(exchange); });
        server.createContext("/", exchange -> {
            byte[] html = """
                    <!doctype html><html lang=zh><meta charset=utf-8><title>Cancellation dropdown</title>
                    <input id=city role=combobox aria-label=城市 aria-controls=cities aria-expanded=false readonly value=北京>
                    <div id=cities role=listbox hidden>
                      <div role=option data-value=bj aria-selected=true onclick="choose(this,'北京')">北京</div>
                      <div role=option data-value=sh aria-selected=false onclick="choose(this,'上海')">上海</div>
                    </div>
                    <script>
                      window.selectedReceipt=Promise.resolve();
                      document.getElementById('city').onclick=async function(){
                        await fetch('/opened');
                        document.getElementById('cities').hidden=false;
                        this.setAttribute('aria-expanded','true');
                        await fetch('/ready');
                      };
                      function choose(option,label){
                        for(const sibling of document.querySelectorAll('[role=option]'))
                          sibling.setAttribute('aria-selected',String(sibling===option));
                        document.getElementById('city').value=label;
                        window.selectedReceipt=fetch('/selected');
                      }
                    </script></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        server.start();
        browser = new BrowserService();
        ReflectionTestUtils.setField(browser, "networkGuard", mock(NetworkGuard.class));
        ReflectionTestUtils.setField(browser, "headless", true);
        String result = browser.navigate(SESSION, "http://127.0.0.1:" + server.getAddress().getPort() + "/");
        assertTrue(ToolResult.fromLegacy(result).isSuccess(), result);
    }

    @AfterEach void stop() {
        allowOptions.countDown();
        try { if (browser != null) browser.shutdown(); }
        finally {
            if (server != null) server.stop(0);
            if (workers != null) workers.shutdownNow();
        }
    }

    @Test void cancellationAfterExpansionNeverSelectsAnOptionWhenThePopupBecomesVisibleLater() throws Exception {
        JsonNode before = city();
        assertEquals("北京", before.path("value").asText());
        assertTrue(before.path("customPopupHidden").asBoolean());
        assertTrue(before.path("customSelectSupported").asBoolean());
        AtomicReference<String> result = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> result.set(browser.select(SESSION,
                before.path("ref").asText(), List.of("sh"), "value")));
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS), "real Chromium must have clicked the combobox");
            // The HTTP response is withheld: no timing assumption can expose the option before cancellation.
            caller.interrupt(); caller.join(2_000);
            assertFalse(caller.isAlive());
            assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(result.get()).status(), result.get());
            assertTrue(browser.hasPendingOperations(SESSION), "caller cancellation must not hide the live actor");
            assertEquals(0, selections.get());
            allowOptions.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS), "the fixture must really reveal the selectable option");
            // This queued observation also drains the old select operation before inspecting the final state.
            JsonNode after = city();
            assertFalse(browser.hasPendingOperations(SESSION));
            assertEquals("北京", after.path("value").asText());
            assertEquals(List.of("bj"), JSON.convertValue(after.path("selectedValues"), List.class));
            assertFalse(after.path("customPopupHidden").asBoolean());
            String settled = browser.evaluate(SESSION, "async () => { await window.selectedReceipt; return true; }");
            assertTrue(ToolResult.fromLegacy(settled).isSuccess(), settled);
            assertEquals(0, selections.get(), "the independent HTTP fixture must receive no option selection");
        } finally {
            allowOptions.countDown(); caller.interrupt(); caller.join(5_000);
        }
    }

    private JsonNode city() throws Exception {
        String raw = browser.formInspect(SESSION);
        JsonNode data = JSON.readTree(raw);
        assertTrue(data.path("success").asBoolean(), raw);
        for (JsonNode field : data.path("fields")) if ("city".equals(field.path("id").asText())) return field;
        throw new AssertionError("Missing fixture city field: " + raw);
    }

    private static void noContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1); exchange.close();
    }
}
