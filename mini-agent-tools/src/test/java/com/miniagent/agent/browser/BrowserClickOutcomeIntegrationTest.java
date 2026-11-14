package com.miniagent.agent.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real Chromium; local fixture counts actual clicks and stalled navigation independently of tool messages. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserClickOutcomeIntegrationTest {
    private final BrowserService service = new BrowserService();
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger submitted = new AtomicInteger();
    private final CountDownLatch finishNavigation = new CountDownLatch(1);
    private final CountDownLatch trialEntered = new CountDownLatch(1);
    private final CountDownLatch releaseCover = new CountDownLatch(1);
    private HttpServer fixture;
    private ExecutorService workers;
    private String url;

    @BeforeEach void start() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(); fixture.setExecutor(workers);
        fixture.createContext("/", exchange -> {
            byte[] html = """
                    <!doctype html><title>Click outcome fixture</title>
                    <style>body{height:2200px}#covered{position:absolute;left:20px;top:150px;width:120px;height:40px}
                    #cover{position:absolute;left:20px;top:150px;width:120px;height:40px;z-index:10;background:#ccc}
                    #cancel-target,#cancel-cover{position:absolute;left:20px;top:1700px;width:140px;height:40px}
                    #cancel-cover{z-index:10;background:#ccc}</style>
                    <button id="ready" onclick="window.clicks++;this.textContent='Saved '+window.clicks">Save</button>
                    <button id="hidden" style="display:none" onclick="window.clicks++">Hidden</button>
                    <button id="disabled" disabled onclick="window.clicks++">Disabled</button>
                    <button id="covered" onclick="window.clicks++">Covered</button><div id="cover">Overlay</div>
                    <a id="submit" href="/slow" onclick="window.clicks++">Submit</a>
                    <button id="cancel-target" onclick="window.clicks++">Cancel boundary</button><div id="cancel-cover">Waiting</div>
                    <script>window.clicks=0;
                    addEventListener('scroll',()=>{
                      if(scrollY<800||window.signalled)return;window.signalled=true;
                      fetch('/trial-entered');
                      fetch('/release-cover').then(()=>document.getElementById('cancel-cover').remove());
                    });</script>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        fixture.createContext("/trial-entered", exchange -> {
            trialEntered.countDown(); exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        fixture.createContext("/release-cover", exchange -> {
            try { releaseCover.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        fixture.createContext("/slow", exchange -> {
            submitted.incrementAndGet();
            try { finishNavigation.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] html = "<!doctype html><title>Receipt</title><h1>Submitted once</h1>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            try {
                exchange.sendResponseHeaders(200, html.length);
                try (var out = exchange.getResponseBody()) { out.write(html); }
            } finally { exchange.close(); }
        });
        fixture.start(); url = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/";
        ReflectionTestUtils.setField(service, "headless", true);
        ReflectionTestUtils.setField(service, "networkGuard", mock(NetworkGuard.class));
        assertTrue(service.navigate("chat", url).contains("Click outcome fixture"));
    }

    @AfterEach void stop() {
        finishNavigation.countDown();
        releaseCover.countDown();
        try { service.shutdown(); }
        finally {
            if (fixture != null) fixture.stop(0);
            if (workers != null) workers.shutdownNow();
        }
    }

    @Test void hiddenTargetFailsBeforeDispatch() throws Exception { assertNotClicked("#hidden", "css"); }
    @Test void disabledObservedTargetFailsBeforeDispatch() throws Exception {
        String ref = reference(service.snapshot("chat", false), "button \"Disabled\"");
        assertNotClicked(ref, "ref");
    }
    @Test void coveredTargetFailsBeforeDispatch() throws Exception { assertNotClicked("#covered", "css"); }

    @Test void clickUsesExactlyOneActualInputAndExpiresItsOldRef() {
        String ref = reference(service.snapshot("chat", false), "button \"Save\"");
        String result = service.click("chat", ref, "ref");
        assertTrue(result.contains("Saved 1"), result);
        assertTrue(service.click("chat", ref, "ref").contains("STALE_OBSERVATION"));
        assertTrue(service.evaluate("chat", "window.clicks").contains("1"));
    }

    @Test void replacedNodeIsRejectedWithoutDispatch() throws Exception {
        service.evaluate("chat", "setTimeout(()=>document.getElementById('ready').replaceWith(document.getElementById('ready').cloneNode(true)),300);true");
        String ref = reference(service.snapshot("chat", false), "button \"Save\"");
        Thread.sleep(500);
        String result = service.click("chat", ref, "ref");
        assertTrue(result.contains("STALE_OBSERVATION"), result);
        assertTrue(service.evaluate("chat", "window.clicks").contains("0"));
    }

    @Test void clickThatStartsNavigationButTimesOutRemainsUnknownAndIsNeverReplayed() throws Exception {
        String ref = reference(service.snapshot("chat", false), "link \"Submit\"");
        String result = service.click("chat", ref, "ref");
        assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(result).status(), result);
        assertTrue(json.readTree(result).path("inputDispatched").asBoolean());
        assertEquals("dispatch", json.readTree(result).path("phase").asText());
        assertEquals(1, submitted.get(), "the independent HTTP fixture must see exactly one submission");
        assertTrue(service.click("chat", ref, "ref").contains("STALE_OBSERVATION"));
        finishNavigation.countDown();
        String observed = service.snapshot("chat", false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!observed.contains("Submitted once") && System.nanoTime() < deadline) {
            Thread.sleep(50);
            observed = service.snapshot("chat", false);
        }
        assertTrue(observed.contains("Submitted once"), observed);
        assertEquals(1, submitted.get(), "observation must never replay the click");
    }

    @Test void cancelledCallerDuringTrialCannotDispatchALateClickWhenTheOverlayDisappears() throws Exception {
        String ref = reference(service.snapshot("chat", false), "button \"Cancel boundary\"");
        AtomicReference<String> result = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> result.set(service.click("chat", ref, "ref")));
        try {
            // This signal comes from the real trial scrolling to the offscreen,
            // still-covered button. The fixture holds the overlay until cancellation.
            assertTrue(trialEntered.await(5, TimeUnit.SECONDS), "trial must be waiting on the covered target");
            caller.interrupt();
            caller.join(2_000);
            assertFalse(caller.isAlive());
            assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(result.get()).status(), result.get());
            releaseCover.countDown();
            assertTrue(service.snapshot("chat", false).contains("Cancel boundary"), "drain the actor after trial exits");
            assertTrue(service.evaluate("chat", "window.clicks").contains("0"), "no actual click after cancellation");
            String fresh = service.snapshot("chat", false);
            String successful = service.click("chat", reference(fresh, "button \"Save\""), "ref");
            assertTrue(successful.contains("Saved 1"), "cancellation must not poison a later actor call: " + successful);
        } finally {
            caller.interrupt(); releaseCover.countDown(); caller.join(5_000);
        }
    }

    private void assertNotClicked(String target, String by) throws Exception {
        String raw = service.click("chat", target, by);
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(raw).status(), raw);
        var result = json.readTree(raw);
        assertFalse(result.path("inputDispatched").asBoolean(true));
        assertEquals("actionability", result.path("phase").asText());
        assertTrue(result.path("observation").asText().contains("observationId="));
        assertFalse(result.path("error").asText().contains("Timeout"));
        assertTrue(service.evaluate("chat", "window.clicks").contains("0"));
        assertEquals(0, submitted.get());
    }

    private static String reference(String observation, String role) {
        var found = Pattern.compile("\\[(b\\d+\\.\\d+)] - " + Pattern.quote(role)).matcher(observation);
        assertTrue(found.find(), observation); return found.group(1);
    }
}
