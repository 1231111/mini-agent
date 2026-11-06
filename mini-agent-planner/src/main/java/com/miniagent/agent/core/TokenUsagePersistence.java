package com.miniagent.agent.core;

import java.util.Map;

/** Durable usage sink supplied by the application module. */
public interface TokenUsagePersistence {
    void add(String sessionId, long inputTokens, long outputTokens, int toolCalls, int llmCalls);
    TokenUsageTracker.UsageStats get(String sessionId);
    Map<String, TokenUsageTracker.UsageStats> getForUser(Long userId);
    void delete(String sessionId);
}
