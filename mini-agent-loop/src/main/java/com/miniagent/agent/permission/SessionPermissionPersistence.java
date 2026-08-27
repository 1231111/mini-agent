package com.miniagent.agent.permission;

import java.util.Optional;
import java.util.Set;

/** Shared persistence contract implemented by the application module. */
public interface SessionPermissionPersistence {
    record State(String mode, boolean planApproved, Set<String> askGrantedTools,
                 String confirmPolicy) {}

    Optional<State> load(String sessionId);
    void save(String sessionId, State state);
    void delete(String sessionId);
}
