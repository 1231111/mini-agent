package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.config.ConditionalOnCloudMemory;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.memory.repository.AgentProjectRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.contract.MemoryApi.ItemPage;
import com.miniagent.memory.contract.MemoryApi.ItemView;
import com.miniagent.memory.contract.MemoryApi.ItemWrite;
import com.miniagent.memory.contract.MemoryApi.ScopeType;
import com.miniagent.memory.contract.ProcedureContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 SOP 已存在。云端只保存同一份并自动升级；这里把升级后的当前修订写回本库。
 *
 * <p>ponytail: 定时同步扫全部本地 SOP 找归属人。条数上万后再改成按用户游标。</p>
 */
@Component
@ConditionalOnCloudMemory
public class ProcedureCloudSync {
    private static final Logger log = LoggerFactory.getLogger(ProcedureCloudSync.class);
    private static final int PAGE = 100;
    private static final int MAX_PAGES = 20;
    private static final String SOURCE_CLOUD = "CLOUD_SYNC";

    private final CloudMemoryClient cloud;
    private final AgentProcedureRepository procedures;
    private final AgentProjectRepository projects;
    private final ProcedureRevisionService revisions;
    private final ObjectMapper json;
    private final TransactionTemplate sameTx;
    private final TransactionTemplate newTx;
    private final long pullIntervalMs;
    private final ConcurrentHashMap<String, Long> lastPull = new ConcurrentHashMap<>();

    public ProcedureCloudSync(CloudMemoryClient cloud, AgentProcedureRepository procedures,
                              AgentProjectRepository projects, ProcedureRevisionService revisions,
                              ObjectMapper json, PlatformTransactionManager transactions,
                              @Value("${agent.memory.service.sync-interval-ms:15000}")
                              long pullIntervalMs) {
        this.cloud = cloud;
        this.procedures = procedures;
        this.projects = projects;
        this.revisions = revisions;
        this.json = json;
        this.pullIntervalMs = pullIntervalMs;
        this.sameTx = new TransactionTemplate(transactions);
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(initialDelayString = "${agent.memory.service.sync-interval-ms:15000}",
            fixedDelayString = "${agent.memory.service.sync-interval-ms:15000}")
    public void syncStoredCopies() {
        Set<String> seen = new HashSet<>();
        for (AgentProcedureEntity procedure : procedures.findAll()) {
            Long userId = ProcedureCloudImport.ownerUserId(procedure, projects);
            if (userId == null || procedure.getTenantId() == null) {
                continue;
            }
            String key = procedure.getTenantId() + "\n" + userId;
            if (!seen.add(key)) {
                continue;
            }
            try (OwnerContext.Scope ignored = OwnerContext.bind(
                    new OwnerContext(userId, procedure.getTenantId()))) {
                pull();
            } catch (RuntimeException e) {
                log.warn("同步用户 SOP 失败 tenant={} user={}: {}", procedure.getTenantId(), userId,
                        e.getMessage());
            }
        }
    }

    /** 把云端当前修订写回当前用户的本库。失败只记日志，规划仍用上次同步下来的内容。 */
    public void pull() {
        OwnerContext owner = OwnerContext.capture();
        if (owner.userId() == null || owner.tenantId() == null || owner.tenantId().isBlank()) {
            return;
        }
        String key = owner.tenantId() + "\n" + owner.userId();
        long now = System.currentTimeMillis();
        Long previous = lastPull.get(key);
        if (previous != null && now - previous < pullIntervalMs) {
            return;
        }
        lastPull.put(key, now);
        try {
            Long cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                ItemPage batch = cloud.list(null, cursor, PAGE);
                if (batch == null || batch.items() == null || batch.items().isEmpty()) {
                    return;
                }
                for (ItemView item : batch.items()) {
                    apply(owner.tenantId(), item);
                }
                if (batch.nextCursor() == null) {
                    return;
                }
                cursor = batch.nextCursor();
            }
        } catch (RuntimeException e) {
            lastPull.remove(key);
            log.warn("从云端同步 SOP 失败: {}", e.getMessage());
        }
    }

    /** 用户在客户端改了 SOP 之后推到云端。云端再升级时，由 {@link #pull()} 写回。 */
    public void push(Long localId) {
        pushWith(localId, sameTx);
    }

    private void pushInNewTransaction(Long localId) {
        pushWith(localId, newTx);
    }

    private void pushWith(Long localId, TransactionTemplate tx) {
        if (localId == null) {
            return;
        }
        AgentProcedureEntity row = procedures.findById(localId).orElse(null);
        if (row == null) {
            return;
        }
        ItemView saved = cloud.upsert(toWrite(row));
        tx.executeWithoutResult(status -> link(localId, saved));
    }

    private void apply(String tenantId, ItemView item) {
        if (item == null || item.content() == null) {
            return;
        }
        AgentProcedureEntity existing = procedures.findByTenantIdAndCloudItemId(tenantId, item.id())
                .orElse(null);
        if (existing == null) {
            existing = findByIdentity(tenantId, item);
        }
        // 还没对上云端、云端仍是导入时的第一版：本库改动推上去，避免把未推送的编辑盖掉。
        if (existing != null && existing.getCloudItemId() == null
                && !sameContent(existing, item.content()) && item.revisionNo() <= 1) {
            pushInNewTransaction(existing.getId());
            return;
        }
        newTx.executeWithoutResult(status -> applyInTx(tenantId, item));
    }

