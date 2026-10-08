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
        if (StringUtils.isBlank(id)) {
            return null;
        }
        for (Preset p : presets) {
            if (Objects.nonNull(p) && id.equals(p.getId())) {
                return p;
            }
        }
        return null;
    }

    public static class Preset {
        private String id;
        private String label;
        private String baseUrl;
        private String modelName;
        private String apiKey;
        /**
         * 该模型厂商侧的上下文窗口（token）。0/未配置 = 未知，回退到 agent.context.max-tokens。
         *
         * <p>为什么不配也能跑、但生产必须配：工作窗口写死 512k 时，选一个 128k 的模型
         * 会让压缩阈值永远不触发，请求被上游以 context-length 400 拒绝 ——
         * 用户看到的却是"模型连接异常"。这里填了窗口，循环才知道该在什么时候压缩。</p>
         */
        private int contextWindowTokens = 0;

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
        public int getContextWindowTokens() { return contextWindowTokens; }
        public void setContextWindowTokens(int contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
        }
    }
}
