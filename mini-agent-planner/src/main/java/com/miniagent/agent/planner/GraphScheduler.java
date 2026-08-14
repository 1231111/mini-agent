package com.miniagent.agent.planner;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduler：只选 READY，按 priority 出提案。不锁定具体工具。
 */
@Component
public class GraphScheduler {

    private final ReadyTaskSelector readySelector = new ReadyTaskSelector();

    public List<TaskNode> select(TaskGraph graph) {
        return readySelector.select(graph);
    }

    public ActionProposal propose(StateSnapshot snap, List<TaskNode> ready, int batchSize) {
        int n = Math.max(1, batchSize);
        List<ActionSpec> actions = new ArrayList<>();
        int taken = 0;
        for (TaskNode node : ready) {
            if (taken >= n) break;
            String cap = node.capability() == null ? "" : node.capability();
            actions.add(new ActionSpec(
                    "act_" + UUID.randomUUID().toString().substring(0, 8),
                    node.id(),
                    cap,
                    Map.of(),
                    node.doneWhen().wire(),
                    ""));
            taken++;
        }
        return new ActionProposal(
                "prop_" + UUID.randomUUID().toString().substring(0, 8),
                snap.version(),
                snap.executionId(),
                actions);
    }
}
