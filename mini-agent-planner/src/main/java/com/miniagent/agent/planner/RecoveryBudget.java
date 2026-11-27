package com.miniagent.agent.planner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Request-local recovery allowance stored in the same CAS snapshot as its graph change.
 * A background wake preserves its round; only a trusted new user request may rotate it.
 * Graph generation ({@code StateSnapshot.recoveryCount}) is deliberately not a budget.
 */
public final class RecoveryBudget {
    public static final String ROUND_ID = "recoveryRoundId";
    public static final String EXHAUSTED_REASON = "RECOVERY_BUDGET_EXHAUSTED";
    static final String SCHEMA_VERSION = "recoveryBudgetSchemaVersion";
    static final String CLASS_PREFIX = "recovery.";
    static final String LEGACY_TOTAL = "legacyRecoveryCountTotal";
    private static final int CURRENT_SCHEMA = 1;
    private static final ThreadLocal<RoundRequest> CURRENT_ROUND = new ThreadLocal<>();

    private record RoundRequest(String roundId, boolean newUserRequest) { }

    private RecoveryBudget() { }

    public static Scope bindRound(String roundId, boolean newUserRequest) {
        if (roundId == null || roundId.isBlank()) throw new IllegalArgumentException("recovery roundId required");
        RoundRequest previous = CURRENT_ROUND.get();
        CURRENT_ROUND.set(new RoundRequest(roundId, newUserRequest));
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final RoundRequest previous;
        private boolean closed;
        private Scope(RoundRequest previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT_ROUND.remove(); else CURRENT_ROUND.set(previous);
        }
    }

    /** Normalize old data without inventing extra audit recoveries or granting a new allowance. */
    public static Map<String, Object> prepare(StateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, Object> execution = new LinkedHashMap<>(snapshot.execution());
        String storedId = Objects.toString(execution.get(ROUND_ID), "");
        RoundRequest requested = CURRENT_ROUND.get();
        if (requested != null && !requested.newUserRequest() && !storedId.isBlank()
                && !storedId.equals(requested.roundId()))
            throw new IllegalStateException("background wake cannot change recovery round");
        if (storedId.isBlank()) {
            // Legacy snapshots retain their already-used allowance. A trusted user round
            // can rotate it below; repeated automatic wakes cannot repeatedly migrate it.
            execution.put(ROUND_ID, "legacy:" + Objects.toString(snapshot.executionId(), snapshot.sessionId()));
            storedId = execution.get(ROUND_ID).toString();
        }
        if (counter(execution.get(SCHEMA_VERSION)) < CURRENT_SCHEMA) {
            if (execution.containsKey(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL))
                execution.putIfAbsent(LEGACY_TOTAL, execution.get(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL));
            // The old total may contain a known double-add. Preserve it verbatim as legacy
            // audit rather than attempting to guess history. New accounting adds exactly once.
            execution.put(SCHEMA_VERSION, CURRENT_SCHEMA);
            execution.putIfAbsent(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL,
                    Math.max(0, snapshot.recoveryCount()));
            // An old snapshot without a round counter must not gain a free automatic
            // allowance merely by loading it. A trusted new user round resets below.
            execution.putIfAbsent(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT,
                    Math.max(0, snapshot.recoveryCount()));
        }
        if (requested != null && requested.newUserRequest() && !storedId.equals(requested.roundId())) {
            execution.put(ROUND_ID, requested.roundId());
            execution.put(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT, 0);
            for (FailureClass failureClass : FailureClass.values()) execution.remove(CLASS_PREFIX + failureClass.name());
            // Preserve the blocked round receipt until the user wait is consumed, even
            // if round normalization happens before consumeHumanWait on another path.
        }
        execution.putIfAbsent(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT, 0);
        return execution;
    }

    static boolean isNewUserRound(StateSnapshot snapshot) {
        RoundRequest requested = CURRENT_ROUND.get();
        if (requested == null || !requested.newUserRequest()) return false;
        Object blocked = snapshot.execution().get("recoveryBudgetBlocked");
        String blockedRound = blocked instanceof Map<?, ?> metadata
                ? Objects.toString(metadata.get("roundId"), "")
                : Objects.toString(snapshot.execution().get(ROUND_ID), "");
        return !requested.roundId().equals(blockedRound);
    }

    static int roundUsed(StateSnapshot snapshot) {
        return counter(prepare(snapshot).get(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT));
    }

    static int classUsed(StateSnapshot snapshot, FailureClass failureClass) {
        return counter(prepare(snapshot).get(CLASS_PREFIX + failureClass.name()));
    }

    static boolean available(StateSnapshot snapshot, FailureClass failureClass,
                             int maxRound, int maxClass, boolean reservedExplicitResume) {
        Map<String, Object> execution = prepare(snapshot);
        return reservedExplicitResume || available(counter(execution.get(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT)),
                maxRound, counter(execution.get(CLASS_PREFIX + failureClass.name())), maxClass);
    }

    static boolean available(int roundUsed, int maxRound, int classUsed, int maxClass) {
        return roundUsed < Math.max(0, maxRound) && classUsed < Math.max(0, maxClass);
    }

    /** Caller commits the returned map together with the exact graph transition, once. */
    static Map<String, Object> consume(StateSnapshot snapshot, FailureClass failureClass,
                                       int maxRound, int maxClass, boolean reservedExplicitResume) {
        Map<String, Object> execution = prepare(snapshot);
        int round = counter(execution.get(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT));
        int byClass = counter(execution.get(CLASS_PREFIX + failureClass.name()));
        if (!reservedExplicitResume && !available(round, maxRound, byClass, maxClass))
            throw new Exhausted(round, byClass);
        execution.put(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT, increment(round));
        execution.put(CLASS_PREFIX + failureClass.name(), increment(byClass));
        execution.put(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL,
                increment(counter(execution.get(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL))));
        return execution;
    }

    static int counter(Object value) {
        if (value == null) return 0;
        try {
            long count = value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, count));
        } catch (NumberFormatException malformed) { return 0; }
    }

    private static int increment(int count) { return count == Integer.MAX_VALUE ? count : count + 1; }

    static final class Exhausted extends IllegalStateException {
        Exhausted(int roundUsed, int classUsed) {
            super("recovery allowance exhausted: round=" + roundUsed + ", class=" + classUsed);
        }
    }
}
