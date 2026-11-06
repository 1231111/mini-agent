package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.config.ConditionalOnCloudMemory;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.entity.AgentProjectEntity;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.memory.repository.AgentProjectRepository;
import com.miniagent.memory.contract.MemoryApi.ItemImport;
import com.miniagent.memory.contract.MemoryApi.ItemWrite;
import com.miniagent.memory.contract.MemoryApi.ScopeType;
import com.miniagent.memory.contract.ProcedureContent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 云端档启动时把本库已有 SOP 导入 memory-service（按 legacyProcedureId 幂等）。
 * 本库仍是客户端存储；导入之后由 {@link ProcedureCloudSync} 把云端升级写回来。
 */
@Component
@ConditionalOnCloudMemory
public class ProcedureCloudImport {
    private static final Logger log = LoggerFactory.getLogger(ProcedureCloudImport.class);

    private final CloudMemoryClient cloud;
    private final AgentProcedureRepository procedures;
    private final AgentProjectRepository projects;
    private final ObjectMapper json;

    public ProcedureCloudImport(CloudMemoryClient cloud, AgentProcedureRepository procedures,
                                AgentProjectRepository projects, ObjectMapper json) {
        this.cloud = cloud;
        this.procedures = procedures;
        this.projects = projects;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importLocal() {
        int imported = 0;
        int skipped = 0;
        for (AgentProcedureEntity procedure : procedures.findAll()) {
            if (procedure.getStatus() != AgentMemoryEntryEntity.Status.ACTIVE) {
                continue;
            }
            Long owner = ownerUserId(procedure, projects);
            if (owner == null) {
                skipped++;
                continue;
            }
            try {
                cloud.importItem(new ItemImport(procedure.getTenantId(), owner, toWrite(procedure)));
                imported++;
            } catch (RuntimeException e) {
                log.warn("导入 SOP 失败 id={} name={}: {}", procedure.getId(), procedure.getName(),
                        e.getMessage());
                skipped++;
            }
        }
        log.info("本地 SOP 导入 memory-service 完成 imported={} skipped={}", imported, skipped);
    }

    static Long ownerUserId(AgentProcedureEntity procedure, AgentProjectRepository projects) {
        if (procedure.getScopeType() == AgentMemoryEntryEntity.ScopeType.USER) {
            try {
                return Long.valueOf(procedure.getScopeId());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return projects.findByProjectIdAndTenantId(procedure.getScopeId(), procedure.getTenantId())
                .map(AgentProjectEntity::getOwnerUserId).orElse(null);
    }

    private ItemWrite toWrite(AgentProcedureEntity procedure) {
        return new ItemWrite(ScopeType.valueOf(procedure.getScopeType().name()),
                procedure.getScopeId(), procedure.getName(),
                new ProcedureContent(procedure.getDescription(), parse(procedure.getPreconditionsJson()),
                        parse(procedure.getStepsJson()), parse(procedure.getSuccessConditionsJson())),
                procedure.getId());
    }

    private List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
