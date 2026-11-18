package com.miniagent.agent.security;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.http.HttpHeaders;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardedHttpClientTest {

    @Test
    void validatesEveryRedirectBeforeOpeningTheNextConnection() throws Exception {
        FakeTransport transport = new FakeTransport(raw(302,
                Map.of("Location", List.of("http://127.0.0.1/admin")), ""));
        GuardedHttpClient client = client(transport);

        assertThatThrownBy(() -> client.get(
                "https://one.example.com/start", Map.of(), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("SSRF");
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void pinsTheSingleDnsAnswerThatPassedPolicyInsteadOfResolvingAgainAtConnectTime() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        NetworkGuard guard = new NetworkGuard(true, Set.of(80, 443), host -> {
            if (resolutions.incrementAndGet() == 1) {
                return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
            }
            return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        });
        FakeTransport transport = new FakeTransport(raw(200, Map.of(), "ok"));
        GuardedHttpClient client = new GuardedHttpClient(guard, 0, transport);

        GuardedHttpClient.Response response = client.get(
                "https://rebinding.example.com/resource", Map.of(), Duration.ofSeconds(1), 1024);

        assertThat(response.bodyText()).isEqualTo("ok");
        assertThat(resolutions).hasValue(1);
        assertThat(transport.pinnedAddresses)
                .containsExactly(List.of(InetAddress.getByName("93.184.216.34")));
        assertThat(transport.requests.getFirst().uri().getHost()).isEqualTo("rebinding.example.com");
    }

    @Test
    void apacheTransportConnectsUsingThePinnedAddressWhileKeepingTheOriginalHost() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pinned", exchange -> {
            byte[] body = exchange.getRequestHeaders().getFirst("Host").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            AtomicInteger resolutions = new AtomicInteger();
            NetworkGuard guard = new NetworkGuard(false,
                    Set.of(server.getAddress().getPort()), host -> {
                resolutions.incrementAndGet();
                return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
            });
            GuardedHttpClient client = new GuardedHttpClient(guard, 0);

            GuardedHttpClient.Response response = client.get(
                    "http://pinned.test:" + server.getAddress().getPort() + "/pinned",
                    Map.of(), Duration.ofSeconds(3), 1024);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.bodyText()).contains("pinned.test");
            assertThat(resolutions).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stripsCredentialsOnCrossOriginRedirects() throws Exception {
        FakeTransport transport = new FakeTransport(
                raw(302, Map.of("Location", List.of("https://two.example.com/end")), ""),
                raw(200, Map.of("Content-Type", List.of("text/plain; charset=UTF-8")), "ok"));
        GuardedHttpClient client = client(transport);

        GuardedHttpClient.Response response = client.send(
                "GET", "https://one.example.com/start", null,
                Map.of("Authorization", "Bearer secret", "User-Agent", "test"),
                Duration.ofSeconds(1), 1024);

        assertThat(response.bodyText()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.get(0).headers().get("Authorization"))
                .isEqualTo("Bearer secret");
        assertThat(transport.requests.get(1).headers()).doesNotContainKey("Authorization");
        assertThat(transport.requests.get(1).headers().get("User-Agent"))
                .isEqualTo("test");
    }

    @Test
    void rejectsHttpsDowngrades() {
        FakeTransport transport = new FakeTransport(raw(302,
                Map.of("Location", List.of("http://two.example.com/end")), ""));
        GuardedHttpClient client = client(transport);

        assertThatThrownBy(() -> client.get(
                "https://one.example.com/start", Map.of(), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTPS to HTTP");
    }

    @Test
    void enforcesDeclaredAndStreamingResponseLimits() {
        FakeTransport declared = new FakeTransport(raw(200,
                Map.of("Content-Length", List.of("2048")), "small"));
        FakeTransport streamed = new FakeTransport(raw(200, Map.of(), "x".repeat(1025)));

        assertThatThrownBy(() -> client(declared).get(
                "https://one.example.com", Map.of(), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds limit");
        assertThatThrownBy(() -> client(streamed).get(
                "https://one.example.com", Map.of(), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds limit");
    }

    @Test
    void providerModeDoesNotFollowRedirects() {
        FakeTransport transport = new FakeTransport(raw(307,
                Map.of("Location", List.of("https://two.example.com/end")), ""));

        assertThatThrownBy(() -> client(transport).sendWithoutRedirects(
                "POST", "https://one.example.com/start", "{}",
                Map.of("Authorization", "Bearer secret"), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("redirect limit");
        assertThat(transport.requests).hasSize(1);
    }

    private static GuardedHttpClient client(FakeTransport transport) {
        NetworkGuard guard = new NetworkGuard(true, Set.of(80, 443),
                host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        return new GuardedHttpClient(guard, 5, transport);
    }

    private static GuardedHttpClient.RawResponse raw(
            int status, Map<String, List<String>> headers, String body) {
        HttpHeaders httpHeaders = HttpHeaders.of(headers, (name, value) -> true);
        return new GuardedHttpClient.RawResponse(status, httpHeaders,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class FakeTransport implements GuardedHttpClient.Transport {
        private final Queue<GuardedHttpClient.RawResponse> responses = new ArrayDeque<>();
        private final List<GuardedHttpClient.OutboundRequest> requests = new ArrayList<>();
        private final List<List<InetAddress>> pinnedAddresses = new ArrayList<>();

        private FakeTransport(GuardedHttpClient.RawResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public GuardedHttpClient.RawResponse send(GuardedHttpClient.OutboundRequest request,
                                                  List<InetAddress> pinned,
                                                  int maxResponseBytes) throws IOException {
            requests.add(request);
            pinnedAddresses.add(List.copyOf(pinned));
            GuardedHttpClient.RawResponse response = responses.poll();
            if (response == null) throw new IOException("unexpected request");
            return response;
        }
    }
}
