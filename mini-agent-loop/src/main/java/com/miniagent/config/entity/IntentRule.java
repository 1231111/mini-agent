package com.miniagent.config.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "intent_rule", indexes = {
        @Index(name = "idx_intent_rule_set", columnList = "rule_set_id"),
        @Index(name = "idx_intent_rule_group", columnList = "signal_group")
})
public class IntentRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_set_id", nullable = false)
    private Long ruleSetId;

    /** WEB / FILE / IMAGE_INTO_DOC / PURE_IMAGE / QUESTION / TASK_ACTION / CONTINUE / COMPLEX / IMAGE_AND_DOC */
    @Column(name = "signal_group", nullable = false, length = 40)
    private String signalGroup;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String pattern;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int priority = 100;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRuleSetId() { return ruleSetId; }
    public void setRuleSetId(Long ruleSetId) { this.ruleSetId = ruleSetId; }
    public String getSignalGroup() { return signalGroup; }
    public void setSignalGroup(String signalGroup) { this.signalGroup = signalGroup; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
