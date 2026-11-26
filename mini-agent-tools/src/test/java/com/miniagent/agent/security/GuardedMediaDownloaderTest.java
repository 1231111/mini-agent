package com.miniagent.agent.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardedMediaDownloaderTest {
    private static final byte[] AUDIO = "ID3-mock-audio".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private GuardedMediaDownloader downloader;
    private AtomicInteger privateHits;

    @BeforeEach
    void startLocalProvider() throws Exception {
        privateHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> respond(exchange, 200, "audio/mpeg", AUDIO));
        server.createContext("/oversize", exchange -> respond(exchange, 200, "audio/mpeg",
                new byte[128]));
        server.createContext("/redirect-private", exchange -> {
            exchange.getResponseHeaders().add("Location", localUrl("/private"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/private", exchange -> {
            privateHits.incrementAndGet();
            respond(exchange, 200, "audio/mpeg", AUDIO);
        });
        server.start();

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        NetworkGuard guard = mock(NetworkGuard.class);
        when(guard.validateAndResolveUrl(anyString())).thenAnswer(invocation -> {
            URI target = URI.create(invocation.getArgument(0));
            if ("provider.test".equalsIgnoreCase(target.getHost())) {
                // Test-only DNS pin: the public provider hostname resolves to the local fake server.
                return new NetworkGuard.UrlValidation(null, List.of(loopback));
            }
            return new NetworkGuard.UrlValidation("blocked by test SSRF policy", List.of());
        });
        downloader = new GuardedMediaDownloader(new GuardedHttpClient(guard, 5));
    }

    @AfterEach
    void stopLocalProvider() {
        if (server != null) server.stop(0);
    }

    @Test
    void allowsProviderStyleUrlAndReturnsMimeAndBoundedBytes() throws Exception {
        var result = downloader.download(providerUrl("/audio"), Duration.ofSeconds(5), 1024);

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.contentType()).isEqualTo("audio/mpeg");
        assertThat(result.body()).isEqualTo(AUDIO);
        assertThat(result.finalUrl()).startsWith("http://provider.test:");
    }

    @Test
    void rejectsDirectPrivateAddressBeforeOpeningConnection() {
        assertThatThrownBy(() -> downloader.download(localUrl("/private"), Duration.ofSeconds(5), 1024))
                .isInstanceOf(GuardedHttpClient.NetworkRejectedException.class)
                .hasMessageContaining("blocked by test SSRF policy");
        assertThat(privateHits).hasValue(0);
    }

    @Test
    void validatesEveryRedirectAndNeverRequestsPrivateRedirectTarget() {
        assertThatThrownBy(() -> downloader.download(providerUrl("/redirect-private"),
                Duration.ofSeconds(5), 1024))
                .isInstanceOf(GuardedHttpClient.NetworkRejectedException.class)
                .hasMessageContaining("blocked by test SSRF policy");
        assertThat(privateHits).hasValue(0);
    }

    @Test
    void rejectsOversizedResponseInsteadOfBufferingUnboundedAudio() {
        assertThatThrownBy(() -> downloader.download(providerUrl("/oversize"),
                Duration.ofSeconds(5), 64))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("exceeds limit");
    }

    private String providerUrl(String path) {
        return "http://provider.test:" + server.getAddress().getPort() + path;
    }

    private String localUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String contentType, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
