package com.miniagent.config.service;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.config.entity.UserModelConfig;
import com.miniagent.config.model.AgentModelsProperties;
import com.miniagent.config.model.EffectiveModelSettings;
import com.miniagent.config.repository.UserModelConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.commons.lang3.StringUtils;

/**
 * 按用户解析 / 持久化聊天模型配置。API 回显永远脱敏。
 *
 * <p><b>自定义 Base URL 的闸门在本类里，不在 {@code ProductionReadinessValidator} 里。</b>
 * 那个校验器只管 prod 档（它还要求 {@code ddl-auto=validate}、Flyway 开启、
 * {@code agent.replica.mode=redis}），客户端档下它一次都不会执行。
 * 所以就出现了「配置里写着 {@code custom-base-url-enabled=false}，
 * 但运行时没有任何代码读它」这种状态 —— 见 {@link #customBaseUrlEnabled}。
 */
@Slf4j
@Service
public class UserModelConfigService {

    static final int UNKNOWN_CUSTOM_CONTEXT_WINDOW = 8192;
    static final int MIN_CONTEXT_WINDOW = 1024;
    static final int MAX_CONTEXT_WINDOW = 10_000_000;

    @Autowired
    private UserModelConfigRepository repository;
    @Autowired
    private AgentModelsProperties modelsProperties;
    /** 模型密钥的静态加密（写入加密、读取解密）。 */
    @Autowired
    private com.miniagent.config.security.SecretCryptoService crypto;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String globalApiKey;
    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String globalBaseUrl;
    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String globalModelName;

    /**
     * 是否允许用户自定义模型 Base URL。默认 true（保持原有开发行为），
     * prod 档显式置 false；desktop 允许用户使用自带 Key 的独立端点。
     *
     * <p>为什么这个开关必须由业务代码执行、不能只写在配置里：
     * {@code ProductionReadinessValidator} 也检查同一个键，但它是<b>启动断言</b>，
     * 且只在 prod 档运行。桌面客户端档跑不到那里，于是那条配置形同注释。
     *
     * <p>URL、模型和凭据作为一组校验。自定义端点不同于预设端点时，
     * 只能使用用户自己的 Key，不能回退到平台 Key。
     */
    @Value("${agent.models.custom-base-url-enabled:true}")
    private boolean customBaseUrlEnabled;

    @PostConstruct
    void logBaseUrlPolicy() {
        log.info("自定义模型 Base URL: {}（agent.models.custom-base-url-enabled={}）",
                customBaseUrlEnabled ? "允许" : "禁止", customBaseUrlEnabled);
    }

    /** 供对话路径建连 */
    public EffectiveModelSettings getEffective(Long userId) {
        UserModelConfig row = Objects.isNull(userId) ? null : repository.findByUserId(userId).orElse(null);
        return resolve(row);
    }

    /** 仅按预设解析（多模态 vision 切换，不套用户自定义覆盖） */
    public EffectiveModelSettings getEffectivePreset(String presetId) {
        return resolvePresetOnly(presetId);
    }

