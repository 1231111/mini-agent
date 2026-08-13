package com.miniagent.agent.memory.retriever;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.common.milvus.SharedMilvusClient;
import com.miniagent.memory.model.*;
import com.miniagent.memory.retriever.HybridSearchEngine;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
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
import java.time.ZoneId;
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

                    MemoryEntry memory = toMemoryEntry(entity);
                    HybridScore score = scoreCalculator.buildScore(
                        hit.getScore(), 0, memory, scopeMatch(query, memory));
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
                    MemoryEntry memory = toMemoryEntry(entity);
                    double keywordScore = keywordRelevance(query.getQuery(), entity.getContent());
                    HybridScore score = scoreCalculator.buildScore(
                        0, keywordScore, memory, scopeMatch(query, memory));
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

    private double scopeMatch(MemoryQuery query, MemoryEntry memory) {
        if (memory.getScope() == null) return 0.0;
        if (memory.getScope().scopeType() == query.getScope().scopeType()
            && Objects.equals(memory.getScope().scopeId(), query.getScope().scopeId())) {
            return 1.0;
        }
        if (memory.getScope().scopeType() == MemoryScope.ScopeType.TENANT) {
            return 0.5; // tenant 级记忆部分匹配
        }
        return 0.0;
    }

    private double keywordRelevance(String query, String content) {
        if (query == null || content == null) return 0;
        String q = query.toLowerCase();
        String c = content.toLowerCase();
        if (c.contains(q)) return 1.0;
        // 按词匹配
        String[] words = q.split("\\s+");
        int matched = 0;
        for (String w : words) {
            if (w.length() > 1 && c.contains(w)) matched++;
        }
        return words.length > 0 ? (double) matched / words.length : 0;
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

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    /** 向 Milvus 写入一条记忆的向量（供 DefaultEventProcessor 调用） */
    public void upsert(String tenantId, Long memoryId, String content) {
        if (!ready) return;
        try {
            float[] vec = embeddingModel.embed(content);
            if (vec.length == 0) return;
            JsonObject row = new JsonObject();
            row.addProperty("pk", "mem_" + memoryId);
            row.addProperty("tenant_id", tenantId);
            row.addProperty("memory_id", memoryId);
            row.addProperty("content", content.length() > 65000 ? content.substring(0, 65000) : content);
            JsonArray arr = new JsonArray();
            for (float v : vec) arr.add(v);
            row.add("vector", arr);
            milvus.get().upsert(UpsertReq.builder().collectionName(collection).data(List.of(row)).build());
        } catch (Exception e) {
            log.debug("Milvus upsert 失败: {}", e.getMessage());
        }
    }

    private void ensureCollection(MilvusClientV2 client) {
        Boolean has = client.hasCollection(HasCollectionReq.builder().collectionName(collection).build());
        if (Boolean.TRUE.equals(has)) return;
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder().fieldName("pk").dataType(DataType.VarChar).maxLength(128).isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder().fieldName("tenant_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("memory_id").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar).maxLength(65535).build());
        schema.addField(AddFieldReq.builder().fieldName("vector").dataType(DataType.FloatVector).dimension(dimension).build());
        IndexParam index = IndexParam.builder()
            .fieldName("vector")
            .indexType(IndexParam.IndexType.AUTOINDEX)
            .metricType(IndexParam.MetricType.COSINE)
            .build();
        client.createCollection(CreateCollectionReq.builder()
            .collectionName(collection)
            .collectionSchema(schema)
            .indexParams(List.of(index))
            .build());
        log.info("已创建 Milvus collection {}", collection);
    }
}
