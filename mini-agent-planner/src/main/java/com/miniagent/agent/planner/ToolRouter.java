package com.miniagent.agent.planner;

import com.miniagent.agent.tool.CapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Executor 调工具前按 capability 展开工具面。不锁定单工具。
 */
@Component
public class ToolRouter {

    private final CapabilityRegistry capabilityRegistry;

    public ToolRouter(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    /**
     * 按节点 capability 展开同族工具；遗留 toolHint 若已注册则排在前面，不锁死单工具。
     */
    public List<String> allowedFor(ActionProposal proposal, TaskGraph graph, boolean hardGate) {
        Set<String> set = new LinkedHashSet<>();
        for (ActionSpec a : proposal.actions()) {
            TaskNode node = graph == null ? null : graph.byId(a.taskId());
            String cap = node != null ? node.capability()
                    : (a.capability() == null || a.capability().isBlank()
                    ? a.tool() : a.capability());
            String hint = node != null ? node.toolHint() : "";
            List<String> blocked = node == null ? List.of() : node.blockedTools();
            if (!blocked.contains(hint) && capabilityRegistry.containsTool(hint)) {
                set.add(hint);
            }
            set.addAll(capabilityRegistry.toolsFor(cap));
            set.removeAll(blocked);
        }
        set.add(CapabilityRegistry.FALLBACK_TOOL);
        if (!hardGate) {
            set.add("memory");
        }
        return new ArrayList<>(set);
    }
}
