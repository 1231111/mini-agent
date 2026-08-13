package com.miniagent.agent.memory.retriever;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.memory.model.*;
import com.miniagent.memory.retriever.HybridSearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 本地混合检索引擎（不依赖 Milvus）。
 * 使用 MySQL 关键词检索 + 内存中的 embedding 相似度计算。
 */
@Component
@ConditionalOnProperty(name = "agent.memory.vector.backend", havingValue = "local", matchIfMissing = true)
public class LocalHybridSearchEngine implements HybridSearchEngine {

    private static final Logger log = LoggerFactory.getLogger(LocalHybridSearchEngine.class);

    @Autowired
    private AgentMemoryEntryRepository entryRepository;

    @Autowired(required = false)
    private SharedEmbeddingModel embeddingModel;

    @Autowired
    private HybridScoreCalculator scoreCalculator;

    @Value("${agent.memory.vector.min-score:0.3}")
    private double minScore;

    @Override
    public List<ScoredMemory> search(MemoryQuery query) {
        // 从 MySQL 获取候选集
        List<AgentMemoryEntryEntity> candidates;
        if (query.getScope().scopeType() != null && query.getScope().scopeId() != null) {
            candidates = entryRepository.findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                query.getScope().tenantId(),
                AgentMemoryEntryEntity.ScopeType.valueOf(query.getScope().scopeType().name()),
                query.getScope().scopeId(),
                AgentMemoryEntryEntity.Status.ACTIVE);
        } else {
            candidates = entryRepository.findByTenantIdAndStatus(
                query.getScope().tenantId(), AgentMemoryEntryEntity.Status.ACTIVE);
        }

        // 类型过滤
        if (!query.getTypeFilter().isEmpty()) {
            Set<String> allowedTypes = query.getTypeFilter().stream()
                .map(Enum::name).collect(Collectors.toSet());
            candidates = candidates.stream()
                .filter(e -> allowedTypes.contains(e.getMemoryType().name()))
                .collect(Collectors.toList());
        }

        // 重要度过滤
        if (query.getMinImportance() != null) {
            candidates = candidates.stream()
                .filter(e -> e.getImportance() != null && e.getImportance() >= query.getMinImportance())
                .collect(Collectors.toList());
        }

        // 评分
        float[] queryVec = (embeddingModel != null && embeddingModel.isEnabled())
            ? embeddingModel.embed(query.getQuery()) : new float[0];

        List<ScoredMemory> results = new ArrayList<>();
        for (AgentMemoryEntryEntity entity : candidates) {
            MemoryEntry memory = toMemoryEntry(entity);

            // 语义分数
            double semanticScore = 0;
            if (queryVec.length > 0) {
                float[] memVec = embeddingModel.embed(memory.getContent());
                if (memVec.length > 0) {
                    semanticScore = SharedEmbeddingModel.cosine(queryVec, memVec);
                }
            }

            // 关键词分数
            double keywordScore = keywordRelevance(query.getQuery(), memory.getContent());

            // scope 匹配
            double scopeMatch = scopeMatch(query, memory);

            HybridScore score = scoreCalculator.buildScore(semanticScore, keywordScore, memory, scopeMatch);
            if (score.getFinalScore() >= minScore) {
                results.add(new ScoredMemory(memory, score));
            }
        }

        // 排序 + 截断
        results.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
        return results.stream().limit(query.getTopK()).collect(Collectors.toList());
    }

    private MemoryEntry toMemoryEntry(AgentMemoryEntryEntity entity) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(entity.getId());
        entry.setTenantId(entity.getTenantId());
        entry.setMemoryType(MemoryType.valueOf(entity.getMemoryType().name()));
        entry.setScope(new MemoryScope(entity.getTenantId(),
            MemoryScope.ScopeType.valueOf(entity.getScopeType().name()), entity.getScopeId()));
        entry.setContent(entity.getContent());
        entry.setSummary(entity.getSummary());
        entry.setImportance(entity.getImportance() != null ? entity.getImportance() : 0.5);
        entry.setConfidence(entity.getConfidence() != null ? entity.getConfidence() : 0.5);
        entry.setAccessCount(entity.getAccessCount() != null ? entity.getAccessCount() : 0);
        entry.setStatus(MemoryStatus.valueOf(entity.getStatus().name()));
        entry.setCreatedAt(entity.getCreatedAt() != null ?
            entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : 0);
        return entry;
    }

    private double keywordRelevance(String query, String content) {
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

    private double scopeMatch(MemoryQuery query, MemoryEntry memory) {
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
}
