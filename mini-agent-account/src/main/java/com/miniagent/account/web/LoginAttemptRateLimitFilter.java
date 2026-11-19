package com.miniagent.account.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Bounds password-login attempts by source address and normalized account. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public final class LoginAttemptRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final String[] LOGIN_PATHS = {"/api/tokens", "/api/portal/session"};

    private final ObjectMapper json;
    private final LoginAttemptRateLimiter limiter;
    private final List<IpRange> trustedProxies;

    public LoginAttemptRateLimitFilter(ObjectMapper json, LoginAttemptRateLimiter limiter,
            @Value("${agent.account.login-rate-limit.trusted-proxies:}") String trustedProxyConfig) {
        this.json = json;
        this.limiter = limiter;
        this.trustedProxies = parseTrustedProxyCidrs(trustedProxyConfig);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
        return Arrays.stream(LOGIN_PATHS).noneMatch(path::equals);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        String accountKey = accountKey(body);
        String ip = clientAddress(request);
        long retryAfter;
        try {
            retryAfter = limiter.acquire(accountKey, ip);
        } catch (RuntimeException unavailable) {
            // Login admission is a security control; never turn Redis failures into fail-open traffic.
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Retry-After", "5");
            response.getWriter().write(json.writeValueAsString(
                    ApiResponse.fail(ErrorCode.AUTH_LOGIN_FAILED, "登录服务暂不可用，请稍后重试")));
            return;
        }
        if (retryAfter > 0) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.getWriter().write(json.writeValueAsString(
                    ApiResponse.fail(ErrorCode.AUTH_LOGIN_FAILED, "登录尝试过于频繁，请稍后重试")));
            return;
        }
        chain.doFilter(new ReplayableBodyRequest(request, body), response);
    }

    private String accountKey(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode username = root == null ? null : root.get("username");
            String value = username != null && username.isTextual() ? username.textValue() : "<invalid>";
            String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            if (normalized.length() > 256) normalized = normalized.substring(0, 256);
            return normalized;
        } catch (Exception ignored) {
            return "<invalid>";
        }
    }

    private String clientAddress(HttpServletRequest request) {
        String peer = normalizeIpLiteral(request.getRemoteAddr());
        if (peer == null) peer = "unknown";
        if (!isTrustedProxy(peer)) return peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 2048) return peer;
        String[] hops = forwarded.split(",", 17);
        if (hops.length > 16) return peer;
        String current = peer;
        for (int i = hops.length - 1; i >= 0 && isTrustedProxy(current); i--) {
            String candidate = normalizeIpLiteral(hops[i].trim());
            if (candidate == null) break;
            current = candidate;
        }
        return current;
    }

    private boolean isTrustedProxy(String address) {
        return trustedProxies.stream().anyMatch(range -> range.contains(address));
    }

    private static List<IpRange> parseTrustedProxyCidrs(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return Arrays.stream(value.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).map(IpRange::parse).toList();
        } catch (RuntimeException e) {
            throw new IllegalStateException("agent.account.login-rate-limit.trusted-proxies must contain valid IP/CIDR values", e);
        }
    }

    private static byte[] addressBytes(String value) {
        if (value == null || value.isBlank() || !value.matches("[0-9a-fA-F:.]+")) return null;
        if (value.indexOf(':') < 0) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) return null;
            for (String octet : octets) {
                if (octet.isEmpty() || (octet.length() > 1 && octet.charAt(0) == '0')) return null;
                try { if (Integer.parseInt(octet) > 255) return null; }
                catch (NumberFormatException e) { return null; }
            }
        }
        try { return InetAddress.getByName(value).getAddress(); }
        catch (UnknownHostException e) { return null; }
    }

    private static String normalizeIpLiteral(String value) {
        byte[] bytes = addressBytes(value);
        if (bytes == null) return null;
        try { return InetAddress.getByAddress(bytes).getHostAddress(); }
        catch (UnknownHostException impossible) { return null; }
    }

    private record IpRange(byte[] network, int prefixBits) {
        private static IpRange parse(String value) {
            String[] parts = value.split("/", -1);
            if (parts.length > 2) throw new IllegalArgumentException("Invalid CIDR");
            byte[] address = addressBytes(parts[0].trim());
            if (address == null) throw new IllegalArgumentException("Invalid IP address");
            int maxBits = address.length * 8;
            int prefix = parts.length == 1 ? maxBits : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > maxBits) throw new IllegalArgumentException("Invalid CIDR prefix");
            return new IpRange(address, prefix);
        }
        private boolean contains(String value) {
            byte[] candidate = addressBytes(value);
            if (candidate == null || candidate.length != network.length) return false;
            int full = prefixBits / 8;
            for (int i = 0; i < full; i++) if (candidate[i] != network[i]) return false;
            int remainder = prefixBits % 8;
            if (remainder == 0) return true;
            int mask = 0xff << (8 - remainder);
            return ((candidate[full] & 0xff) & mask) == ((network[full] & 0xff) & mask);
        }
    }

    private static final class ReplayableBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private ReplayableBodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding())));
        }
    }
}
