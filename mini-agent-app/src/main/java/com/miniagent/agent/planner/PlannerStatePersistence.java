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

    /**
     * 仅追加审计事件（不升 planner_version）；expectedVersion 不匹配则 false。
     */
    boolean updateEvents(String sessionId, long expectedVersion, List<DomainEvent> events);

    void delete(String sessionId);
}
