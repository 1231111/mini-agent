package com.miniagent.config.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "intent_rule_feedback", indexes = {
        @Index(name = "idx_intent_fb_exec", columnList = "execution_id"),
        @Index(name = "idx_intent_fb_status", columnList = "status")
})
public class IntentRuleFeedback {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PROPOSED = "PROPOSED";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", length = 60)
    private String executionId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "predicted_intent", length = 40)
    private String predictedIntent;

    @Column(name = "correct_intent", nullable = false, length = 40)
    private String correctIntent;

    /** WRONG_INTENT / MISSED_RULE / BAD_RULE / OTHER */
    @Column(name = "feedback_type", nullable = false, length = 40)
    private String feedbackType = "WRONG_INTENT";

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "user_text", columnDefinition = "TEXT")
    private String userText;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(nullable = false, length = 20)
    private String status = STATUS_OPEN;

    @Column(name = "proposal_id")
    private Long proposalId;

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
    public String getPredictedIntent() { return predictedIntent; }
    public void setPredictedIntent(String predictedIntent) { this.predictedIntent = predictedIntent; }
    public String getCorrectIntent() { return correctIntent; }
    public void setCorrectIntent(String correctIntent) { this.correctIntent = correctIntent; }
    public String getFeedbackType() { return feedbackType; }
    public void setFeedbackType(String feedbackType) { this.feedbackType = feedbackType; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getUserText() { return userText; }
    public void setUserText(String userText) { this.userText = userText; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getProposalId() { return proposalId; }
    public void setProposalId(Long proposalId) { this.proposalId = proposalId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
