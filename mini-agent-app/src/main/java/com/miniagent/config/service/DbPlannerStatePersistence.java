package com.miniagent.config.service;

import com.miniagent.agent.planner.DomainEvent;
import com.miniagent.agent.planner.PlannerStateJson;
import com.miniagent.agent.planner.PlannerStatePersistence;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.agent.planner.PlannerEventHistory;
import com.miniagent.config.entity.AgentSessionPlanner;
import com.miniagent.config.repository.AgentSessionPlannerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "agent.planner.storage", havingValue = "db", matchIfMissing = true)
public class DbPlannerStatePersistence implements PlannerStatePersistence {

    @Autowired
    private AgentSessionPlannerRepository repo;

    @Override
    @Transactional(readOnly = true)
    public Optional<Bundle> load(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return repo.findById(sessionId).map(this::toBundle);
    }

    @Override
    @Transactional
    public void replace(String sessionId, StateSnapshot snapshot, List<DomainEvent> events) {
        requireStream(sessionId, snapshot.executionId(), snapshot);
        AgentSessionPlanner row = repo.findById(sessionId).orElseGet(AgentSessionPlanner::new);
        row.setSessionId(sessionId);
        row.setPlannerVersion(snapshot.version());
        row.setExecutionId(snapshot.executionId());
        row.setStateJson(PlannerStateJson.snapshotToJson(snapshot));
        row.setEventsJson(PlannerStateJson.eventsToJson(events));
        row.setUpdatedAt(LocalDateTime.now());
        repo.save(row);
    }

    @Override
    @Transactional
    public boolean compareAndSet(String sessionId, long expectedVersion,
                                 StateSnapshot next, List<DomainEvent> events) {
        return compareAndSet(sessionId, expectedVersion, next.executionId(), next, events);
    }

    @Override
    @Transactional
    public boolean compareAndSet(String sessionId, long expectedVersion, String expectedExecutionId,
                                 StateSnapshot next, List<DomainEvent> events) {
        return compareAndSetAppending(sessionId, expectedVersion, expectedExecutionId, next, events);
    }

    @Override
    @Transactional
    public boolean compareAndSetAppending(String sessionId, long expectedVersion, String expectedExecutionId,
                                          StateSnapshot next, List<DomainEvent> appended) {
        requireStream(sessionId, expectedExecutionId, next);
        if (next.version() != expectedVersion + 1)
            throw new IllegalArgumentException(
                    "next.version must be expected+1, got " + next.version()
                            + " expectedBase=" + expectedVersion);
        var locked = repo.lockStream(sessionId);
        if (locked.isEmpty() || !matches(locked.get(), expectedVersion, expectedExecutionId)) return false;
        List<DomainEvent> events = PlannerEventHistory.append(
                PlannerStateJson.eventsFromJson(locked.get().getEventsJson()), appended);
        int updated = repo.casUpdate(
                sessionId,
                expectedVersion,
                expectedExecutionId,
                next.version(),
                PlannerStateJson.snapshotToJson(next),
                PlannerStateJson.eventsToJson(events),
                LocalDateTime.now());
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean updateEvents(String sessionId, long expectedVersion, List<DomainEvent> events) {
        Optional<Bundle> existing = load(sessionId);
        return existing.isPresent() && updateEvents(sessionId, expectedVersion,
                existing.get().snapshot().executionId(), events);
    }

    @Override
    @Transactional
    public boolean updateEvents(String sessionId, long expectedVersion, String expectedExecutionId,
                                List<DomainEvent> events) {
        return appendEvents(sessionId, expectedVersion, expectedExecutionId, events);
    }

    @Override
    @Transactional
    public boolean appendEvents(String sessionId, long expectedVersion, String expectedExecutionId,
                                List<DomainEvent> appended) {
        var locked = repo.lockStream(sessionId);
        if (locked.isEmpty() || !matches(locked.get(), expectedVersion, expectedExecutionId)) return false;
        List<DomainEvent> events = PlannerEventHistory.append(
                PlannerStateJson.eventsFromJson(locked.get().getEventsJson()), appended);
        int updated = repo.updateEvents(
                sessionId, expectedVersion,
                expectedExecutionId,
                PlannerStateJson.eventsToJson(events),
                LocalDateTime.now());
        return updated == 1;
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        if (sessionId != null) {
            repo.deleteById(sessionId);
        }
    }

    private Bundle toBundle(AgentSessionPlanner row) {
        StateSnapshot snap = PlannerStateJson.snapshotFromJson(row.getStateJson());
        if (!Objects.equals(row.getExecutionId(), snap.executionId()))
            throw new IllegalStateException("PLANNER_EXECUTION_METADATA_MISMATCH");
        // 以列上的 planner_version 为准，防 JSON 与列漂移
        if (snap.version() != row.getPlannerVersion()) {
            snap = new StateSnapshot(
                    row.getPlannerVersion(), snap.sessionId(), snap.executionId(),
                    snap.goal(), snap.graph(), snap.execution(), snap.environment(),
                    snap.knowledgeRefs(), snap.recoveryCount(), snap.planRevision(), snap.intent(),
                    snap.compilationSource(), snap.degraded());
        }
        return new Bundle(snap, PlannerStateJson.eventsFromJson(row.getEventsJson()));
    }

    private static void requireStream(String sessionId, String executionId, StateSnapshot next) {
        if (!Objects.equals(sessionId, next.sessionId()) || !Objects.equals(executionId, next.executionId()))
            throw new IllegalArgumentException("planner stream identity cannot change within CAS");
    }

    private static boolean matches(AgentSessionPlannerRepository.LockedStream stream, long version, String executionId) {
        return stream.getPlannerVersion() == version && Objects.equals(executionId, stream.getExecutionId());
    }
}
