package com.miniagent.agent.memory.retriever;

import com.miniagent.memory.model.HybridScore;
import com.miniagent.memory.model.MemoryEntry;
import com.miniagent.memory.model.MemoryQuery;
import com.miniagent.memory.model.MemoryScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 混合评分计算器。
 * score = w1*semantic + w2*keyword + w3*importance + w4*recency + w5*confidence + w6*scopeRelevance
 */
@Component
public class HybridScoreCalculator {

    @Value("${agent.memory.score.semantic:0.35}")
    private double wSemantic;

    @Value("${agent.memory.score.keyword:0.20}")
    private double wKeyword;

    @Value("${agent.memory.score.importance:0.15}")
    private double wImportance;

    @Value("${agent.memory.score.recency:0.10}")
    private double wRecency;

    @Value("${agent.memory.score.confidence:0.10}")
    private double wConfidence;

    @Value("${agent.memory.score.scope:0.10}")
    private double wScope;

    /**
     * 计算混合评分。
     *
     * @param semanticScore   向量相似度 (0~1)
     * @param keywordScore    关键词匹配度 (0~1)
     * @param importance      记忆重要度 (0~1)
     * @param createdAtMillis 记忆创建时间
     * @param confidence      置信度 (0~1)
     * @param scopeMatch      scope 完全匹配 = 1.0，部分匹配 = 0.5，不匹配 = 0.0
     */
    public double calculate(double semanticScore, double keywordScore, double importance,
                            long createdAtMillis, double confidence, double scopeMatch) {
        double recency = recencyScore(createdAtMillis);
        return wSemantic * semanticScore
             + wKeyword * keywordScore
             + wImportance * importance
             + wRecency * recency
             + wConfidence * confidence
             + wScope * scopeMatch;
    }

    public HybridScore buildScore(double semanticScore, double keywordScore, MemoryEntry entry, double scopeMatch) {
        HybridScore score = new HybridScore();
        score.setSemantic(semanticScore);
        score.setKeyword(keywordScore);
        score.setImportance(entry.getImportance());
        score.setRecency(recencyScore(entry.getCreatedAt()));
        score.setConfidence(entry.getConfidence());
        score.setScopeRelevance(scopeMatch);
        score.setFinalScore(calculate(semanticScore, keywordScore, entry.getImportance(),
            entry.getCreatedAt(), entry.getConfidence(), scopeMatch));
        return score;
    }

    public double scopeMatch(MemoryQuery query, MemoryEntry memory) {
        if (memory.getScope() == null) return 0.0;
        if (memory.getScope().scopeType() == query.getScope().scopeType()
            && Objects.equals(memory.getScope().scopeId(), query.getScope().scopeId())) {
            return 1.0;
        }
        if (memory.getScope().scopeType() == MemoryScope.ScopeType.TENANT) {
            return 0.5;
        }
        return 0.0;
    }

    public double keywordRelevance(String query, String content) {
        if (query == null || content == null) return 0;
        String q = query.toLowerCase();
        String c = content.toLowerCase();
        if (c.contains(q)) return 1.0;
        String[] words = q.split("\\s+");
        int matched = 0;
        for (String w : words) {
            if (w.length() > 1 && c.contains(w)) matched++;
        }
        return words.length > 0 ? (double) matched / words.length : 0;
    }

    /**
     * 时间衰减：越新越值钱。30 天内线性衰减。
     */
    private double recencyScore(long createdAtMillis) {
        long ageMs = System.currentTimeMillis() - createdAtMillis;
        long thirtyDaysMs = 30L * 24 * 60 * 60 * 1000;
        if (ageMs <= 0) return 1.0;
        if (ageMs >= thirtyDaysMs) return 0.1;
        return 1.0 - 0.9 * ((double) ageMs / thirtyDaysMs);
    }
}
