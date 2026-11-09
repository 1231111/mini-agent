package com.miniagent.agent.permission;

import java.util.Set;
import java.util.Map;

/** Shared persistence contract implemented by the application module. */
public interface SessionPermissionPersistence {
    /**
     * 会话权限快照。
     *
     * <p>字段顺序里 {@code version} 必须永远在最后：{@code compareAndSet} 依赖版本号做 CAS，
     * 把它挪到中间会让所有按位置构造的旧调用悄悄错位。新增的
     * {@code alwaysAllowedTools}（「本会话始终允许」的工具名集合）因此放在 {@code version}
     * <b>之前</b>。
     */
    record State(
            String mode,
            boolean planApproved,
            Set<String> askGrantedTools,
            Map<String, AskGrant> askGrants,
            String confirmPolicy,
            String execPolicyOverride,
            Set<String> alwaysAllowedTools,
            long version) {
        /** Compatibility constructor for the pre-token persistence format (6 args). */
        public State(
                String mode,
                boolean planApproved,
                Set<String> askGrantedTools,
                String confirmPolicy,
                String execPolicyOverride,
                long version) {
            this(mode, planApproved, askGrantedTools, Map.of(),
                    confirmPolicy, execPolicyOverride, Set.of(), version);
        }

        /** Compatibility constructor for the pre-always format (7 args, with askGrants). */
        public State(
                String mode,
                boolean planApproved,
                Set<String> askGrantedTools,
                Map<String, AskGrant> askGrants,
                String confirmPolicy,
                String execPolicyOverride,
                long version) {
            this(mode, planApproved, askGrantedTools, askGrants,
                    confirmPolicy, execPolicyOverride, Set.of(), version);
        }

        public State {
            askGrantedTools = askGrantedTools == null
                    ? Set.of()
                    : Set.copyOf(askGrantedTools);
            askGrants = askGrants == null ? Map.of() : Map.copyOf(askGrants);
            alwaysAllowedTools = alwaysAllowedTools == null
                    ? Set.of()
                    : Set.copyOf(alwaysAllowedTools);
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
