package com.miniagent.account.config;

import com.miniagent.common.security.RepositoryCredentialDefaults;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Fails fast when the account service is started with production settings. */
@Component
@Profile("prod")
public class AccountProductionReadinessValidator {
    private final Environment environment;

    public AccountProductionReadinessValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        List<String> errors = new ArrayList<>();
        requireConfiguredAlias("DB_URL", errors);
        validateDatabaseTls(errors);
        validatePrivateBindOrTls(errors);
        validateSharedRedis(errors);
        String databaseUsername = requireConfiguredAlias("DB_USERNAME", errors);
        if (!isProductionDatabaseUsername(databaseUsername)) {
            errors.add("DB_USERNAME must name a dedicated database user, not root");
        }
        requireSecret("spring.datasource.password", "DB_PASSWORD", 32, errors);
        requireSecret("agent.account.internal-key", "ACCOUNT_INTERNAL_KEY", 32, errors);
        if (environment.getProperty("agent.account.membership.simulated-payment-enabled", Boolean.class, false)) {
            errors.add("simulated membership payment must remain disabled in production");
        }
        validateMemoryTokenKey(errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Account production readiness validation failed: "
                    + String.join("; ", errors));
        }
    }

    private String require(String property, String envAlias, List<String> errors) {
        String value = effectiveValue(property, envAlias);
        if (value.isBlank()) {
            errors.add(property + " is required (env " + envAlias + ")");
        }
        return value;
    }

    private String requireConfiguredAlias(String envAlias, List<String> errors) {
        String value = environment.getProperty(envAlias, "");
        value = value == null ? "" : value.trim();
        if (value.isBlank()) {
            errors.add(envAlias + " is required; production must explicitly configure a dedicated database account");
        }
        return value;
    }

    private static boolean isProductionDatabaseUsername(String value) {
        return value != null && !value.isBlank() && !"root".equalsIgnoreCase(value.trim());
    }

    private String requireSecret(String property, String envAlias, int minimumBytes, List<String> errors) {
        String value = require(property, envAlias, errors);
        if (value.isBlank()) {
            return value;
        }
        if (RepositoryCredentialDefaults.isKnownDefault(value)) {
            errors.add(property + " must not use a repository development credential");
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("replace-") || normalized.contains("changeme")) {
            errors.add(property + " still contains a placeholder value");
        }
        if (value.trim().getBytes(StandardCharsets.UTF_8).length < minimumBytes) {
            errors.add(property + " must be at least " + minimumBytes + " bytes");
        }
        return value;
    }

    private void validateMemoryTokenKey(List<String> errors) {
        String value = require("agent.account.memory-token-key", "ACCOUNT_MEMORY_TOKEN_KEY", errors);
        if (value.isBlank()) {
            return;
        }
        if (RepositoryCredentialDefaults.isKnownDefault(value)) {
            errors.add("agent.account.memory-token-key must not use a repository development credential");
        }
        try {
            if (Base64.getDecoder().decode(value.trim()).length != 32) {
                errors.add("ACCOUNT_MEMORY_TOKEN_KEY must decode to 32 bytes");
            }
        } catch (IllegalArgumentException e) {
            errors.add("ACCOUNT_MEMORY_TOKEN_KEY must be valid Base64");
        }
    }

    private void validateSharedRedis(List<String> errors) {
        String redisHost = requireConfiguredAlias("REDIS_HOST", errors);
        if (redisHost.equalsIgnoreCase("localhost") || redisHost.equals("127.0.0.1")
                || redisHost.equals("::1") || redisHost.equals("0.0.0.0")) {
            errors.add("REDIS_HOST must point to shared non-loopback Redis for cross-replica login throttling");
        }
        requireSecret("spring.data.redis.password", "REDIS_PASSWORD", 32, errors);
        if (!environment.getProperty("spring.data.redis.ssl.enabled", Boolean.class, false)) {
            errors.add("spring.data.redis.ssl.enabled must be true (env REDIS_SSL_ENABLED) in production");
        }
    }

    private String effectiveValue(String property, String envAlias) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(envAlias, "");
        }
        return value == null ? "" : value.trim();
    }

    private void validateDatabaseTls(List<String> errors) {
        String url = environment.getProperty("spring.datasource.url",
                environment.getProperty("DB_URL", ""));
        String normalized = url.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("usessl=false") || normalized.contains("sslmode=disabled")
                || (!normalized.contains("sslmode=verify_identity")
                    && !normalized.contains("sslmode=verify_ca"))) {
            errors.add("spring.datasource.url must enable MySQL TLS with sslMode=VERIFY_IDENTITY (or VERIFY_CA)");
        }
    }

    private void validatePrivateBindOrTls(List<String> errors) {
        boolean tls = environment.getProperty("server.ssl.enabled", Boolean.class, false);
        String address = environment.getProperty("server.address", "127.0.0.1").trim();
        if (!tls && (address.isEmpty() || "0.0.0.0".equals(address) || "::".equals(address))) {
            errors.add("production server must bind privately when TLS is terminated by a reverse proxy");
        }
    }
}
