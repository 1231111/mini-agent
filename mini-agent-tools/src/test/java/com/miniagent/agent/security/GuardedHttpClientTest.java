package com.miniagent.agent.security;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

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
        assertThat(transport.requests.get(0).headers().firstValue("Authorization"))
                .contains("Bearer secret");
        assertThat(transport.requests.get(1).headers().firstValue("Authorization"))
                .isEmpty();
        assertThat(transport.requests.get(1).headers().firstValue("User-Agent"))
                .contains("test");
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
        private final List<HttpRequest> requests = new ArrayList<>();

        private FakeTransport(GuardedHttpClient.RawResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public GuardedHttpClient.RawResponse send(HttpRequest request) throws IOException {
            requests.add(request);
            GuardedHttpClient.RawResponse response = responses.poll();
            if (response == null) throw new IOException("unexpected request");
            return response;
        }
    }
}
