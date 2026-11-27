package com.miniagent.agent.core;

import java.util.Set;

/** Trusted application scope for an unattended continuation; model text cannot create it. */
public final class GoalContinuationContext {
    public record Continuation(String executionId, long taskId, String recoveryRoundId, String wakeupReason,
                               boolean unattended) {
        public Continuation(String executionId, long taskId, String recoveryRoundId, String wakeupReason) {
            this(executionId, taskId, recoveryRoundId, wakeupReason, true);
        }
    }
    private static final ThreadLocal<Continuation> CURRENT = new ThreadLocal<>();
    private static final Set<String> SAFE = Set.of("read_file", "list_files", "read_package", "image_inspect",
            "search_code", "ast_search", "codebase_search", "web_search", "web_extract", "http_get",
            "skill_list", "skill_view", "todo", "capability_discover");
    private GoalContinuationContext() { }
    public static Continuation current() { return CURRENT.get(); }
    public static boolean active() { return current() != null; }
    public static boolean unattended() { return current() != null && current().unattended(); }
    public static boolean allows(String name) { return name != null && SAFE.contains(name); }
    public static void restore(Continuation value) { if (value == null) CURRENT.remove(); else CURRENT.set(value); }
    public static Scope bind(Continuation value) {
        Continuation previous = current(); restore(value); return () -> restore(previous);
    }
    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
}
