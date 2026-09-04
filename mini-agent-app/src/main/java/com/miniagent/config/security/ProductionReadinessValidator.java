package com.miniagent.config.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.net.URI;
import java.time.ZoneId;

/** Fails fast when a production process would otherwise start insecurely. */
@Component
@Profile("prod")
public class ProductionReadinessValidator {

    @Autowired
    private Environment environment;

    @PostConstruct
    void validate() {
        List<String> errors = new ArrayList<>();
        require("DB_URL", errors);
        require("DB_USERNAME", errors);
        requireSecret("DB_PASSWORD", errors);
        requireSecret("REDIS_PASSWORD", errors);
        requireSecret("LLM_API_KEY", errors);
        require("ALLOWED_ORIGINS", errors);
        String encryptionKey = requireSecret("MODEL_CONFIG_ENCRYPTION_KEY", errors);
        validateEncryptionKey(encryptionKey, errors);
        requireSecret("COOKIE_SECRET", errors);
        String cookieSecret = environment.getProperty(
                "agent.auth.cookie-secret", environment.getProperty("COOKIE_SECRET", ""));
        if (cookieSecret != null) {
            String cookie = cookieSecret.toLowerCase(java.util.Locale.ROOT);
            if (cookie.contains("not-for-production") || cookie.contains("local-dev-cookie")) {
                errors.add("COOKIE_SECRET must not be the local development default");
            }
        }
        String bootstrapUsername = environment.getProperty("BOOTSTRAP_ADMIN_USERNAME", "").trim();
        String bootstrapPassword = environment.getProperty("BOOTSTRAP_ADMIN_PASSWORD", "");
        if (!bootstrapUsername.isEmpty() || !bootstrapPassword.isBlank()) {
            if (bootstrapUsername.isEmpty()) {
                errors.add("BOOTSTRAP_ADMIN_USERNAME is required when bootstrap password is set");
            }
            String normalized = bootstrapPassword.trim().toLowerCase(java.util.Locale.ROOT);
            if (bootstrapPassword.length() < 16 || normalized.contains("replace-")
                    || normalized.contains("changeme")) {
                errors.add("bootstrap administrator password must be a non-placeholder value of at least 16 characters");
            }
        }

        if (!environment.getProperty("agent.auth.secure-cookie", Boolean.class, false)) {
            errors.add("agent.auth.secure-cookie must be true");
        }
        if (environment.getProperty("agent.auth.registration-enabled", Boolean.class, true)) {
            errors.add("public registration must be disabled");
        }
        if (!"Strict".equalsIgnoreCase(environment.getProperty("agent.auth.same-site", ""))) {
            errors.add("agent.auth.same-site must be Strict");
        }
        if (environment.getProperty("agent.auth.password-min-length", Integer.class, 0) < 12) {
            errors.add("password minimum length must be at least 12");
        }
        if (environment.getProperty("agent.auth.bcrypt-strength", Integer.class, 0) < 12) {
            errors.add("BCrypt strength must be at least 12");
        }
        if (environment.getProperty("agent.auth.jwt-exp-seconds", Integer.class, 0) < 300) {
            errors.add("session lifetime (agent.auth.jwt-exp-seconds) must be at least 300 seconds");
        }
        String origins = environment.getProperty("agent.auth.allowed-origins", "");
        for (String origin : origins.split(",")) {
            String value = origin.trim();
            if (!value.isEmpty() && !isExplicitHttpsOrigin(value)) {
                errors.add("allowed origins must be exact HTTPS origins without path, query or credentials");
                break;
            }
        }
        String ddl = environment.getProperty("spring.jpa.hibernate.ddl-auto", "");
        if (!"validate".equalsIgnoreCase(ddl)) {
            errors.add("Hibernate ddl-auto must be validate");
        }
        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, false)) {
            errors.add("Flyway must be enabled");
        }
        if (!"redis".equalsIgnoreCase(environment.getProperty("agent.replica.mode", ""))) {
            errors.add("agent.replica.mode must be redis");
        }
        if (environment.getProperty("agent.models.custom-base-url-enabled", Boolean.class, true)) {
            errors.add("custom model Base URLs must be disabled in production");
        }
        if (environment.getProperty("agent.tools.exec-enabled", Boolean.class, false)) {
            errors.add("exec tool must be explicitly reviewed before enabling in production");
        }
        if (environment.getProperty("agent.tools.allow-absolute-write", Boolean.class, false)) {
            errors.add("absolute file writes must be disabled in production");
        }
        if (!environment.getProperty("agent.tools.block-private-network", Boolean.class, false)) {
            errors.add("private-network SSRF blocking must be enabled in production");
        }
        if (!hasOnlyDefaultWebPorts(environment.getProperty(
                "agent.tools.allowed-http-ports", ""))) {
            errors.add("production outbound HTTP ports must be limited to 80 and 443");
        }
        int redirects = environment.getProperty(
                "agent.tools.max-http-redirects", Integer.class, -1);
        if (redirects < 0 || redirects > 5) {
            errors.add("agent.tools.max-http-redirects must be between 0 and 5");
        }
        if (environment.getProperty("agent.browser.evaluate-enabled", Boolean.class, true)) {
            errors.add("browser_evaluate must be disabled in production");
        }
        if (!environment.getProperty("agent.browser.headless", Boolean.class, false)) {
            errors.add("the production browser must run headless");
        }
        try {
            ZoneId.of(environment.getProperty("agent.quota.zone-id", ""));
        } catch (Exception e) {
            errors.add("agent.quota.zone-id must be a valid explicit time zone");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Production readiness validation failed: "
                    + String.join("; ", errors));
        }
    }

    private String require(String key, List<String> errors) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            errors.add(key + " is required");
        }
        return value;
    }

    private String requireSecret(String key, List<String> errors) {
        String value = require(key, errors);
        if (value != null) {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("replace-") || normalized.contains("changeme")) {
                errors.add(key + " still contains a placeholder value");
            }
        }
        return value;
    }

    private static void validateEncryptionKey(String encoded, List<String> errors) {
        if (encoded == null || encoded.isBlank()) {
            return;
        }
        try {
            if (Base64.getDecoder().decode(encoded.trim()).length != 32) {
                errors.add("MODEL_CONFIG_ENCRYPTION_KEY must decode to 32 bytes");
            }
        } catch (IllegalArgumentException e) {
            errors.add("MODEL_CONFIG_ENCRYPTION_KEY must be valid Base64");
        }
    }

    private static boolean isExplicitHttpsOrigin(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasOnlyDefaultWebPorts(String configured) {
        java.util.Set<String> ports = new java.util.HashSet<>();
        for (String value : configured.split(",")) {
            if (!value.isBlank()) {
                ports.add(value.trim());
            }
        }
        return ports.equals(java.util.Set.of("80", "443"));
    }
}
