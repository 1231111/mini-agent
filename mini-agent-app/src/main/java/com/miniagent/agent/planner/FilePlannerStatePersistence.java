package com.miniagent.agent.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 单机文件回退（非多副本）。水平扩容请用 storage=db + replica.mode=redis。
 */
@Component
@ConditionalOnProperty(name = "agent.planner.storage", havingValue = "file")
public class FilePlannerStatePersistence implements PlannerStatePersistence {

    private static final Logger log = LoggerFactory.getLogger(FilePlannerStatePersistence.class);

    private final Path persistDir;

    public FilePlannerStatePersistence(
            @Value("${agent.planner.persist-dir:${agent.data-dir:${user.home}/.miniagent}/workspace/.planner}")
            String persistDir) {
        this.persistDir = Path.of(persistDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.persistDir);
        } catch (Exception e) {
            log.warn("无法创建 planner 目录 {}: {}", this.persistDir, e.getMessage());
        }
    }

    @Override
    public Optional<Bundle> load(String sessionId) {
        Path f = file(sessionId);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            var root = PlannerStateJson.MAPPER.readTree(raw);
            StateSnapshot snap = PlannerStateJson.snapshotFromJson(root.get("snapshot").toString());
            List<DomainEvent> events = root.has("events")
                    ? PlannerStateJson.eventsFromJson(root.get("events").toString())
                    : List.of();
            return Optional.of(new Bundle(snap, events));
        } catch (Exception e) {
            log.warn("读 planner 文件失败 {}: {}", f, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public synchronized void replace(String sessionId, StateSnapshot snapshot, List<DomainEvent> events) {
        write(sessionId, snapshot, events);
    }

    @Override
    public synchronized boolean compareAndSet(String sessionId, long expectedVersion,
                                              StateSnapshot next, List<DomainEvent> events) {
        Optional<Bundle> cur = load(sessionId);
        if (cur.isEmpty()) return false;
        if (cur.get().snapshot().version() != expectedVersion) return false;
        write(sessionId, next, events);
        return true;
    }

    @Override
    public synchronized boolean updateEvents(String sessionId, long expectedVersion,
                                             List<DomainEvent> events) {
        Optional<Bundle> cur = load(sessionId);
        if (cur.isEmpty()) return false;
        if (cur.get().snapshot().version() != expectedVersion) return false;
        write(sessionId, cur.get().snapshot(), events);
        return true;
    }

    @Override
    public void delete(String sessionId) {
        try {
            Files.deleteIfExists(file(sessionId));
        } catch (Exception e) {
            log.warn("删 planner 文件失败: {}", e.getMessage());
        }
    }

    private void write(String sessionId, StateSnapshot snapshot, List<DomainEvent> events) {
        try {
            var node = PlannerStateJson.MAPPER.createObjectNode();
            node.set("snapshot", PlannerStateJson.MAPPER.valueToTree(snapshot));
            node.set("events", PlannerStateJson.MAPPER.valueToTree(events == null ? List.of() : events));
            Files.writeString(file(sessionId),
                    PlannerStateJson.MAPPER.writeValueAsString(node), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("写 planner 文件失败: " + e.getMessage(), e);
        }
    }

    private Path file(String sessionId) {
        String safe = sessionId == null ? "_" : sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return persistDir.resolve(safe + ".json");
    }
}
