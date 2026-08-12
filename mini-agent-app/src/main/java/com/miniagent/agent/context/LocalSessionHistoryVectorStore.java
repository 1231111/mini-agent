package com.miniagent.agent.context;

import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.memory.AgentDataPaths;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话历史向量（本地）：InMemoryEmbeddingStore + 按 session 落盘 JSON。
 * 在 agent.memory.vector.backend=local 时装配。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.memory.vector.backend", havingValue = "local", matchIfMissing = true)
public class LocalSessionHistoryVectorStore implements SessionHistoryVectorStore {

    private final SharedEmbeddingModel embedding;
    private final AgentDataPaths dataPaths;
    private final Map<String, InMemoryEmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> seqs = new ConcurrentHashMap<>();
    @Value("${agent.context.history.ref-vector-enabled:true}")
    private boolean enabled;

    private Path root;

    public LocalSessionHistoryVectorStore(SharedEmbeddingModel embedding, AgentDataPaths dataPaths) {
        this.embedding = embedding;
        this.dataPaths = dataPaths;
    }

    @PostConstruct
    void init() {
        root = dataPaths.root().resolve("session-history-vec");
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            log.warn("无法创建会话历史向量目录: {}", e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled && embedding != null && embedding.isEnabled();
    }

    @Override
    public void upsertMessage(String sessionId, String role, String text) {
        if (!isEnabled() || StringUtils.isBlank(sessionId) || StringUtils.isBlank(text)) return;
        String sid = sessionId.trim();
        String r = role == null ? "user" : role;
        String body = text.length() > 8000 ? text.substring(0, 8000) : text;
        long seq = seqs.computeIfAbsent(sid, k -> new AtomicLong(0)).incrementAndGet();
        if (!embedding.isEnabled())
            return;
        try {
            float[] vec = embedding.embed(body);
            if (vec.length == 0)
                return;
            InMemoryEmbeddingStore<TextSegment> store =
                    stores.computeIfAbsent(sid, this::loadOrCreate);
            String payload = seq + "\n" + r + "\n" + body;
            store.add(Embedding.from(vec), TextSegment.from(payload));
            persist(sid, store);
        } catch (Exception e) {
            log.warn("本地会话历史向量写入失败 sessionId={}: {}",
                    sid, e.getMessage());
        }
    }

    @Override
    public List<Hit> search(String sessionId, String query, int topK, double minScore) {
        if (!isEnabled() || StringUtils.isBlank(sessionId) || StringUtils.isBlank(query)) {
            return List.of();
        }
        if (!embedding.isEnabled())
            return List.of();
        try {
            InMemoryEmbeddingStore<TextSegment> store =
                    stores.computeIfAbsent(sessionId.trim(), this::loadOrCreate);
            float[] q = embedding.embed(query);
            if (q.length == 0)
                return List.of();
            var result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(q))
                    .maxResults(Math.max(1, topK))
                    .minScore(minScore)
                    .build());
            List<Hit> out = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> m : result.matches()) {
                String payload = m.embedded() == null ? "" : m.embedded().text();
                Hit hit = parse(payload, m.score());
                if (hit != null)
                    out.add(hit);
            }
            out.sort(Comparator.comparingLong(Hit::seq));
            return out;
        } catch (Exception e) {
            log.warn("本地会话历史向量检索失败 sessionId={}: {}",
                    sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        if (StringUtils.isBlank(sessionId)) return;
        String sid = sessionId.trim();
        stores.remove(sid);
        seqs.remove(sid);
        try {
            Files.deleteIfExists(path(sid));
        } catch (Exception ignored) {}
    }

    private InMemoryEmbeddingStore<TextSegment> loadOrCreate(String sid) {
        Path p = path(sid);
        if (Files.exists(p)) {
            try {
                return InMemoryEmbeddingStore.fromFile(p);
            } catch (Exception e) {
                log.warn("加载会话历史向量文件失败，重建: {}", e.getMessage());
            }
        }
        return new InMemoryEmbeddingStore<>();
    }

    private void persist(String sid, InMemoryEmbeddingStore<TextSegment> store) {
        try {
            Files.createDirectories(root);
            store.serializeToFile(path(sid));
        } catch (Exception e) {
            log.warn("落盘会话历史向量失败: {}", e.getMessage());
        }
    }

    private Path path(String sid) {
        String safe = sid.replaceAll("[^a-zA-Z0-9._-]", "_");
        return root.resolve(safe + ".json");
    }

    private static Hit parse(String payload, double score) {
        if (StringUtils.isBlank(payload)) return null;
        String[] parts = payload.split("\n", 3);
        if (parts.length < 3) {
            return new Hit(0, "user", payload, score);
        }
        long seq;
        try { seq = Long.parseLong(parts[0]); } catch (Exception e) { seq = 0; }
        return new Hit(seq, parts[1], parts[2], score);
    }
}
