package com.miniagent.agent.memory.writer;

import com.miniagent.common.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.MemoryIndexOutboxService;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.memory.MemoryScopePolicy;
import com.miniagent.memory.model.*;
import com.miniagent.memory.writer.Deduplicator;
import com.miniagent.memory.writer.EventProcessor;
import com.miniagent.memory.writer.ImportanceEvaluator;
import com.miniagent.memory.writer.MemoryClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 默认事件处理器：编排评估 → 分类 → 去重 → 冲突解决 → 持久化。
 */
@Component
public class DefaultEventProcessor implements EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultEventProcessor.class);

    @Autowired
    private ImportanceEvaluator importanceEvaluator;

    @Autowired
    private MemoryClassifier classifier;

    @Autowired
    private Deduplicator deduplicator;

    @Autowired
    private com.miniagent.memory.lifecycle.ConflictResolver conflictResolver;

    @Autowired
    private AgentMemoryEntryRepository entryRepository;

    @Autowired(required = false)
    private MemoryIndexOutboxService indexOutbox;

    @Autowired(required = false)
    private SharedEmbeddingModel embeddingModel;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${agent.memory.importance-threshold:0.3}")
    private double importanceThreshold;

    @Override
    public MemoryEntry process(AgentEvent event) {
        // 1. 评估重要度
        double importance = importanceEvaluator.evaluate(event);
        if (importance < importanceThreshold) {
            log.debug("事件重要度 {} 低于阈值 {}，跳过", importance, importanceThreshold);
            return null;
        }

        // 2. 分类
        MemoryType type = classifier.classify(event);

        // 3. 构建 MemoryEntry
        MemoryEntry entry = buildMemoryEntry(event, type, importance);

        // 4. 去重
        Optional<MemoryEntry> duplicate = deduplicator.findDuplicate(entry);
        if (duplicate.isPresent()) {
            MemoryEntry resolved = conflictResolver.resolve(duplicate.get(), entry);
            if (resolved == null) {
                log.debug("去重后丢弃新记忆");
                return null;
            }
            // 归档旧记忆，保存新记忆
            archiveEntry(duplicate.get().getId());
            return persistEntry(resolved);
        }

        // 5. 无重复，直接保存
        return persistEntry(entry);
    }

    private MemoryEntry buildMemoryEntry(AgentEvent event, MemoryType type, double importance) {
        MemoryEntry entry = new MemoryEntry();
        entry.setTenantId(event.getTenantId());
        entry.setMemoryType(type);
        entry.setScope(MemoryScopePolicy.resolve(event, type));
        entry.setContent(buildContent(event));
        entry.setSummary(buildSummary(event));
        entry.setImportance(importance);
        entry.setConfidence(0.7); // 默认置信度
        entry.setSourceType(SourceType.AGENT_INFERRED);
        entry.setSourceId(event.getId() != null ? String.valueOf(event.getId()) : null);

        // 从 payload 推断来源
        if (event.getActor() != null) {
            switch (event.getActor()) {
                case "user" -> entry.setSourceType(SourceType.USER_STATED);
                case "system" -> entry.setSourceType(SourceType.SYSTEM_CONFIG);
                case "executor" -> entry.setSourceType(SourceType.TOOL_OBSERVED);
                case "planner" -> entry.setSourceType(SourceType.AGENT_INFERRED);
            }
        }

        return entry;
    }

    private String buildContent(AgentEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(event.getEventType()).append("] ");
        if (event.getPayload() != null) {
            Object tool = event.getPayload().get("tool");
            if (tool != null) sb.append("工具: ").append(tool).append(" ");
            Object error = event.getPayload().get("error");
            if (error != null) sb.append("错误: ").append(error).append(" ");
            Object result = event.getPayload().get("result");
            if (result != null) sb.append("结果: ").append(truncate(result.toString(), 200));
        }
        return sb.toString().trim();
    }

    private String buildSummary(AgentEvent event) {
        if (event.getEventType() == AgentEvent.EventType.TOOL_EXECUTION) {
            Object tool = event.getPayload() != null ? event.getPayload().get("tool") : null;
            String status = event.getStatus() == AgentEvent.EventStatus.FAILED ? "失败" : "成功";
            return (tool != null ? tool : "工具") + "执行" + status;
        }
        if (event.getEventType() == AgentEvent.EventType.ERROR) {
            Object error = event.getPayload() != null ? event.getPayload().get("error") : null;
            return "错误: " + (error != null ? truncate(error.toString(), 100) : "未知");
        }
        return event.getEventType().toString();
    }

    private MemoryEntry persistEntry(MemoryEntry entry) {
        AgentMemoryEntryEntity entity = new AgentMemoryEntryEntity();
        entity.setTenantId(entry.getTenantId());
        entity.setMemoryType(AgentMemoryEntryEntity.MemoryType.valueOf(entry.getMemoryType().name()));
        entity.setScopeType(AgentMemoryEntryEntity.ScopeType.valueOf(entry.getScope().scopeType().name()));
        entity.setScopeId(entry.getScope().scopeId());
        entity.setContent(entry.getContent());
        entity.setSummary(entry.getSummary());
        entity.setImportance(entry.getImportance());
        entity.setConfidence(entry.getConfidence());
        entity.setStatus(AgentMemoryEntryEntity.Status.ACTIVE);
        entity.setSourceType(AgentMemoryEntryEntity.SourceType.valueOf(entry.getSourceType().name()));
        entity.setSourceId(entry.getSourceId());
        entity.setAccessCount(0);

        entity = entryRepository.save(entity);
        entry.setId(entity.getId());
        if (indexOutbox != null) indexOutbox.enqueueUpsert(entity.getId());

        // 异步生成 embedding（不阻塞主流程）
        if (embeddingModel != null && embeddingModel.isEnabled()) {
            try {
                float[] vec = embeddingModel.embed(entry.getContent());
                if (vec.length > 0) {
                    // 存入 embeddings 表（作为 Milvus 的 fallback）
                    // Milvus 写入由 MilvusMemoryVectorIndex 处理
                }
            } catch (Exception e) {
                log.debug("生成 embedding 失败: {}", e.getMessage());
            }
        }

        log.debug("记忆已保存: id={}, type={}, importance={}", entity.getId(), entry.getMemoryType(), entry.getImportance());
        return entry;
    }

    private void archiveEntry(Long id) {
        entryRepository.findById(id).ifPresent(entity -> {
            entity.setStatus(AgentMemoryEntryEntity.Status.ARCHIVED);
            entryRepository.save(entity);
            if (indexOutbox != null) indexOutbox.enqueueDelete(id);
        });
    }

    private String truncate(String s, int maxLen) {
        return StringUtils.truncate(s, maxLen);
    }
}
