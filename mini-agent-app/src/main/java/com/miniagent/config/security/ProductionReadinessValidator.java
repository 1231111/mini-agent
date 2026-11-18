package com.miniagent.config.security;

import com.miniagent.common.permission.ExecPolicy;
import com.miniagent.common.security.RepositoryCredentialDefaults;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
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
        validateDatabaseTls(errors);
        validatePrivateBindOrTls(errors);
        String databaseUsername = require("DB_USERNAME", errors);
        if (databaseUsername != null && !isProductionDatabaseUsername(databaseUsername)) {
            errors.add("DB_USERNAME must name a dedicated database user, not root");
        }
        requireProductionSecret("spring.datasource.password", "DB_PASSWORD", 32, errors);
        requireProductionSecret("spring.data.redis.password", "REDIS_PASSWORD", 32, errors);
        // The desktop profile intentionally stores the MiMo credential as
        // MIMO_API_KEY in ~/.miniagent/config.yml.  application.yml resolves
        // that value into the effective LangChain4j property, so requiring
        // the environment variable name here incorrectly rejects a valid
        // MiMo-only configuration in prod.
        requireConfiguredLlmKey(errors);
        validateLlmEndpoints(errors);
        require("ALLOWED_ORIGINS", errors);
        String encryptionKey = requireSecret("MODEL_CONFIG_ENCRYPTION_KEY", errors);
        validateEncryptionKey(encryptionKey, errors);
        validateJwtSecret(errors);
        requireProductionSecret("agent.account.cloud.internal-key", "ACCOUNT_INTERNAL_KEY", 32, errors);
        validateIndependentJudge(errors);
        boolean antivirusRequired = environment.getProperty(
                "file.upload.antivirus.required", Boolean.class, false);
        boolean antivirusEnabled = environment.getProperty(
                "file.upload.antivirus.enabled", Boolean.class, false);
        if (antivirusRequired && !antivirusEnabled) {
            errors.add("file.upload.antivirus.enabled must be true when upload antivirus is required");
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

        if (environment.getProperty("agent.auth.registration-enabled", Boolean.class, true)) {
            errors.add("public registration must be disabled");
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
        // exec_command 策略：prod 不允许 allow（免批执行）。ask / block 都放行。
        // 注意这里必须和 ExecPolicyService 用同一套解析 —— 否则会出现
        // "启动断言认为没放开、运行时 ExecPolicyService 解析成了 allow 而不是 ask" 这种自相矛盾。
        // 之前这个断言还有个更根本的问题：它只检查"配置填得对不对"，
        // 而运行时那次工具调用根本没有代码读它（custom-base-url-enabled 也一样）。
        // 现在 exec 这一侧真实生效的闸门在 ExecPolicyService + ToolPipeline。
        String execPolicyRaw = environment.getProperty("agent.tools.exec-policy", "");
        ExecPolicy execPolicy = ExecPolicy.parse(execPolicyRaw);
        if (execPolicy == null) {
            if (StringUtils.isNotBlank(execPolicyRaw)) {
                errors.add("agent.tools.exec-policy is not recognized: '" + execPolicyRaw
                        + "' (expected block / ask / allow)");
            }
            String legacy = environment.getProperty("agent.tools.exec-enabled", "");
            execPolicy = ExecPolicy.fromLegacyExecEnabled(legacy);
            if (execPolicy == null && StringUtils.isNotBlank(legacy)) {
                errors.add("agent.tools.exec-enabled is not recognized: '" + legacy
                        + "' (expected true / false; prefer agent.tools.exec-policy)");
            }
        }
        if (!isProductionExecPolicy(execPolicyRaw)) {
            errors.add("exec tool must be blocked in production until command isolation is available "
                    + "(agent.tools.exec-policy must be block)");
        }
        validateProcessEnvironmentPassthrough(errors);
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

    private String requireProductionSecret(String property, String envAlias, int minimumBytes,
                                           List<String> errors) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(envAlias, "");
        }
        if (value == null || value.isBlank()) {
            errors.add(property + " is required (env " + envAlias + ")");
            return value;
        }
        if (RepositoryCredentialDefaults.isKnownDefault(value)) {
            errors.add(property + " must not use a repository development credential");
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("replace-") || normalized.contains("changeme")) {
            errors.add(property + " still contains a placeholder value");
        }
        if (value.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < minimumBytes) {
            errors.add(property + " must be at least " + minimumBytes + " bytes");
        }
        return value;
    }

    private void validateProcessEnvironmentPassthrough(List<String> errors) {
        String passthrough = environment.getProperty("agent.tools.env-passthrough", "");
        if (hasProductionEnvironmentPassthrough(passthrough)) {
            errors.add("agent.tools.env-passthrough must be empty in production; shell commands run without isolation");
        }
    }

    /**
     * Validate the credential actually consumed by the chat client.  Keep
     * environment-variable compatibility while accepting the supported MiMo
     * configuration ({@code MIMO_API_KEY}) and its resolved property.
     */
    private String requireConfiguredLlmKey(List<String> errors) {
        String value = environment.getProperty("langchain4j.open-ai.chat-model.api-key", "");
        if (value == null || value.isBlank()) {
            value = environment.getProperty("LLM_API_KEY", "");
        }
        if (value == null || value.isBlank()) {
            value = environment.getProperty("MIMO_API_KEY", "");
        }
        if (value == null || value.isBlank()) {
            errors.add("LLM_API_KEY or MIMO_API_KEY is required");
            return value;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("replace-") || normalized.contains("changeme")) {
            errors.add("LLM_API_KEY/MIMO_API_KEY still contains a placeholder value");
        }
        return value;
    }

    private void validateLlmEndpoints(List<String> errors) {
        requireHttpsEndpoint("langchain4j.open-ai.chat-model.base-url", "LLM_BASE_URL", errors);
        requireHttpsEndpoint("langchain4j.open-ai.streaming-chat-model.base-url",
                "LLM_BASE_URL", errors);
    }

    /**
     * Production enables the independent semantic judge so that the executor
     * cannot grade its own output.  Treat a missing judge configuration as a
     * startup error instead of allowing the runtime to silently fall back to
     * the execution model.
     */
    private void validateIndependentJudge(List<String> errors) {
        if (!environment.getProperty("agent.planner.require-independent-judge", Boolean.class, false)) {
            return;
        }
        requireNonBlank("agent.planner.judge-model-name", "AGENT_JUDGE_MODEL_NAME", errors);
        requireHttpsEndpoint("agent.planner.judge-base-url", "AGENT_JUDGE_BASE_URL", errors);
        requireProductionSecret("agent.planner.judge-api-key", "AGENT_JUDGE_API_KEY", 16, errors);
    }

    private String requireHttpsEndpoint(String property, String envAlias, List<String> errors) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(envAlias, "");
        }
        if (value == null || value.isBlank()) {
            errors.add(property + " is required and must use HTTPS (env " + envAlias + ")");
            return "";
        }
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
                errors.add(property + " must be an absolute HTTPS URL without credentials, query or fragment");
            }
        } catch (IllegalArgumentException e) {
            errors.add(property + " must be a valid absolute HTTPS URL");
        }
        return value.trim();
    }

    private String requireNonBlank(String property, String envAlias, List<String> errors) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(envAlias, "");
        }
        if (value == null || value.isBlank()) {
            errors.add(property + " is required when independent judge is enabled (env " + envAlias + ")");
            return "";
        }
        return value.trim();
    }

    /**
     * JWT 签名密钥（{@code agent.auth.jwt-secret}，env {@code JWT_SECRET}，旧名 {@code COOKIE_SECRET}）。
     *
     * <p>此前校验的是 cookie 三件套（COOKIE_SECRET / secure-cookie / same-site）——
     * cookie 通路已随无状态改造整体移除，那三项已无任何消费者，留着只会让运维维护
     * 一个没人读的密钥。签名密钥本身仍然必须校验，这里换成它的新名字。
     */
    private void validateJwtSecret(List<String> errors) {
        // yml 里是 jwt-secret: ${JWT_SECRET:${COOKIE_SECRET:}}，所以读解析后的属性就够，
        // 不必再单独看环境变量名 —— 旧名 COOKIE_SECRET 也能经回退生效。
        String secret = environment.getProperty("agent.auth.jwt-secret", "");
        if (secret == null || secret.isBlank()) {
            errors.add("agent.auth.jwt-secret is required (env JWT_SECRET)");
            return;
        }
        String normalized = secret.trim().toLowerCase(java.util.Locale.ROOT);
        if (RepositoryCredentialDefaults.isKnownDefault(secret)
                || normalized.contains("not-for-production") || normalized.contains("replace-")
                || normalized.contains("changeme")) {
            errors.add("agent.auth.jwt-secret must not be a placeholder or local development default");
        }
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 8 < 256) {
            errors.add("agent.auth.jwt-secret must be at least 256 bits for HS256");
        }
    }

    static boolean isProductionExecPolicy(String value) {
        return ExecPolicy.parse(value) == ExecPolicy.BLOCK;
    }

    static boolean hasProductionEnvironmentPassthrough(String value) {
        return value != null && !value.isBlank();
    }

    static boolean isProductionDatabaseUsername(String value) {
        return value != null && !value.isBlank() && !"root".equalsIgnoreCase(value.trim());
    }

    private static void validateEncryptionKey(String encoded, List<String> errors) {        if (encoded == null || encoded.isBlank()) {
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

    private void validateDatabaseTls(List<String> errors) {
        String url = environment.getProperty("spring.datasource.url",
                environment.getProperty("DB_URL", ""));
        String normalized = url.toLowerCase(java.util.Locale.ROOT);
        // A production URL must negotiate and verify the MySQL server
        // certificate.  Do not accept the legacy useSSL=false form or an
        // explicit disabled sslMode even when DB_URL is supplied by env.
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
