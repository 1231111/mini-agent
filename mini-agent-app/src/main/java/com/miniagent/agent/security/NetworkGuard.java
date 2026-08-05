package com.miniagent.agent.security;

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

    private final boolean blockPrivate;

    public NetworkGuard(@Value("${agent.tools.block-private-network:true}") boolean blockPrivate) {
        this.blockPrivate = blockPrivate;
    }

    /** @return null if OK, else error message */
    public String validateUrl(String url) {
        if (url == null || url.isBlank()) return "URL 为空";
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return "非法 URL: " + e.getMessage();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "仅允许 http/https 协议";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) return "URL 缺少 host";
        if (!blockPrivate) return null;
        if (isBlockedHost(host)) {
            return "SSRF 防护：禁止访问内网/本地地址: " + host;
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (isBlockedAddress(addr)) {
                    return "SSRF 防护：解析到内网/本地地址: " + addr.getHostAddress();
                }
            }
        } catch (UnknownHostException e) {
            return "无法解析主机: " + host;
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
