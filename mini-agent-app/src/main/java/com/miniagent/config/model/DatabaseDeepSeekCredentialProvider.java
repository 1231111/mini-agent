package com.miniagent.config.model;

import com.miniagent.agent.web.DeepSeekCredentialProvider;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.service.UserModelConfigService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Exposes a user's DB-backed key to DeepSeek web tools only when the saved model endpoint
 * is the official DeepSeek API. A credential for another provider must never be forwarded.
 */
@Slf4j
@Component
public class DatabaseDeepSeekCredentialProvider implements DeepSeekCredentialProvider {
    private static final String DEEPSEEK_API_ROOT = "https://api.deepseek.com";
    private static final Set<String> ALLOWED_PATHS = Set.of("", "/", "/v1", "/v1/");

    private final UserModelConfigService modelConfigService;

    public DatabaseDeepSeekCredentialProvider(UserModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @Override
    public Optional<Credential> currentUserCredential() {
        Long userId = OwnerContext.currentUserId();
        if (userId == null) return Optional.empty();

        EffectiveModelSettings settings;
        try {
            settings = modelConfigService.getEffective(userId);
        } catch (RuntimeException e) {
            log.warn("读取用户 DeepSeek 凭据失败: userId={} type={}", userId, e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (settings == null || StringUtils.isBlank(settings.apiKey())
                || !isOfficialDeepSeekEndpoint(settings.baseUrl())) {
            return Optional.empty();
        }
        return Optional.of(new Credential(settings.apiKey(), DEEPSEEK_API_ROOT));
    }

    static boolean isOfficialDeepSeekEndpoint(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) return false;
        try {
            URI uri = URI.create(baseUrl.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "api.deepseek.com".equals(uri.getHost() == null
                            ? null : uri.getHost().toLowerCase(Locale.ROOT))
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && ALLOWED_PATHS.contains(Optional.ofNullable(uri.getPath()).orElse(""));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
