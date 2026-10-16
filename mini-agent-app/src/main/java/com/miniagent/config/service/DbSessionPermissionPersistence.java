package com.miniagent.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.AskGrant;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionPersistence;
import com.miniagent.config.entity.AgentSessionPermission;
import com.miniagent.config.repository.AgentSessionPermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Service
public class DbSessionPermissionPersistence implements SessionPermissionPersistence {

    private static final int MAX_CREATE_ATTEMPTS = 3;

    @Autowired
    private AgentSessionPermissionRepository repository;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public State loadOrCreate(String sessionId) {
        for (int attempt = 0; attempt < MAX_CREATE_ATTEMPTS; attempt++) {
            AgentSessionPermission existing =
                    repository.findById(sessionId).orElse(null);
            if (existing != null) {
                return state(existing);
            }
            try {
                AgentSessionPermission created = new AgentSessionPermission();
                created.setSessionId(sessionId);
                created.setMode(PermissionMode.DEFAULT.wireName());
                created.setPlanApproved(false);
                created.setAskGrantsJson("[]");
                created.setConfirmPolicy(
                        ConfirmPolicy.DANGEROUS.wireName());
                return state(repository.saveAndFlush(created));
            } catch (DataIntegrityViolationException e) {
                AgentSessionPermission concurrent =
                        repository.findById(sessionId).orElse(null);
                if (concurrent != null) {
                    return state(concurrent);
                }
                if (attempt == MAX_CREATE_ATTEMPTS - 1) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException(
                "Unable to initialize permissions for session " + sessionId);
    }

    @Override
    public boolean compareAndSet(
            String sessionId, State expected, State next) {
        if (next.version() != expected.version() + 1L) {
            throw new IllegalArgumentException(
                    "Permission CAS must increment version by one");
        }
        return repository.compareAndSet(
                sessionId,
                expected.version(),
                next.mode(),
                next.planApproved(),
                writeGrants(next.askGrantedTools(), next.askGrants()),
                next.confirmPolicy(),
                next.execPolicyOverride(),
                LocalDateTime.now()) == 1;
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        repository.deleteById(sessionId);
    }

    private State state(AgentSessionPermission entity) {
        GrantData grants = readGrants(entity.getAskGrantsJson());
        return new State(
                entity.getMode(),
                entity.isPlanApproved(),
                grants.toolNames(),
                grants.grants(),
                entity.getConfirmPolicy(),
                entity.getExecPolicyOverride(),
                entity.getVersion());
    }

    private GrantData readGrants(String json) {
        if (json == null || json.isBlank()) {
            return new GrantData(Set.of(), Map.of());
        }
        try {
            String trimmed = json.trim();
            if (trimmed.startsWith("{")) {
                Map<String, AskGrant> grants = objectMapper.readValue(
                        trimmed, new TypeReference<Map<String, AskGrant>>() {});
                Set<String> names = grants.values().stream()
                        .map(AskGrant::toolName).collect(java.util.stream.Collectors.toUnmodifiableSet());
                return new GrantData(names, grants);
            }
            // Existing installations stored a JSON array of tool names. Keep
            // it visible for compatibility, but the execution pipeline does
            // not consider names alone to be authorization.
            Set<String> names = objectMapper.readValue(
                    trimmed, new TypeReference<Set<String>>() {});
            return new GrantData(names, Map.of());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid permission grants JSON", e);
        }
    }

    private String writeGrants(Set<String> names, Map<String, AskGrant> grants) {
        try {
            if (grants != null && !grants.isEmpty()) {
                return objectMapper.writeValueAsString(grants);
            }
            return objectMapper.writeValueAsString(names == null ? Set.of() : names);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist permission grants", e);
        }
    }

    private record GrantData(Set<String> toolNames, Map<String, AskGrant> grants) {
    }
}
