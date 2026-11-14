package com.miniagent.agent.planner;

import java.util.List;
import java.util.Optional;

/**
 * Planner 状态持久化端口。DB/Redis 供水平扩容；file 供单机回退。
 */
public interface PlannerStatePersistence {

    record Bundle(StateSnapshot snapshot, List<DomainEvent> events) {
        public Bundle {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    Optional<Bundle> load(String sessionId);

    /** 覆盖写入（init / 强制重置），version 以 snapshot 为准。 */
    void replace(String sessionId, StateSnapshot snapshot, List<DomainEvent> events);

    /**
     * CAS：仅当当前 plannerVersion == expectedVersion 时写入 next（version 须为 expected+1）。
     * @return false 表示版本冲突
     */
    boolean compareAndSet(String sessionId, long expectedVersion,
                          StateSnapshot next, List<DomainEvent> events);

    /** Atomic CAS also fences a replaced execution whose version restarted at the same value. */
    default boolean compareAndSet(String sessionId, long expectedVersion, String expectedExecutionId,
                                  StateSnapshot next, List<DomainEvent> events) {
        throw new UnsupportedOperationException("PLANNER_EXECUTION_CAS_REQUIRED");
    }

    /** Atomically commits the graph and appends only these new events to the latest retained history. */
    default boolean compareAndSetAppending(String sessionId, long expectedVersion, String expectedExecutionId,
                                          StateSnapshot next, List<DomainEvent> appended) {
        throw new UnsupportedOperationException("PLANNER_ATOMIC_EVENT_APPEND_REQUIRED");
    }

    /**
     * Legacy audit API; implementations merge additions without removing committed events.
     * Production callers use appendEvents with a delta, never a previously loaded full history.
     */
    boolean updateEvents(String sessionId, long expectedVersion, List<DomainEvent> events);

    /** Audit writes must never attach an old execution's event to its successor. */
    default boolean updateEvents(String sessionId, long expectedVersion, String expectedExecutionId,
                                 List<DomainEvent> events) {
        throw new UnsupportedOperationException("PLANNER_EXECUTION_CAS_REQUIRED");
    }

    /** Appends a delta under the same stream lock as graph commits, without changing graph version. */
    default boolean appendEvents(String sessionId, long expectedVersion, String expectedExecutionId,
                                 List<DomainEvent> appended) {
        throw new UnsupportedOperationException("PLANNER_ATOMIC_EVENT_APPEND_REQUIRED");
    }

    void delete(String sessionId);
}
