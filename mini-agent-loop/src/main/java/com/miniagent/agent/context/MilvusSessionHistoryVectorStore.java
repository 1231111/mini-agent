package com.miniagent.agent.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.common.milvus.MilvusCollectionInitializer;
import com.miniagent.common.milvus.SharedMilvusClient;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话历史向量（Milvus）：写入 upsert，指代按 session_id 过滤检索。
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "agent.memory.vector.backend", havingValue = "milvus")
public class MilvusSessionHistoryVectorStore implements SessionHistoryVectorStore {

    private final SharedMilvusClient milvus;
    private final SharedEmbeddingModel embedding;
    private final AtomicLong seqGen = new AtomicLong(System.currentTimeMillis());

    @Value("${agent.context.history.ref-vector-enabled:true}")
    private boolean enabled;
    @Value("${agent.context.history.vector.collection:agent_session_history}")
    private String collection;
    @Value("${agent.memory.vector.milvus.dimension:1024}")
    private int dimension;

    private volatile boolean ready;

    public MilvusSessionHistoryVectorStore(SharedMilvusClient milvus, SharedEmbeddingModel embedding) {
        this.milvus = milvus;
        this.embedding = embedding;
    }

    @PostConstruct
    void init() {
        if (!enabled || embedding == null || !embedding.isEnabled()) {
            log.warn("会话历史 Milvus 向量未启用（开关关闭或无 embedding）");
            return;
        }
        try {
            MilvusClientV2 client = milvus.get();
            ensureCollection(client);
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
            ready = true;
            log.info("会话历史向量已就绪 collection={} dim={}", collection, dimension);
        } catch (Exception e) {
            log.error("会话历史 Milvus 初始化失败: {}", e.getMessage());
            ready = false;
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled && ready && embedding != null && embedding.isEnabled();
    }

    @Override
    public void upsertMessage(String sessionId, String role, String text) {
        if (!isEnabled() || StringUtils.isBlank(sessionId) || StringUtils.isBlank(text)) return;
        String sid = sessionId.trim();
        String r = role == null ? "user" : role;
        String body = text.length() > 65000 ? text.substring(0, 65000) : text;
        long seq = seqGen.incrementAndGet();
        try {
            float[] vec = embedding.embed(body);
            if (vec.length == 0) return;
            JsonObject row = new JsonObject();
            row.addProperty("pk", pk(sid, seq, r));
            row.addProperty("session_id", sid);
            row.addProperty("seq", seq);
            row.addProperty("role", r.length() > 16 ? r.substring(0, 16) : r);
            row.addProperty("text", body);
            JsonArray arr = new JsonArray();
            for (float v : vec) arr.add(v);
            row.add("vector", arr);
            milvus.get().upsert(UpsertReq.builder()
                    .collectionName(collection)
                    .data(List.of(row))
                    .build());
        } catch (Exception e) {
            log.warn("会话历史向量写入失败 sessionId={}: {}", sid, e.getMessage());
        }
    }

    @Override
    public List<Hit> search(String sessionId, String query, int topK, double minScore) {
        if (!isEnabled() || StringUtils.isBlank(sessionId) || StringUtils.isBlank(query)) {
            return List.of();
        }
        try {
            float[] q = embedding.embed(query);
            if (q.length == 0) return List.of();
            String filter = "session_id == \"" + escape(sessionId.trim()) + "\"";
            SearchResp resp = milvus.get().search(SearchReq.builder()
                    .collectionName(collection)
                    .data(Collections.singletonList(new FloatVec(q)))
                    .filter(filter)
                    .topK(Math.max(1, topK))
                    .outputFields(List.of("seq", "role", "text"))
                    .build());
            List<Hit> out = new ArrayList<>();
            List<List<SearchResp.SearchResult>> groups = resp.getSearchResults();
            if (groups == null || groups.isEmpty()) return out;
            for (SearchResp.SearchResult hit : groups.get(0)) {
                if (hit.getScore() < minScore) continue;
                Object ent = hit.getEntity();
                if (!(ent instanceof Map<?, ?> map)) continue;
                long seq = toLong(map.get("seq"));
                Object roleObj = map.get("role");
                Object textObj = map.get("text");
                String role = roleObj == null ? "user" : String.valueOf(roleObj);
                String text = textObj == null ? "" : String.valueOf(textObj);
                if (StringUtils.isNotBlank(text)) {
                    out.add(new Hit(seq, role, text, hit.getScore()));
                }
            }
            out.sort((a, b) -> Long.compare(a.seq(), b.seq()));
            return out;
        } catch (Exception e) {
            log.warn("会话历史向量检索失败 sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        if (!isEnabled() || StringUtils.isBlank(sessionId)) return;
        try {
            milvus.get().delete(DeleteReq.builder()
                    .collectionName(collection)
                    .filter("session_id == \"" + escape(sessionId.trim()) + "\"")
                    .build());
        } catch (Exception e) {
            log.warn("会话历史向量删除失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private void ensureCollection(MilvusClientV2 client) {
        MilvusCollectionInitializer.ensureCollection(client, collection, dimension, List.of(
                MilvusCollectionInitializer.FieldDef.varchar("session_id", 128),
                MilvusCollectionInitializer.FieldDef.int64("seq"),
                MilvusCollectionInitializer.FieldDef.varchar("role", 16),
                MilvusCollectionInitializer.FieldDef.varchar("text", 65535)));
    }

    private static String pk(String sessionId, long seq, String role) {
        return UUID.nameUUIDFromBytes((sessionId + "|" + seq + "|" + role).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
    }
}
