package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.entity.AgentProcedureRevisionEntity;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.memory.repository.AgentProcedureRevisionRepository;
import com.miniagent.common.context.OwnerContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Service
public class ProcedureRevisionService {
    private final AgentProcedureRepository procedures;
    private final AgentProcedureRevisionRepository revisions;
    private final ObjectMapper mapper;

    public ProcedureRevisionService(AgentProcedureRepository procedures,
                                   AgentProcedureRevisionRepository revisions, ObjectMapper mapper) {
        this.procedures = procedures; this.revisions = revisions; this.mapper = mapper;
    }

    @Transactional
    public void capture(AgentProcedureEntity procedure, String sourceType, String sourceId, Long actorId) {
        if (procedure == null || procedure.getId() == null || procedure.getProcedureVersion() == null) return;
        if (revisions.findByTenantIdAndProcedureIdAndProcedureVersion(procedure.getTenantId(),
                procedure.getId(), procedure.getProcedureVersion()).isPresent()) return;
        String content = content(procedure);
        AgentProcedureRevisionEntity revision = new AgentProcedureRevisionEntity();
        revision.setTenantId(procedure.getTenantId()); revision.setProcedureId(procedure.getId());
        revision.setProcedureVersion(procedure.getProcedureVersion()); revision.setContentJson(content);
        revision.setContentHash(sha256(content)); revision.setSourceType(sourceType);
        revision.setSourceId(sourceId); revision.setActorUserId(actorId);
        revisions.saveAndFlush(revision);
    }

    @Transactional
    public boolean rollback(Long procedureId, Integer targetVersion) {
        OwnerContext owner = OwnerContext.capture();
        if (owner.userId() == null || owner.tenantId() == null) throw new SecurityException("owner required");
        AgentProcedureEntity current = procedures.findById(procedureId).orElseThrow();
        if (!owner.tenantId().equals(current.getTenantId())
                || current.getScopeType() != com.miniagent.agent.memory.entity.AgentMemoryEntryEntity.ScopeType.USER
                || !String.valueOf(owner.userId()).equals(current.getScopeId())) throw new SecurityException("procedure inaccessible");
        AgentProcedureRevisionEntity target = revisions.findByTenantIdAndProcedureIdAndProcedureVersion(
                owner.tenantId(), procedureId, targetVersion).orElseThrow();
        try {
            @SuppressWarnings("unchecked") Map<String, Object> value = mapper.readValue(target.getContentJson(), Map.class);
            int expected = current.getProcedureVersion();
            return procedures.upgradeContent(procedureId, owner.tenantId(), expected, expected + 1,
                    String.valueOf(value.get("name")), String.valueOf(value.getOrDefault("description", "")),
                    mapper.writeValueAsString(value.get("preconditions")),
                    mapper.writeValueAsString(value.get("steps")),
                    mapper.writeValueAsString(value.get("successConditions")),
                    current.getImportance() == null ? 0.5 : current.getImportance(),
                    com.miniagent.agent.memory.entity.AgentMemoryEntryEntity.Status.ACTIVE) == 1;
        } catch (Exception e) { throw new IllegalStateException("Unable to restore procedure revision", e); }
    }

    private String content(AgentProcedureEntity p) {
        try {
            var value = mapper.createObjectNode();
            value.put("name", p.getName());
            value.put("description", p.getDescription() == null ? "" : p.getDescription());
            value.set("preconditions", jsonValue(p.getPreconditionsJson()));
            value.set("steps", jsonValue(p.getStepsJson()));
            value.set("successConditions", jsonValue(p.getSuccessConditionsJson()));
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize procedure revision", e);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode jsonValue(String json) throws Exception {
        return json == null || json.isBlank() ? mapper.createArrayNode() : mapper.readTree(json);
    }

    private String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
