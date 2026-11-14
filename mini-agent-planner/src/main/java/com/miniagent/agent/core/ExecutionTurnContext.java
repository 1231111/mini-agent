package com.miniagent.agent.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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
        private final String executionId;
        private final String planVersion;
        private final DispatchFence fence;
        private final List<ActionBinding> bindings;
        private final String effectNamespace;
        private final Map<String, ActionBinding> resolved = new HashMap<>();
        private final Set<String> assigned = new HashSet<>();
        private final AtomicBoolean rejected = new AtomicBoolean();

        private Scope(String sessionId, String executionId, long planVersion, DispatchFence fence, List<ActionBinding> bindings) {
            this(sessionId, executionId, planVersion, fence, bindings, null);
        }

        private Scope(String sessionId, String executionId, long planVersion, DispatchFence fence,
                      List<ActionBinding> bindings, String inheritedNamespace) {
            this.sessionId = sessionId;
            this.executionId = Objects.requireNonNullElse(executionId, "");
            this.planVersion = Long.toString(planVersion);
            this.fence = fence == null ? () -> true : fence;
            this.bindings = bindings == null ? List.of() : List.copyOf(bindings);
            this.effectNamespace = inheritedNamespace == null ? bindingNamespace(this.bindings) : inheritedNamespace;
        }

        public String sessionId() { return sessionId; }
        public String executionId() { return executionId; }
        public String planVersion() { return planVersion; }
        /** Stable across provider call IDs and proposal retries, isolated for each child branch. */
        public String effectNamespace() { return effectNamespace; }
        public boolean isRejected() { return rejected.get(); }
        public boolean isValid() {
            boolean valid;
            try {
                valid = !rejected.get() && fence.isValid();
            } catch (RuntimeException unavailable) {
                rejected.set(true);
                throw unavailable;
            }
            if (!valid) {
                rejected.set(true);
            }
            return valid;
        }
        public String rejectionReason() { return fence.rejectionReason(); }

        /** Child work inherits revocation, but never consumes the parent's action bindings. */
        public Scope forSubagent(String childSessionId) {
            return new Scope(childSessionId, executionId, Long.parseLong(planVersion), this::isValid, List.of(),
                    digest(effectNamespace + "\nchild\n" + Objects.requireNonNullElse(childSessionId, "")));
        }

        /**
         * Model calls are operations, not a proof that an entire DAG action completed.
         * A new call must read fresh state and may repeat a confirmed click. The
         * pipeline's stable effect permit independently blocks unresolved writes.
         */
        public synchronized ActionBinding resolveInvocation(String callId, String toolName) {
            ActionBinding binding = resolve(callId, toolName);
            ActionBinding owner = bindings.stream().filter(b -> b.actionId().equals(binding.actionId()))
                    .findFirst().orElse(bindings.size() == 1 ? bindings.getFirst() : null);
            String node = owner == null ? "scope-" + effectNamespace : owner.nodeId();
            String stable = owner == null ? effectNamespace : owner.idempotencyKey();
            return new ActionBinding(binding.actionId(), node, toolName,
                    "invocation:" + digest(stable + "\n" + toolName + "\n" + Objects.requireNonNullElse(callId, "")));
        }

        /** Find unresolved pre-operation receipts without adopting their success as business output. */
        public ActionBinding legacyBinding(ActionBinding invocation) {
            if (!invocation.idempotencyKey().startsWith("invocation:")) return invocation;
            return bindings.stream().filter(b -> b.actionId().equals(invocation.actionId()))
                    .findFirst().orElse(bindings.size() == 1 ? bindings.getFirst() : invocation);
        }

        public synchronized ActionBinding resolve(String callId, String toolName) {
            String key = Objects.requireNonNullElse(callId, "");
            ActionBinding existing = resolved.get(key);
            if (existing != null) {
                return existing;
            }
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

        private static String bindingNamespace(List<ActionBinding> bindings) {
            StringBuilder identity = new StringBuilder();
            bindings.stream().map(b -> List.of(b.nodeId(), b.toolName(), b.idempotencyKey()))
                    .map(parts -> parts.stream().map(s -> s.length() + ":" + s).reduce("", String::concat))
                    .sorted().forEach(s -> identity.append(s.length()).append(':').append(s));
            return digest(identity.toString());
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private ExecutionTurnContext() {}

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static Scope open(String sessionId, long planVersion, List<ActionBinding> bindings, DispatchFence fence) {
        return open(sessionId, "", planVersion, bindings, fence);
    }

    /** The logical execution survives proposal retries and differs for a new task in the same chat. */
    public static Scope open(String sessionId, String executionId, long planVersion, List<ActionBinding> bindings, DispatchFence fence) {
        Scope scope = new Scope(sessionId, executionId, planVersion, fence, bindings);
        CURRENT.set(scope);
        return scope;
    }
    public static Scope current() { return CURRENT.get(); }
    public static void set(Scope scope) { if (scope == null) CURRENT.remove(); else CURRENT.set(scope); }
    public static void clear() { CURRENT.remove(); }
}
