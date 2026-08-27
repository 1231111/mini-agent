package com.miniagent.config.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "agent_token_usage")
public class AgentTokenUsage extends BaseEntity {
    @Id
    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;
    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;
    @Column(name = "tool_calls", nullable = false)
    private int toolCalls;
    @Column(name = "llm_calls", nullable = false)
    private int llmCalls;
    @Version
    @Column(nullable = false)
    private long version;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }
    public int getLlmCalls() { return llmCalls; }
    public void setLlmCalls(int llmCalls) { this.llmCalls = llmCalls; }
    public long getVersion() { return version; }
}
