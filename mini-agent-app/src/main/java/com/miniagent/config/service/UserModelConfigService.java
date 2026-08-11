package com.miniagent.config.service;

import com.miniagent.config.entity.UserModelConfig;
import com.miniagent.config.model.AgentModelsProperties;
import com.miniagent.config.model.EffectiveModelSettings;
import com.miniagent.config.repository.UserModelConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按用户解析 / 持久化聊天模型配置。API 回显永远脱敏。
 */
@Service
@RequiredArgsConstructor
public class UserModelConfigService {

    private final UserModelConfigRepository repository;
    private final AgentModelsProperties modelsProperties;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String globalApiKey;
    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String globalBaseUrl;
    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String globalModelName;

    /** 供对话路径建连 */
    public EffectiveModelSettings getEffective(Long userId) {
        UserModelConfig row = userId == null ? null : repository.findByUserId(userId).orElse(null);
        return resolve(row);
    }

    /** GET /api/model-config 视图（无明文 key） */
    public Map<String, Object> getView(Long userId) {
        UserModelConfig row = userId == null ? null : repository.findByUserId(userId).orElse(null);
        EffectiveModelSettings eff = resolve(row);

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("presetId", eff.presetId());
        current.put("label", eff.label());
        current.put("baseUrl", eff.baseUrl());
        current.put("modelName", eff.modelName());
        current.put("hasApiKey", eff.apiKey() != null && !eff.apiKey().isBlank());
        current.put("apiKeyMasked", maskKey(eff.apiKey()));
        current.put("customBaseUrl", row == null ? "" : nullToEmpty(row.getCustomBaseUrl()));
        current.put("customModelName", row == null ? "" : nullToEmpty(row.getCustomModelName()));
        current.put("hasCustomApiKey", row != null && row.getCustomApiKey() != null && !row.getCustomApiKey().isBlank());

        List<Map<String, Object>> presets = new ArrayList<>();
        for (AgentModelsProperties.Preset p : modelsProperties.getPresets()) {
            if (p == null || p.getId() == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("label", p.getLabel() != null ? p.getLabel() : p.getId());
            EffectiveModelSettings pe = resolvePresetOnly(p.getId());
            item.put("baseUrl", pe.baseUrl());
            item.put("modelName", pe.modelName());
            item.put("hasApiKey", pe.apiKey() != null && !pe.apiKey().isBlank());
            presets.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("current", current);
        out.put("presets", presets);
        out.put("defaultPreset", modelsProperties.getDefaultPreset());
        return out;
    }

    /**
     * 保存用户配置。
     * apiKey 为 null 或空串 → 不覆盖已存自定义 key；传 "__CLEAR__" 可清空自定义 key。
     */
    @Transactional
    public Map<String, Object> save(Long userId, String presetId, String baseUrl, String modelName, String apiKey) {
        if (userId == null) {
            return Map.of("success", false, "message", "Not authenticated");
        }
        String pid = (presetId == null || presetId.isBlank())
                ? modelsProperties.getDefaultPreset() : presetId.trim();
        if (modelsProperties.findPreset(pid) == null && !"default".equals(pid)) {
            // 允许未知 id 仅当等于 default；否则回退 default
            if (modelsProperties.findPreset(modelsProperties.getDefaultPreset()) != null) {
                pid = modelsProperties.getDefaultPreset();
            }
        }

        UserModelConfig row = repository.findByUserId(userId).orElseGet(() -> {
            UserModelConfig n = new UserModelConfig();
            n.setUserId(userId);
            return n;
        });
        row.setPresetId(pid);
        row.setCustomBaseUrl(blankToNull(baseUrl));
        row.setCustomModelName(blankToNull(modelName));

        if (apiKey != null) {
            String trimmed = apiKey.trim();
            if ("__CLEAR__".equals(trimmed)) {
                row.setCustomApiKey(null);
            } else if (!trimmed.isEmpty()) {
                row.setCustomApiKey(trimmed);
            }
            // 空串：保留原 customApiKey
        }

        repository.save(row);
        return getView(userId);
    }

    @Transactional
    public Map<String, Object> resetToDefault(Long userId) {
        if (userId == null) {
            return Map.of("success", false, "message", "Not authenticated");
        }
        repository.findByUserId(userId).ifPresent(repository::delete);
        return getView(userId);
    }

    EffectiveModelSettings resolve(UserModelConfig row) {
        String presetId = row != null && row.getPresetId() != null && !row.getPresetId().isBlank()
                ? row.getPresetId()
                : modelsProperties.getDefaultPreset();
        EffectiveModelSettings base = resolvePresetOnly(presetId);

        String baseUrl = base.baseUrl();
        String modelName = base.modelName();
        String apiKey = base.apiKey();
        String label = base.label();

        if (row != null) {
            if (notBlank(row.getCustomBaseUrl())) baseUrl = row.getCustomBaseUrl().trim();
            if (notBlank(row.getCustomModelName())) modelName = row.getCustomModelName().trim();
            if (notBlank(row.getCustomApiKey())) apiKey = row.getCustomApiKey().trim();
        }
        return new EffectiveModelSettings(presetId, label, baseUrl, modelName, apiKey);
    }

    EffectiveModelSettings resolvePresetOnly(String presetId) {
        String pid = presetId == null || presetId.isBlank()
                ? modelsProperties.getDefaultPreset() : presetId;
        AgentModelsProperties.Preset p = modelsProperties.findPreset(pid);
        String label = p != null && p.getLabel() != null ? p.getLabel() : pid;
        String baseUrl = firstNonBlank(p != null ? p.getBaseUrl() : null, globalBaseUrl);
        String modelName = firstNonBlank(p != null ? p.getModelName() : null, globalModelName);
        String apiKey = firstNonBlank(p != null ? p.getApiKey() : null, globalApiKey);
        return new EffectiveModelSettings(pid, label, baseUrl, modelName, apiKey);
    }

    static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        String k = key.trim();
        if (k.length() <= 4) return "***";
        return "***" + k.substring(k.length() - 4);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        return b == null ? "" : b.trim();
    }
}
