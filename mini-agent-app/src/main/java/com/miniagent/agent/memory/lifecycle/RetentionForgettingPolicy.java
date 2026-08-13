package com.miniagent.agent.memory.lifecycle;

import com.miniagent.memory.lifecycle.ForgettingPolicy;
import com.miniagent.memory.model.MemoryEntry;
import com.miniagent.memory.model.MemoryStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 保留策略：基于 importance + accessCount + recency + confidence 计算保留分数。
 *
 * retention_score = importance * 0.4
 *                 + log(1 + accessCount) / 10 * 0.2
 *                 + recency_decay * 0.2
 *                 + confidence * 0.2
 *
 * score < 0.2 → DELETED
 * score < 0.4 → ARCHIVED
 * score >= 0.4 → ACTIVE
 */
@Component
public class RetentionForgettingPolicy implements ForgettingPolicy {

    @Value("${agent.memory.forget.threshold-archive:0.4}")
    private double archiveThreshold;

    @Value("${agent.memory.forget.threshold-delete:0.2}")
    private double deleteThreshold;

    @Override
    public MemoryStatus evaluate(MemoryEntry memory) {
        if (memory.getStatus() == MemoryStatus.DELETED) {
            return MemoryStatus.DELETED;
        }

        double score = retentionScore(memory);

        if (score < deleteThreshold) {
            return MemoryStatus.DELETED;
        }
        if (score < archiveThreshold) {
            return MemoryStatus.ARCHIVED;
        }
        return MemoryStatus.ACTIVE;
    }

    private double retentionScore(MemoryEntry memory) {
        double importance = memory.getImportance();
        double accessFreq = Math.log(1 + memory.getAccessCount()) / 10.0;
        double recency = recencyDecay(memory.getLastAccessedAt());
        double confidence = memory.getConfidence();

        return importance * 0.4
             + Math.min(accessFreq, 1.0) * 0.2
             + recency * 0.2
             + confidence * 0.2;
    }

    private double recencyDecay(long lastAccessedAt) {
        if (lastAccessedAt <= 0) return 0.5; // 从未访问
        long ageMs = System.currentTimeMillis() - lastAccessedAt;
        long thirtyDaysMs = 30L * 24 * 60 * 60 * 1000;
        if (ageMs <= 0) return 1.0;
        if (ageMs >= thirtyDaysMs) return 0.1;
        return 1.0 - 0.9 * ((double) ageMs / thirtyDaysMs);
    }
}
