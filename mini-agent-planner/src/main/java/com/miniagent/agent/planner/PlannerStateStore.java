package com.miniagent.agent.planner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Planner 单一事实源：session 绑定 + version CAS。
 * 有 {@link PlannerStatePersistence} 时走共享存储（DB/Redis，可水平扩容）；
 * 无持久化时回退进程内 map（单测）。
 */
@Component
public class PlannerStateStore {

    public static final class VersionConflictException extends RuntimeException {
        private final long expected;
        private final long actual;

        public VersionConflictException(long expected, long actual) {
            super("state version conflict expected=" + expected + " actual=" + actual);
            this.expected = expected;
            this.actual = actual;
        }

        public long expected() { return expected; }
        public long actual() { return actual; }
    }

    private static final int MAX_EVENTS = 500;
    private static final int TRIM_TO = 400;
    private static final String RESUME_REQUESTED_KEY = "_planner.resumeRequested";

    private final ConcurrentHashMap<String, StateSnapshot> memory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<DomainEvent>> memoryEvents = new ConcurrentHashMap<>();
    private final PlannerStatePersistence persistence;
    /** ponytail: 进程内续跑标记；多实例时改走 persistence */
    private final Set<String> resumeSessions = ConcurrentHashMap.newKeySet();

    /** 单测：纯内存 */
    public PlannerStateStore() {
        this.persistence = null;
    }

    @Autowired
    public PlannerStateStore(@Autowired(required = false) PlannerStatePersistence persistence) {
        this.persistence = persistence;
    }

    public Optional<StateSnapshot> get(String sessionId) {
        if (sessionId == null) return Optional.empty();
        if (persistence != null)
            return persistence.load(sessionId).map(PlannerStatePersistence.Bundle::snapshot);
        return Optional.ofNullable(memory.get(sessionId));
    }

    public StateSnapshot init(String sessionId, String executionId, Goal goal, TaskGraph graph) {
        StateSnapshot snap = new StateSnapshot(
                1L, sessionId, executionId, goal, graph,
                Map.of(), Map.of(), List.of(), 0, PlanRevision.initial("initial_compile"));
        DomainEvent ev = new DomainEvent(
                "ev_" + UUID.randomUUID().toString().substring(0, 8),
                DomainEventType.GRAPH_COMPILED, null, null,
                Map.of("nodes", graph.nodes().size(), "version", 1L, "planVersion", snap.planVersion()), null);
        List<DomainEvent> events = List.of(ev);
        if (persistence != null) {
            persistence.replace(sessionId, snap, events);
        } else {
            memory.put(sessionId, snap);
            memoryEvents.put(sessionId, new ArrayList<>(events));
        }
        return snap;
    }