    private void applyInTx(String tenantId, ItemView item) {
        AgentProcedureEntity row = procedures.findByTenantIdAndCloudItemId(tenantId, item.id())
                .orElse(null);
        if (row == null) {
            row = findByIdentity(tenantId, item);
        }
        if (row == null) {
            insert(tenantId, item);
            return;
        }
        if (row.getCloudItemId() == null && sameContent(row, item.content())) {
            linkRow(row, item);
            procedures.save(row);
            return;
        }
        if (Objects.equals(row.getCloudItemId(), item.id())
                && Objects.equals(row.getCloudRevisionNo(), item.revisionNo())
                && sameStatus(row, item.status())) {
            return;
        }
        boolean contentChanged = !sameContent(row, item.content());
        revisions.capture(row, SOURCE_CLOUD, String.valueOf(item.id()),
                OwnerContext.currentUserId());
        fill(row, item);
        if (contentChanged) {
            row.setSuccessCount(0L);
            row.setAttemptCount(0L);
            row.setSuccessRate(0.0);
            row.setUsageCount(0);
        }
        AgentProcedureEntity saved = procedures.save(row);
        revisions.capture(saved, SOURCE_CLOUD, String.valueOf(item.id()),
                OwnerContext.currentUserId());
    }

    private void insert(String tenantId, ItemView item) {
        AgentProcedureEntity row = new AgentProcedureEntity();
        row.setTenantId(tenantId);
        row.setScopeType(AgentMemoryEntryEntity.ScopeType.valueOf(item.scopeType().name()));
        row.setScopeId(item.scopeId());
        row.setImportance(0.5);
        fill(row, item);
        AgentProcedureEntity saved = procedures.save(row);
        revisions.capture(saved, SOURCE_CLOUD, String.valueOf(item.id()),
                OwnerContext.currentUserId());
    }

    private void link(Long localId, ItemView saved) {
        AgentProcedureEntity current = procedures.findById(localId).orElse(null);
        if (current == null || saved == null) {
            return;
        }
        linkRow(current, saved);
        procedures.save(current);
    }

    private static void linkRow(AgentProcedureEntity row, ItemView item) {
        row.setCloudItemId(item.id());
        row.setCloudRevisionNo(item.revisionNo());
    }

    private void fill(AgentProcedureEntity row, ItemView item) {
        ProcedureContent content = item.content();
        row.setName(item.name() == null ? "" : item.name().trim());
        row.setDescription(content.description());
        row.setPreconditionsJson(writeJson(content.preconditions()));
        row.setStepsJson(writeJson(content.steps()));
        row.setSuccessConditionsJson(writeJson(content.successConditions()));
        row.setStatus(statusOf(item.status()));
        row.setProcedureVersion(item.revisionNo());
        linkRow(row, item);
    }

    private AgentProcedureEntity findByIdentity(String tenantId, ItemView item) {
        AgentMemoryEntryEntity.ScopeType scope = AgentMemoryEntryEntity.ScopeType.valueOf(
                item.scopeType().name());
        String key = ProcedureUpsertService.identityKey(item.name());
        return procedures.findByTenantIdAndScopeTypeAndScopeId(tenantId, scope, item.scopeId())
                .stream()
                .filter(p -> p.getStatus() != AgentMemoryEntryEntity.Status.DELETED)
                .filter(p -> ProcedureUpsertService.identityKey(p.getName()).equals(key))
                .findFirst()
                .orElse(null);
    }

    private ItemWrite toWrite(AgentProcedureEntity row) {
        return new ItemWrite(ScopeType.valueOf(row.getScopeType().name()), row.getScopeId(),
                row.getName(), new ProcedureContent(row.getDescription(),
                parse(row.getPreconditionsJson()), parse(row.getStepsJson()),
                parse(row.getSuccessConditionsJson())), row.getId());
    }

    private boolean sameContent(AgentProcedureEntity row, ProcedureContent content) {
        return Objects.equals(Objects.toString(row.getDescription(), ""),
                Objects.toString(content.description(), ""))
                && Objects.equals(parse(row.getPreconditionsJson()),
                nullToEmpty(content.preconditions()))
                && Objects.equals(parse(row.getStepsJson()), nullToEmpty(content.steps()))
                && Objects.equals(parse(row.getSuccessConditionsJson()),
                nullToEmpty(content.successConditions()));
    }

    private static boolean sameStatus(AgentProcedureEntity row, String status) {
        return row.getStatus() == statusOf(status);
    }

    private static AgentMemoryEntryEntity.Status statusOf(String status) {
        if (status == null || status.isBlank()) {
            return AgentMemoryEntryEntity.Status.ACTIVE;
        }
        try {
            return AgentMemoryEntryEntity.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AgentMemoryEntryEntity.Status.ARCHIVED;
        }
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String writeJson(List<String> values) {
        try {
            return json.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
