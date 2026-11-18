package com.miniagent.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(name = "agent.todo.storage", havingValue = "db", matchIfMissing = true)
public class DbSessionTodoPersistence
        implements SessionTodoPersistence, TaskScopePersistence {

    private static final int MAX_CREATE_ATTEMPTS = 3;
    private static final int MAX_TASK_PARTITIONS = 24;
    private static final ObjectMapper JSON = new ObjectMapper();

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

    /**
     * Merge one task's checklist into the latest session row while holding a database
     * row lock. Full-row snapshots from independent replicas can otherwise overwrite
     * each other's task partitions even when JPA retries an optimistic-lock conflict.
     */
    @Override
    @Transactional
    public void saveTaskPartition(
            String sessionId,
            long taskId,
            String partitionJson,
            String fullSnapshotJson) {
        AgentSessionTodo row = repo.findByIdForUpdate(sessionId).orElse(null);
        if (row == null) {
            // TaskScopeRegistry normally creates this row first. Retain safe behavior
            // for migration/import paths which persist a checklist before scope state.
            row = new AgentSessionTodo();
            row.setSessionId(sessionId);
            row = repo.saveAndFlush(row);
            row = repo.findByIdForUpdate(sessionId).orElse(row);
        }

        ObjectNode partitions = readPartitions(row.getActiveJson());
        String partitionKey = String.valueOf(taskId);
        if (partitionJson == null || partitionJson.isBlank()) {
            partitions.remove(partitionKey);
        } else {
            JsonNode partition = parsePartition(partitionJson);
            if (isEmptyPartition(partition)) {
                partitions.remove(partitionKey);
            } else {
                partitions.set(partitionKey, partition);
            }
        }
        trimOldPartitions(partitions, row.getCurrentTaskId());
        try {
            row.setActiveJson(JSON.writeValueAsString(partitions));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize todo partitions", e);
        }
        row.setSuspendedJson(null);
        repo.saveAndFlush(row);
    }

    private ObjectNode readPartitions(String activeJson) {
        try {
            JsonNode root = JSON.readTree(activeJson == null || activeJson.isBlank()
                    ? "[]" : activeJson);
            ObjectNode partitions = JSON.createObjectNode();
            if (root == null || root.isNull()) {
                return partitions;
            }
            if (root.isArray()) {
                if (!root.isEmpty()) {
                    partitions.set("0", root);
                }
                return partitions;
            }
            if (!root.isObject()) {
                throw new IllegalStateException("Todo partition snapshot must be an object or array");
            }
            return (ObjectNode) root.deepCopy();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // Never replace an unreadable durable snapshot with a partial checklist.
            throw new IllegalStateException("Unable to read existing todo partitions", e);
        }
    }

    private JsonNode parsePartition(String json) {
        try {
            JsonNode partition = JSON.readTree(json);
            if (partition == null || partition.isNull()
                    || !(partition.isArray()
                    || (partition.isObject()
                    && partition.path("archived").asBoolean(false)
                    && partition.path("items").isArray()))) {
                throw new IllegalArgumentException("Invalid task todo partition");
            }
            return partition;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid task todo partition", e);
        }
    }

    private static boolean isEmptyPartition(JsonNode partition) {
        return partition.isArray() && partition.isEmpty()
                || partition.isObject() && partition.path("items").isArray()
                && partition.path("items").isEmpty();
    }

    private static void trimOldPartitions(ObjectNode partitions, long currentTaskId) {
        List<String> ids = new ArrayList<>();
        partitions.fieldNames().forEachRemaining(id -> {
            try {
                Long.parseLong(id);
                ids.add(id);
            } catch (NumberFormatException ignored) {
                // Preserve unknown legacy keys, but they do not count as task partitions.
            }
        });
        ids.sort(Comparator.comparingLong(Long::parseLong));
        while (ids.size() > MAX_TASK_PARTITIONS) {
            String oldest = ids.stream()
                    .filter(id -> Long.parseLong(id) != currentTaskId)
                    .findFirst().orElse(null);
            if (oldest == null) {
                break;
            }
            ids.remove(oldest);
            partitions.remove(oldest);
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
