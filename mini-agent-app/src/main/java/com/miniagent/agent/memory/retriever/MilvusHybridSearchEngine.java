package com.miniagent.agent.memory.retriever;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.common.milvus.MilvusCollectionInitializer;
import com.miniagent.common.milvus.SharedMilvusClient;
import com.miniagent.memory.model.*;
import com.miniagent.memory.retriever.HybridSearchEngine;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Milvus 混合检索引擎：向量语义检索 + MySQL 关键词检索 → 加权融合。
 */
@Component
@ConditionalOnProperty(name = "agent.memory.vector.backend", havingValue = "milvus")
public class MilvusHybridSearchEngine implements HybridSearchEngine {

    private static final Logger log = LoggerFactory.getLogger(MilvusHybridSearchEngine.class);

    @Autowired
    private SharedMilvusClient milvus;

    @Autowired
    private SharedEmbeddingModel embeddingModel;

    @Autowired
    private AgentMemoryEntryRepository entryRepository;

    @Autowired
    private HybridScoreCalculator scoreCalculator;

    @Value("${agent.memory.vector.milvus.collection:agent_memory_v2}")
    private String collection;

    @Value("${agent.memory.vector.milvus.dimension:1792}")
    private int dimension;

    @Value("${agent.memory.vector.min-score:0.3}")
    private double minScore;

    private volatile boolean ready;

    @PostConstruct
    void init() {
        if (embeddingModel == null || !embeddingModel.isEnabled()) {
            log.warn("Milvus 混合检索未启用（无 embedding key）");
            return;
        }
        try {
            MilvusClientV2 client = milvus.get();
            ensureCollection(client);
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
            ready = true;
            log.info("Milvus 混合检索已就绪 collection={}", collection);
        } catch (Exception e) {
            log.error("Milvus 混合检索初始化失败: {}", e.getMessage());
            ready = false;
        }
    }

    @Override
    public List<ScoredMemory> search(MemoryQuery query) {
        if (!ready) return List.of();

        // 1. 向量检索
        List<ScoredMemory> vectorResults = vectorSearch(query);

        // 2. 关键词检索
        List<ScoredMemory> keywordResults = keywordSearch(query);

        // 3. 合并去重 + 混合评分
        Map<Long, ScoredMemory> merged = new LinkedHashMap<>();
        for (ScoredMemory sm : vectorResults) {
            merged.put(sm.getMemory().getId(), sm);
        }
        for (ScoredMemory sm : keywordResults) {
            Long id = sm.getMemory().getId();
            if (merged.containsKey(id)) {
                // 已有向量分数，补充关键词分数
                ScoredMemory existing = merged.get(id);
                existing.getScore().setKeyword(sm.getScore().getKeyword());
                existing.getScore().setFinalScore(
                    scoreCalculator.calculate(
                        existing.getScore().getSemantic(),
                        sm.getScore().getKeyword(),
                        existing.getMemory().getImportance(),
                        existing.getMemory().getCreatedAt(),
                        existing.getMemory().getConfidence(),
                        existing.getScore().getScopeRelevance()
                    )
                );
            } else {
                merged.put(id, sm);
            }
        }

        // 4. 按 finalScore 降序排列，过滤低分
        return merged.values().stream()
            .filter(sm -> sm.getFinalScore() >= minScore)
            .sorted((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()))
            .limit(query.getTopK())
            .collect(Collectors.toList());
    }