    /** GET /api/model-config 视图（无明文 key） */
    public Map<String, Object> getView(Long userId) {
        UserModelConfig row = Objects.isNull(userId) ? null : repository.findByUserId(userId).orElse(null);
        EffectiveModelSettings eff;
        String configurationError = null;
        try {
            eff = resolve(row);
        } catch (IllegalStateException invalid) {
            // Keep settings repairable without presenting a fallback endpoint as effective.
            eff = resolvePresetOnly(Objects.isNull(row) ? null : row.getPresetId());
            configurationError = invalid.getMessage();
        }
        boolean configurationValid = configurationError == null;

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("presetId", eff.presetId());
        current.put("label", eff.label());
        current.put("baseUrl", configurationValid ? eff.baseUrl() : "");
        current.put("modelName", configurationValid ? eff.modelName() : "");
        current.put("hasApiKey", configurationValid && StringUtils.isNotBlank(eff.apiKey()));
        // 只脱敏"用户自己的 key"。全局/预设 key 的后四位不该回显给每个登录用户 ——
        // 那属于平台凭据，泄露四位没有任何业务价值，却能被用来确认密钥是否被更换。
        boolean hasOwnKey = Objects.nonNull(row) && StringUtils.isNotBlank(row.getCustomApiKey());
        current.put("apiKeyMasked", hasOwnKey && configurationValid ? maskKey(eff.apiKey()) : "");
        current.put("apiKeySource", configurationValid ? (hasOwnKey ? "user" : "global") : "none");
        current.put("configurationValid", configurationValid);
        current.put("configurationError", configurationError);
        current.put("customBaseUrl", Objects.isNull(row) ? "" : nullToEmpty(row.getCustomBaseUrl()));
        current.put("customModelName", Objects.isNull(row) ? "" : nullToEmpty(row.getCustomModelName()));
        current.put("hasCustomApiKey", Objects.nonNull(row) && Objects.nonNull(row.getCustomApiKey()) && StringUtils.isNotBlank(row.getCustomApiKey()));
        int requestedWindow = row == null || row.getCustomContextWindowTokens() == null ? 0 : row.getCustomContextWindowTokens();
        boolean unknownCustomWindow = configurationValid && requestedWindow <= 0
                && !matchesPresetModel(eff, resolvePresetOnly(eff.presetId()));
        current.put("customContextWindowTokens", requestedWindow);
        current.put("contextWindowTokens", configurationValid ? eff.contextWindowTokens() : 0);
        current.put("contextWindowSource", unknownCustomWindow ? "conservative" : requestedWindow > 0 ? "user" : "preset");
        current.put("contextWindowWarning", unknownCustomWindow
                ? "自定义模型窗口未确认，暂按8192 token保守预算运行；请按服务商实际输入窗口填写。" : "");

        List<Map<String, Object>> presets = new ArrayList<>();
        for (AgentModelsProperties.Preset p : modelsProperties.getPresets()) {
            if (Objects.isNull(p) || Objects.isNull(p.getId())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("label", Optional.ofNullable(p.getLabel()).orElse(p.getId()));
            EffectiveModelSettings pe = resolvePresetOnly(p.getId());
            item.put("baseUrl", pe.baseUrl());
            item.put("modelName", pe.modelName());
            item.put("hasApiKey", Objects.nonNull(pe.apiKey()) && StringUtils.isNotBlank(pe.apiKey()));
            item.put("contextWindowTokens", pe.contextWindowTokens());
            presets.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("current", current);
        out.put("presets", presets);
        out.put("defaultPreset", modelsProperties.getDefaultPreset());
        // 前端据此决定要不要让用户编辑 Base URL 输入框。不告诉它的话，
        // 用户填了地址、保存成功、实际却没生效 —— 这种「成功但没用」最难排查。
        out.put("customBaseUrlAllowed", customBaseUrlEnabled);
        out.put("configurationError", configurationError);
        return out;
    }

    /**
     * 保存用户配置。
     * apiKey 为 null 或空串 → 不覆盖已存自定义 key；传 "__CLEAR__" 可清空自定义 key。
     */
    @Transactional
    public Map<String, Object> save(Long userId, String presetId, String baseUrl, String modelName, String apiKey) {
        return save(userId, presetId, baseUrl, modelName, apiKey, null);
    }

    /** Missing window preserves an existing value only for the same effective model and endpoint. */
    @Transactional
    public Map<String, Object> save(Long userId, String presetId, String baseUrl, String modelName, String apiKey,
                                    Integer contextWindowTokens) {
        if (Objects.isNull(userId)) {
            return Map.of("success", false, "message", "Not authenticated");
        }
        String wantedBaseUrl = blankToNull(baseUrl);
        String wantedModelName = blankToNull(modelName);
        if (contextWindowTokens != null && contextWindowTokens != 0
                && (contextWindowTokens < MIN_CONTEXT_WINDOW || contextWindowTokens > MAX_CONTEXT_WINDOW))
            return saveFailure("INVALID_CONTEXT_WINDOW", "上下文窗口须为0（自动），或1024至10000000之间的整数；本次配置未保存。", false);
        if ((wantedBaseUrl != null && wantedBaseUrl.length() > 512)
                || (wantedModelName != null && wantedModelName.length() > 128))
            return saveFailure("CONFIGURATION_TOO_LONG", "Base URL 最多512字符，Model Name 最多128字符；本次配置未保存。", wantedBaseUrl != null && wantedBaseUrl.length() > 512);
        if (wantedBaseUrl != null && !customBaseUrlEnabled) {
            log.warn("拒绝整组模型配置：自定义端点被禁用，userId={}", userId);
            return saveFailure("CUSTOM_BASE_URL_DISABLED", "当前服务禁止自定义 Base URL，本次模型、地址和 Key 均未保存。请选择预设并清空地址，或联系管理员启用自定义端点。", true);
        }
        if (wantedBaseUrl != null && !validEndpoint(wantedBaseUrl))
            return saveFailure("INVALID_BASE_URL", "Base URL 必须是完整的 HTTP/HTTPS 地址，不能包含账号密码、查询参数或片段；本次配置未保存。", true);
        String pid = (StringUtils.isBlank(presetId))
                ? modelsProperties.getDefaultPreset() : presetId.trim();
        if (Objects.isNull(modelsProperties.findPreset(pid)) && !pid.equals(modelsProperties.getDefaultPreset()))
            return saveFailure("INVALID_PRESET", "所选模型预设不存在，请刷新模型设置后重新选择；本次配置未保存。", false);

        UserModelConfig row = repository.findByUserId(userId).orElse(null);
        EffectiveModelSettings selected = resolvePresetOnly(pid);
        String endpoint = wantedBaseUrl == null ? selected.baseUrl() : wantedBaseUrl;
        String effectiveModel = wantedModelName == null ? selected.modelName() : wantedModelName;
        if (!sameEndpoint(endpoint, selected.baseUrl()) && wantedModelName == null)
            return saveFailure("CUSTOM_ENDPOINT_REQUIRES_MODEL", "自定义端点必须明确填写该服务支持的模型名称；本次配置未保存。", false);
        String oldEndpoint = row == null ? selected.baseUrl() : firstNonBlank(row.getCustomBaseUrl(),
                resolvePresetOnly(row.getPresetId()).baseUrl());
        String incomingKey = blankToNull(apiKey);
        boolean clearKey = "__CLEAR__".equals(incomingKey);
        boolean newKey = incomingKey != null && !clearKey;
        boolean storedKey = row != null && notBlank(row.getCustomApiKey());
        if (storedKey && !newKey && !clearKey && (!sameEndpoint(oldEndpoint, endpoint)
                || !sameEndpoint(row.getCustomApiKeyBaseUrl(), endpoint)))
            return saveFailure("CREDENTIAL_ENDPOINT_MISMATCH", "旧 Key 未绑定当前端点，或模型端点已变化。请重新输入该端点的 API Key，或明确清除旧 Key；本次配置未保存。", false);
        boolean ownKey = newKey || (storedKey && !clearKey);
        if (storedKey && !newKey && !clearKey) {
            try { decryptStoredKey(row); }
            catch (IllegalStateException unreadable) {
                return saveFailure("API_KEY_UNREADABLE", unreadable.getMessage() + "；本次配置未保存。", false);
            }
        }
        if (!sameEndpoint(endpoint, selected.baseUrl()) && !ownKey)
            return saveFailure("CUSTOM_ENDPOINT_REQUIRES_KEY", "自定义端点必须填写自己的 API Key，不能使用平台或预设 Key；本次配置未保存。", false);
        if (!ownKey && !notBlank(selected.apiKey()))
            return saveFailure("API_KEY_REQUIRED", "所选预设没有可用 Key，请填写该端点的 API Key；本次配置未保存。", false);

        Integer wantedWindow = contextWindowTokens;
        if (wantedWindow == null) {
            String oldModel = row == null ? selected.modelName() : firstNonBlank(row.getCustomModelName(),
                    resolvePresetOnly(row.getPresetId()).modelName());
            wantedWindow = row != null && sameEndpoint(oldEndpoint, endpoint) && Objects.equals(oldModel, effectiveModel)
                    ? row.getCustomContextWindowTokens() : null;
        }
        String encryptedKey = newKey ? crypto.encrypt(incomingKey) : null;
        if (newKey && encryptedKey.length() > UserModelConfig.API_KEY_STORAGE_LENGTH)
            return saveFailure("API_KEY_TOO_LONG", "API Key 加密后超过存储上限（1024字符）；请使用服务商签发的较短 Key。本次配置未保存。", false);

        // All checks precede mutations: JPA managed entities must remain unchanged on rejection.
        if (row == null) {
            UserModelConfig n = new UserModelConfig();
            n.setUserId(userId);
            row = n;
        }
        row.setPresetId(pid);
        row.setCustomBaseUrl(wantedBaseUrl);

        row.setCustomModelName(wantedModelName);
        row.setCustomContextWindowTokens(wantedWindow);

        if (Objects.nonNull(apiKey)) {
            String trimmed = apiKey.trim();
            if ("__CLEAR__".equals(trimmed)) {
                row.setCustomApiKey(null);
                row.setCustomApiKeyBaseUrl(null);
            } else if (!trimmed.isEmpty()) {
                // 静态加密：密钥属于用户凭据，明文落库等于把"读一次库"变成"拿到所有用户的模型 key"。
                // SecretCryptoService 在未配置 MODEL_CONFIG_ENCRYPTION_KEY 时原样返回（不抛），
                // 保持本地开发可用；prod 档由 ProductionReadinessValidator 强制要求该密钥。
                row.setCustomApiKey(encryptedKey);
                row.setCustomApiKeyBaseUrl(endpoint);
            }
            // 空串：保留原 customApiKey
        }

        repository.save(row);
        Map<String, Object> out = getView(userId);
        out.put("customBaseUrlRejected", false);
        return out;
    }

    private Map<String, Object> saveFailure(String code, String message, boolean urlRejected) {
        return Map.of("success", false, "errorCode", code, "message", message,
                "customBaseUrlRejected", urlRejected, "customBaseUrlAllowed", customBaseUrlEnabled);
    }

    @Transactional
    public Map<String, Object> resetToDefault(Long userId) {
        if (Objects.isNull(userId)) {
            return Map.of("success", false, "message", "Not authenticated");
        }
        repository.findByUserId(userId).ifPresent(repository::delete);
        return getView(userId);
    }

    EffectiveModelSettings resolve(UserModelConfig row) {
        String presetId = Objects.nonNull(row) && StringUtils.isNotBlank(row.getPresetId())
                ? row.getPresetId()
                : modelsProperties.getDefaultPreset();
        if (row != null && modelsProperties.findPreset(presetId) == null
                && !presetId.equals(modelsProperties.getDefaultPreset()))
            throw new IllegalStateException("已保存的模型预设不存在；请打开模型设置重新选择预设并输入对应 Key，或恢复默认配置。");
        EffectiveModelSettings base = resolvePresetOnly(presetId);

        String baseUrl = base.baseUrl();
        String modelName = base.modelName();
        String apiKey = base.apiKey();
        String label = base.label();

        if (Objects.nonNull(row)) {
            // Historical overrides must be rejected as a group; never combine their
            // credentials/model with a silently substituted platform endpoint.
            if (notBlank(row.getCustomBaseUrl())) {
                if (!customBaseUrlEnabled)
                    throw new IllegalStateException("已保存的自定义模型端点被当前服务禁用；请打开模型设置，选择预设并重新填写对应 Key，或恢复默认配置。");
                baseUrl = row.getCustomBaseUrl().trim();
                if (!validEndpoint(baseUrl))
                    throw new IllegalStateException("已保存的模型端点格式无效；请打开模型设置重新保存 HTTP/HTTPS 地址。");
                if (!sameEndpoint(baseUrl, base.baseUrl()) && !notBlank(row.getCustomApiKey()))
                    throw new IllegalStateException("自定义模型端点缺少自己的 API Key；请打开模型设置补充，平台 Key 不会发送到自定义端点。");
            }
            if (notBlank(row.getCustomModelName())) {
                modelName = row.getCustomModelName().trim();
            }
            if (notBlank(row.getCustomApiKey())) {
                if (!sameEndpoint(row.getCustomApiKeyBaseUrl(), baseUrl))
                    throw new IllegalStateException("已保存的模型 Key 未绑定当前端点或端点已变更；请打开模型设置重新输入该端点的 Key，或清除旧 Key 后恢复预设。");
                apiKey = decryptStoredKey(row);
            }
        }
        int requestedWindow = row == null || row.getCustomContextWindowTokens() == null ? 0 : row.getCustomContextWindowTokens();
        if (requestedWindow != 0 && (requestedWindow < MIN_CONTEXT_WINDOW || requestedWindow > MAX_CONTEXT_WINDOW))
            throw new IllegalStateException("已保存的上下文窗口无效；请打开模型设置填写0或1024至10000000之间的整数。");
        boolean presetModel = sameEndpoint(baseUrl, base.baseUrl()) && Objects.equals(modelName, base.modelName());
        int window = requestedWindow > 0 ? requestedWindow
                : presetModel ? base.contextWindowTokens() : UNKNOWN_CUSTOM_CONTEXT_WINDOW;
        if (presetModel && base.contextWindowTokens() > 0 && requestedWindow > 0)
            window = Math.min(window, base.contextWindowTokens());
        return new EffectiveModelSettings(presetId, label, baseUrl, modelName, apiKey, window);
    }

    private static boolean matchesPresetModel(EffectiveModelSettings effective, EffectiveModelSettings preset) {
        return sameEndpoint(effective.baseUrl(), preset.baseUrl()) && Objects.equals(effective.modelName(), preset.modelName());
    }

    /**
     * 读取用户自定义 key 并解密。
     *
     * <p>写入侧加密、读取侧必须解密 —— 只做一半是最糟的状态：
     * 迁移器把明文改写成 {@code enc:v1:…} 之后，读路径会把这段密文当 API key 交给
     * LangChain4j，该用户的模型调用从此稳定 401，而日志里只看到"认证失败"。
     * 所以这里解密失败时给出可执行的提示（换过密钥 → 让用户重新保存一次），
     * 而不是抛一句无上下文的 IllegalStateException。</p>
     */
    private String decryptStoredKey(UserModelConfig row) {
        String stored = row.getCustomApiKey().trim();
        try {
            return crypto.decrypt(stored).trim();
        } catch (RuntimeException e) {
            log.error("用户模型密钥无法解密（userId={}）。通常是 MODEL_CONFIG_ENCRYPTION_KEY 换过或丢失；"
                    + "该用户需要重新保存一次自己的模型密钥。", row.getUserId());
            throw new IllegalStateException(
                    "已保存的模型密钥无法解密：请重新保存模型配置（若刚轮换过 "
                            + "MODEL_CONFIG_ENCRYPTION_KEY，旧密钥需要有备份才能恢复）", e);
        }
    }

    EffectiveModelSettings resolvePresetOnly(String presetId) {
        String pid = StringUtils.isBlank(presetId)
                ? modelsProperties.getDefaultPreset() : presetId;
        AgentModelsProperties.Preset p = modelsProperties.findPreset(pid);
        String label = Objects.nonNull(p) ? Optional.ofNullable(p.getLabel()).orElse(pid) : pid;
        String baseUrl = firstNonBlank(Objects.nonNull(p) ? p.getBaseUrl() : null, globalBaseUrl);
        String modelName = firstNonBlank(Objects.nonNull(p) ? p.getModelName() : null, globalModelName);
        String presetKey = Objects.nonNull(p) ? p.getApiKey() : null;
        // A preset that changes destination cannot borrow credentials from the global endpoint.
        String apiKey = firstNonBlank(presetKey, sameEndpoint(baseUrl, globalBaseUrl) ? globalApiKey : "");
        int window = Objects.nonNull(p) ? p.effectiveContextWindow() : 0;
        return new EffectiveModelSettings(pid, label, baseUrl, modelName, apiKey, window);
    }

    static String maskKey(String key) {
        if (StringUtils.isBlank(key)) {
            return "";
        }
        String k = key.trim();
        if (k.length() <= 4) {
            return "***";
        }
        return "***" + k.substring(k.length() - 4);
    }

    private static boolean validEndpoint(String value) {
        try {
            URI uri = new URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && (uri.getPort() == -1 || uri.getPort() > 0) && uri.getPort() <= 65535;
        } catch (URISyntaxException invalid) { return false; }
    }

    private static boolean sameEndpoint(String first, String second) {
        if (!notBlank(first) || !notBlank(second)) return false;
        try {
            URI a = new URI(first.trim()).normalize(), b = new URI(second.trim()).normalize();
            String ap = Optional.ofNullable(a.getRawPath()).orElse("").replaceAll("/+$", "");
            String bp = Optional.ofNullable(b.getRawPath()).orElse("").replaceAll("/+$", "");
            int aPort = a.getPort() == -1 ? ("https".equalsIgnoreCase(a.getScheme()) ? 443 : 80) : a.getPort();
            int bPort = b.getPort() == -1 ? ("https".equalsIgnoreCase(b.getScheme()) ? 443 : 80) : b.getPort();
            return validEndpoint(first.trim()) && validEndpoint(second.trim())
                    && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                    && aPort == bPort && ap.equals(bp);
        } catch (URISyntaxException invalid) { return false; }
    }

    private static boolean notBlank(String s) {
        return StringUtils.isNotBlank(s);
    }

    private static String blankToNull(String s) {
        if (StringUtils.isBlank(s)) {
            return null;
        }
        return s.trim();
    }

    private static String nullToEmpty(String s) {
        return Optional.ofNullable(s).orElse("");
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.isNotBlank(a)) {
            return a.trim();
        }
        return Objects.isNull(b) ? "" : b.trim();
    }
}
