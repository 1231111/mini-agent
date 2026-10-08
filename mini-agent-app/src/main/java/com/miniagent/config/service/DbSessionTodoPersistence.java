package com.miniagent.config.service;

import com.miniagent.agent.todo.SessionTodoPersistence;
import com.miniagent.agent.scope.TaskScopePersistence;
import com.miniagent.config.entity.AgentSessionTodo;
import com.miniagent.config.repository.AgentSessionTodoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "agent.todo.storage", havingValue = "db", matchIfMissing = true)
public class DbSessionTodoPersistence
        implements SessionTodoPersistence, TaskScopePersistence {

    private static final int MAX_CREATE_ATTEMPTS = 3;

    @Autowired
    private AgentSessionTodoRepository repo;

    @Override
    @Transactional(readOnly = true)
    public SessionTodoPersistence.State load(String sessionId) {
        return repo.findById(sessionId)
                .map(e -> new SessionTodoPersistence.State(
                        e.getActiveJson(), e.getSuspendedJson()))
                .orElse(new SessionTodoPersistence.State("[]", null));
    }

    @Override
    public void save(String sessionId, String activeJson, String suspendedJson) {
        String active = activeJson == null || activeJson.isBlank() ? "[]" : activeJson;
        for (int i = 0; i < 3; i++) {
            try {
                AgentSessionTodo row = repo.findById(sessionId).orElseGet(AgentSessionTodo::new);
                row.setSessionId(sessionId);
                row.setActiveJson(active);
                row.setSuspendedJson(suspendedJson);
                repo.saveAndFlush(row);
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (i == 2) {
                    throw e;
                }
            }
        }
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        AgentSessionTodo row = repo.findById(sessionId).orElse(null);
        if (row == null) {
            return;
        }
        row.setActiveJson("[]");
        row.setSuspendedJson(null);
        repo.save(row);
    }

    @Override
    @Transactional
    public void deleteScope(String sessionId) {
        repo.deleteById(sessionId);
    }

    @Override
    public TaskScopePersistence.State loadOrCreate(String sessionId) {
        for (int attempt = 0; attempt < MAX_CREATE_ATTEMPTS; attempt++) {
            AgentSessionTodo existing = repo.findById(sessionId).orElse(null);
            if (existing != null) {
                return scopeState(existing);
            }
            try {
                AgentSessionTodo created = new AgentSessionTodo();
                created.setSessionId(sessionId);
                return scopeState(repo.saveAndFlush(created));
            } catch (DataIntegrityViolationException e) {
                AgentSessionTodo concurrent =
                        repo.findById(sessionId).orElse(null);
                if (concurrent != null) {
                    return scopeState(concurrent);
                }
                if (attempt == MAX_CREATE_ATTEMPTS - 1) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException(
                "Unable to initialize task scope for session " + sessionId);
    }

    @Override
    public boolean compareAndSet(
            String sessionId,
            TaskScopePersistence.State expected,
            TaskScopePersistence.State next) {
        if (next.version() != expected.version() + 1L) {
            throw new IllegalArgumentException(
                    "Task scope CAS must increment version by one");
        }
        return repo.compareAndSetScope(
                sessionId,
                expected.version(),
                next.currentTaskId(),
                next.pausedTaskId(),
                next.sequence(),
                LocalDateTime.now()) == 1;
    }

    private TaskScopePersistence.State scopeState(AgentSessionTodo row) {
        long version = row.getVersion() == null ? 0L : row.getVersion();
        return new TaskScopePersistence.State(
                row.getCurrentTaskId(),
                row.getPausedTaskId(),
                row.getScopeSequence(),
                version);
    }
}
