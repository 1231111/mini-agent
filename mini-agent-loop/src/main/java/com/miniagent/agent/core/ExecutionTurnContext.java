package com.miniagent.agent.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Planner 到 Executor 的不可绕过执行栅栏，并显式传递到工具虚拟线程。 */
public final class ExecutionTurnContext {
    public interface DispatchFence {
        boolean isValid();
        default String rejectionReason() { return "dispatch fence 已失效，计划已修订或节点不再 RUNNING"; }
    }

    public record ActionBinding(String actionId, String nodeId, String toolName, String idempotencyKey) {
        public ActionBinding {
            actionId = normalize(actionId, "action");
            nodeId = normalize(nodeId, actionId);
            toolName = normalize(toolName, "unknown-tool");
            idempotencyKey = normalize(idempotencyKey, actionId);
        }
        private static String normalize(String value, String fallback) {
            String normalized = Objects.requireNonNullElse(value, "").trim();
            return normalized.isEmpty() ? fallback : normalized;
        }
    }

    public static final class Scope {
        private final String sessionId;
        private final String planVersion;
        private final DispatchFence fence;
        private final List<ActionBinding> bindings;
        private final Map<String, ActionBinding> resolved = new HashMap<>();
        private final Set<String> assigned = new HashSet<>();
        private final AtomicBoolean rejected = new AtomicBoolean();

        private Scope(String sessionId, long planVersion, DispatchFence fence, List<ActionBinding> bindings) {
            this.sessionId = sessionId;
            this.planVersion = Long.toString(planVersion);
            this.fence = fence == null ? () -> true : fence;
            this.bindings = bindings == null ? List.of() : List.copyOf(bindings);
        }

        public String sessionId() { return sessionId; }
        public String planVersion() { return planVersion; }
        public boolean isValid() {
            boolean valid = !rejected.get() && fence.isValid();
            if (!valid) rejected.set(true);
            return valid;
        }
        public String rejectionReason() { return fence.rejectionReason(); }

        public synchronized ActionBinding resolve(String callId, String toolName) {
            String key = Objects.requireNonNullElse(callId, "");
            ActionBinding existing = resolved.get(key);
            if (existing != null) return existing;
            for (ActionBinding binding : bindings) {
                if (!assigned.contains(binding.actionId()) && binding.toolName().equalsIgnoreCase(toolName)) {
                    assigned.add(binding.actionId());
                    resolved.put(key, binding);
                    return binding;
                }
            }
            ActionBinding synthetic = new ActionBinding(key, key, toolName, key);
            resolved.put(key, synthetic);
            return synthetic;
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private ExecutionTurnContext() {}

    public static Scope open(String sessionId, long planVersion, List<ActionBinding> bindings, DispatchFence fence) {
        Scope scope = new Scope(sessionId, planVersion, fence, bindings);
        CURRENT.set(scope);
        return scope;
    }
    public static Scope current() { return CURRENT.get(); }
    public static void set(Scope scope) { if (scope == null) CURRENT.remove(); else CURRENT.set(scope); }
    public static void clear() { CURRENT.remove(); }
}
