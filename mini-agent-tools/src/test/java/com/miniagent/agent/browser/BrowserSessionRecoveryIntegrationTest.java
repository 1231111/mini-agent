package com.miniagent.agent.browser;

import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.context.OwnerContext;
import com.microsoft.playwright.Browser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real browser state crosses process-style recreation only inside its owner/tenant/chat identity. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserSessionRecoveryIntegrationTest {
    @TempDir Path data;
    private BrowserService service;
    private HttpServer fixture;
    private String url;
    private String first;

    @BeforeEach
    void start() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            byte[] html = "<!doctype html><title>Browser recovery fixture</title><h1>Ready</h1>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        fixture.start();
        url = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/";
        first = identity(7L, "tenant-a", "chat-a");
        service = createService();
    }

    @AfterEach
    void stop() {
        try { if (service != null) service.shutdown(); }
        finally {
            if (fixture != null) fixture.stop(0);
            WorkspaceContext.clearSessionOverride();
            OwnerContext.clearCurrentUser();
            OwnerContext.clearCurrentTenant();
        }
    }

    @Test
    void restoresCookiesAndLocalStorageAfterClosingAndAfterANewBrowserService() {
        signIn(first);
        String closed = service.close(first);
        assertTrue(closed.contains("已保留"), closed);
        open(first);
        assertSignedIn(first);
        service.shutdown();
        service = createService();
        open(first);
        assertSignedIn(first);
    }

    @Test
    void persistedStateIsIsolatedBetweenTenantsUsersAndChats() {
        signIn(first);
        service.close(first);
        for (String other : List.of(identity(7L, "tenant-a", "chat-b"),
                identity(8L, "tenant-a", "chat-a"), identity(7L, "tenant-b", "chat-a"))) {
            open(other);
            assertLoggedOut(other);
            service.close(other);
        }
        open(first);
        assertSignedIn(first);
    }

    @Test
    void disconnectedBrowserRecreatesItsContextFromLastSavedAuthentication() {
        signIn(first);
        String terminated = ReflectionTestUtils.invokeMethod(service, "onActor", (Callable<String>) () -> {
            // Playwright ownership stays on its actor even for this forced-disconnection fixture.
            Browser active = (Browser) ReflectionTestUtils.getField(service, "browser");
            active.close();
            return "disconnected";
        });
        assertEquals("disconnected", terminated);
        open(first);
        assertSignedIn(first);
    }

    @Test
    void corruptedStateIsNotSilentlyOverwrittenAndExplicitResetRestoresUsability() throws Exception {
        signIn(first);
        service.close(first);
        Path saved = data.resolve("browser-sessions").resolve(first + ".state");
        byte[] bad = Files.readAllBytes(saved);
        bad[bad.length - 1] ^= 1;
        Files.write(saved, bad);
        String failed = service.navigate(first, url);
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(failed).status(), failed);
        assertTrue(failed.contains("损坏"), failed);
        assertArrayEquals(bad, Files.readAllBytes(saved));
        String reset = service.clearSessionState(first);
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(reset).status(), reset);
        open(first);
        assertLoggedOut(first);
    }

    @Test
    void clearingOneLiveSessionRemovesOnlyItsOwnSavedAndInMemoryAuthentication() {
        String other = identity(7L, "tenant-a", "chat-b");
        signIn(first);
        signIn(other);
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(service.clearSessionState(first)).status());
        open(first);
        assertLoggedOut(first);
        assertSignedIn(other);
        service.close(other);
        open(other);
        assertSignedIn(other);
    }

    private void signIn(String identity) {
        open(identity);
        String changed = service.evaluate(identity,
                "document.cookie='auth_secret=fixture-secret; Path=/; SameSite=Lax'; localStorage.setItem('session_secret','fixture-storage'); 'signed-in'");
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(changed).status(), changed);
        assertSignedIn(identity);
    }

    private void open(String identity) {
        String result = service.navigate(identity, url);
        assertEquals(ToolStatus.SUCCESS, ToolResult.fromLegacy(result).status(), result);
        assertTrue(result.contains("Browser recovery fixture"), result);
    }

    private void assertSignedIn(String identity) {
        String state = readState(identity);
        assertTrue(state.contains("auth_secret=fixture-secret"), state);
        assertTrue(state.contains("fixture-storage"), state);
    }

    private void assertLoggedOut(String identity) {
        String state = readState(identity);
        assertFalse(state.contains("fixture-secret"), state);
        assertFalse(state.contains("fixture-storage"), state);
    }

    private String readState(String identity) {
        return service.evaluate(identity, "JSON.stringify({cookie:document.cookie,storage:localStorage.getItem('session_secret')})");
    }

    private BrowserService createService() {
        BrowserService result = new BrowserService();
        ReflectionTestUtils.setField(result, "sessionStateStore", new BrowserSessionStateStore(data.toString()));
        ReflectionTestUtils.setField(result, "networkGuard", mock(NetworkGuard.class));
        ReflectionTestUtils.setField(result, "headless", true);
        return result;
    }

    private static String identity(long user, String tenant, String chat) {
        OwnerContext.setCurrentUser(user);
        OwnerContext.setCurrentTenant(tenant);
        WorkspaceContext.setSessionOverride(chat);
        return BrowserSessionIdentity.resolve(null);
    }
}
