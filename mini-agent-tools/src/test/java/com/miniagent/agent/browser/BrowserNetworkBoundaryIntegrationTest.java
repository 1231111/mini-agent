package com.miniagent.agent.browser;

import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** A controlled local policy allows the fixture only; production private-network policy is never disabled. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserNetworkBoundaryIntegrationTest {
    private BrowserService service;
    private HttpServer server;
    private ServerSocket websocketServer;
    private ExecutorService websocketWorker;
    private int websocketPort;
    private String base;
    private String crossSite;
    private final Map<String, AtomicInteger> received = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/", this::serve); server.start();
        startWebSocketFixture();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        crossSite = "http://127.0.0.2:" + server.getAddress().getPort();
        NetworkGuard guard = mock(NetworkGuard.class);
        when(guard.validateUrl(anyString())).thenAnswer(call -> {
            URI target = URI.create(call.getArgument(0));
            if (!("http".equals(target.getScheme()) && ("127.0.0.1".equals(target.getHost()) || "127.0.0.2".equals(target.getHost()))
                    && target.getPort() == server.getAddress().getPort())) return "fixture origin denied";
            return target.getPath().startsWith("/denied") ? "fixture request denied" : null;
        });
        when(guard.validateWebSocketUrl(anyString())).thenAnswer(call -> {
            URI target = URI.create(call.getArgument(0));
            return "127.0.0.1".equals(target.getHost()) && target.getPort() == websocketPort
                    && List.of("/allowed-websocket", "/websocket-redirect").contains(target.getPath())
                    ? null : "fixture websocket denied";
        });
        service = new BrowserService();
        ReflectionTestUtils.setField(service, "networkGuard", guard);
        ReflectionTestUtils.setField(service, "headless", true);
    }

    @AfterEach
    void stop() {
        try { if (service != null) service.shutdown(); }
        finally {
            if (server != null) server.stop(0);
            try { if (websocketServer != null) websocketServer.close(); }
            catch (java.io.IOException ignored) { }
            if (websocketWorker != null) websocketWorker.shutdownNow();
        }
    }

    @Test
    void blocksDirectFramesImagesFetchAndWorkerRequestsBeforeTheyReachTheServer() {
        open("/page");
        String result = service.evaluate("network", """
                (async () => {
                  const calls = ['/denied/fetch?token=private', '/resource-redirect'];
                  await Promise.all(calls.map(url => fetch(url).catch(() => 'blocked')));
                  await new Promise(resolve => {
                    const worker = new Worker('/worker.js');
                    worker.onmessage = () => { worker.terminate(); resolve(); };
                    worker.onerror = () => { worker.terminate(); resolve(); };
                  });
                  return 'settled';
                })()
                """);
        assertTrue(result.contains("settled"), result);
        assertEquals(0, hits("/denied/image"));
        assertEquals(0, hits("/denied/frame"));
        assertEquals(0, hits("/denied/fetch"));
        assertEquals(0, hits("/denied/resource-target"));
        assertEquals(0, hits("/denied/worker"));
        assertTrue(hits("/allowed-worker") > 0);
        assertTrue(hits("/worker-redirect") > 0);
        assertTrue(hits("/allowed-image") > 0);
        String diagnostics = service.snapshot("network", false);
        assertTrue(diagnostics.contains("blockedRequests"), diagnostics);
        assertFalse(diagnostics.contains("token=private"), diagnostics);
    }

    @Test
    void validatesEveryRedirectHopAndPreservesAllowedRedirectNavigation() {
        String denied = service.navigate("network", base + "/redirect-start");
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(denied).status(), denied);
        assertEquals(1, hits("/redirect-start")); assertEquals(1, hits("/redirect-middle"));
        assertEquals(0, hits("/denied/redirect-target"));
        service.close("network"); // A denied navigation can leave Chromium committing its internal error document.
        open("/allowed-redirect");
        assertEquals(1, hits("/allowed-target"));
        assertTrue(service.evaluate("network", "location.pathname").contains("/allowed-target"));
    }

    @Test
    void crossSiteIframeAndPopupRedirectsCannotBypassTheContextBoundary() {
        open("/page");
        String settled = service.evaluate("network", """
                (async () => {
                  await new Promise(resolve => {
                    const iframe = document.createElement('iframe');
                    iframe.src = '%s/frame-page';
                    iframe.onload = resolve; iframe.onerror = resolve; document.body.append(iframe);
                  });
                  window.open('/popup-redirect');
                  window.open('/allowed-popup');
                  await new Promise(resolve => setTimeout(resolve, 300));
                  return 'settled';
                })()
                """.formatted(crossSite));
        assertTrue(settled.contains("settled"), settled);
        assertTrue(hits("/frame-page") > 0);
        assertTrue(hits("/frame-resource-redirect") > 0);
        assertEquals(0, hits("/denied/frame-resource"));
        assertEquals(0, hits("/denied/popup"));
        assertTrue(hits("/allowed-popup") > 0);
    }

    @Test
    void outOfProcessIframeAllowsNormalResourcesAndDoesNotFollowUnsupportedRedirects() {
        ReflectionTestUtils.invokeMethod(service, "onActor", (Callable<String>) () -> {
            Playwright driver = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            Browser isolated = driver.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions()
                    .setHeadless(true).setArgs(List.of("--site-per-process")));
            ReflectionTestUtils.setField(service, "playwright", driver);
            ReflectionTestUtils.setField(service, "browser", isolated);
            return "isolated";
        });
        open("/page");
        String settled = service.evaluate("network", """
                (async () => {
                  await new Promise(resolve => {
                    const iframe = document.createElement('iframe');
                    iframe.src = '%s/frame-page';
                    iframe.onload = resolve; iframe.onerror = resolve; document.body.append(iframe);
                  });
                  await new Promise(resolve => setTimeout(resolve, 250));
                  return 'settled';
                })()
                """.formatted(crossSite));
        assertTrue(settled.contains("settled"), settled);
        assertTrue(hits("/allowed-frame-resource") > 0);
        assertTrue(hits("/frame-resource-redirect") > 0);
        assertEquals(0, hits("/denied/frame-resource"));
    }

    @Test
    void blocksServiceWorkerRegistrationAndDisallowedWebSocketBeforeHandshake() {
        open("/page");
        String result = service.evaluate("network", """
                (async () => {
                  const sw = await navigator.serviceWorker.register('/service-worker.js').then(reg => reg ? 'registered' : 'blocked', () => 'blocked');
                  await new Promise(resolve => {
                    const ws = new WebSocket('ws://127.0.0.1:%d/denied/ws');
                    ws.onclose = resolve; ws.onerror = resolve;
                  });
                  return sw + ':' + (await navigator.serviceWorker.getRegistrations()).length;
                })()
                """.formatted(websocketPort));
        assertTrue(result.contains("blocked:0"), result);
        assertEquals(0, hits("/service-worker.js"));
        assertEquals(0, hits("/denied/ws"));
    }

    @Test
    void allowedWebSocketCanHandshakeAndChromiumDoesNotFollowHttpHandshakeRedirects() {
        open("/page");
        String opened = service.evaluate("network", """
                new Promise(resolve => {
                  const deadline = setTimeout(() => resolve('timeout'), 3000);
                  const finish = value => { clearTimeout(deadline); resolve(value); };
                  const ws = new WebSocket('ws://127.0.0.1:%d/allowed-websocket');
                  ws.onopen = () => { ws.close(); finish('opened'); };
                  ws.onerror = () => finish('failed');
                  ws.onclose = () => finish('closed');
                })
                """.formatted(websocketPort));
        assertTrue(opened.contains("opened"), opened);
        String redirect = service.evaluate("network", """
                new Promise(resolve => {
                  const deadline = setTimeout(() => resolve('timeout'), 3000);
                  const finish = value => { clearTimeout(deadline); resolve(value); };
                  const ws = new WebSocket('ws://127.0.0.1:%d/websocket-redirect');
                  ws.onopen = () => { ws.close(); finish('unexpected'); };
                  ws.onerror = () => finish('rejected');
                  ws.onclose = () => finish('rejected');
                })
                """.formatted(websocketPort));
        assertTrue(redirect.contains("rejected"), redirect);
        assertEquals(1, hits("/allowed-websocket"));
        assertEquals(1, hits("/websocket-redirect"));
        assertEquals(0, hits("/denied/websocket-target"));
    }

    private void open(String path) {
        String result = service.navigate("network", base + path);
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(result).status(), result);
        assertTrue(result.contains("Network fixture"), result);
    }

    private int hits(String path) { return received.getOrDefault(path, new AtomicInteger()).get(); }

    /** HttpServer is not a WebSocket server: use a real upgrade/close handshake and explicit TCP EOF for 302. */
    private void startWebSocketFixture() throws Exception {
        websocketServer = new ServerSocket();
        websocketServer.bind(new InetSocketAddress("127.0.0.1", 0), 8);
        websocketPort = websocketServer.getLocalPort();
        websocketWorker = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "browser-websocket-fixture"); thread.setDaemon(true); return thread;
        });
        websocketWorker.execute(() -> {
            while (!websocketServer.isClosed()) {
                try (Socket socket = websocketServer.accept()) {
                    socket.setSoTimeout(2000);
                    var input = socket.getInputStream();
                    var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII));
                    String firstLine = reader.readLine();
                    if (firstLine == null) continue;
                    String path = URI.create(firstLine.split(" ")[1]).getPath();
                    received.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
                    String key = null;
                    for (int count = 0; count < 40; count++) {
                        String header = reader.readLine();
                        if (header == null || header.isEmpty()) break;
                        if (header.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:"))
                            key = header.substring(header.indexOf(':') + 1).trim();
                    }
                    var output = socket.getOutputStream();
                    if (path.equals("/allowed-websocket")) {
                        String accept = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                        // Finish the native close handshake so the next fixture connection is independent.
                        input.readNBytes(2);
                        output.write(new byte[]{(byte) 0x88, 2, 3, (byte) 0xe8}); output.flush();
                    } else {
                        output.write(("HTTP/1.1 302 Found\r\nLocation: /denied/websocket-target\r\n"
                                + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                    }
                } catch (Exception stoppedOrClosed) {
                    // Every fixture client is bounded by a socket timeout; close/interrupt ends the listener.
                    if (websocketServer.isClosed()) return;
                }
            }
        });
    }

    private void serve(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        received.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        String destination = switch (path) {
            case "/redirect-start" -> "/redirect-middle";
            case "/redirect-middle" -> "/denied/redirect-target";
            case "/allowed-redirect" -> "/allowed-target";
            case "/resource-redirect" -> "/denied/resource-target";
            case "/frame-resource-redirect" -> "/denied/frame-resource";
            case "/popup-redirect" -> "/denied/popup";
            case "/worker-redirect" -> "/denied/worker";
            default -> null;
        };
        if (destination != null) {
            exchange.getResponseHeaders().set("Location", destination);
            exchange.sendResponseHeaders(302, -1); exchange.close(); return;
        }
        String body;
        String contentType = "text/html; charset=utf-8";
        if (path.equals("/worker.js")) {
            contentType = "application/javascript";
            body = "Promise.all([fetch('/allowed-worker'),fetch('/worker-redirect').catch(()=>null)]).then(()=>postMessage('settled')).catch(()=>postMessage('blocked'));";
        } else if (path.equals("/frame-page")) {
            body = "<title>Network fixture</title><h1>Frame</h1><script>fetch('/allowed-frame-resource').catch(()=>{});fetch('/frame-resource-redirect').catch(()=>{});</script>";
        } else if (path.equals("/page")) {
            body = "<title>Network fixture</title><h1>Ready</h1><img src='/allowed-image'><img src='/denied/image'><iframe src='/denied/frame'></iframe>";
        } else { body = "<title>Network fixture</title><h1>Allowed target</h1>"; }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
