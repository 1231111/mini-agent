package com.miniagent.agent.browser;

import com.miniagent.agent.security.NetworkGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Playwright boundary test. It uses a local HTTP server and the bundled
 * headless shell; it never drives a desktop browser or a client UI.
 */
class BrowserServiceHeadlessContractTest {
    private BrowserService service;
    private HttpServer http;

    @BeforeEach
    void startLocalPage() throws Exception {
        Path root = invokeStatic("resolveBrowsersRoot");
        String directory = invokeStatic("expectedBrowserDirectory", true);
        boolean installed = Boolean.TRUE.equals(invokeStatic("isBrowserDirValid", root.resolve(directory), true));
        Assumptions.assumeTrue(installed,
                "Playwright headless shell is not installed; headless integration is skipped explicitly");
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            byte[] body = ("<!doctype html><html><head><title>actor-test</title></head>"
                    + "<body><main>session-boundary</main></body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        http.start();
        service = new BrowserService();
        ReflectionTestUtils.setField(service, "networkGuard",
                new NetworkGuard(false, Set.of(http.getAddress().getPort()), java.net.InetAddress::getAllByName));
        ReflectionTestUtils.setField(service, "headless", true);
        ReflectionTestUtils.setField(service, "actorOperationTimeoutMillis", 15_000L);
        ReflectionTestUtils.setField(service, "maxActiveSessions", 4);
    }

    @AfterEach
    void stopLocalPage() {
        if (service != null) service.shutdown();
        if (http != null) http.stop(0);
    }

    @Test
    void sessionsHaveIndependentContextsAndClosingOneDoesNotBreakTheOther() {
        String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/";
        assertThat(service.navigate("session-one", url)).contains("actor-test", "session-boundary");
        assertThat(service.navigate("session-two", url)).contains("actor-test", "session-boundary");

        assertThat(service.evaluate("session-one", "() => { localStorage.setItem('owner', 'one'); return localStorage.getItem('owner'); }"))
                .contains("one");
        assertThat(service.evaluate("session-two", "() => localStorage.getItem('owner')"))
                .contains("null");

        assertThat(service.close("session-one")).contains("浏览器页面已关闭");
        assertThat(service.evaluate("session-two", "() => document.body.innerText"))
                .contains("session-boundary");
        assertThat(service.hasPendingOperations("session-two")).isFalse();
    }

    @Test
    void aLongRunningJavascriptOperationIsBoundedAndDoesNotBlockAnotherSession() throws Exception {
        String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/";
        assertThat(service.navigate("stuck-session", url)).contains("actor-test");
        assertThat(service.navigate("healthy-session", url)).contains("actor-test");
        BrowserService stuckWorker = ReflectionTestUtils.invokeMethod(service, "workerFor", "stuck-session");
        ReflectionTestUtils.setField(stuckWorker, "actorOperationTimeoutMillis", 300L);
        Object ownedProcess = ReflectionTestUtils.getField(stuckWorker, "ownedBrowserProcess");
        assertThat(ownedProcess).as("Chromium must expose a PID for deterministic timeout recovery").isNotNull();
        java.lang.reflect.Method handleAccessor = ownedProcess.getClass().getDeclaredMethod("handle");
        handleAccessor.setAccessible(true);
        ProcessHandle handle = (ProcessHandle) handleAccessor.invoke(ownedProcess);

        long started = System.nanoTime();
        String result = service.evaluate("stuck-session", "() => new Promise(() => {})");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isLessThan(5_000L);
        assertThat(result).containsAnyOf("OUTCOME_UNKNOWN", "TIMEOUT", "DEPENDENCY_UNAVAILABLE");
        assertThat(handle.onExit().get(5, TimeUnit.SECONDS).isAlive()).isFalse();

        // A timed-out worker is quarantined; its independent sibling remains available.
        assertThat(service.evaluate("healthy-session", "() => document.title")).contains("actor-test");
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeStatic(String name, Object... args) throws Exception {
        for (Method method : BrowserService.class.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            method.setAccessible(true);
            return (T) method.invoke(null, args);
        }
        throw new NoSuchMethodException(name);
    }

}
