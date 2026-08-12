package com.miniagent.agent.planner;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本轮规划上下文（ThreadLocal）：AgentLoop / TodoTool 硬闸门 + 漂移标记。
 */
public final class PlanningContext {

    private static final ThreadLocal<Holder> TL = new ThreadLocal<>();

    public record Holder(
            String sessionId,
            long stateVersion,
            String proposalId,
            List<String> allowedTools,
            String focusTaskId,
            String focusTaskName,
            Set<Integer> focusTodoIds,
            boolean forceProposalToolsOnly,
            boolean hardGate,
            AtomicBoolean driftFlag,
            AtomicInteger driftCount
    ) {
        public Holder {
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
            focusTodoIds = focusTodoIds == null ? Set.of() : Set.copyOf(focusTodoIds);
            focusTaskName = focusTaskName == null ? "" : focusTaskName;
            driftFlag = driftFlag == null ? new AtomicBoolean(false) : driftFlag;
            driftCount = driftCount == null ? new AtomicInteger(0) : driftCount;
        }

        public void markDrift() {
            driftFlag.set(true);
            driftCount.incrementAndGet();
        }

        public boolean consumeDrift() {
            return driftFlag.getAndSet(false);
        }

        public int driftHits() {
            return driftCount.get();
        }
    }

    private PlanningContext() {}

    public static void set(Holder h) { TL.set(h); }

    public static Holder get() { return TL.get(); }

    public static boolean active() { return TL.get() != null; }

    public static boolean hardGateActive() {
        Holder h = TL.get();
        return h != null && h.hardGate();
    }

    public static void clear() { TL.remove(); }
}
