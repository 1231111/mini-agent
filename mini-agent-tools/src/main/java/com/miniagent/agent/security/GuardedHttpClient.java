package com.miniagent.agent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded HTTP transport for untrusted targets. Redirects are handled manually so
 * every hop passes {@link NetworkGuard} before a connection is opened.
 */
@Component
public class GuardedHttpClient {

    private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;
    private static final int ABSOLUTE_MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> CROSS_ORIGIN_STRIPPED_HEADERS = Set.of(
            "authorization", "cookie", "proxy-authorization", "origin", "referer"
    );
    private static final Pattern CHARSET_PATTERN = Pattern.compile(
            "(?i)(?:^|;)\\s*charset\\s*=\\s*\"?([^;\"\\s]+)");

    private final NetworkGuard networkGuard;
    private final int maxRedirects;
    private final Transport transport;

    /**
     * Default constructor for Spring bean creation.
     * Creates a client with sensible defaults.
     */
    public GuardedHttpClient() {
        this(new NetworkGuard(), 5, new JdkTransport(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()));
    }

    public GuardedHttpClient(
            NetworkGuard networkGuard,
            @Value("${agent.tools.max-http-redirects:5}") int maxRedirects) {
        this(networkGuard, maxRedirects, new JdkTransport(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()));
    }

    GuardedHttpClient(NetworkGuard networkGuard, int maxRedirects, Transport transport) {
        if (maxRedirects < 0 || maxRedirects > 10) {
            throw new IllegalArgumentException("max HTTP redirects must be between 0 and 10");
        }
        this.networkGuard = Objects.requireNonNull(networkGuard, "networkGuard");
        this.maxRedirects = maxRedirects;
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public Response get(String url, Map<String, String> headers, Duration timeout,
                        int maxResponseBytes) throws IOException, InterruptedException {
        return send("GET", url, null, headers, timeout, maxResponseBytes, maxRedirects);
    }

    public Response send(String method, String url, String body,
                         Map<String, String> headers, Duration timeout,
                         int maxResponseBytes) throws IOException, InterruptedException {
        return send(method, url, body, headers, timeout, maxResponseBytes, maxRedirects);
    }

    public Response sendWithoutRedirects(String method, String url, String body,
                                         Map<String, String> headers, Duration timeout,
                                         int maxResponseBytes)
            throws IOException, InterruptedException {
        return send(method, url, body, headers, timeout, maxResponseBytes, 0);
    }

    private Response send(String method, String url, String body,
                          Map<String, String> headers, Duration timeout,
                          int maxResponseBytes, int redirectLimit)
            throws IOException, InterruptedException {
        String currentMethod = normalizeMethod(method);
        byte[] currentBody = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (currentBody.length > MAX_REQUEST_BODY_BYTES) {
            throw new IOException("HTTP request body exceeds 1 MiB");
        }
        validateLimits(timeout, maxResponseBytes);

        URI current;
        try {
            current = URI.create(url.trim());
        } catch (RuntimeException e) {
            throw new IOException("Invalid URL", e);
        }
        Map<String, String> currentHeaders = sanitizeHeaders(headers);

        for (int redirects = 0; ; redirects++) {
            requireAllowed(current);
            HttpRequest request = buildRequest(
                    currentMethod, current, currentBody, currentHeaders, timeout);
            RawResponse raw = transport.send(request);
            try (InputStream responseBody = raw.body()) {
                if (!REDIRECT_STATUSES.contains(raw.statusCode())) {
                    byte[] bytes = readBounded(
                            responseBody, raw.headers(), maxResponseBytes);
                    return new Response(raw.statusCode(), current, raw.headers(), bytes);
                }

                Optional<String> location = raw.headers().firstValue("Location");
                if (location.isEmpty()) {
                    byte[] bytes = readBounded(
                            responseBody, raw.headers(), maxResponseBytes);
                    return new Response(raw.statusCode(), current, raw.headers(), bytes);
                }
                if (redirects >= redirectLimit) {
                    throw new IOException("HTTP redirect limit exceeded");
                }

                URI next;
                try {
                    next = current.resolve(location.get().trim());
                } catch (RuntimeException e) {
                    throw new IOException("Invalid HTTP redirect target", e);
                }
                requireAllowed(next);
                if ("https".equalsIgnoreCase(current.getScheme())
                        && "http".equalsIgnoreCase(next.getScheme())) {
                    throw new IOException("HTTPS to HTTP redirect is not allowed");
                }
                if (!sameOrigin(current, next)) {
                    currentHeaders = stripCrossOriginHeaders(currentHeaders);
                }
                if (raw.statusCode() == 303
                        || ((raw.statusCode() == 301 || raw.statusCode() == 302)
                        && "POST".equals(currentMethod))) {
                    currentMethod = "GET";
                    currentBody = new byte[0];
                    currentHeaders = removeContentHeaders(currentHeaders);
                }
                current = next;
            }
        }
    }

    private void requireAllowed(URI uri) throws IOException {
        String blocked = networkGuard.validateUrl(uri.toString());
        if (blocked != null) {
            throw new IOException(blocked);
        }
    }

    private static HttpRequest buildRequest(String method, URI uri, byte[] body,
                                            Map<String, String> headers,
                                            Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(builder::header);
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private static byte[] readBounded(InputStream body, HttpHeaders headers,
                                      int maxBytes) throws IOException {
        long declared = headers.firstValueAsLong("Content-Length").orElse(-1L);
        if (declared > maxBytes) {
            throw new IOException("HTTP response body exceeds limit");
        }
        byte[] bytes = body.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new IOException("HTTP response body exceeds limit");
        }
        return bytes;
    }

    private static String normalizeMethod(String method) {
        String normalized = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!"GET".equals(normalized) && !"POST".equals(normalized)) {
            throw new IllegalArgumentException("Only GET and POST are supported");
        }
        return normalized;
    }

