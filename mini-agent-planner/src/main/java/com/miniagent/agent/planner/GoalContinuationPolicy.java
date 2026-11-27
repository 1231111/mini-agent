package com.miniagent.agent.planner;

import com.miniagent.agent.core.GoalContinuationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared objective-state classification for scheduling and the final dispatch boundary. */
public final class GoalContinuationPolicy {
    private static final Set<String> SAFE_CAPABILITIES = Set.of("file_read", "research", "plan", "qa", "web");
    private GoalContinuationPolicy() { }
    public static boolean readOnlyNode(StateSnapshot snapshot, TaskNode node) {
        if (node == null || node.toolStatus() == com.miniagent.agent.tool.ToolStatus.UNKNOWN) return false;
        Object repairs = snapshot.execution().get("acceptanceRepairs");
        if (repairs instanceof Map<?, ?> map && map.containsKey(snapshot.planVersion() + ":" + node.id())) return true;
        if (!SAFE_CAPABILITIES.contains(node.capability())) return false;
        if (node.doneWhen() != null && (node.doneWhen().isFile() || node.doneWhen().isCommand()
                || "media_delivered".equals(node.doneWhen().type()))) return false;
        return node.toolHint().isBlank() || GoalContinuationContext.allows(node.toolHint());
    }
    public static List<TaskNode> readyReadOnlyNodes(StateSnapshot snapshot) {
        return snapshot.graph().normalizeForScheduling().readyNodes().stream()
                .filter(node -> readOnlyNode(snapshot, node)).toList();
    }
    public static String blockReason(StateSnapshot snapshot) {
        if (snapshot == null || snapshot.graph().isEmpty()) return "NO_PERSISTED_GOAL";
        if (snapshot.graph().allTerminalSuccess()) return "COMPLETED";
        if (snapshot.graph().hasAwaitingConfirm() || snapshot.intent().needsClarification()) return "AWAITING_HUMAN";
        if (snapshot.graph().nodes().stream().anyMatch(node -> node.status() == TaskNodeStatus.OUTCOME_UNKNOWN
                || node.status() == TaskNodeStatus.RUNNING || node.toolStatus() == com.miniagent.agent.tool.ToolStatus.UNKNOWN))
            return "OUTCOME_UNKNOWN";
        return readyReadOnlyNodes(snapshot).isEmpty() ? "NEEDS_AUTHORIZED_ACTION" : "";
    }
}
