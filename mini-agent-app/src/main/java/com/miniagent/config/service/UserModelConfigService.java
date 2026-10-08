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
     * prod 与 desktop 档显式置 false。
     *
     * <p>为什么这个开关必须由业务代码执行、不能只写在配置里：
     * {@code ProductionReadinessValidator} 也检查同一个键，但它是<b>启动断言</b>，
     * 且只在 prod 档运行。桌面客户端档跑不到那里，于是那条配置形同注释。
     *
     * <p>为什么自定义 Base URL 值钱：{@link #resolve} 里 baseUrl 与 apiKey
     * 是<b>分别</b>回退的。用户填一个自定义 baseUrl、apiKey 留空，
     * 拿到的是出厂的全局 apiKey —— 也就是<b>不需要知道密钥内容</b>，
     * 只要改一个地址就能把出厂密钥引到任意端点。
     * 关掉这个开关即切断该路径。
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
        EffectiveModelSettings eff = resolve(row);

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("presetId", eff.presetId());
        current.put("label", eff.label());
        current.put("baseUrl", eff.baseUrl());
        current.put("modelName", eff.modelName());
        current.put("hasApiKey", Objects.nonNull(eff.apiKey()) && StringUtils.isNotBlank(eff.apiKey()));
        // 只脱敏"用户自己的 key"。全局/预设 key 的后四位不该回显给每个登录用户 ——
        // 那属于平台凭据，泄露四位没有任何业务价值，却能被用来确认密钥是否被更换。
        boolean hasOwnKey = Objects.nonNull(row) && StringUtils.isNotBlank(row.getCustomApiKey());
        current.put("apiKeyMasked", hasOwnKey ? maskKey(eff.apiKey()) : "");
        current.put("apiKeySource", hasOwnKey ? "user" : "global");
        current.put("customBaseUrl", Objects.isNull(row) ? "" : nullToEmpty(row.getCustomBaseUrl()));
        current.put("customModelName", Objects.isNull(row) ? "" : nullToEmpty(row.getCustomModelName()));
        current.put("hasCustomApiKey", Objects.nonNull(row) && Objects.nonNull(row.getCustomApiKey()) && StringUtils.isNotBlank(row.getCustomApiKey()));

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
        return out;
    }

    /**
     * 保存用户配置。
     * apiKey 为 null 或空串 → 不覆盖已存自定义 key；传 "__CLEAR__" 可清空自定义 key。
     */
    @Transactional
    public Map<String, Object> save(Long userId, String presetId, String baseUrl, String modelName, String apiKey) {
        if (Objects.isNull(userId)) {
            return Map.of("success", false, "message", "Not authenticated");
        }
        String pid = (StringUtils.isBlank(presetId))
                ? modelsProperties.getDefaultPreset() : presetId.trim();
        if (Objects.isNull(modelsProperties.findPreset(pid)) && !"default".equals(pid)) {
            // 允许未知 id 仅当等于 default；否则回退 default
            if (Objects.nonNull(modelsProperties.findPreset(modelsProperties.getDefaultPreset()))) {
                pid = modelsProperties.getDefaultPreset();
            }
        }

        UserModelConfig row = repository.findByUserId(userId).orElseGet(() -> {
            UserModelConfig n = new UserModelConfig();
            n.setUserId(userId);
            return n;
        });
        row.setPresetId(pid);

        // 闸门。关掉时一律不落库，并让调用方从响应里看出这次没生效 ——
        // 静默忽略会变成「用户以为换到自己的网关了，实际请求还打在我们的端点上」。
        boolean baseUrlRejected = false;
        String wantedBaseUrl = blankToNull(baseUrl);
        if (Objects.nonNull(wantedBaseUrl) && !customBaseUrlEnabled) {
            baseUrlRejected = true;
            log.warn("拒绝保存自定义模型 baseUrl（agent.models.custom-base-url-enabled=false）: userId={}", userId);
            row.setCustomBaseUrl(null);
        } else {
            row.setCustomBaseUrl(wantedBaseUrl);
        }

        row.setCustomModelName(blankToNull(modelName));

        if (Objects.nonNull(apiKey)) {
            String trimmed = apiKey.trim();
            if ("__CLEAR__".equals(trimmed)) {
                row.setCustomApiKey(null);
            } else if (!trimmed.isEmpty()) {
                // 静态加密：密钥属于用户凭据，明文落库等于把"读一次库"变成"拿到所有用户的模型 key"。
                // SecretCryptoService 在未配置 MODEL_CONFIG_ENCRYPTION_KEY 时原样返回（不抛），
                // 保持本地开发可用；prod 档由 ProductionReadinessValidator 强制要求该密钥。
                row.setCustomApiKey(crypto.encrypt(trimmed));
            }
            // 空串：保留原 customApiKey
        }

        repository.save(row);
        Map<String, Object> out = getView(userId);
        out.put("customBaseUrlRejected", baseUrlRejected);
        return out;
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
        EffectiveModelSettings base = resolvePresetOnly(presetId);

        String baseUrl = base.baseUrl();
        String modelName = base.modelName();
        String apiKey = base.apiKey();
        String label = base.label();

        if (Objects.nonNull(row)) {
            // 关掉开关时这里也忽略 —— save() 已经不再写入，但历史行里可能还留着值
            // （开关是在某个版本之后才置 false 的）。只堵 save() 不堵 resolve()，
            // 等于给存量行留了后门。
            if (customBaseUrlEnabled && notBlank(row.getCustomBaseUrl())) {
                baseUrl = row.getCustomBaseUrl().trim();
            } else if (!customBaseUrlEnabled && notBlank(row.getCustomBaseUrl())) {
                log.warn("忽略已存的自定义 baseUrl（agent.models.custom-base-url-enabled=false），"
                        + "回退到全局配置: userId={}", row.getUserId());
            }
            if (notBlank(row.getCustomModelName())) {
                modelName = row.getCustomModelName().trim();
            }
            if (notBlank(row.getCustomApiKey())) {
                apiKey = decryptStoredKey(row);
            }
        }
        return new EffectiveModelSettings(presetId, label, baseUrl, modelName, apiKey,
                base.contextWindowTokens());
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
        String apiKey = firstNonBlank(Objects.nonNull(p) ? p.getApiKey() : null, globalApiKey);
        int window = Objects.nonNull(p) ? p.getContextWindowTokens() : 0;
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
