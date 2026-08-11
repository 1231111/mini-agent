package com.miniagent.config.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "intent_rule_proposal", indexes = {
        @Index(name = "idx_intent_proposal_status", columnList = "status")
})
public class IntentRuleProposal {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "feedback_id")
    private Long feedbackId;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "signal_group", nullable = false, length = 40)
    private String signalGroup;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String pattern;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "published_rule_set_id")
    private Long publishedRuleSetId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFeedbackId() { return feedbackId; }
    public void setFeedbackId(Long feedbackId) { this.feedbackId = feedbackId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSignalGroup() { return signalGroup; }
    public void setSignalGroup(String signalGroup) { this.signalGroup = signalGroup; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public Long getPublishedRuleSetId() { return publishedRuleSetId; }
    public void setPublishedRuleSetId(Long publishedRuleSetId) { this.publishedRuleSetId = publishedRuleSetId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
