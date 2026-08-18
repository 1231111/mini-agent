package com.miniagent.config.service;

import com.miniagent.agent.planner.DomainEvent;
import com.miniagent.agent.planner.PlannerStateJson;
import com.miniagent.agent.planner.PlannerStatePersistence;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.config.entity.AgentSessionPlanner;
import com.miniagent.config.repository.AgentSessionPlannerRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "agent.planner.storage", havingValue = "db", matchIfMissing = true)
public class DbPlannerStatePersistence implements PlannerStatePersistence {

    private final AgentSessionPlannerRepository repo;

    public DbPlannerStatePersistence(AgentSessionPlannerRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Bundle> load(String sessionId) {
        if (sessionId == null) return Optional.empty();
        return repo.findById(sessionId).map(this::toBundle);
    }

    @Override
    @Transactional
    public void replace(String sessionId, StateSnapshot snapshot, List<DomainEvent> events) {
        AgentSessionPlanner row = repo.findById(sessionId).orElseGet(AgentSessionPlanner::new);
        row.setSessionId(sessionId);
        row.setPlannerVersion(snapshot.version());
        row.setStateJson(PlannerStateJson.snapshotToJson(snapshot));
        row.setEventsJson(PlannerStateJson.eventsToJson(events));
        row.setUpdatedAt(LocalDateTime.now());
        repo.save(row);
    }

    @Override
    @Transactional
    public boolean compareAndSet(String sessionId, long expectedVersion,
                                 StateSnapshot next, List<DomainEvent> events) {
        if (next.version() != expectedVersion + 1)
            throw new IllegalArgumentException(
                    "next.version must be expected+1, got " + next.version()
                            + " expectedBase=" + expectedVersion);
        if (!repo.existsById(sessionId)) return false;
        int updated = repo.casUpdate(
                sessionId,
                expectedVersion,
                next.version(),
                PlannerStateJson.snapshotToJson(next),
                PlannerStateJson.eventsToJson(events),
                LocalDateTime.now());
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean updateEvents(String sessionId, long expectedVersion, List<DomainEvent> events) {
        if (!repo.existsById(sessionId)) return false;
        int updated = repo.updateEvents(
                sessionId, expectedVersion,
                PlannerStateJson.eventsToJson(events),
                LocalDateTime.now());
        return updated == 1;
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        if (sessionId != null) repo.deleteById(sessionId);
    }

    private Bundle toBundle(AgentSessionPlanner row) {
        StateSnapshot snap = PlannerStateJson.snapshotFromJson(row.getStateJson());
        // 以列上的 planner_version 为准，防 JSON 与列漂移
        if (snap.version() != row.getPlannerVersion()) {
            snap = new StateSnapshot(
                    row.getPlannerVersion(), snap.sessionId(), snap.executionId(),
                    snap.goal(), snap.graph(), snap.execution(), snap.environment(),
                    snap.knowledgeRefs(), snap.recoveryCount(), snap.planRevision());
        }
        return new Bundle(snap, PlannerStateJson.eventsFromJson(row.getEventsJson()));
    }
}
