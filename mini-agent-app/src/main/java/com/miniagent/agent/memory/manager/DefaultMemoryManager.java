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
import com.miniagent.common.context.OwnerContext;
import com.miniagent.agent.planner.ProcedurePromptInput;
import com.miniagent.agent.planner.ProcedurePromptPort;
import com.miniagent.memory.service.evolution.CandidateProcedure;
import com.miniagent.memory.service.web.EvolutionResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.SecurityScanner;
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
public class DefaultMemoryManager implements MemoryManager, ProcedurePromptPort {

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
    private ProcedureUpsertService procedureUpsertService;

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
        requireSafeForPrompt(entry.getContent(), entry.getSummary(), "记忆条目");
        AgentMemoryEntryEntity entity = toEntity(entry);
        entity = entryRepository.save(entity);
        entry.setId(entity.getId());

        // 写入向量
        if (indexOutbox != null) {
            indexOutbox.enqueueUpsert(entity.getId());
        }
    }

    /**
     * 写入前的内容闸门。
     *
     * <p><b>为什么是硬拒绝</b>：记忆会作为系统提示的一部分注入**之后每一轮**对话。
     * 一条被注入的记忆等于给会话装了一个持久后门 —— 而它可能来自网页正文、文件内容或
     * 工具输出（模型"顺手"记下来），所以写入侧的每一条路径都必须过扫描，
     * 不能只在 {@code MemoryStore.add} 那一处做（那条路径只覆盖 memory 工具的 blob 写入，
     * REST 的 {@code /memories}、{@code /facts}、{@code /procedures} 与巩固期的 LLM 提炼
     * 都不经过它）。</p>
     *
     * <p>命中即抛：调用方（REST 请求或巩固流程）需要知道"这条没写进去"，
     * 而不是静默存一条被改写的记忆。</p>
     */
    private void requireSafeForPrompt(String content, String summary, String what) {
        String reason = SecurityScanner.scan(content);
        if (reason == null) {
            reason = SecurityScanner.scan(summary);
        }
        if (reason != null) {
            log.warn("{}被安全扫描拒绝: {}", what, reason);
            throw new BusinessException(ErrorCode.MEMORY_CONTENT_REJECTED, what + "未写入：" + reason);
        }
    }

    @Override
    @Transactional
    public void updateMemory(Long id, MemoryEntry update) {
        entryRepository.findById(id).ifPresent(entity -> {
            if (update.getContent() != null) {
                entity.setContent(update.getContent());
            }
            if (update.getSummary() != null) {
                entity.setSummary(update.getSummary());
            }
            if (update.getImportance() > 0) {
                entity.setImportance(update.getImportance());
            }
            if (update.getConfidence() > 0) {
                entity.setConfidence(update.getConfidence());
            }
            entryRepository.save(entity);

            // 更新向量
            if (indexOutbox != null && update.getContent() != null) {
                indexOutbox.enqueueUpsert(id);
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemoryEntry> getMemory(String tenantId, Long id) {
        if (tenantId == null || tenantId.isBlank() || id == null) {
            return Optional.empty();
        }
        return entryRepository.findByIdAndTenantId(id, tenantId)
                .filter(entity -> entity.getStatus() != AgentMemoryEntryEntity.Status.DELETED)
                .map(this::toMemoryEntryModel);
    }

    @Override
    @Transactional
    public void updateMemory(String tenantId, Long id, MemoryEntry update) {
        if (tenantId == null || tenantId.isBlank() || id == null || update == null) {
            return;
        }
        entryRepository.findByIdAndTenantId(id, tenantId)
                .filter(entity -> entity.getStatus() != AgentMemoryEntryEntity.Status.DELETED)
                .ifPresent(entity -> applyUpdate(id, update, entity));
    }

    private void applyUpdate(Long id, MemoryEntry update, AgentMemoryEntryEntity entity) {
        // 更新同样要过闸门：改一条已存在的记忆也能把注入载荷写进未来的系统提示
        requireSafeForPrompt(update.getContent(), update.getSummary(), "记忆更新");
        if (update.getContent() != null) {
            entity.setContent(update.getContent());
        }
        if (update.getSummary() != null) {
            entity.setSummary(update.getSummary());
        }
        if (update.getImportance() > 0) {
            entity.setImportance(update.getImportance());
        }
        if (update.getConfidence() > 0) {
            entity.setConfidence(update.getConfidence());
        }
        entryRepository.save(entity);
        if (indexOutbox != null && update.getContent() != null) {
            indexOutbox.enqueueUpsert(id);
        }
    }

    @Override
    @Transactional
    public void deleteMemory(Long id) {
        entryRepository.findById(id).ifPresent(entity -> {
            entity.setStatus(AgentMemoryEntryEntity.Status.DELETED);
            entryRepository.save(entity);
            if (indexOutbox != null) {
                indexOutbox.enqueueDelete(id);
            }
        });
    }

    @Override
    @Transactional
    public void deleteMemory(String tenantId, Long id) {
        if (tenantId == null || tenantId.isBlank() || id == null) {
            return;
        }
        entryRepository.findByIdAndTenantId(id, tenantId).ifPresent(entity -> {
            entity.setStatus(AgentMemoryEntryEntity.Status.DELETED);
            entryRepository.save(entity);
            if (indexOutbox != null) {
                indexOutbox.enqueueDelete(id);
            }
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
            List<Long> touchedIds = new ArrayList<>();
            for (ScoredMemory sm : memories) {
                MemoryEntry m = sm.getMemory();
                m.touch(); // 内存侧更新访问计数
                if (m.getId() != null) {
                    touchedIds.add(m.getId());
                }

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

            // 持久化「被召回」这一事实：遗忘策略依赖 accessCount 与 lastAccessedAt，
            // 只改内存对象等于让这两个因子永远失效。
            persistAccess(touchedIds);
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
        //
        // 作用域必须与 ProcedurePromptPort.findForCurrentOwner 一致：那条路径是
        // 规划器拿候选的唯一来源，只认 USER scope（tenant + userId）。这里若继续只查
        // PROJECT，对话执行又没有 projectId，结果就是「用户写的 SOP 在规划器里可见、
        // 在系统提示的可用方法里不可见」—— 同一份记忆两个视图，排查时会被引到错方向。
        // 有项目上下文时仍以 PROJECT 为准，没有才回落到 USER。
        if (ctx.getGoal() != null) {
            AgentMemoryEntryEntity.ScopeType sopScope = null;
            String sopScopeId = null;
            if (StringUtils.isNotBlank(ctx.getProjectId())) {
                sopScope = AgentMemoryEntryEntity.ScopeType.PROJECT;
                sopScopeId = ctx.getProjectId();
            } else if (StringUtils.isNotBlank(ctx.getUserId())) {
                sopScope = AgentMemoryEntryEntity.ScopeType.USER;
                sopScopeId = ctx.getUserId();
            }
            if (sopScope != null) {
                List<AgentProcedureEntity> procs = procedureRepository
                        .findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                                ctx.getTenantId(), sopScope, sopScopeId,
                                AgentMemoryEntryEntity.Status.ACTIVE);
                for (AgentProcedureEntity p : procs) {
                    context.addSkill(p.getName() + ": "
                            + (p.getDescription() != null ? p.getDescription() : ""));
                }
            }
        }

        return context;
    }

    /**
     * 批量持久化访问记录。刻意不抛异常：召回已经完成，
     * 访问统计失败不应让整轮上下文装配失败。
     */
    private void persistAccess(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            entryRepository.touchAccessBatch(ids);
        } catch (Exception e) {
            log.debug("更新记忆访问计数失败: count={}, {}", ids.size(), e.getMessage());
        }
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
        // 事实会以"已知事实"注入提示（DefaultMemoryService 的 facts 段），同样要过闸门。
        // 巩固期由模型从工具输出/网页内容里提炼出来的"事实"尤其危险：
        // 攻击者只要让模型把一句话总结成事实，就能让它出现在之后每一次系统提示里。
        requireSafeForPrompt(
                fact.getSubject() + " " + fact.getPredicate() + " " + fact.getObjectValue(),
                null, "语义事实");
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
    public Procedure writeProcedure(Procedure procedure) {
        // SOP 会以"可用方法"注入提示，是"持久化指令"最容易被忽视的一条路径
        requireSafeForPrompt(
                procedure.getName() + " " + procedure.getDescription(),
                null, "程序性记忆(SOP)");
        // 身份判定、升版、计数清零都在 ProcedureUpsertService 里，这里只管安全闸门与模型转换。
        ProcedureUpsertService.Upserted upserted = procedureUpsertService.upsert(procedure);
        return toProcedureModel(upserted.entity());
    }

    @Autowired(required = false)
    private RemoteProcedureSettlementPort remoteEvolutionClient;

    private int canaryAssignment(String tenantId, Long userId, String sessionId, long taskId,
                                 Long procedureId, long proposalId) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String key = String.join("|", tenantId, userId.toString(), sessionId,
                    Long.toString(taskId), procedureId.toString(), Long.toString(proposalId));
            byte[] bytes = digest.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long value = java.nio.ByteBuffer.wrap(bytes, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
            return (int) (value % 100);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Value("${agent.memory.procedure.evolution-routing-enabled:false}")
    private boolean canaryRoutingEnabled;

    @Override
    @Transactional(readOnly = true)
    public List<ProcedurePromptInput> findForCurrentOwner(String query) {
        OwnerContext owner = OwnerContext.capture();
        if (owner.userId() == null || owner.tenantId() == null || owner.tenantId().isBlank()) {
            return List.of();
        }
        String projectId = OwnerContext.currentProjectId();
        List<AgentProcedureEntity> candidates = new ArrayList<>(procedureRepository
                .findByTenantIdAndScopeTypeAndScopeIdAndStatus(owner.tenantId(),
                        AgentMemoryEntryEntity.ScopeType.USER, String.valueOf(owner.userId()),
                        AgentMemoryEntryEntity.Status.ACTIVE));
        if (projectId != null && !projectId.isBlank()) {
            candidates.addAll(procedureRepository.findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                    owner.tenantId(), AgentMemoryEntryEntity.ScopeType.PROJECT, projectId,
                    AgentMemoryEntryEntity.Status.ACTIVE));
        }
        List<ProcedurePromptInput> baseline = candidates.stream()
                .filter(p -> query == null || query.isBlank()
                        || p.getName().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                        || Objects.toString(p.getDescription(), "").toLowerCase(Locale.ROOT)
                                .contains(query.toLowerCase(Locale.ROOT)))
                .map(p -> new ProcedurePromptInput(p.getId(), p.getProcedureVersion(), p.getName(),
                        p.getDescription(), parseJsonList(p.getPreconditionsJson()),
                        parseJsonList(p.getStepsJson()), parseJsonList(p.getSuccessConditionsJson())))
                .toList();
        RemoteProcedureSettlementPort remoteClient = canaryRoutingEnabled ? remoteEvolutionClient : null;
        if (remoteClient == null) return baseline.stream().limit(20).toList();
        String sessionId = com.miniagent.agent.core.AgentLoop.getCurrentSession();
        long taskId = com.miniagent.agent.core.TaskExecutionContext.currentTaskId();
        if (StringUtils.isBlank(sessionId) || taskId <= 0) return baseline.stream().limit(20).toList();
        Map<Long, ProcedurePromptInput> selected = new LinkedHashMap<>();
        List<EvolutionResponse> activeCanaries;
        try {
            activeCanaries = remoteClient.listCanaryCandidates(owner.tenantId(), owner.userId());
        } catch (Exception e) {
            log.warn("Canary assignment lookup failed; excluding USER procedures for this plan: {}",
                    e.getClass().getSimpleName());
            Set<Long> userProcedureIds = candidates.stream()
                    .filter(local -> local.getScopeType() == AgentMemoryEntryEntity.ScopeType.USER)
                    .map(AgentProcedureEntity::getId).collect(Collectors.toSet());
            return baseline.stream().filter(p -> !userProcedureIds.contains(p.procedureId()))
                    .limit(20).toList();
        }
        Set<Long> excludedProcedures = new HashSet<>();
        for (EvolutionResponse proposal : activeCanaries) {
            if (proposal.procedureId() == null || proposal.userId() == null
                    || !owner.userId().equals(proposal.userId()) || !owner.tenantId().equals(proposal.tenantId())) continue;
            ProcedurePromptInput local = baseline.stream()
                    .filter(p -> Objects.equals(p.procedureId(), proposal.procedureId())
                            && Objects.equals(p.version(), proposal.baselineVersion()))
                    .findFirst().orElse(null);
            if (local == null || proposal.canaryTargetPercent() <= 0) continue;
            boolean candidate = canaryAssignment(owner.tenantId(), owner.userId(), sessionId, taskId,
                    proposal.procedureId(), proposal.proposalId()) < proposal.canaryTargetPercent();
            if (candidate) {
                try {
                    EvolutionResponse snapshot = remoteClient.fetchCanaryCandidate(owner.tenantId(), owner.userId(),
                            proposal.procedureId(), proposal.proposalId(), proposal.candidateHash());
                    CandidateProcedure content = objectMapper.readValue(snapshot.candidateContentJson(), CandidateProcedure.class);
                    selected.put(proposal.procedureId(), new ProcedurePromptInput(proposal.procedureId(),
                            proposal.baselineVersion(), local.name(), content.description(), content.preconditions(),
                            content.steps(), content.successConditions(), proposal.proposalId(),
                            proposal.candidateHash(), "CANDIDATE", proposal.canaryTargetPercent()));
                } catch (Exception e) {
                    log.warn("Canary candidate snapshot unavailable; excluding procedure for this plan: proposal={} status={}",
                            proposal.proposalId(), e.getClass().getSimpleName());
                    excludedProcedures.add(proposal.procedureId());
                }
            }
        }
        baseline.stream().filter(p -> !excludedProcedures.contains(p.procedureId()))
                .forEach(p -> selected.putIfAbsent(p.procedureId(), p));
        return selected.values().stream().limit(20).toList();
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

    @Override
    public List<Episode> recallEpisodes(String query, String tenantId, String userId,
                                        String projectId, int topK) {
        List<AgentEpisodeEntity> entities = episodeRepository
                .findByTenantIdAndUserIdAndTaskSummaryContaining(tenantId, userId, query);
        return entities.stream()
                .filter(e -> projectId == null || Objects.equals(projectId, e.getProjectId()))
                .limit(Math.max(0, topK))
                .map(this::toEpisodeModel)
                .collect(Collectors.toList());
    }

    // === 生命周期 ===

    @Override
    public void consolidate(String sessionId) {
        consolidationService.consolidate(sessionId);
    }

    @Override
    public void consolidate(String tenantId, String sessionId) {
        consolidationService.consolidate(tenantId, sessionId);
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
                if (indexOutbox != null) {
                    indexOutbox.enqueueDelete(entity.getId());
                }
                archived++;
            } else if (newStatus == MemoryStatus.DELETED && entity.getStatus() != AgentMemoryEntryEntity.Status.DELETED) {
                entity.setStatus(AgentMemoryEntryEntity.Status.DELETED);
                entryRepository.save(entity);
                if (indexOutbox != null) {
                    indexOutbox.enqueueDelete(entity.getId());
                }
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
        // 必须映射：RetentionForgettingPolicy 的 recency 因子读的就是这个值，
        // 漏掉它会让 recencyDecay 永远走「从未访问」分支（恒 0.5）。
        entry.setLastAccessedAt(entity.getLastAccessedAt() != null ?
            entity.getLastAccessedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : 0);
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
        ep.setSessionId(entity.getSessionId());
        ep.setTaskId(entity.getTaskId());
        ep.setTaskSummary(entity.getTaskSummary());
        ep.setOutcome(Episode.Outcome.valueOf(entity.getOutcome().name()));
        ep.setFailureCode(entity.getFailureCode());
        ep.setActions(parseJsonList(entity.getActionsJson()));
        ep.setObservations(parseJsonList(entity.getObservationsJson()));
        ep.setResolution(entity.getResolution());
        ep.setImportance(entity.getImportance() != null ? entity.getImportance() : 0.5);
        ep.setAccessCount(entity.getAccessCount() != null ? entity.getAccessCount() : 0);
        ep.setLastAccessedAt(entity.getLastAccessedAt() != null ?
            entity.getLastAccessedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : 0);
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
        proc.setVersion(entity.getProcedureVersion());
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
        proc.setAttemptCount(entity.getAttemptCount() != null ? entity.getAttemptCount() : 0L);
        proc.setSuccessCount(entity.getSuccessCount() != null ? entity.getSuccessCount() : 0L);
        proc.setImportance(entity.getImportance() != null ? entity.getImportance() : 0.5);
        if (entity.getStatus() != null) {
            proc.setStatus(MemoryStatus.valueOf(entity.getStatus().name()));
        }
        return proc;
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeJson(Object obj) {
        if (obj == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
