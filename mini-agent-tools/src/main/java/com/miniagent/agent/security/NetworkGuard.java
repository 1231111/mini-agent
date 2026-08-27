package com.miniagent.agent.security;

import com.miniagent.common.MessageConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * SSRF guard for http_get / http_post / browser_navigate / web fetch.
 */
@Component
public class NetworkGuard {

    @Value("${agent.tools.block-private-network:true}")
    private boolean blockPrivate = true;

    private final Set<Integer> allowedPorts;
    private final HostResolver hostResolver;

    public NetworkGuard() {
        this.allowedPorts = Set.of(80, 443);
        this.hostResolver = InetAddress::getAllByName;
    }

    public NetworkGuard(boolean blockPrivate, Set<Integer> allowedPorts,
                        HostResolver hostResolver) {
        this.blockPrivate = blockPrivate;
        this.allowedPorts = allowedPorts == null || allowedPorts.isEmpty()
                ? Set.of(80, 443) : Set.copyOf(allowedPorts);
        if (this.allowedPorts.stream().anyMatch(port -> port == null || port < 1 || port > 65535)) {
            throw new IllegalArgumentException("allowed ports must be between 1 and 65535");
        }
        this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver");
    }

    /** @return null if OK, else error message */
    public String validateUrl(String url) {
        return validate(url, Set.of("http", "https"));
    }

    /** WebSocket endpoints obey the same host, port and DNS restrictions. */
    public String validateWebSocketUrl(String url) {
        return validate(url, Set.of("ws", "wss"));
    }

    private String validate(String url, Set<String> allowedSchemes) {
        if (url == null || url.isBlank()) {
            return MessageConstants.NET_URL_EMPTY;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return String.format(MessageConstants.NET_URL_INVALID, e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedSchemes.contains(scheme)) {
            return allowedSchemes.contains("ws")
                    ? "仅允许 ws/wss 协议"
                    : MessageConstants.NET_PROTOCOL_NOT_ALLOWED;
        }
        if (uri.getRawUserInfo() != null) {
            return "URL credentials are not allowed";
        }
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority != null && (rawAuthority.contains("%25") || rawAuthority.contains("%"))) {
            return "IPv6 zone identifiers are not allowed";
        }
        String host = normalizeHost(uri.getHost());
        if (host == null || host.isBlank()) {
            return MessageConstants.NET_HOST_MISSING;
        }
        int port = uri.getPort() >= 0 ? uri.getPort()
                : (scheme.equals("https") || scheme.equals("wss") ? 443 : 80);
        if (!allowedPorts.contains(port)) {
            return "URL port " + port + " is not allowed";
        }
        if (!blockPrivate) {
            return null;
        }
        if (isBlockedHost(host)) {
            return String.format(MessageConstants.NET_SSRF_BLOCKED_HOST, host);
        }
        try {
            InetAddress[] addrs = hostResolver.resolve(host);
            if (addrs == null || addrs.length == 0) {
                return String.format(MessageConstants.NET_HOST_UNRESOLVABLE, host);
            }
            for (InetAddress addr : addrs) {
                if (addr == null) {
                    return String.format(MessageConstants.NET_HOST_UNRESOLVABLE, host);
                }
                if (isBlockedAddress(addr)) {
                    return String.format(MessageConstants.NET_SSRF_BLOCKED_RESOLVED, addr.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            return String.format(MessageConstants.NET_HOST_UNRESOLVABLE, host);
        }
        return null;
    }

    public static boolean isBlockedHost(String host) {
        if (host == null) {
            return true;
        }
        String h = normalizeHost(host);
        if (h == null || h.isBlank()) return true;
        if (h.equals("localhost") || h.equals("localhost.localdomain")
                || h.equals("0") || h.equals("0.0.0.0")
                || h.startsWith("127.")
                || h.equals("::") || h.equals("::1")) return true;
        if (h.endsWith(".local") || h.endsWith(".internal")
                || h.endsWith(".localhost") || h.endsWith(".home")
                || h.endsWith(".lan")) {
            return true;
        }
        if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) {
            return true;
        }
        // 172.16.0.0 – 172.31.255.255
        if (h.startsWith("172.")) {
            String[] parts = h.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        if (addr == null || addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (bytes.length != 16) {
            return true;
        }

        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        // Unique-local, link-local, multicast, documentation and transition ranges.
        if ((first & 0xfe) == 0xfc
                || (first == 0xfe && (second & 0xc0) == 0x80)
                || first == 0xff
                || prefix(bytes, 0x20, 0x01, 0x0d, 0xb8)
                || prefix(bytes, 0x20, 0x01, 0x00, 0x02)
                || (first == 0x20 && second == 0x02)) {
            return true;
        }

        // IPv4-compatible and IPv4-mapped forms must inherit IPv4 policy.
        boolean firstTenZero = allZero(bytes, 0, 10);
        if (firstTenZero && unsigned(bytes[10]) == 0xff && unsigned(bytes[11]) == 0xff) {
            return isBlockedIpv4(Arrays.copyOfRange(bytes, 12, 16));
        }
        return allZero(bytes, 0, 12)
                && isBlockedIpv4(Arrays.copyOfRange(bytes, 12, 16));
    }

    private static boolean isBlockedIpv4(byte[] address) {
        int a = unsigned(address[0]);
        int b = unsigned(address[1]);
        int c = unsigned(address[2]);
        return a == 0
                || a == 10
                || (a == 100 && b >= 64 && b <= 127)
                || a == 127
                || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 0 && c == 0)
                || (a == 192 && b == 0 && c == 2)
                || (a == 192 && b == 88 && c == 99)
                || (a == 192 && b == 168)
                || (a == 198 && (b == 18 || b == 19))
                || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113)
                || a >= 224;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static boolean prefix(byte[] value, int... prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (unsigned(value[i]) != prefix[i]) return false;
        }
        return true;
    }

    private static boolean allZero(byte[] value, int start, int end) {
        for (int i = start; i < end; i++) {
            if (value[i] != 0) return false;
        }
        return true;
    }

    private static String normalizeHost(String host) {
        if (host == null) return null;
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
