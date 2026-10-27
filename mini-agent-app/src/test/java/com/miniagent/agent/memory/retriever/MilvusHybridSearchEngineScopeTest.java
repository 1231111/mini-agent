package com.miniagent.agent.memory.retriever;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.memory.model.MemoryQuery;
import com.miniagent.memory.model.MemoryScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MilvusHybridSearchEngineScopeTest {

    @Test
    void matchesScopeRequiresTenantTypeAndId() {
        MemoryQuery query = MemoryQuery.of("deploy", MemoryScope.ofUser("tenant-1", "user-7"));
        AgentMemoryEntryEntity entry = entry("tenant-1", "USER", "user-7");

        assertTrue(MilvusHybridSearchEngine.matchesScope(query, entry));
        assertFalse(MilvusHybridSearchEngine.matchesScope(query, entry("tenant-2", "USER", "user-7")));
        assertFalse(MilvusHybridSearchEngine.matchesScope(query, entry("tenant-1", "SESSION", "user-7")));
        assertFalse(MilvusHybridSearchEngine.matchesScope(query, entry("tenant-1", "USER", "user-8")));
    }

    private static AgentMemoryEntryEntity entry(String tenant, String scopeType, String scopeId) {
        AgentMemoryEntryEntity entity = new AgentMemoryEntryEntity();
        entity.setTenantId(tenant);
        entity.setScopeType(AgentMemoryEntryEntity.ScopeType.valueOf(scopeType));
        entity.setScopeId(scopeId);
        return entity;
    }
}
