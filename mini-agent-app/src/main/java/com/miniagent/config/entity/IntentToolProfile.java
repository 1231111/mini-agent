package com.miniagent.config.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "intent_tool_profile", indexes = {
        @Index(name = "idx_intent_tool_profile_set", columnList = "rule_set_id")
})
public class IntentToolProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_set_id", nullable = false)
    private Long ruleSetId;

    /** FULL / IMAGE / QUESTION */
    @Column(nullable = false, length = 20)
    private String profile;

    /** JSON array of tool names; null/empty FULL means registry-all */
    @Column(name = "tools_json", columnDefinition = "TEXT")
    private String toolsJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRuleSetId() { return ruleSetId; }
    public void setRuleSetId(Long ruleSetId) { this.ruleSetId = ruleSetId; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public String getToolsJson() { return toolsJson; }
    public void setToolsJson(String toolsJson) { this.toolsJson = toolsJson; }
}
