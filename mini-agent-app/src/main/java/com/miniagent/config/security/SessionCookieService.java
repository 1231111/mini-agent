package com.miniagent.config.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-signed session cookies: uid + uexp + usig.
 * Plain uid alone is no longer trusted.
 */
@Service
public class SessionCookieService {

    public static final String COOKIE_UID = "uid";
    public static final String COOKIE_EXP = "uexp";
    public static final String COOKIE_SIG = "usig";
    public static final String COOKIE_UNAME = "uname";
    public static final String ATTR_USER_ID = "authUserId";

    @Value("${agent.auth.cookie-secret}")
    private String cookieSecret;
    @Value("${agent.auth.cookie-max-age-seconds:604800}")
    private int maxAgeSeconds;
    private byte[] secret;

    @PostConstruct
    private void initSecret() {
        if (cookieSecret == null || cookieSecret.isBlank()) {
            throw new IllegalStateException("agent.auth.cookie-secret must be set");
        }
        this.secret = cookieSecret.getBytes(StandardCharsets.UTF_8);
    }

    public void issueSession(HttpServletResponse response, Long userId, String username) {
        long exp = System.currentTimeMillis() / 1000 + maxAgeSeconds;
        String sig = sign(userId, exp);
        addCookie(response, COOKIE_UID, String.valueOf(userId), maxAgeSeconds, true);
        addCookie(response, COOKIE_EXP, String.valueOf(exp), maxAgeSeconds, true);
        addCookie(response, COOKIE_SIG, sig, maxAgeSeconds, true);
        if (username != null) {
            addCookie(response, COOKIE_UNAME, username, maxAgeSeconds, false);
        }
    }

    public void clearSession(HttpServletResponse response) {
        addCookie(response, COOKIE_UID, "", 0, true);
        addCookie(response, COOKIE_EXP, "", 0, true);
        addCookie(response, COOKIE_SIG, "", 0, true);
        addCookie(response, COOKIE_UNAME, "", 0, false);
    }

    /** Validate cookies; returns userId or null. */
    public Long resolveUserId(HttpServletRequest request) {
        String uid = cookie(request, COOKIE_UID);
        String expStr = cookie(request, COOKIE_EXP);
        String sig = cookie(request, COOKIE_SIG);
        if (uid == null || expStr == null || sig == null) return null;
        try {
            long userId = Long.parseLong(uid);
            long exp = Long.parseLong(expStr);
            if (exp < System.currentTimeMillis() / 1000) return null;
            String expected = sign(userId, exp);
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    sig.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            return userId;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Long userIdFromRequest(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_USER_ID);
        if (v instanceof Long id) return id;
        return null;
    }

    private String sign(long userId, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal((userId + "|" + exp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static void addCookie(HttpServletResponse response, String name, String value,
                                  int maxAge, boolean httpOnly) {
        Cookie c = new Cookie(name, value);
        c.setPath("/");
        c.setMaxAge(maxAge);
        c.setHttpOnly(httpOnly);
        response.addCookie(c);
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
