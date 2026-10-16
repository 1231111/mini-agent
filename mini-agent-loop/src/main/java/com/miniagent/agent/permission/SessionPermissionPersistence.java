package com.miniagent.agent.permission;

import java.util.Set;
import java.util.Map;

/** Shared persistence contract implemented by the application module. */
public interface SessionPermissionPersistence {
    record State(
            String mode,
            boolean planApproved,
            Set<String> askGrantedTools,
            Map<String, AskGrant> askGrants,
            String confirmPolicy,
            String execPolicyOverride,
            long version) {
        /** Compatibility constructor for the pre-token persistence format. */
        public State(
                String mode,
                boolean planApproved,
                Set<String> askGrantedTools,
                String confirmPolicy,
                String execPolicyOverride,
                long version) {
            this(mode, planApproved, askGrantedTools, Map.of(),
                    confirmPolicy, execPolicyOverride, version);
        }

        public State {
            askGrantedTools = askGrantedTools == null
                    ? Set.of()
                    : Set.copyOf(askGrantedTools);
            askGrants = askGrants == null ? Map.of() : Map.copyOf(askGrants);
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