    private static void validateLimits(Duration timeout, int maxResponseBytes) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("HTTP timeout must be between 1 ms and 2 minutes");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > ABSOLUTE_MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Invalid HTTP response size limit");
        }
    }

    private static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        headers.forEach((name, value) -> {
            if (name == null || value == null || name.length() > 128
                    || value.length() > 8192 || name.indexOf('\r') >= 0
                    || name.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Invalid HTTP header");
            }
            result.put(name, value);
        });
        return result;
    }

    private static Map<String, String> stripCrossOriginHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (!CROSS_ORIGIN_STRIPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                result.put(name, value);
            }
        });
        return result;
    }

    private static Map<String, String> removeContentHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Content-Type")
                    && !name.equalsIgnoreCase("Content-Length")) {
                result.put(name, value);
            }
        });
        return result;
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    public record Response(int statusCode, URI finalUri, HttpHeaders headers, byte[] body) {
        public Response {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        public String bodyText() {
            Charset charset = headers.firstValue("Content-Type")
                    .flatMap(GuardedHttpClient::charsetFromContentType)
                    .orElse(StandardCharsets.UTF_8);
            return new String(body, charset);
        }
    }

    private static Optional<Charset> charsetFromContentType(String contentType) {
        Matcher matcher = CHARSET_PATTERN.matcher(contentType);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Charset.forName(matcher.group(1)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    record RawResponse(int statusCode, HttpHeaders headers, InputStream body) {
        RawResponse {
            headers = headers == null
                    ? HttpHeaders.of(Map.of(), (name, value) -> true) : headers;
            body = body == null ? new ByteArrayInputStream(new byte[0]) : body;
        }
    }

    @FunctionalInterface
    interface Transport {
        RawResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    private record JdkTransport(HttpClient client) implements Transport {
        @Override
        public RawResponse send(HttpRequest request) throws IOException, InterruptedException {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            return new RawResponse(response.statusCode(), response.headers(), response.body());
        }
    }
}
