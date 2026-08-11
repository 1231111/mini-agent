package com.miniagent.agent.memory;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.memory.AgentDataPaths;
import com.miniagent.memory.MemoryVectorIndex;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * 长期记忆向量检索（按 userId 隔离）。
 *
 * 复用 codebase_search 的同款技术栈：OpenAiEmbeddingModel(bge-m3) + InMemoryEmbeddingStore，
 * 不引入新依赖、不建新表。每个用户一个向量库，持久化到 memory/users/{userId}/.memory-vec.json。
 *
 * 用途：长期记忆条目（MEMORY/USER）规模变大后，全量注入系统提示既浪费 token 又稀释重点。
 * 本组件按「当前对话语义」召回 Top-K 最相关条目，实现按需注入。
 *
 * 优雅降级：embedding 未启用或无 key 时，{@link #isEnabled()} 返回 false，
 * 调用方回退到 MemoryStore 的全量快照注入。
 */
@Slf4j
@Component
public class VectorMemoryStore implements MemoryVectorIndex {

    @Autowired
    private AgentDataPaths dataPaths;

    @Value("${agent.memory.vector.enabled:true}")
    private boolean enabled;
    @Value("${agent.memory.vector.top-k:6}")
    private int topK;
    @Value("${agent.memory.vector.min-score:0.4}")
    private double minScore;

    // 复用 codebase 的 embedding 配置（同一套 siliconflow key/model）
    @Value("${agent.codebase.embedding-api-key:}")
    private String apiKey;
    @Value("${agent.codebase.embedding-base-url:https://api.siliconflow.cn/v1}")
    private String baseUrl;
    @Value("${agent.codebase.embedding-model:BAAI/bge-m3}")
    private String embeddingModelName;

    private Path memoryDir;
    private EmbeddingModel embeddingModel;
    private final Map<Long, InMemoryEmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();
    private static final Long DEFAULT_USER = -1L;

    @PostConstruct
    private void initAutowiredComputed() {
        this.memoryDir = dataPaths.memory();
    }

    /** 向量检索是否可用（受配置开关与 embedding key 共同控制）。 */
    public boolean isEnabled() {
        return enabled && StringUtils.isNotBlank(apiKey);
    }

    /** 某用户是否已有向量索引（内存或磁盘）。用于判断是否需要首建。 */
    public boolean hasIndex(Long userId) {
        Long uid = Optional.ofNullable(userId).orElse(DEFAULT_USER);
        return stores.containsKey(uid) || Files.exists(vecPath(uid));
    }

    private Path userDir(Long userId) {
        String name = DEFAULT_USER.equals(userId) ? "_default" : String.valueOf(userId);
        return memoryDir.resolve("users").resolve(name);
    }

    private Path vecPath(Long userId) {
        return userDir(userId).resolve(".memory-vec.json");
    }

    private EmbeddingModel embeddingModel() {
        if (Objects.isNull(embeddingModel)) {
            // 显式 JDK 客户端，避免与 SpringRestClient 在 classpath 冲突
            JdkHttpClientBuilder http = new JdkHttpClientBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .readTimeout(Duration.ofSeconds(60));
            embeddingModel = OpenAiEmbeddingModel.builder()
                    .httpClientBuilder(http)
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(embeddingModelName)
                    .build();
        }
        return embeddingModel;
    }

    /**
     * 用给定记忆条目重建某用户的向量索引（条目变更后调用）。
     * entries 为该用户全部长期记忆条目（MEMORY + USER 合并），每条作为一个可召回单元。
     */
    public synchronized void reindex(Long userId, List<String> entries) {
        if (!isEnabled()) return;
        Long uid = Optional.ofNullable(userId).orElse(DEFAULT_USER);
        try {
            InMemoryEmbeddingStore<TextSegment> fresh = new InMemoryEmbeddingStore<>();
            for (String entry : entries) {
                if (StringUtils.isBlank(entry)) continue;
                try {
                    TextSegment seg = TextSegment.from(entry);
                    Embedding emb = embeddingModel().embed(seg).content();
                    fresh.add(emb, seg);
                } catch (Exception e) {
                    log.warn("记忆条目嵌入失败（跳过一条）: {}", e.getMessage());
                }
            }
            stores.put(uid, fresh);
            // 持久化
            try {
                Path p = vecPath(uid);
                Files.createDirectories(p.getParent());
                fresh.serializeToFile(p);
            } catch (Exception e) {
                log.warn("记忆向量索引持久化失败（不影响本次）: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("记忆向量重建失败 userId={}", uid, e);
        }
    }

    /** 取某用户的向量库，首次访问时尝试从磁盘加载。 */
    private InMemoryEmbeddingStore<TextSegment> storeOf(Long uid) {
        InMemoryEmbeddingStore<TextSegment> store = stores.get(uid);
        if (Objects.nonNull(store)) return store;
        Path p = vecPath(uid);
        if (Files.exists(p)) {
            try {
                store = InMemoryEmbeddingStore.fromFile(p);
                stores.put(uid, store);
                return store;
            } catch (Exception e) {
                log.warn("加载记忆向量索引失败 userId={}: {}", uid, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 按当前对话语义召回某用户最相关的长期记忆条目。
     * @return 召回的条目文本列表；不可用或无命中时返回空列表（调用方据此回退全量注入）。
     */
    public List<String> recall(Long userId, String query) {
        if (!isEnabled() || StringUtils.isBlank(query)) return List.of();
        Long uid = Optional.ofNullable(userId).orElse(DEFAULT_USER);
        try {
            InMemoryEmbeddingStore<TextSegment> store = storeOf(uid);
            if (Objects.isNull(store)) return List.of();
            Embedding q = embeddingModel().embed(query).content();
            EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                    .queryEmbedding(q)
                    .maxResults(Math.max(1, topK))
                    .minScore(minScore)
                    .build();
            List<EmbeddingMatch<TextSegment>> matches = store.search(req).matches();
            List<String> out = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> m : matches) {
                out.add(m.embedded().text());
            }
            return out;
        } catch (Exception e) {
            log.warn("记忆向量召回失败 userId={}: {}", uid, e.getMessage());
            return List.of();
        }
    }
}