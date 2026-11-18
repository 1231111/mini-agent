package com.miniagent.agent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpHeaders;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded HTTP transport for untrusted targets. Redirects are handled manually so
 * every hop passes {@link NetworkGuard} before a connection is opened.
 */
@Component
public class GuardedHttpClient {

    /** A redirect target rejected by NetworkGuard must never be retried through an external fetch provider. */
    public static class NetworkRejectedException extends IOException {
        public NetworkRejectedException(String message) { super(message); }
    }

    private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;
    private static final int ABSOLUTE_MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> CROSS_ORIGIN_STRIPPED_HEADERS = Set.of(
            "authorization", "cookie", "proxy-authorization", "origin", "referer"
    );
    private static final Pattern CHARSET_PATTERN = Pattern.compile(
            "(?i)(?:^|;)\\s*charset\\s*=\\s*\"?([^;\"\\s]+)");
    private static final ScheduledExecutorService REQUEST_DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "guarded-http-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    private final NetworkGuard networkGuard;
    private final int maxRedirects;
    private final Transport transport;

    /**
     * Default constructor for Spring bean creation.
     * Creates a client with sensible defaults.
     */
    public GuardedHttpClient() {
        this(new NetworkGuard(), 5, new ApacheTransport());
    }

    public GuardedHttpClient(
            NetworkGuard networkGuard,
            @Value("${agent.tools.max-http-redirects:5}") int maxRedirects) {
        this(networkGuard, maxRedirects, new ApacheTransport());
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

        List<InetAddress> validatedNextHop = null;
        for (int redirects = 0; ; redirects++) {
            List<InetAddress> pinnedAddresses = validatedNextHop == null
                    ? resolveAllowed(current) : validatedNextHop;
            validatedNextHop = null;
            OutboundRequest request = buildRequest(
                    currentMethod, current, currentBody, currentHeaders, timeout);
            RawResponse raw = transport.send(request, pinnedAddresses, maxResponseBytes);
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
                validatedNextHop = resolveAllowed(next);
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

    private List<InetAddress> resolveAllowed(URI uri) throws IOException {
        NetworkGuard.UrlValidation validation = networkGuard.validateAndResolveUrl(uri.toString());
        if (!validation.isAllowed()) {
            throw new NetworkRejectedException(validation.error());
        }
        return validation.addresses();
    }

    private static OutboundRequest buildRequest(String method, URI uri, byte[] body,
                                                Map<String, String> headers,
                                                Duration timeout) {
        return new OutboundRequest(method, uri, body, headers, timeout);
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

    record OutboundRequest(String method, URI uri, byte[] body,
                           Map<String, String> headers, Duration timeout) {
        OutboundRequest {
            body = body == null ? new byte[0] : body.clone();
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    @FunctionalInterface
    interface Transport {
        RawResponse send(OutboundRequest request, List<InetAddress> pinnedAddresses,
                         int maxResponseBytes) throws IOException, InterruptedException;
    }

    /**
     * Uses a request-scoped DNS resolver so the socket connects only to addresses
     * already approved by NetworkGuard. The request URI keeps its original host,
     * preserving HTTP virtual hosting, TLS SNI, and hostname certificate checks.
     */
    private static final class ApacheTransport implements Transport {
        @Override
        public RawResponse send(OutboundRequest request, List<InetAddress> pinnedAddresses,
                                int maxResponseBytes) throws IOException {
            if (pinnedAddresses == null || pinnedAddresses.isEmpty()) {
                throw new IOException("No validated destination addresses are available");
            }
            String requestHost = normalizeHost(request.uri().getHost());
            InetAddress[] addresses = pinnedAddresses.toArray(InetAddress[]::new);
            DnsResolver pinnedResolver = new DnsResolver() {
                @Override
                public InetAddress[] resolve(String host) throws UnknownHostException {
                    if (!requestHost.equals(normalizeHost(host))) {
                        throw new UnknownHostException("Unexpected host lookup: " + host);
                    }
                    return addresses.clone();
                }

                @Override
                public String resolveCanonicalHostname(String host) throws UnknownHostException {
                    if (!requestHost.equals(normalizeHost(host))) {
                        throw new UnknownHostException("Unexpected host lookup: " + host);
                    }
                    return requestHost;
                }
            };
            PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(pinnedResolver)
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(10))
                            .setSocketTimeout(Timeout.of(request.timeout()))
                            .build())
                    .build();
            HttpUriRequestBase outbound = new HttpUriRequestBase(request.method(), request.uri());
            outbound.setConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.of(request.timeout()))
                    .setResponseTimeout(Timeout.of(request.timeout()))
                    .setRedirectsEnabled(false)
                    .build());
            if ("POST".equals(request.method())) {
                outbound.setEntity(new ByteArrayEntity(request.body(), ContentType.APPLICATION_OCTET_STREAM));
            }
            request.headers().forEach(outbound::setHeader);
            ScheduledFuture<?> deadline = REQUEST_DEADLINES.schedule(outbound::cancel,
                    Math.max(1, request.timeout().toMillis()), TimeUnit.MILLISECONDS);
            try (CloseableHttpClient client = HttpClients.custom()
                    .setConnectionManager(manager)
                    .disableRedirectHandling()
                    .disableContentCompression()
                    .build()) {
                try (CloseableHttpResponse response = client.execute(outbound)) {
                    Map<String, List<String>> headers = new LinkedHashMap<>();
                    for (Header header : response.getHeaders()) {
                        headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
                                .add(header.getValue());
                    }
                    HttpEntity entity = response.getEntity();
                    byte[] body = new byte[0];
                    if (entity != null && entity.getContent() != null) {
                        long declaredLength = entity.getContentLength();
                        if (declaredLength > maxResponseBytes) {
                            throw new IOException("HTTP response body exceeds limit");
                        }
                        body = entity.getContent().readNBytes(maxResponseBytes + 1);
                    }
                    return new RawResponse(response.getCode(),
                            HttpHeaders.of(headers, (name, value) -> true),
                            new ByteArrayInputStream(body));
                }
            } finally {
                deadline.cancel(false);
            }
        }

        private static String normalizeHost(String host) {
            if (host == null) return "";
            String normalized = host.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            while (normalized.endsWith(".")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }
    }
}
