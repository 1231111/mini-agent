package com.miniagent.config.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "user_model_config", indexes = {
        @Index(name = "uk_user_model_config_user", columnList = "user_id", unique = true)
})
public class UserModelConfig extends BaseEntity {

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

    @Column(name = "custom_api_key", length = 512)
    private String customApiKey;

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
}
