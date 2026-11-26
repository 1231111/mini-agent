package com.miniagent.agent.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSearchProviderHttpContractTest {
    private HttpServer server;

    @BeforeEach
    void startProvider() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "provider-contract-test");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/ok", exchange -> {
            byte[] body = "{\"results\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/limited", exchange -> {
            exchange.sendResponseHeaders(429, 0);
            exchange.close();
        });
        server.createContext("/too-large", exchange -> {
            exchange.sendResponseHeaders(200, WebSearchService.MAX_PROVIDER_RESPONSE_BYTES + 1L);
            exchange.close();
        });
        server.createContext("/chunked-large", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                byte[] chunk = new byte[8192];
                for (int i = 0; i <= WebSearchService.MAX_PROVIDER_RESPONSE_BYTES / chunk.length; i++) {
                    out.write(chunk);
                }
            } catch (java.io.IOException expectedAfterCancellation) { }
        });
        server.createContext("/slow-body", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write('{'); out.flush();
                Thread.sleep(2_000);
                out.write('}');
            } catch (Exception ignored) { }
        });
        server.start();
    }

    @AfterEach
    void stopProvider() { if (server != null) server.stop(0); }

    @Test
    void validJsonResponseCompletesWithoutChangingBody() throws Exception {
        var response = WebSearchService.sendProviderRequest(request("/ok", 2_000), "fake");
        assertThat(response.body()).isEqualTo("{\"results\":[]}");
    }

    @Test
    void non2xxStatusCannotBecomeEmptySuccessfulSearch() {
        assertThatThrownBy(() -> WebSearchService.sendProviderRequest(request("/limited", 2_000), "fake"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("HTTP 429");
    }

    @Test
    void declaredOversizedResponseIsCancelledBeforeReadingIt() {
        assertThatThrownBy(() -> WebSearchService.sendProviderRequest(request("/too-large", 2_000), "fake"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("exceeds limit");
    }

    @Test
    void chunkedResponseWithoutContentLengthStillHonorsTheByteBudget() {
        assertThatThrownBy(() -> WebSearchService.sendProviderRequest(request("/chunked-large", 2_000), "fake"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("exceeds limit");
    }

    @Test
    void slowResponseBodyIsCoveredByTheWholeRequestDeadline() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> WebSearchService.sendProviderRequest(request("/slow-body", 200), "fake"))
                .isInstanceOf(java.net.http.HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    private HttpRequest request(String path, long timeoutMs) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .timeout(Duration.ofMillis(timeoutMs)).GET().build();
    }
}
