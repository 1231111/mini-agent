package com.miniagent.agent.security;

import com.miniagent.common.MessageConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF guard for http_get / browser_navigate / web fetch.
 */
@Component
public class NetworkGuard {

    @Value("${agent.tools.block-private-network:true}")
    private boolean blockPrivate;

    /** @return null if OK, else error message */
    public String validateUrl(String url) {
        if (url == null || url.isBlank()) return MessageConstants.NET_URL_EMPTY;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return String.format(MessageConstants.NET_URL_INVALID, e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return MessageConstants.NET_PROTOCOL_NOT_ALLOWED;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) return MessageConstants.NET_HOST_MISSING;
        if (!blockPrivate) return null;
        if (isBlockedHost(host)) {
            return String.format(MessageConstants.NET_SSRF_BLOCKED_HOST, host);
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
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
        if (host == null) return true;
        String h = host.toLowerCase(Locale.ROOT).trim();
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("0.0.0.0")
                || h.equals("::1") || h.equals("[::1]")) return true;
        if (h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".localhost")) return true;
        if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return true;
        // 172.16.0.0 – 172.31.255.255
        if (h.startsWith("172.")) {
            String[] parts = h.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        return addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress();
    }
}
