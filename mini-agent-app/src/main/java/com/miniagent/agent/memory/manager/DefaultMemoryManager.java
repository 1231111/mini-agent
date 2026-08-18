package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.*;
import com.miniagent.agent.memory.MemoryIndexOutboxService;
import com.miniagent.agent.memory.lifecycle.DefaultConsolidationService;
import com.miniagent.agent.memory.lifecycle.RetentionForgettingPolicy;
import com.miniagent.agent.memory.lifecycle.WorkingMemoryManager;
import com.miniagent.agent.memory.repository.*;
import com.miniagent.agent.memory.retriever.MilvusHybridSearchEngine;
import com.miniagent.common.embedding.SharedEmbeddingModel;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.lifecycle.ForgettingPolicy;
import com.miniagent.memory.model.*;
import com.miniagent.memory.retriever.ContextCompressor;
import com.miniagent.memory.retriever.HybridSearchEngine;
import com.miniagent.memory.retriever.Reranker;
import com.miniagent.memory.writer.EventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认记忆管理器：编排写入、检索、生命周期各组件。
 */
@Service
public class DefaultMemoryManager implements MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryManager.class);

    @Autowired
    private EventProcessor eventProcessor;

    @Autowired
    private HybridSearchEngine searchEngine;

    @Autowired
    private Reranker reranker;

    @Autowired
    private ContextCompressor compressor;

    @Autowired
    private WorkingMemoryManager workingMemoryManager;

    @Autowired
    private DefaultConsolidationService consolidationService;

    @Autowired
    private RetentionForgettingPolicy forgettingPolicy;

    @Autowired
    private AgentMemoryEntryRepository entryRepository;

    @Autowired
    private AgentEpisodeRepository episodeRepository;

    @Autowired
    private AgentSemanticFactRepository factRepository;

    @Autowired
    private AgentProcedureRepository procedureRepository;

    @Autowired
    private AgentEventRepository eventRepository;

    @Autowired(required = false)
    private SharedEmbeddingModel embeddingModel;

    @Autowired(required = false)
    private MilvusHybridSearchEngine milvusSearchEngine;

    @Autowired(required = false)
    private MemoryIndexOutboxService indexOutbox;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${agent.memory.context.max-tokens:4000}")
    private int contextMaxTokens;

    @Value("${agent.memory.context.max-episodes:5}")
    private int maxEpisodes;

    // === 写入 ===

    @Override
    public void recordEvent(AgentEvent event) {
        // 持久化事件
        AgentEventEntity entity = new AgentEventEntity();
        entity.setTenantId(event.getTenantId());
        entity.setSessionId(event.getSessionId());
        entity.setTaskId(event.getTaskId());
        entity.setEventType(AgentEventEntity.EventType.valueOf(event.getEventType().name()));
        entity.setActor(event.getActor());
        entity.setPayloadJson(writeJson(event.getPayload()));
        entity.setStatus(event.getStatus() != null ?
            AgentEventEntity.EventStatus.valueOf(event.getStatus().name()) : null);
        entity.setProcessed(false);
        eventRepository.save(entity);

        // 异步处理事件转化为记忆
        try {
            MemoryEntry memory = eventProcessor.process(event);
            if (memory != null && memory.getId() != null && indexOutbox != null) {
                indexOutbox.enqueueUpsert(memory.getId());
            }
        } catch (Exception e) {
            log.warn("事件处理失败: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void writeMemory(MemoryEntry entry) {
        AgentMemoryEntryEntity entity = toEntity(entry);
        entity = entryRepository.save(entity);
        entry.setId(entity.getId());

        // 写入向量
        if (indexOutbox != null) {
            indexOutbox.enqueueUpsert(entity.getId());
        }
    }

    @Override
    @Transactional
    public void updateMemory(Long id, MemoryEntry update) {
        entryRepository.findById(id).ifPresent(entity -> {
            if (update.getContent() != null) entity.setContent(update.getContent());
            if (update.getSummary() != null) entity.setSummary(update.getSummary());
            if (update.getImportance() > 0) entity.setImportance(update.getImportance());
            if (update.getConfidence() > 0) entity.setConfidence(update.getConfidence());
            entryRepository.save(entity);

            // 更新向量
            if (indexOutbox != null && update.getContent() != null) {
                indexOutbox.enqueueUpsert(id);
            }
        });
    }

    @Override
    @Transactional
    public void deleteMemory(Long id) {
        entryRepository.findById(id).ifPresent(entity -> {
            entity.setStatus(AgentMemoryEntryEntity.Status.DELETED);
            entryRepository.save(entity);
            if (indexOutbox != null) indexOutbox.enqueueDelete(id);
        });
    }

    // === 检索 ===

    @Override
    public List<ScoredMemory> retrieve(MemoryQuery query) {
        List<ScoredMemory> results = searchEngine.search(query);
        return reranker.rerank(query.getQuery(), results, query.getTopK());
    }

    @Override
    public MemoryContext buildContext(AgentContext ctx) {
        MemoryContext context = new MemoryContext();

        // 1. 工作记忆
        if (ctx.getSessionId() != null) {
            WorkingMemory wm = workingMemoryManager.get(ctx.getSessionId());
            context.setWorkingMemory(wm);
        }

        // 2. 检索相关记忆
        MemoryScope scope = ctx.getProjectId() != null
            ? MemoryScope.ofProject(ctx.getTenantId(), ctx.getProjectId())
            : MemoryScope.ofUser(ctx.getTenantId(), ctx.getUserId());

        if (ctx.getGoal() != null && !ctx.getGoal().isBlank()) {
            MemoryQuery query = MemoryQuery.of(ctx.getGoal(), scope);
            query.setTopK(20);
            List<ScoredMemory> memories = searchEngine.search(query);
            memories = reranker.rerank(ctx.getGoal(), memories, 10);

            // 分类注入
            for (ScoredMemory sm : memories) {
                MemoryEntry m = sm.getMemory();
                m.touch(); // 更新访问计数

                switch (m.getMemoryType()) {
                    case SEMANTIC -> context.addFact(m.getContent());
                    case USER -> context.addPreference(m.getContent());
                    case PROCEDURAL -> context.addSkill(m.getContent());
                    case EPISODIC -> {
                        if (context.getEpisodes().size() < maxEpisodes) {
                            Episode ep = new Episode();
                            ep.setTaskSummary(m.getSummary() != null ? m.getSummary() : m.getContent());
                            ep.setImportance(m.getImportance());
                            context.getEpisodes().add(ep);
                        }
                    }
                    default -> context.getRawMemories().add(m);
                }
            }
        }

        // 3. 查询语义事实
        if (ctx.getProjectId() != null) {
            List<AgentSemanticFactEntity> facts = factRepository.findActiveFacts(
                ctx.getTenantId(),
                AgentMemoryEntryEntity.ScopeType.PROJECT,
                ctx.getProjectId());
            for (AgentSemanticFactEntity f : facts) {
                context.addFact(f.getSubject() + " " + f.getPredicate() + " " + f.getObjectValue());
            }
        }

        // 4. 查询可用 SOP
        if (ctx.getProjectId() != null && ctx.getGoal() != null) {
            List<AgentProcedureEntity> procs = procedureRepository.findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                ctx.getTenantId(),
                AgentMemoryEntryEntity.ScopeType.PROJECT,
                ctx.getProjectId(),
                AgentMemoryEntryEntity.Status.ACTIVE);
            for (AgentProcedureEntity p : procs) {
                context.addSkill(p.getName() + ": " + (p.getDescription() != null ? p.getDescription() : ""));
            }
        }

        return context;
    }

    // === 工作记忆 ===

    @Override
    public WorkingMemory getWorkingMemory(String sessionId) {
        return workingMemoryManager.get(sessionId);
    }

    @Override
    public void updateWorkingMemory(String sessionId, WorkingMemory update) {
        workingMemoryManager.update(sessionId, update);
    }

    // === 语义事实 ===

    @Override
    @Transactional
    public void writeFact(SemanticFact fact) {
        // 检查是否已有相同三元组
        List<AgentSemanticFactEntity> existing = factRepository.findActiveByTriple(
            fact.getTenantId(), fact.getSubject(), fact.getPredicate());

        AgentSemanticFactEntity entity = new AgentSemanticFactEntity();
        entity.setTenantId(fact.getTenantId());
        entity.setScopeType(AgentMemoryEntryEntity.ScopeType.valueOf(fact.getScope().scopeType().name()));
        entity.setScopeId(fact.getScope().scopeId());
        entity.setSubject(fact.getSubject());
        entity.setPredicate(fact.getPredicate());
        entity.setObjectValue(fact.getObjectValue());
        entity.setConfidence(fact.getConfidence());
        entity.setSource(fact.getSource());

        entity = factRepository.save(entity);

        // 归档旧版本
        for (AgentSemanticFactEntity old : existing) {
            old.setSupersededBy(entity.getId());
            old.setValidTo(LocalDateTime.now());
            factRepository.save(old);
        }
    }

    @Override
    public List<SemanticFact> queryFacts(String tenantId, String scopeType, String scopeId, String subject) {
        List<AgentSemanticFactEntity> entities;
        if (subject != null) {
            entities = factRepository.findByTenantIdAndScopeTypeAndScopeIdAndSubject(
                tenantId, AgentMemoryEntryEntity.ScopeType.valueOf(scopeType), scopeId, subject);
        } else {
            entities = factRepository.findByTenantIdAndScopeTypeAndScopeId(
                tenantId, AgentMemoryEntryEntity.ScopeType.valueOf(scopeType), scopeId);
        }
        return entities.stream().map(this::toFactModel).collect(Collectors.toList());
    }

    // === 程序性记忆 ===

    @Override
    @Transactional
    public void writeProcedure(Procedure procedure) {
        AgentProcedureEntity entity = new AgentProcedureEntity();
        entity.setTenantId(procedure.getTenantId());
        entity.setScopeType(AgentMemoryEntryEntity.ScopeType.valueOf(procedure.getScope().scopeType().name()));
        entity.setScopeId(procedure.getScope().scopeId());
        entity.setName(procedure.getName());
        entity.setDescription(procedure.getDescription());
        entity.setStepsJson(writeJson(procedure.getSteps()));
        entity.setPreconditionsJson(writeJson(procedure.getPreconditions()));
        entity.setSuccessConditionsJson(writeJson(procedure.getSuccessConditions()));
        entity.setImportance(procedure.getImportance());
        procedureRepository.save(entity);
    }

    @Override
    public List<Procedure> queryProcedures(String tenantId, String scopeType, String scopeId, String name) {
        List<AgentProcedureEntity> entities;
        if (name != null) {
            entities = procedureRepository.findByTenantIdAndScopeTypeAndScopeIdAndNameContainingAndStatus(
                tenantId, AgentMemoryEntryEntity.ScopeType.valueOf(scopeType), scopeId, name,
                AgentMemoryEntryEntity.Status.ACTIVE);
        } else {
            entities = procedureRepository.findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                tenantId, AgentMemoryEntryEntity.ScopeType.valueOf(scopeType), scopeId,
                AgentMemoryEntryEntity.Status.ACTIVE);
        }
        return entities.stream().map(this::toProcedureModel).collect(Collectors.toList());
    }

    // === 情景记忆 ===

    @Override
    public List<Episode> recallEpisodes(String query, String tenantId, String projectId, int topK) {
        // 先用关键词搜索
        List<AgentEpisodeEntity> entities = episodeRepository.findByTenantIdAndTaskSummaryContaining(
            tenantId, query);

        return entities.stream()
            .limit(topK)
            .map(this::toEpisodeModel)
            .collect(Collectors.toList());
    }

    // === 生命周期 ===

    @Override
    public void consolidate(String sessionId) {
        consolidationService.consolidate(sessionId);
    }

    @Override
    @Transactional
    public void forget(String tenantId) {
        List<AgentMemoryEntryEntity> actives = entryRepository.findByTenantIdAndStatus(
            tenantId, AgentMemoryEntryEntity.Status.ACTIVE);

        int archived = 0, deleted = 0;
        for (AgentMemoryEntryEntity entity : actives) {
            MemoryEntry memory = toMemoryEntryModel(entity);
            MemoryStatus newStatus = forgettingPolicy.evaluate(memory);

            if (newStatus == MemoryStatus.ARCHIVED && entity.getStatus() != AgentMemoryEntryEntity.Status.ARCHIVED) {
                entity.setStatus(AgentMemoryEntryEntity.Status.ARCHIVED);
                entryRepository.save(entity);
                if (indexOutbox != null) indexOutbox.enqueueDelete(entity.getId());
                archived++;
            } else if (newStatus == MemoryStatus.DELETED && entity.getStatus() != AgentMemoryEntryEntity.Status.DELETED) {
                entity.setStatus(AgentMemoryEntryEntity.Status.DELETED);
                entryRepository.save(entity);
                if (indexOutbox != null) indexOutbox.enqueueDelete(entity.getId());
                deleted++;
            }
        }

        log.info("遗忘执行: tenantId={}, archived={}, deleted={}", tenantId, archived, deleted);
    }

    // === 统计 ===

    @Override
    public MemoryStats getStats(String tenantId, String scopeType, String scopeId) {
        MemoryStats stats = new MemoryStats();

        stats.setTotalMemories(entryRepository.countByTenantIdAndScopeTypeAndScopeId(
            tenantId,
            scopeType != null ? AgentMemoryEntryEntity.ScopeType.valueOf(scopeType) : AgentMemoryEntryEntity.ScopeType.TENANT,
            scopeId != null ? scopeId : tenantId));
        stats.setActiveMemories(entryRepository.countByTenantIdAndStatus(tenantId, AgentMemoryEntryEntity.Status.ACTIVE));
        stats.setArchivedMemories(entryRepository.countByTenantIdAndStatus(tenantId, AgentMemoryEntryEntity.Status.ARCHIVED));
        stats.setDeletedMemories(entryRepository.countByTenantIdAndStatus(tenantId, AgentMemoryEntryEntity.Status.DELETED));
        stats.setTotalEpisodes(episodeRepository.countByTenantId(tenantId));
        stats.setTotalFacts(factRepository.countByTenantId(tenantId));
        stats.setTotalProcedures(procedureRepository.countByTenantId(tenantId));
        stats.setTotalEvents(eventRepository.countByTenantId(tenantId));
        stats.setUnprocessedEvents(eventRepository.countByProcessedFalse());

        // 类型分布
        List<Object[]> typeCounts = entryRepository.countByType(tenantId);
        Map<MemoryType, Long> dist = new HashMap<>();
        for (Object[] row : typeCounts) {
            if (row[0] instanceof AgentMemoryEntryEntity.MemoryType type && row[1] instanceof Long count) {
                dist.put(MemoryType.valueOf(type.name()), count);
            }
        }
        stats.setTypeDistribution(dist);

        return stats;
    }

    // === 转换工具 ===

    private AgentMemoryEntryEntity toEntity(MemoryEntry entry) {
        AgentMemoryEntryEntity entity = new AgentMemoryEntryEntity();
        entity.setTenantId(entry.getTenantId());
        entity.setMemoryType(AgentMemoryEntryEntity.MemoryType.valueOf(entry.getMemoryType().name()));
        entity.setScopeType(AgentMemoryEntryEntity.ScopeType.valueOf(entry.getScope().scopeType().name()));
        entity.setScopeId(entry.getScope().scopeId());
        entity.setContent(entry.getContent());
        entity.setSummary(entry.getSummary());
        entity.setImportance(entry.getImportance());
        entity.setConfidence(entry.getConfidence());
        entity.setStatus(AgentMemoryEntryEntity.Status.valueOf(entry.getStatus().name()));
        entity.setSourceType(entry.getSourceType() != null ?
            AgentMemoryEntryEntity.SourceType.valueOf(entry.getSourceType().name()) : null);
        entity.setSourceId(entry.getSourceId());
        entity.setAccessCount(entry.getAccessCount());
        return entity;
    }

    private MemoryEntry toMemoryEntryModel(AgentMemoryEntryEntity entity) {
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

    private Episode toEpisodeModel(AgentEpisodeEntity entity) {
        Episode ep = new Episode();
        ep.setId(entity.getId());
        ep.setTenantId(entity.getTenantId());
        ep.setUserId(entity.getUserId());
        ep.setProjectId(entity.getProjectId());
        ep.setTaskSummary(entity.getTaskSummary());
        ep.setOutcome(Episode.Outcome.valueOf(entity.getOutcome().name()));
        ep.setFailureCode(entity.getFailureCode());
        ep.setActions(parseJsonList(entity.getActionsJson()));
        ep.setObservations(parseJsonList(entity.getObservationsJson()));
        ep.setResolution(entity.getResolution());
        ep.setImportance(entity.getImportance() != null ? entity.getImportance() : 0.5);
        ep.setAccessCount(entity.getAccessCount() != null ? entity.getAccessCount() : 0);
        ep.setCreatedAt(entity.getCreatedAt() != null ?
            entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : 0);
        return ep;
    }

    private SemanticFact toFactModel(AgentSemanticFactEntity entity) {
        SemanticFact fact = new SemanticFact();
        fact.setId(entity.getId());
        fact.setTenantId(entity.getTenantId());
        fact.setScope(new MemoryScope(entity.getTenantId(),
            MemoryScope.ScopeType.valueOf(entity.getScopeType().name()), entity.getScopeId()));
        fact.setSubject(entity.getSubject());
        fact.setPredicate(entity.getPredicate());
        fact.setObjectValue(entity.getObjectValue());
        fact.setConfidence(entity.getConfidence() != null ? entity.getConfidence() : 0.9);
        fact.setSource(entity.getSource());
        fact.setSupersededBy(entity.getSupersededBy());
        return fact;
    }

    private Procedure toProcedureModel(AgentProcedureEntity entity) {
        Procedure proc = new Procedure();
        proc.setId(entity.getId());
        proc.setTenantId(entity.getTenantId());
        proc.setScope(new MemoryScope(entity.getTenantId(),
            MemoryScope.ScopeType.valueOf(entity.getScopeType().name()), entity.getScopeId()));
        proc.setName(entity.getName());
        proc.setDescription(entity.getDescription());
        proc.setSteps(parseJsonList(entity.getStepsJson()));
        proc.setPreconditions(parseJsonList(entity.getPreconditionsJson()));
        proc.setSuccessConditions(parseJsonList(entity.getSuccessConditionsJson()));
        proc.setUsageCount(entity.getUsageCount() != null ? entity.getUsageCount() : 0);
        proc.setSuccessRate(entity.getSuccessRate() != null ? entity.getSuccessRate() : 0);
        proc.setImportance(entity.getImportance() != null ? entity.getImportance() : 0.5);
        return proc;
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
