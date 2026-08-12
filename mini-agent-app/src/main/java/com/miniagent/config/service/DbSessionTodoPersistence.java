package com.miniagent.config.service;

import com.miniagent.agent.todo.SessionTodoPersistence;
import com.miniagent.config.entity.AgentSessionTodo;
import com.miniagent.config.repository.AgentSessionTodoRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "agent.todo.storage", havingValue = "db", matchIfMissing = true)
public class DbSessionTodoPersistence implements SessionTodoPersistence {

    private final AgentSessionTodoRepository repo;

    public DbSessionTodoPersistence(AgentSessionTodoRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public State load(String sessionId) {
        return repo.findById(sessionId)
                .map(e -> new State(e.getActiveJson(), e.getSuspendedJson()))
                .orElse(new State("[]", null));
    }

    @Override
    @Transactional
    public void save(String sessionId, String activeJson, String suspendedJson) {
        String active = activeJson == null || activeJson.isBlank() ? "[]" : activeJson;
        for (int i = 0; i < 3; i++) {
            try {
                AgentSessionTodo row = repo.findById(sessionId).orElseGet(AgentSessionTodo::new);
                row.setSessionId(sessionId);
                row.setActiveJson(active);
                row.setSuspendedJson(suspendedJson);
                repo.save(row);
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (i == 2) throw e;
            }
        }
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        repo.deleteById(sessionId);
    }
}
