package com.miniagent.agent.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.common.milvus.MilvusCollectionInitializer;
import com.miniagent.common.milvus.SharedMilvusClient;
import com.miniagent.memory.MemoryVectorIndex;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 长期记忆向量（Milvus）：共用 SharedMilvusClient + SharedEmbeddingModel。 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "agent.memory.vector.backend", havingValue = "milvus")
public class MilvusMemoryVectorIndex implements MemoryVectorIndex {

    private static final Long DEFAULT_USER = -1L;

    private final SharedMilvusClient milvus;
    private final SharedEmbeddingModel embedding;

    @Value("${agent.memory.vector.enabled:true}")
    private boolean enabled;
    @Value("${agent.memory.vector.top-k:6}")
    private int topK;
    @Value("${agent.memory.vector.min-score:0.4}")
    private double minScore;
    @Value("${agent.memory.vector.milvus.collection:agent_memory}")
    private String collection;
    @Value("${agent.memory.vector.milvus.dimension:1024}")
    private int dimension;

    private volatile boolean ready;

    public MilvusMemoryVectorIndex(SharedMilvusClient milvus, SharedEmbeddingModel embedding) {
        this.milvus = milvus;
        this.embedding = embedding;
    }

    @PostConstruct
    void init() {
        if (!enabled || embedding == null || !embedding.isEnabled()) {
            log.warn("Milvus 记忆向量未启用（enabled=false 或无 embedding key）");
            return;
        }
        try {
            MilvusClientV2 client = milvus.get();
            ensureCollection(client);
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
            ready = true;
            log.info("Milvus 记忆向量已就绪 collection={} dim={}", collection, dimension);
        } catch (Exception e) {
            log.error("Milvus 记忆向量初始化失败: {}", e.getMessage());
            ready = false;
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled && ready && embedding != null && embedding.isEnabled();
    }

    @Override
    public boolean hasIndex(Long userId) {
        if (!isEnabled()) return false;
        Long uid = Optional.ofNullable(userId).orElse(DEFAULT_USER);
        try {
            SearchResp resp = milvus.get().search(SearchReq.builder()
                    .collectionName(collection)
                    .data(Collections.singletonList(new FloatVec(new float[dimension])))
                    .filter("user_id == " + uid)
                    .topK(1)
                    .outputFields(List.of("pk"))
                    .build());
            List<List<SearchResp.SearchResult>> r = resp.getSearchResults();
            return r != null && !r.isEmpty() && !r.get(0).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public synchronized void reindex(Long userId, List<String> entries) {
        if (!isEnabled()) return;
        Long uid = Optional.ofNullable(userId).orElse(DEFAULT_USER);
        try {
            milvus.get().delete(DeleteReq.builder()
                    .collectionName(collection)
                    .filter("user_id == " + uid)
                    .build());
            if (entries == null || entries.isEmpty()) return;
            List<JsonObject> rows = new ArrayList<>();
            int i = 0;
            for (String entry : entries) {
                if (StringUtils.isBlank(entry)) continue;
                try {
                    float[] vec = embedding.embed(entry);
                    if (vec.length == 0) continue;
                    JsonObject row = new JsonObject();
                    row.addProperty("pk", pk(uid, i++, entry));
                    row.addProperty("user_id", uid);
                    row.addProperty("text", entry.length() > 65000 ? entry.substring(0, 65000) : entry);
                    JsonArray arr = new JsonArray();
                    for (float v : vec) arr.add(v);
                    row.add("vector", arr);
                    rows.add(row);
                } catch (Exception e) {
                    log.warn("记忆条目嵌入失败（跳过）: {}", e.getMessage());
                }
            }
            if (!rows.isEmpty()) {
                milvus.get().upsert(UpsertReq.builder().collectionName(collection).data(rows).build());
            }
            log.info("Milvus reindex userId={} rows={}", uid, rows.size());
        } catch (Exception e) {
            log.error("Milvus reindex 失败 userId={}: {}", uid, e.getMessage());
        }
    }

    @Override
    public List<String> recall(Long userId, String query) {
        if (!isEnabled() || StringUtils.isBlank(query)) return List.of();
        Long uid = Optional.ofNullable(userId).orElse(DEFAULT_USER);
        try {
            float[] q = embedding.embed(query);
            if (q.length == 0) return List.of();
            SearchResp resp = milvus.get().search(SearchReq.builder()
                    .collectionName(collection)
                    .data(Collections.singletonList(new FloatVec(q)))
                    .filter("user_id == " + uid)
                    .topK(Math.max(1, topK))
                    .outputFields(List.of("text"))
                    .build());
            List<String> out = new ArrayList<>();
            List<List<SearchResp.SearchResult>> groups = resp.getSearchResults();
            if (groups == null) return out;
            for (SearchResp.SearchResult hit : groups.isEmpty() ? List.<SearchResp.SearchResult>of() : groups.get(0)) {
                if (hit.getScore() < minScore) continue;
                Object ent = hit.getEntity();
                if (ent instanceof Map<?, ?> map) {
                    Object t = map.get("text");
                    if (t != null) out.add(String.valueOf(t));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Milvus recall 失败 userId={}: {}", uid, e.getMessage());
            return List.of();
        }
    }

    private void ensureCollection(MilvusClientV2 client) {
        MilvusCollectionInitializer.ensureCollection(client, collection, dimension, List.of(
                MilvusCollectionInitializer.FieldDef.int64("user_id"),
                MilvusCollectionInitializer.FieldDef.varchar("text", 65535)));
    }

    private static String pk(long userId, int idx, String text) {
        return UUID.nameUUIDFromBytes((userId + "|" + idx + "|" + text).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