    /**
     * CAS：仅当 expectedVersion 匹配时提交新快照（版本自动 +1）。
     */
    public StateSnapshot commit(String sessionId, long expectedVersion, StateSnapshot next) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(next, "next");
        if (persistence != null)
            return commitPersistent(sessionId, expectedVersion, next);
        return commitMemory(sessionId, expectedVersion, next);
    }

    private StateSnapshot commitPersistent(String sessionId, long expectedVersion, StateSnapshot next) {
        PlannerStatePersistence.Bundle cur = persistence.load(sessionId)
                .orElseThrow(() -> new IllegalStateException("no planner state for session " + sessionId));
        if (cur.snapshot().version() != expectedVersion)
            throw new VersionConflictException(expectedVersion, cur.snapshot().version());

        StateSnapshot committed = buildCommitted(sessionId, cur.snapshot(), next);
        List<DomainEvent> events = appendLocal(cur.events(), new DomainEvent(
                "ev_" + UUID.randomUUID().toString().substring(0, 8),
                DomainEventType.STATE_COMMITTED, null, null,
                Map.of("version", committed.version(), "planVersion", committed.planVersion()), null));
        if (!persistence.compareAndSet(sessionId, expectedVersion, committed, events)) {
            long actual = persistence.load(sessionId)
                    .map(b -> b.snapshot().version()).orElse(-1L);
            throw new VersionConflictException(expectedVersion, actual);
        }
        return committed;
    }

    private StateSnapshot commitMemory(String sessionId, long expectedVersion, StateSnapshot next) {
        for (;;) {
            StateSnapshot cur = memory.get(sessionId);
            if (cur == null)
                throw new IllegalStateException("no planner state for session " + sessionId);
            if (cur.version() != expectedVersion)
                throw new VersionConflictException(expectedVersion, cur.version());
            StateSnapshot committed = buildCommitted(sessionId, cur, next);
            if (memory.replace(sessionId, cur, committed)) {
                appendEvent(sessionId, new DomainEvent(
                        "ev_" + UUID.randomUUID().toString().substring(0, 8),
                        DomainEventType.STATE_COMMITTED, null, null,
                        Map.of("version", committed.version(), "planVersion", committed.planVersion()), null));
                return committed;
            }
        }
    }

    private static StateSnapshot buildCommitted(String sessionId, StateSnapshot cur, StateSnapshot next) {
        if (next.sessionId() != null && !next.sessionId().isBlank()
                && !Objects.equals(sessionId, next.sessionId())) {
            throw new IllegalArgumentException("sessionId cannot change within a planner state stream");
        }
        if (cur.executionId() != null && next.executionId() != null
                && !Objects.equals(cur.executionId(), next.executionId())) {
            throw new IllegalArgumentException("executionId cannot change within a planner state stream");
        }
        PlanRevision revision = next.planRevision();
        if (revision.planVersion() < cur.planVersion()
                || revision.planVersion() > cur.planVersion() + 1) {
            throw new IllegalArgumentException("planVersion must increase by at most one");
        }
        if (revision.planVersion() == cur.planVersion() && !samePlanSemantics(cur, next)) {
            throw new IllegalArgumentException("plan semantic fields changed without a new planVersion");
        }
        if (revision.planVersion() == cur.planVersion() + 1
                && revision.parentPlanVersion() != cur.planVersion()) {
            throw new IllegalArgumentException("planRevision parent does not match current plan");
        }
        return new StateSnapshot(
                cur.version() + 1,
                sessionId,
                next.executionId() != null ? next.executionId() : cur.executionId(),
                next.goal() != null ? next.goal() : cur.goal(),
                next.graph() != null ? next.graph() : cur.graph(),
                next.execution(),
                next.environment(),
                next.knowledgeRefs(),
                next.recoveryCount(), revision);
    }

    private static boolean samePlanSemantics(StateSnapshot left, StateSnapshot right) {
        return Objects.equals(left.goal(), right.goal())
                && sameGraphSemantics(left.graph(), right.graph());
    }

    private static boolean sameGraphSemantics(TaskGraph left, TaskGraph right) {
        if (left == right) return true;
        if (left == null || right == null || left.nodes().size() != right.nodes().size()) return false;
        for (int i = 0; i < left.nodes().size(); i++) {
            TaskNode a = left.nodes().get(i);
            TaskNode b = right.nodes().get(i);
            if (!Objects.equals(a.id(), b.id()) || !Objects.equals(a.name(), b.name())
                    || !Objects.equals(a.capability(), b.capability())
                    || !Objects.equals(a.dependsOn(), b.dependsOn())
                    || !Objects.equals(a.inputs(), b.inputs()) || !Objects.equals(a.outputs(), b.outputs())
                    || !Objects.equals(a.doneWhen(), b.doneWhen()) || !Objects.equals(a.toolHint(), b.toolHint())
                    || !Objects.equals(a.toolArguments(), b.toolArguments())
                    || !Objects.equals(a.compensation(), b.compensation()) || !Objects.equals(a.covers(), b.covers())) {
                return false;
            }
        }
        return true;
    }

    public List<DomainEvent> events(String sessionId) {
        if (persistence != null)
            return persistence.load(sessionId)
                    .map(PlannerStatePersistence.Bundle::events)
                    .orElse(List.of());
        return List.copyOf(memoryEvents.getOrDefault(sessionId, List.of()));
    }

    public void appendEvent(String sessionId, DomainEvent event) {
        if (sessionId == null || event == null) return;
        if (persistence != null) {
            for (int i = 0; i < 3; i++) {
                Optional<PlannerStatePersistence.Bundle> loaded = persistence.load(sessionId);
                if (loaded.isEmpty()) return;
                long ver = loaded.get().snapshot().version();
                List<DomainEvent> events = appendLocal(loaded.get().events(), event);
                if (persistence.updateEvents(sessionId, ver, events)) return;
            }
            return;
        }
        memoryEvents.compute(sessionId, (k, v) -> appendLocal(v, event));
    }

    public void clear(String sessionId) {
        if (sessionId == null) {
            return;
        }
        if (persistence != null) {
            persistence.delete(sessionId);
        }
        memory.remove(sessionId);
        memoryEvents.remove(sessionId);
        resumeSessions.remove(sessionId);
    }

    public void markResume(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            resumeSessions.add(sessionId);
            updateResumeFlag(sessionId, true);
        }
    }

    public boolean peekResume(String sessionId) {
        return sessionId != null && (resumeSessions.contains(sessionId)
                || get(sessionId).map(s -> Boolean.TRUE.equals(s.execution().get(RESUME_REQUESTED_KEY))).orElse(false));
    }

    public void clearResume(String sessionId) {
        if (sessionId != null) {
            resumeSessions.remove(sessionId);
            updateResumeFlag(sessionId, false);
        }
    }

    private void updateResumeFlag(String sessionId, boolean requested) {
        for (int i = 0; i < 3; i++) {
            StateSnapshot current = get(sessionId).orElse(null);
            if (current == null) return;
            boolean existing = Boolean.TRUE.equals(current.execution().get(RESUME_REQUESTED_KEY));
            if (existing == requested) return;
            Map<String, Object> execution = new HashMap<>(current.execution());
            if (requested) execution.put(RESUME_REQUESTED_KEY, true);
            else execution.remove(RESUME_REQUESTED_KEY);
            try {
                commit(sessionId, current.version(), current.withExecution(execution));
                return;
            } catch (VersionConflictException ignored) {
                // Retry against the latest snapshot.
            }
        }
    }

    public boolean hasIncompleteGraph(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return get(sessionId)
                .filter(s -> s.graph() != null && !s.graph().isEmpty())
                .filter(s -> !s.graph().allTerminalSuccess())
                .isPresent();
    }

    private static List<DomainEvent> appendLocal(List<DomainEvent> cur, DomainEvent event) {
        List<DomainEvent> list = cur == null ? new ArrayList<>() : new ArrayList<>(cur);
        list.add(event);
        if (list.size() > MAX_EVENTS)
            list = new ArrayList<>(list.subList(list.size() - TRIM_TO, list.size()));
        return list;
    }
}
