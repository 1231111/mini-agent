package com.miniagent.config.service;

import com.miniagent.agent.core.TokenUsagePersistence;
import com.miniagent.agent.core.TokenUsageTracker;
import com.miniagent.config.entity.AgentTokenUsage;
import com.miniagent.config.repository.AgentTokenUsageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DbTokenUsagePersistence implements TokenUsagePersistence {
    private final AgentTokenUsageRepository repository;
    private final Counter inputCounter;
    private final Counter outputCounter;

    public DbTokenUsagePersistence(AgentTokenUsageRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.inputCounter = meters.counter("miniagent.tokens", "direction", "input");
        this.outputCounter = meters.counter("miniagent.tokens", "direction", "output");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void add(String sessionId, long inputTokens, long outputTokens, int toolCalls, int llmCalls) {
        repository.increment(sessionId, Math.max(0, inputTokens), Math.max(0, outputTokens),
                Math.max(0, toolCalls), Math.max(0, llmCalls));
        inputCounter.increment(Math.max(0, inputTokens));
        outputCounter.increment(Math.max(0, outputTokens));
    }

    @Override
    @Transactional(readOnly = true)
    public TokenUsageTracker.UsageStats get(String sessionId) {
        return repository.findById(sessionId).map(this::map).orElseGet(TokenUsageTracker.UsageStats::new);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, TokenUsageTracker.UsageStats> getForUser(Long userId) {
        Map<String, TokenUsageTracker.UsageStats> result = new LinkedHashMap<>();
        for (AgentTokenUsage usage : repository.findAllForUser(userId)) {
            result.put(usage.getSessionId(), map(usage));
        }
        return result;
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        if (sessionId != null) {
            repository.deleteById(sessionId);
        }
    }

    private TokenUsageTracker.UsageStats map(AgentTokenUsage entity) {
        TokenUsageTracker.UsageStats stats = new TokenUsageTracker.UsageStats();
        stats.inputTokens = entity.getInputTokens();
        stats.outputTokens = entity.getOutputTokens();
        stats.toolCalls = entity.getToolCalls();
        stats.llmCalls = entity.getLlmCalls();
        if (entity.getUpdatedAt() != null) {
            stats.lastUpdated = entity.getUpdatedAt()
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return stats;
    }
}