    private List<ScoredMemory> vectorSearch(MemoryQuery query) {
        try {
            float[] qVec = embeddingModel.embed(query.getQuery());
            if (qVec.length == 0) return List.of();

            String filter = buildFilter(query);
            SearchResp resp = milvus.get().search(SearchReq.builder()
                .collectionName(collection)
                .data(Collections.singletonList(new FloatVec(qVec)))
                .filter(filter)
                .topK(query.getTopK() * 2) // 多取一些，后续 rerank
                .outputFields(List.of("memory_id", "tenant_id", "content"))
                .build());

            List<ScoredMemory> results = new ArrayList<>();
            List<List<SearchResp.SearchResult>> groups = resp.getSearchResults();
            if (groups == null || groups.isEmpty()) return results;

            for (SearchResp.SearchResult hit : groups.get(0)) {
                if (hit.getScore() < minScore) continue;
                Object ent = hit.getEntity();
                if (ent instanceof Map<?, ?> map) {
                    Long memoryId = toLong(map.get("memory_id"));
                    if (memoryId == null) continue;

                    // 从 MySQL 获取完整记忆
                    Optional<AgentMemoryEntryEntity> entityOpt = entryRepository.findById(memoryId);
                    if (entityOpt.isEmpty()) continue;

                    AgentMemoryEntryEntity entity = entityOpt.get();
                    if (entity.getStatus() != AgentMemoryEntryEntity.Status.ACTIVE) continue;

                    MemoryEntry memory = MemoryEntryMapper.fromEntity(entity);
                    HybridScore score = scoreCalculator.buildScore(
                        hit.getScore(), 0, memory, scoreCalculator.scopeMatch(query, memory));
                    results.add(new ScoredMemory(memory, score));
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ScoredMemory> keywordSearch(MemoryQuery query) {
        try {
            List<AgentMemoryEntryEntity> entities = entryRepository.searchByKeyword(
                query.getScope().tenantId(), query.getQuery());

            return entities.stream()
                .filter(e -> e.getStatus() == AgentMemoryEntryEntity.Status.ACTIVE)
                .limit(query.getTopK())
                .map(entity -> {
                    MemoryEntry memory = MemoryEntryMapper.fromEntity(entity);
                    double keywordScore = scoreCalculator.keywordRelevance(query.getQuery(), entity.getContent());
                    HybridScore score = scoreCalculator.buildScore(
                        0, keywordScore, memory, scoreCalculator.scopeMatch(query, memory));
                    return new ScoredMemory(memory, score);
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("关键词检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildFilter(MemoryQuery query) {
        StringBuilder filter = new StringBuilder();
        filter.append("tenant_id == \"").append(query.getScope().tenantId()).append("\"");

        if (!query.getTypeFilter().isEmpty()) {
            String types = query.getTypeFilter().stream()
                .map(t -> "\"" + t.name() + "\"")
                .collect(Collectors.joining(", "));
            filter.append(" && memory_type in [").append(types).append("]");
        }

        return filter.toString();
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    /** 向 Milvus 写入一条记忆的向量（供旧调用方调用） */
    public void upsert(String tenantId, Long memoryId, String content) {
        if (memoryId == null) return;
        com.miniagent.memory.model.MemoryEntry entry = new com.miniagent.memory.model.MemoryEntry();
        entry.setId(memoryId);
        entry.setTenantId(tenantId);
        entry.setContent(content);
        upsert(entry);
    }

    public boolean upsert(com.miniagent.memory.model.MemoryEntry memory) {
        if (!ready) return false;
        try {
            if (memory == null || memory.getId() == null || memory.getContent() == null) return false;
            float[] vec = embeddingModel.embed(memory.getContent());
            if (vec.length == 0) return false;
            JsonObject row = new JsonObject();
            row.addProperty("pk", "mem_" + memory.getId());
            row.addProperty("tenant_id", memory.getTenantId());
            row.addProperty("memory_id", memory.getId());
            String content = memory.getContent();
            row.addProperty("content", content.length() > 65000 ? content.substring(0, 65000) : content);
            JsonArray arr = new JsonArray();
            for (float v : vec) arr.add(v);
            row.add("vector", arr);
            milvus.get().upsert(UpsertReq.builder().collectionName(collection).data(List.of(row)).build());
            return true;
        } catch (Exception e) {
            log.debug("Milvus upsert 失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean delete(Long memoryId) {
        if (!ready || memoryId == null) return false;
        try {
            milvus.get().delete(DeleteReq.builder()
                    .collectionName(collection)
                    .filter("pk == \"mem_" + memoryId + "\"")
                    .build());
            return true;
        } catch (Exception e) {
            log.debug("Milvus delete 失败: {}", e.getMessage());
            return false;
        }
    }

    private void ensureCollection(MilvusClientV2 client) {
        MilvusCollectionInitializer.ensureCollection(client, collection, dimension, List.of(
                MilvusCollectionInitializer.FieldDef.varchar("tenant_id", 64),
                MilvusCollectionInitializer.FieldDef.int64("memory_id"),
                MilvusCollectionInitializer.FieldDef.varchar("content", 65535)));
    }
}
