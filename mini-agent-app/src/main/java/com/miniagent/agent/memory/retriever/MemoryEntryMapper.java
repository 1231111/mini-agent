package com.miniagent.agent.memory.retriever;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.memory.model.*;

import java.time.ZoneId;

/**
 * AgentMemoryEntryEntity → MemoryEntry 转换器。
 * 消除 LocalHybridSearchEngine、MilvusHybridSearchEngine、EmbeddingDeduplicator 中的重复映射。
 */
public final class MemoryEntryMapper {

    private MemoryEntryMapper() {}

    public static MemoryEntry fromEntity(AgentMemoryEntryEntity entity) {
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
}
