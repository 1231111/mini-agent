package com.miniagent.agent.permission;

import java.util.Set;

/** Shared persistence contract implemented by the application module. */
public interface SessionPermissionPersistence {
    record State(
            String mode,
            boolean planApproved,
            Set<String> askGrantedTools,
            String confirmPolicy,
            String execPolicyOverride,
            long version) {
        public State {
            askGrantedTools = askGrantedTools == null
                    ? Set.of()
                    : Set.copyOf(askGrantedTools);
            if (version < 0L) {
                throw new IllegalArgumentException(
                        "Permission state version cannot be negative");
            }
        }
    }

    State loadOrCreate(String sessionId);

    boolean compareAndSet(String sessionId, State expected, State next);

    void delete(String sessionId);
}
