package com.miniagent.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.permission.SessionPermissionPersistence;
import com.miniagent.config.entity.AgentSessionPermission;
import com.miniagent.config.repository.AgentSessionPermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

@Service
public class DbSessionPermissionPersistence implements SessionPermissionPersistence {
    private final AgentSessionPermissionRepository repository;
    private final ObjectMapper objectMapper;

    public DbSessionPermissionPersistence(AgentSessionPermissionRepository repository,
                                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<State> load(String sessionId) {
        return repository.findById(sessionId).map(entity -> new State(
                entity.getMode(), entity.isPlanApproved(), readSet(entity.getAskGrantsJson()),
                entity.getConfirmPolicy()));
    }

    @Override
    @Transactional
    public void save(String sessionId, State state) {
        AgentSessionPermission entity = repository.findById(sessionId)
                .orElseGet(AgentSessionPermission::new);
        entity.setSessionId(sessionId);
        entity.setMode(state.mode());
        entity.setPlanApproved(state.planApproved());
        entity.setAskGrantsJson(writeSet(state.askGrantedTools()));
        entity.setConfirmPolicy(state.confirmPolicy());
        repository.save(entity);
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        repository.deleteById(sessionId);
    }

    private Set<String> readSet(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Set<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid permission grants JSON", e);
        }
    }

    private String writeSet(Set<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Set.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist permission grants", e);
        }
    }
}
