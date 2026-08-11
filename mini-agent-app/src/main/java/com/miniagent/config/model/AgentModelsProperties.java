package com.miniagent.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * agent.models.* — 前端可选的聊天模型预设。
 * 预设中空的 api-key / base-url / model-name 回退全局 langchain4j 配置。
 */
@Component
@ConfigurationProperties(prefix = "agent.models")
public class AgentModelsProperties {

    private String defaultPreset = "default";
    private List<Preset> presets = new ArrayList<>();

    public String getDefaultPreset() {
        return defaultPreset;
    }

    public void setDefaultPreset(String defaultPreset) {
        this.defaultPreset = defaultPreset;
    }

    public List<Preset> getPresets() {
        return presets;
    }

    public void setPresets(List<Preset> presets) {
        this.presets = Optional.ofNullable(presets).orElse(new ArrayList<>());
    }

    public Preset findPreset(String id) {
        if (StringUtils.isBlank(id)) return null;
        for (Preset p : presets) {
            if (Objects.nonNull(p) && id.equals(p.getId())) return p;
        }
        return null;
    }

    public static class Preset {
        private String id;
        private String label;
        private String baseUrl;
        private String modelName;
        private String apiKey;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }
}
