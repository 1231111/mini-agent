package com.miniagent.agent.memory.writer;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.memory.model.MemoryEntry;
import com.miniagent.memory.model.MemoryType;
import com.miniagent.memory.writer.Deduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 基于 embedding 相似度的去重器。
 *
 * 流程：
 * 1. 计算新记忆的 embedding
 * 2. 在同 tenant + 同 type 的 ACTIVE 记忆中查找相似度 > 阈值的条目
 * 3. 返回最相似的一条（如果存在）
 */
@Component
public class EmbeddingDeduplicator implements Deduplicator {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingDeduplicator.class);

    @Autowired
    private SharedEmbeddingModel embeddingModel;

    @Autowired
    private AgentMemoryEntryRepository entryRepository;

    @Value("${agent.memory.dedup-threshold:0.90}")
    private double similarityThreshold;

    @Override
    public Optional<MemoryEntry> findDuplicate(MemoryEntry newEntry) {
        if (!embeddingModel.isEnabled()) {
            return Optional.empty();
        }

        float[] newVec = embeddingModel.embed(newEntry.getContent());
        if (newVec.length == 0) {
            return Optional.empty();
        }

        // 查找同 tenant + 同 type 的所有 ACTIVE 记忆
        List<AgentMemoryEntryEntity> candidates = entryRepository.findByTenantIdAndStatus(
            newEntry.getTenantId(), AgentMemoryEntryEntity.Status.ACTIVE);

        double bestScore = 0;
        AgentMemoryEntryEntity bestMatch = null;

        for (AgentMemoryEntryEntity candidate : candidates) {
            // 跳过不同类型
            if (!candidate.getMemoryType().name().equals(newEntry.getMemoryType().name())) {
                continue;
            }

            // 从 embeddings 表获取向量（如果有）
            // 这里简化：直接用内容重新 embed 比较
            // 生产环境应该缓存 embedding
            float[] candVec = embeddingModel.embed(candidate.getContent());
            if (candVec.length == 0) continue;

            double score = SharedEmbeddingModel.cosine(newVec, candVec);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = candidate;
            }
        }

        if (bestScore >= similarityThreshold && bestMatch != null) {
            log.debug("去重发现相似记忆: id={}, score={}", bestMatch.getId(), bestScore);
            MemoryEntry match = toMemoryEntry(bestMatch);
            match.setId(bestMatch.getId());
            return Optional.of(match);
        }

        return Optional.empty();
    }

    private MemoryEntry toMemoryEntry(AgentMemoryEntryEntity entity) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(entity.getId());
        entry.setTenantId(entity.getTenantId());
        entry.setMemoryType(MemoryType.valueOf(entity.getMemoryType().name()));
        entry.setContent(entity.getContent());
        entry.setSummary(entity.getSummary());
        entry.setImportance(entity.getImportance());
        entry.setConfidence(entity.getConfidence());
        entry.setAccessCount(entity.getAccessCount());
        entry.setStatus(com.miniagent.memory.model.MemoryStatus.valueOf(entity.getStatus().name()));
        return entry;
    }
}
