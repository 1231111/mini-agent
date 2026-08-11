package com.miniagent.config.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "intent_rule_hit_log", indexes = {
        @Index(name = "idx_intent_hit_exec", columnList = "execution_id"),
        @Index(name = "idx_intent_hit_created", columnList = "created_at")
})
public class IntentRuleHitLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", length = 60)
    private String executionId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "rule_set_id")
    private Long ruleSetId;

    @Column(nullable = false, length = 10)
    private String layer;

    @Column(nullable = false, length = 40)
    private String intent;

    @Column(length = 200)
    private String reason;

    @Column(name = "user_text", columnDefinition = "TEXT")
    private String userText;

    @Column(name = "matched_signals", columnDefinition = "TEXT")
    private String matchedSignals;

    @Column(name = "plan_json", columnDefinition = "TEXT")
    private String planJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getRuleSetId() { return ruleSetId; }
    public void setRuleSetId(Long ruleSetId) { this.ruleSetId = ruleSetId; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getUserText() { return userText; }
    public void setUserText(String userText) { this.userText = userText; }
    public String getMatchedSignals() { return matchedSignals; }
    public void setMatchedSignals(String matchedSignals) { this.matchedSignals = matchedSignals; }
    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
