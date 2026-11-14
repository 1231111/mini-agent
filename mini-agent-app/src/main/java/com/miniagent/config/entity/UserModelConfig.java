package com.miniagent.config.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "user_model_config", indexes = {
        @Index(name = "uk_user_model_config_user", columnList = "user_id", unique = true)
})
public class UserModelConfig extends BaseEntity {

    public static final int API_KEY_STORAGE_LENGTH = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "preset_id", length = 64)
    private String presetId;

    @Column(name = "custom_base_url", length = 512)
    private String customBaseUrl;

    @Column(name = "custom_model_name", length = 128)
    private String customModelName;

    @Column(name = "custom_api_key", length = API_KEY_STORAGE_LENGTH)
    private String customApiKey;

    /** Destination confirmed when the user supplied this credential; never inferred from mutable presets. */
    @Column(name = "custom_api_key_base_url", length = 512)
    private String customApiKeyBaseUrl;

    @Column(name = "custom_context_window_tokens")
    private Integer customContextWindowTokens;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPresetId() { return presetId; }
    public void setPresetId(String presetId) { this.presetId = presetId; }
    public String getCustomBaseUrl() { return customBaseUrl; }
    public void setCustomBaseUrl(String customBaseUrl) { this.customBaseUrl = customBaseUrl; }
    public String getCustomModelName() { return customModelName; }
    public void setCustomModelName(String customModelName) { this.customModelName = customModelName; }
    public String getCustomApiKey() { return customApiKey; }
    public void setCustomApiKey(String customApiKey) { this.customApiKey = customApiKey; }
    public String getCustomApiKeyBaseUrl() { return customApiKeyBaseUrl; }
    public void setCustomApiKeyBaseUrl(String customApiKeyBaseUrl) { this.customApiKeyBaseUrl = customApiKeyBaseUrl; }
    public Integer getCustomContextWindowTokens() { return customContextWindowTokens; }
    public void setCustomContextWindowTokens(Integer customContextWindowTokens) { this.customContextWindowTokens = customContextWindowTokens; }
}
